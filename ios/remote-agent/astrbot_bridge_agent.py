#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AstrBot iOS 版 · 宿主侧远端代理（单文件，只用 Python 标准库）。

iOS 跑不了 proot 沙盒，控制台里「宿主状态类」的方法由这个代理在宿主机上执行。
协议真源是 ios/CONTRACT.md 第 4 节：

    GET  /api/qqbot/ping    -> {"ok":true,"ts":…,"name":…,"version":"0.1.0"}
    GET  /api/qqbot/caps    -> {"ok":true,"version":"0.1.0","host":"linux|windows|macos","methods":[…]}
    POST /api/qqbot/bridge  {"m":"state","a":[…]}  -> 响应体就是给前端的那一个字符串
                                                      成功 {"ok":true,…}、失败 {"ok":false,"err":"中文原因"}

字段形状沿用 docs/接口约定-1.1.30.md（Android 版），只增不改。
Python 3.8+，不装任何第三方包。
"""

import argparse
import glob
import hashlib
import json
import logging
import os
import platform
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import error as urlerror
from urllib import request as urlrequest
from urllib.parse import urlsplit

VERSION = "0.1.0"
DEFAULT_PORT = 17880
MAX_BODY = 1024 * 1024       # 请求体上限 1 MB
STATE_TTL = 2.0              # state() 与端口探测的缓存时长（秒）
VER_TTL = 300.0              # AstrBot 版本号缓存：5 分钟
EXEC_TIMEOUT = 60            # exec 超时（秒）
TAIL_LINES = 400             # readLog 默认尾部行数
TAIL_BYTES = 512 * 1024      # 最多读日志尾部这么多字节

# CONTRACT 第 4 节的必须实现最小集
METHODS = (
    "state", "todayStats", "netInfo", "envVers", "astrInfo", "instancesInfo",
    "riskInfo", "riskClear", "action", "exec", "readLog", "clearLog", "rmLog",
    "loadBots", "saveBots", "bootInfo", "deployInfo", "bundleInfo",
)

# 返回纯文本（不是 JSON）的方法：action / exec / readLog / clearLog / rmLog / saveBots
TEXT_METHODS = frozenset(("action", "exec", "readLog", "clearLog", "rmLog", "saveBots"))

LOG = logging.getLogger("qqbot-agent")


# ---------------------------------------------------------------- 小工具

def now_ts():
    return int(time.time())


def host_kind():
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def json_text(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def err_text(msg):
    return json_text({"ok": False, "err": str(msg)})


def to_int(value, default=0):
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return default
    return default


def read_json_file(path, default=None):
    if not path:
        return default
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_text(path, text):
    # 一律 UTF-8 + LF：Windows 上 text 模式会把 \n 翻成 \r\n，显式指定 newline
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def strip_ansi(text):
    return _ANSI.sub("", text or "")


def tail_lines(path, n, max_bytes=TAIL_BYTES):
    """读文件尾部 n 行；读不到一律回空串（日志页空着比报错好）。"""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            if size > max_bytes:
                f.seek(size - max_bytes)
            data = f.read()
    except OSError:
        return ""
    text = data.decode("utf-8", "replace").replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if n and n > 0:
        lines = lines[-n:]
    return "\n".join(lines)


def pid_alive(pid):
    pid = to_int(pid, 0)
    if pid <= 0:
        return False
    if sys.platform.startswith("win"):
        try:
            import ctypes

            handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
            if not handle:
                return False
            code = ctypes.c_ulong()
            ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
            ctypes.windll.kernel32.CloseHandle(handle)
            return bool(ok) and code.value == 259  # STILL_ACTIVE
        except Exception:
            return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    except Exception:
        return False
    return True


def host_of(url):
    try:
        return urlsplit(url or "").hostname or ""
    except ValueError:
        return ""


def detect_root():
    """猜宿主的 AstrBot/Termux 布局根（.qqbot 那一层）。"""
    home = os.path.expanduser("~") or ""
    cands = [
        os.environ.get("QQBOT_HOME") or "",
        os.path.join(home, ".qqbot") if home else "",
        "/opt/qqbot",
        "/root/.qqbot",
        os.path.join(os.environ.get("LOCALAPPDATA") or "", "qqbot"),
        os.path.abspath(".qqbot"),
    ]
    for cand in cands:
        if not cand:
            continue
        cand = os.path.abspath(cand)
        if (os.path.isdir(os.path.join(cand, "state"))
                or os.path.isdir(os.path.join(cand, "logs"))
                or os.path.isfile(os.path.join(cand, "deploy.done"))):
            return cand
    return os.path.abspath(os.path.join(home, ".qqbot")) if home else os.path.abspath(".qqbot")


# ---------------------------------------------------------------- 代理本体

class Agent(object):
    """宿主侧的动作实现。所有方法都只在本进程内跑，不跨仓依赖沙盒脚本。"""

    # deployInfo 的步骤表：key 必须和沙盒里 steps/ 下的文件名一致
    STEPS = (
        ("10-termux", "准备 Termux 工具 (proot / tar / xz / curl)"),
        ("20-rootfs", "下载并解包 Ubuntu 24.04 根文件系统"),
        ("30-apt", "在沙盒内安装运行依赖 (Python / 图形库)"),
        ("40-qq", "安装 Linux QQ"),
        ("50-napcat", "安装 NapCat 并注入 QQ"),
        ("60-astrbot", "安装 AstrBot 与 WebUI"),
        ("65-link", "把 NapCat 与 AstrBot 对接起来"),
        ("70-launcher", "安装 qqbot-* 命令"),
    )

    def __init__(self, cfg):
        self.cfg = dict(cfg or {})
        self.root = os.path.abspath(str(self.cfg.get("root") or detect_root()))
        self.name = str(self.cfg.get("name") or socket.gethostname() or "host")
        self.token = str(self.cfg.get("token") or "")
        commands = self.cfg.get("commands")
        self.commands = commands if isinstance(commands, dict) else {}
        self.log_dir = os.path.abspath(str(self.cfg.get("logDir")
                                           or os.path.join(self.root, "logs")))
        self.state_dir = os.path.abspath(str(self.cfg.get("stateDir")
                                             or os.path.join(self.root, "state")))
        self.started = time.time()
        self._lock = threading.Lock()
        self._ports = {}          # port -> (ts, bool)
        self._state_cache = (0.0, None)
        self._ver_cache = (0.0, "")
        self._init_path = None    # AstrBot 的 astrbot/__init__.py；"" 表示找过但没找到

    # ---- 路径 ----

    def path(self, *parts):
        return os.path.join(self.root, *parts)

    def marker(self, name):
        return os.path.isfile(os.path.join(self.state_dir, name))

    def deployed(self):
        return os.path.isfile(self.path("deploy.done")) or os.path.isfile(
            os.path.join(self.state_dir, "bots.json"))

    def _astrbot_init(self):
        if self._init_path is not None:
            return self._init_path
        cands = []
        if self.cfg.get("astrbotDir"):
            cands.append(str(self.cfg["astrbotDir"]))
        root = self.root
        cands.extend([
            os.path.join(root, "astrbot"),
            os.path.join(root, "AstrBot"),
            os.path.join(os.path.dirname(root), "astrbot"),
            os.path.join(os.path.dirname(root), "AstrBot"),
            "/opt/AstrBot",
            "/opt/astrbot",
            os.path.join(os.path.expanduser("~"), "AstrBot"),
            os.path.join(root, "ubuntu", "opt", "AstrBot"),
        ])
        found = ""
        for cand in cands:
            if not cand:
                continue
            for probe in (os.path.join(cand, "__init__.py"),
                          os.path.join(cand, "astrbot", "__init__.py")):
                if os.path.isfile(probe):
                    found = probe
                    break
            if found:
                break
        self._init_path = found
        return found

    def _astr_data_dir(self):
        init = self._astrbot_init()
        if not init:
            return ""
        pkg_dir = os.path.dirname(init)                      # …/astrbot
        task_root = os.path.dirname(pkg_dir)                 # …/AstrBot
        if os.path.basename(pkg_dir) == "astrbot":
            return os.path.join(task_root, "data")
        return os.path.join(pkg_dir, "data")

    # ---- 缓存 + 探测 ----

    def port_open(self, port):
        port = to_int(port, 0)
        if port <= 0:
            return False
        stamp = time.time()
        with self._lock:
            hit = self._ports.get(port)
            if hit and stamp - hit[0] < STATE_TTL:
                return hit[1]
        ok = False
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.2)
            sock.connect(("127.0.0.1", port))
            ok = True
        except OSError:
            ok = False
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
        with self._lock:
            self._ports[port] = (stamp, ok)
        return ok

    def astrbot_version(self):
        stamp = time.time()
        with self._lock:
            if stamp - self._ver_cache[0] < VER_TTL:
                return self._ver_cache[1]
        version = ""
        init = self._astrbot_init()
        if init:
            try:
                with open(init, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read(256 * 1024)
            except OSError:
                text = ""
            m = re.search(r"""__version__\s*=\s*['"]([^'"]+)['"]""", text)
            if m:
                version = m.group(1).strip()
        with self._lock:
            self._ver_cache = (stamp, version)
        return version

    def _invalidate(self):
        with self._lock:
            self._state_cache = (0.0, None)
            self._ports.clear()

    # ---- 小读 ----

    def _bots_root(self):
        obj = read_json_file(os.path.join(self.state_dir, "bots.json"))
        return obj if isinstance(obj, dict) else {}

    def _bots_summary(self, root_obj):
        bots = root_obj.get("bots") if isinstance(root_obj, dict) else None
        items = [b for b in bots if isinstance(b, dict)] if isinstance(bots, list) else []
        active = root_obj.get("active") if isinstance(root_obj, dict) else ""
        active = active if isinstance(active, str) else ""
        uin = nick = ""
        for bot in items:
            if active and str(bot.get("id") or "") == active:
                uin = str(bot.get("uin") or bot.get("qq") or "")
                nick = str(bot.get("nick") or bot.get("name") or "")
                break
        if not uin and items:
            uin = str(items[0].get("uin") or items[0].get("qq") or "")
            nick = str(items[0].get("nick") or items[0].get("name") or "")
        return len(items), active, uin, nick

    def _log_files(self):
        out = []
        try:
            names = sorted(os.listdir(self.log_dir))
        except OSError:
            return out
        for name in names:
            if not name.lower().endswith(".log"):
                continue
            full = os.path.join(self.log_dir, name)
            try:
                st = os.stat(full)
            except OSError:
                continue
            if not os.path.isfile(full):
                continue
            out.append({"name": name, "size": int(st.st_size), "mtime": int(st.st_mtime)})
        return out

    def _disk_free_gb(self):
        probe = self.root
        for _ in range(8):
            try:
                free = shutil.disk_usage(probe).free
                return int(free // (1024 ** 3))
            except OSError:
                parent = os.path.dirname(probe)
                if parent == probe:
                    break
                probe = parent
        return 0

    def _net_online(self):
        # UDP connect 不发包，只查路由表：没有默认路由时才会失败
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(0.5)
            sock.connect(("223.5.5.5", 53))
            return True
        except OSError:
            return False
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass

    def _net_type(self):
        typed = str(self.cfg.get("netType") or "")
        if typed:
            return typed
        try:
            if glob.glob("/sys/class/net/*/wireless"):
                return "wifi"
        except OSError:
            pass
        return "wired"

    def _deploy_running(self):
        pid_file = self.path("deploy.pid")
        try:
            with open(pid_file, "r", encoding="utf-8", errors="replace") as f:
                pid = f.read(64).strip().splitlines()
        except OSError:
            return False
        return bool(pid) and pid_alive(pid[0])

    # ---- 方法：状态 ----

    def state(self):
        stamp = time.time()
        with self._lock:
            cached_at, cached = self._state_cache
            if cached is not None and stamp - cached_at < STATE_TTL:
                return cached
        bots_root = self._bots_root()
        nap_port = to_int(self.cfg.get("napcatPort"), 6099) or 6099
        astr_port = to_int(self.cfg.get("astrPort"), 6185) or 6185
        raw_port = bots_root.get("astrbotPort")
        if isinstance(raw_port, int) and not isinstance(raw_port, bool) and raw_port > 0:
            astr_port = raw_port
        nap_up = self.port_open(nap_port)
        astr_up = self.port_open(astr_port)
        count, active, uin, nick = self._bots_summary(bots_root)
        obj = {
            "ok": True,
            "appVersion": self.astrbot_version(),
            "hostName": self.name,
            "os": host_kind(),
            "online": bool(nap_up or astr_up),
            "napcatOn": bool(nap_up),
            "astrbotOn": bool(astr_up),
            "guardOn": self.marker("guard.on"),
            "deployed": self.deployed(),
            "napcatPort": nap_port,
            "astrPort": astr_port,
            "astrPortOn": bool(astr_up),
            "botCount": count,
            "activeBot": active,
            "uin": uin,
            "nick": nick,
            "uptimeSec": int(time.time() - self.started),
            "diskFree": self._disk_free_gb(),
            "ts": now_ts(),
            # 以下是 Android 版同义字段（只增不改）：index.html 直接读这几个名字
            "napcatUp": bool(nap_up),
            "astrbotUp": bool(astr_up),
            "napcatOff": not nap_up,
            "astrbotOff": not astr_up,
            "astrbotPort": astr_port,
            "guardWake": self.marker("wake.on"),
            "logFiles": self._log_files(),
            "root": self.root,
        }
        with self._lock:
            self._state_cache = (stamp, obj)
        return obj

    def today_stats(self):
        day = time.strftime("%Y-%m-%d")
        obj = {
            "ok": True, "day": day, "onlineSec": 0, "msgs": 0, "errors": 0,
            "rxBytes": 0, "txBytes": 0, "warm": False,
        }
        data = read_json_file(os.path.join(self.state_dir, "today.json"))
        if isinstance(data, dict) and data:
            obj["warm"] = True
            if str(data.get("day") or "") == day:
                for key in ("onlineSec", "msgs", "errors", "rxBytes", "txBytes"):
                    obj[key] = to_int(data.get(key), 0)
        return obj

    def net_info(self):
        online = self._net_online()
        return {
            "ok": True,
            "online": online,
            "type": self._net_type() if online else "none",
            "lastSwitch": 0,
            "recoveries": 0,
            "ts": now_ts(),
        }

    def env_vers(self):
        return {"ok": True, "astrbot": self.astrbot_version()}

    def astr_info(self):
        data_dir = self._astr_data_dir()
        obj = {
            "ok": True, "cfgExists": False, "base": "", "model": "", "pickedId": "",
            "sourceId": "", "srcHost": "", "hasKey": False, "keyTail": "",
            "persona": "", "personaSel": "default", "dashUser": "astrbot",
            "dashPassDefault": True, "dataDir": data_dir,
        }
        cfg = read_json_file(os.path.join(data_dir, "cmd_config.json")) if data_dir else None
        if not isinstance(cfg, dict):
            return obj
        obj["cfgExists"] = True
        providers = cfg.get("provider")
        if not isinstance(providers, list):
            providers = cfg.get("providers")
        items = [p for p in providers if isinstance(p, dict)] if isinstance(providers, list) else []
        pick = None
        settings = cfg.get("provider_settings")
        wanted = settings.get("default_provider_id") if isinstance(settings, dict) else None
        if isinstance(wanted, str) and wanted:
            for item in items:
                if str(item.get("id") or "") == wanted:
                    pick = item
                    break
        if pick is None:
            for item in items:
                if item.get("enable") is False:
                    continue
                pick = item
                break
        if pick is not None:
            base = str(pick.get("api_base") or "")
            ident = str(pick.get("id") or "")
            model_cfg = pick.get("model_config")
            model = str(model_cfg.get("model") or "") if isinstance(model_cfg, dict) else ""
            if not model:
                model = str(pick.get("model") or "")
            key = pick.get("key")
            if isinstance(key, list):
                key = next((k for k in key if isinstance(k, str) and k), "")
            key = str(key or "")
            obj.update({
                "base": base, "srcHost": host_of(base), "pickedId": ident,
                "sourceId": ident, "model": model, "hasKey": bool(key),
                "keyTail": key[-4:] if len(key) >= 4 else "",
            })
        persona_settings = cfg.get("persona_settings")
        if isinstance(persona_settings, dict):
            sel = persona_settings.get("persona_id")
            if isinstance(sel, str) and sel:
                obj["personaSel"] = sel
        dash = cfg.get("dashboard")
        if isinstance(dash, dict):
            user = dash.get("username")
            if isinstance(user, str) and user:
                obj["dashUser"] = user
            password = dash.get("password")
            if isinstance(password, str) and password:
                obj["dashPassDefault"] = (password == hashlib.md5(b"astrbot").hexdigest())
        return obj

    def instances_info(self):
        data = read_json_file(os.path.join(self.state_dir, "instances.json"))
        return {"ok": True, "instances": data if isinstance(data, dict) else {}}

    def risk_info(self):
        obj = {"ok": True, "risk": False}
        data = read_json_file(os.path.join(self.state_dir, "risk.json"))
        if isinstance(data, dict) and data:
            obj.update({
                "risk": True,
                "instance": str(data.get("instance") or ""),
                "word": str(data.get("word") or ""),
                "line": str(data.get("line") or ""),
                "since": to_int(data.get("since"), 0),
                "file": str(data.get("file") or ""),
            })
        return obj

    def risk_clear(self):
        cleared = 0
        targets = [os.path.join(self.state_dir, "risk.json")]
        targets.extend(glob.glob(os.path.join(self.state_dir, "risk-*.flag")))
        for target in targets:
            try:
                os.remove(target)
                cleared += 1
            except OSError:
                pass
        return {"ok": True, "cleared": cleared}

    def boot_info(self):
        obj = {"ok": True, "lastBoot": 0, "lastStart": 0, "attempts": 0, "lastErr": ""}
        data = read_json_file(os.path.join(self.state_dir, "boot.json"))
        if isinstance(data, dict):
            obj["lastBoot"] = to_int(data.get("lastBoot"), 0)
            obj["lastStart"] = to_int(data.get("lastStart"), 0)
            obj["attempts"] = to_int(data.get("attempts"), 0)
            obj["lastErr"] = str(data.get("lastErr") or "")
        return obj

    def deploy_info(self):
        done = os.path.isfile(self.path("deploy.done"))
        running = self._deploy_running()
        steps = []
        done_count = 0
        steps_dir = self.path("steps")
        for key, label in self.STEPS:
            hit = os.path.isfile(os.path.join(steps_dir, key))
            if hit:
                done_count += 1
            steps.append({"key": key, "label": label, "done": hit})
        log_path = os.path.join(self.log_dir, "deploy.log")
        log_tail = strip_ansi(tail_lines(log_path, 14)) if os.path.isfile(log_path) else ""
        err_path = self.path("deploy.error")
        err = strip_ansi(tail_lines(err_path, 6)).strip() if os.path.isfile(err_path) else ""
        if len(err) > 900:
            err = err[-900:]
        log_age = 0
        try:
            log_age = max(0, int(time.time() - os.path.getmtime(log_path)))
        except OSError:
            log_age = 0
        return {
            "ok": True, "done": done, "running": running, "steps": steps,
            "doneCount": done_count, "total": len(self.STEPS),
            "logTail": log_tail, "err": err, "logAge": log_age, "ts": now_ts(),
        }

    def bundle_info(self):
        bundle = self.path("cache", "bundle.tar.gz")
        if not os.path.isfile(bundle):
            # 瘦包：安装时本来就不带离线包，不是「缺文件」
            return {"ok": True, "asset": False, "slim": True}
        size = os.path.getsize(bundle)
        return {
            "ok": True, "asset": True, "total": size, "size": size,
            "ready": os.path.isfile(self.path("deploy.done")),
            "pending": False,
            "done": os.path.isfile(self.path("deploy.done")),
            "arch": platform.machine() or "",
        }

    # ---- 方法：动作（返回纯文本） ----

    def command_for(self, what):
        cmds = self.commands
        found = cmds.get(what)
        if isinstance(found, str) and found.strip():
            return found.strip()
        parts = what.split(":")
        if len(parts) == 3:
            # svc:napcat:on 允许退到 svc:napcat / svc:on / svc
            for key in ("%s:%s" % (parts[0], parts[1]),
                        "%s:%s" % (parts[0], parts[2]),
                        parts[0]):
                found = cmds.get(key)
                if isinstance(found, str) and found.strip():
                    return found.strip()
        return ""

    def _spawn(self, what, cmd):
        header = "=== %s  %s  ->  %s\n" % (
            time.strftime("%Y-%m-%d %H:%M:%S"), what, cmd)
        handle = None
        try:
            os.makedirs(self.log_dir, exist_ok=True)
            handle = open(os.path.join(self.log_dir, "agent-action.log"), "ab")
            handle.write(header.encode("utf-8"))
            handle.flush()
        except OSError:
            handle = None
        kwargs = {}
        if not sys.platform.startswith("win"):
            kwargs["start_new_session"] = True   # 脱离代理的进程组
        try:
            subprocess.Popen(
                cmd, shell=True, stdin=subprocess.DEVNULL,
                stdout=handle if handle is not None else subprocess.DEVNULL,
                stderr=subprocess.STDOUT if handle is not None else subprocess.DEVNULL,
                **kwargs)
        finally:
            if handle is not None:
                handle.close()

    def action(self, what):
        what = "" if what is None else str(what).strip()
        if not what:
            return "失败：缺少动作名"
        cmd = self.command_for(what)
        if not cmd:
            return "失败：宿主没有配置 %s 对应的命令（在 agent.json 的 commands 里补一条）" % what
        try:
            self._spawn(what, cmd)
        except OSError as exc:
            return "失败：命令没能启动（%s）" % exc
        self._invalidate()
        return "已提交 %s" % what

    # ---- 方法：命令执行（返回纯文本） ----

    def exec_cmd(self, cmd):
        cmd = "" if cmd is None else str(cmd)
        if not cmd.strip():
            return ""
        cwd = self.root if os.path.isdir(self.root) else None
        try:
            proc = subprocess.run(
                cmd, shell=True, cwd=cwd, timeout=EXEC_TIMEOUT,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except subprocess.TimeoutExpired as exc:
            partial = _decode(exc.stdout) + _decode(exc.stderr)
            head = "失败：命令超过 %d 秒还没结束" % EXEC_TIMEOUT
            return (head + "\n" + partial).rstrip() if partial.strip() else head
        except OSError as exc:
            return "失败：%s" % exc
        text = _decode(proc.stdout) + _decode(proc.stderr)
        return text.rstrip("\r\n") if text.strip() else ""

    # ---- 方法：日志 ----

    def _log_path(self, name):
        if not isinstance(name, str):
            return ""
        name = name.strip()
        if not name or "/" in name or "\\" in name or ".." in name:
            return ""
        if not name.lower().endswith(".log"):
            return ""
        base = os.path.realpath(self.log_dir)
        full = os.path.realpath(os.path.join(base, name))
        if os.path.dirname(full) != base:      # 软链接也不许绕出日志目录
            return ""
        return full if os.path.isfile(full) else ""

    def read_log(self, name, lines):
        full = self._log_path(name)
        if not full:
            return ""
        want = to_int(lines, TAIL_LINES)
        if want <= 0:
            want = TAIL_LINES
        return tail_lines(full, min(want, 5000))

    def clear_log(self, name):
        full = self._log_path(name)
        if not full:
            return "日志文件不存在"
        try:
            size = os.path.getsize(full)
            with open(full, "w", encoding="utf-8", newline="\n"):
                pass
        except OSError as exc:
            return "清空失败：%s" % exc
        return "已清空 %s（%d 字节）" % (os.path.basename(full), size)

    def rm_log(self, name):
        full = self._log_path(name)
        if not full:
            return "日志文件不存在"
        try:
            size = os.path.getsize(full)
            os.remove(full)
        except OSError as exc:
            return "删除失败：%s" % exc
        return "已删除 %s（%d 字节）" % (os.path.basename(full), size)

    # ---- 方法：实例存储 ----

    def load_bots(self):
        path = os.path.join(self.state_dir, "bots.json")
        obj = read_json_file(path)
        if not isinstance(obj, dict):
            return json_text({"bots": [], "active": ""})
        if not isinstance(obj.get("bots"), list):
            obj["bots"] = []
        if not isinstance(obj.get("active"), str):
            obj["active"] = ""
        return json_text(obj)

    def save_bots(self, payload):
        if isinstance(payload, (dict, list)):
            payload = json_text(payload)
        text = "" if payload is None else str(payload)
        try:
            obj = json.loads(text)
        except ValueError:
            return "格式不对，没有写盘（保存失败）"
        if not isinstance(obj, dict) or not isinstance(obj.get("bots"), list):
            return "格式不对，没有写盘（保存失败）"
        target = os.path.join(self.state_dir, "bots.json")
        tmp = target + ".tmp"
        try:
            os.makedirs(self.state_dir, exist_ok=True)
            with open(tmp, "w", encoding="utf-8", newline="\n") as f:
                f.write(json_text(obj))
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, target)
        except OSError as exc:
            try:
                os.remove(tmp)
            except OSError:
                pass
            return "保存失败：%s" % exc
        self._invalidate()
        return "已保存"


def _decode(data):
    if not data:
        return ""
    if isinstance(data, bytes):
        return data.decode("utf-8", "replace")
    return str(data)


# ---------------------------------------------------------------- 方法分派

def call_method(agent, method, args):
    def arg(index, default=None):
        return args[index] if len(args) > index else default

    if method == "state":
        return json_text(agent.state())
    if method == "todayStats":
        return json_text(agent.today_stats())
    if method == "netInfo":
        return json_text(agent.net_info())
    if method == "envVers":
        return json_text(agent.env_vers())
    if method == "astrInfo":
        return json_text(agent.astr_info())
    if method == "instancesInfo":
        return json_text(agent.instances_info())
    if method == "riskInfo":
        return json_text(agent.risk_info())
    if method == "riskClear":
        return json_text(agent.risk_clear())
    if method == "bootInfo":
        return json_text(agent.boot_info())
    if method == "deployInfo":
        return json_text(agent.deploy_info())
    if method == "bundleInfo":
        return json_text(agent.bundle_info())
    if method == "action":
        return agent.action(arg(0))
    if method == "exec":
        return agent.exec_cmd(arg(0))
    if method == "readLog":
        return agent.read_log(arg(0), arg(1))
    if method == "clearLog":
        return agent.clear_log(arg(0))
    if method == "rmLog":
        return agent.rm_log(arg(0))
    if method == "loadBots":
        return agent.load_bots()
    if method == "saveBots":
        return agent.save_bots(arg(0))
    return err_text("宿主未提供 %s" % method)


# ---------------------------------------------------------------- HTTP 层

class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "AstrBotAgent/" + VERSION
    protocol_version = "HTTP/1.1"

    @property
    def agent(self):
        return getattr(self.server, "agent", None)

    def log_message(self, fmt, *args):
        LOG.info("%s %s", self.address_string(), fmt % args)

    def _send(self, code, text):
        body = (text or "").encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.close_connection = True

    def _token_ok(self):
        agent = self.agent
        if agent is None or not agent.token:
            return True
        given = self.headers.get("X-AstrBot-Key") or ""
        try:
            return secrets.compare_digest(given, agent.token)
        except Exception:
            return given == agent.token

    def _content_length(self):
        try:
            return int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return 0

    def _drain(self):
        left = min(self._content_length(), MAX_BODY + 1)
        try:
            while left > 0:
                chunk = self.rfile.read(min(left, 65536))
                if not chunk:
                    break
                left -= len(chunk)
        except OSError:
            pass

    def do_GET(self):
        try:
            path = (self.path or "").split("?")[0]
            if not self._token_ok():
                return self._send(200, err_text("token 不对"))
            if path == "/api/qqbot/ping":
                agent = self.agent
                return self._send(200, json_text({
                    "ok": True, "ts": now_ts(),
                    "name": agent.name if agent else "", "version": VERSION,
                }))
            if path == "/api/qqbot/caps":
                return self._send(200, json_text({
                    "ok": True, "version": VERSION, "host": host_kind(),
                    "methods": list(METHODS),
                }))
            return self._send(404, err_text("没有这个接口：%s" % path))
        except Exception as exc:  # 兜底：任何情况下都不让连接挂着
            LOG.exception("GET 处理失败")
            self._send(500, err_text("代理内部错误：%s" % exc))

    def do_POST(self):
        try:
            path = (self.path or "").split("?")[0]
            if path != "/api/qqbot/bridge":
                self._drain()
                return self._send(404, err_text("没有这个接口：%s" % path))
            if not self._token_ok():
                self._drain()
                return self._send(200, err_text("token 不对"))
            length = self._content_length()
            if length > MAX_BODY:
                self._drain()
                return self._send(200, err_text("请求体太大（上限 1 MB）"))
            if length <= 0:
                return self._send(200, err_text("请求体是空的"))
            try:
                raw = self.rfile.read(length)
            except OSError:
                return self._send(200, err_text("读取请求体失败"))
            try:
                payload = json.loads(raw.decode("utf-8", "replace"))
            except ValueError:
                return self._send(200, err_text("请求体不是合法 JSON"))
            if not isinstance(payload, dict):
                return self._send(200, err_text("请求体必须是 JSON 对象"))
            method = payload.get("m")
            if not isinstance(method, str) or not method.strip():
                return self._send(200, err_text("请求体缺少 m 字段"))
            method = method.strip()
            args = payload.get("a")
            if args is None:
                args = []
            elif not isinstance(args, list):
                args = [args]
            return self._send(200, self._bridge(method, args))
        except Exception as exc:
            LOG.exception("POST 处理失败")
            self._send(200, err_text("代理内部错误：%s" % exc))

    def _bridge(self, method, args):
        agent = self.agent
        if agent is None:
            return err_text("代理还没准备好")
        try:
            return call_method(agent, method, args)
        except Exception as exc:
            LOG.exception("方法 %s 执行失败", method)
            if method in TEXT_METHODS:
                return "失败：宿主执行 %s 出错（%s）" % (method, exc)
            return err_text("宿主执行 %s 出错（%s）" % (method, exc))


# ---------------------------------------------------------------- 启停

class AgentHTTPServer(ThreadingHTTPServer):
    # Windows 上 SO_REUSEADDR 连「端口已被监听」都放行：第二个进程会悄悄绑上却收不到包。
    # 那里改用独占绑定，端口冲突才能当场报错；POSIX 保持默认，方便快速重启。
    allow_reuse_address = not sys.platform.startswith("win")
    daemon_threads = True


def make_server(bind, port, agent):
    httpd = AgentHTTPServer((bind, port), BridgeHandler)
    httpd.agent = agent
    return httpd


def load_config(path):
    if path:
        cands = [path]
    else:
        here = os.path.dirname(os.path.abspath(__file__))
        cands = [os.path.join(here, "agent.json"), os.path.abspath("agent.json")]
    for cand in cands:
        if cand and os.path.isfile(cand):
            try:
                with open(cand, "r", encoding="utf-8") as f:
                    obj = json.load(f)
            except (OSError, ValueError) as exc:
                LOG.warning("配置文件读不了（%s）：%s", cand, exc)
                return {}, cand
            if not isinstance(obj, dict):
                LOG.warning("配置文件不是 JSON 对象：%s", cand)
                return {}, cand
            return obj, cand
    return {}, ""


def build_agent(args, cfg):
    merged = dict(cfg or {})
    if args.root:
        merged["root"] = args.root
    if args.token is not None:
        merged["token"] = args.token
    return Agent(merged)


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="astrbot_bridge_agent.py",
        description="AstrBot iOS 版宿主侧远端代理（默认只监听回环地址）")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help="监听端口（默认 %d）" % DEFAULT_PORT)
    parser.add_argument("--bind", default="127.0.0.1",
                        help="监听地址（默认 127.0.0.1）")
    parser.add_argument("--lan", action="store_true",
                        help="允许绑定 0.0.0.0 等非回环地址（对局域网开放，必须显式给）")
    parser.add_argument("--token", default=None,
                        help="桥令牌；给了就校验请求头 X-AstrBot-Key")
    parser.add_argument("--root", default=None,
                        help="宿主上 AstrBot/Termux 布局的根（默认自动探测）")
    parser.add_argument("--config", default=None,
                        help="agent.json 路径（默认脚本同目录，其次当前目录）")
    parser.add_argument("--selftest", action="store_true",
                        help="起临时服务自测，成功后打印 SELFTEST OK 并退 0")
    return parser.parse_args(argv)


def run_server(args):
    bind = str(args.bind or "127.0.0.1")
    loopback = bind in ("127.0.0.1", "localhost", "::1", "127.0.0.2")
    if not loopback and not args.lan:
        LOG.error("拒绝启动：--bind %s 会让桥暴露到局域网，要这么做请显式加 --lan", bind)
        return 2
    cfg, cfg_path = load_config(args.config)
    agent = build_agent(args, cfg)
    if not loopback and not agent.token:
        LOG.warning("正在监听 %s 但没配 token：网段内任何人都能在宿主上执行命令，强烈建议加 --token", bind)
    try:
        httpd = make_server(bind, args.port, agent)
    except OSError as exc:
        LOG.error("端口 %s 起不来：%s（多半是端口被占，换个 --port 或先停掉旧进程）", args.port, exc)
        return 2
    port = httpd.server_address[1]
    LOG.info("远端代理已启动：http://%s:%d", bind, port)
    LOG.info("root=%s  token=%s  os=%s", agent.root, "开" if agent.token else "关", host_kind())
    if cfg_path:
        LOG.info("配置文件：%s", cfg_path)
    LOG.info("方法：%s", ", ".join(METHODS))
    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        LOG.info("收到 Ctrl+C，正在退出")
    finally:
        httpd.server_close()
    return 0


# ---------------------------------------------------------------- 自测

class SelfTestError(Exception):
    def __init__(self, message, body=None):
        Exception.__init__(self, message)
        self.body = body


def _expect(cond, message, body=None):
    if not cond:
        raise SelfTestError(message, body)


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _parse_json(text):
    try:
        obj = json.loads(text)
    except ValueError:
        raise SelfTestError("响应体不是合法 JSON", text)
    if not isinstance(obj, dict):
        raise SelfTestError("响应体不是 JSON 对象", text)
    return obj


def _http(base, path, data=None, headers=None, timeout=15):
    req = urlrequest.Request(base + path, data=data, headers=headers or {})
    try:
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urlerror.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def _bridge(base, method, *args, **kwargs):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    headers.update(kwargs.get("headers") or {})
    body = json.dumps({"m": method, "a": list(args)}, ensure_ascii=False).encode("utf-8")
    return _http(base, "/api/qqbot/bridge", body, headers)


def _start_test_server(agent):
    httpd = make_server("127.0.0.1", 0, agent)
    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.02})
    thread.daemon = True
    thread.start()
    return httpd, "http://127.0.0.1:%d" % port


def _note(name):
    print("[ok] " + name)


def selftest():
    """自己起临时服务，把 ping / caps / bridge 的主要形状断言一遍。"""
    tmp = tempfile.mkdtemp(prefix="qqbot-agent-selftest-")
    servers = []
    passed = True
    try:
        root = os.path.join(tmp, ".qqbot")
        state_dir = os.path.join(root, "state")
        log_dir = os.path.join(root, "logs")
        os.makedirs(state_dir)
        os.makedirs(log_dir)
        write_text(os.path.join(log_dir, "selftest.log"), "line1\nline2\nline3\n")
        write_text(os.path.join(state_dir, "bots.json"), json_text({
            "active": "bot1",
            "bots": [{"id": "bot1", "name": "主号", "napcatPort": 6099}],
        }))
        write_text(os.path.join(root, "deploy.done"), "")

        agent = Agent({"name": "selftest-host", "root": root})
        httpd, base = _start_test_server(agent)
        servers.append(httpd)

        # 1) ping
        code, text = _http(base, "/api/qqbot/ping")
        obj = _parse_json(text)
        _expect(code == 200, "ping 状态码应为 200，实际 %s" % code, text)
        _expect(obj.get("ok") is True, "ping 的 ok 应为 true", text)
        _expect(_is_int(obj.get("ts")) and obj["ts"] > 0, "ping 的 ts 应是 epoch 秒整数", text)
        _expect(obj.get("name") == "selftest-host", "ping 的 name 应回配置里的宿主名", text)
        _expect(obj.get("version") == VERSION, "ping 的 version 应为 %s" % VERSION, text)
        _note("ping")

        # 2) caps
        code, text = _http(base, "/api/qqbot/caps")
        obj = _parse_json(text)
        _expect(code == 200, "caps 状态码应为 200", text)
        _expect(obj.get("ok") is True, "caps 的 ok 应为 true", text)
        _expect(obj.get("version") == VERSION, "caps 的 version 不对", text)
        _expect(obj.get("host") in ("linux", "windows", "macos"), "caps 的 host 取值不对", text)
        methods = obj.get("methods")
        _expect(isinstance(methods, list) and len(methods) >= 18, "caps.methods 应是方法名数组", text)
        for name in ("state", "exec", "readLog", "loadBots"):
            _expect(name in methods, "caps.methods 里缺 %s" % name, text)
        _note("caps")

        # 3) state：字段与类型
        code, text = _bridge(base, "state")
        st = _parse_json(text)
        _expect(code == 200, "state 状态码应为 200，实际 %s" % code, text)
        _expect(st.get("ok") is True, "state 的 ok 应为 true", text)
        for key in ("appVersion", "hostName", "os", "activeBot", "uin", "nick"):
            _expect(isinstance(st.get(key), str), "state.%s 应是字符串" % key, text)
        for key in ("online", "napcatOn", "astrbotOn", "guardOn", "deployed", "astrPortOn"):
            _expect(isinstance(st.get(key), bool), "state.%s 应是布尔值" % key, text)
        for key in ("napcatPort", "astrPort", "botCount", "uptimeSec", "diskFree", "ts"):
            _expect(_is_int(st.get(key)), "state.%s 应是整数" % key, text)
        _expect(st["os"] in ("linux", "windows", "macos"), "state.os 取值不对", text)
        _expect(st["botCount"] == 1, "state.botCount 应为 1", text)
        _expect(st["activeBot"] == "bot1", "state.activeBot 应取 bots.json 的 active", text)
        _expect(st["deployed"] is True, "state.deployed 应为 true（deploy.done 在）", text)
        _expect(st["uptimeSec"] >= 0, "state.uptimeSec 不应为负", text)
        _note("state")

        # 4) exec（纯文本）
        code, text = _bridge(base, "exec", "echo selftest-ok")
        _expect(code == 200, "exec 状态码应为 200", text)
        _expect("selftest-ok" in text, "exec 应回显 selftest-ok", text)
        _note("exec")

        # 5) readLog（尾部 N 行 + 路径穿越必须拒绝）
        code, text = _bridge(base, "readLog", "selftest.log", 2)
        _expect(text.splitlines() == ["line2", "line3"], "readLog 应只回尾部 2 行", text)
        code, text = _bridge(base, "readLog", "../state/bots.json", 5)
        _expect(text == "", "readLog 必须拒绝路径穿越（应回空串）", text)
        code, text = _bridge(base, "readLog", "selftest.txt", 5)
        _expect(text == "", "readLog 只允许 *.log", text)
        _note("readLog")

        # 6) loadBots / saveBots
        code, text = _bridge(base, "loadBots")
        bots = _parse_json(text)
        _expect(isinstance(bots.get("bots"), list) and bots["bots"], "loadBots 应回 bots.json 的内容", text)
        _expect(bots["bots"][0].get("id") == "bot1", "loadBots 里第一个实例应是 bot1", text)
        code, text = _bridge(base, "saveBots", json_text({
            "active": "bot2", "bots": [{"id": "bot2", "name": "备用号"}],
        }))
        _expect("已保存" in text, "saveBots 成功应回「已保存」", text)
        code, text = _bridge(base, "loadBots")
        _expect("bot2" in text, "saveBots 之后 loadBots 应能看到新实例", text)
        code, text = _bridge(base, "saveBots", "{这不是 JSON")
        _expect("格式不对" in text, "saveBots 收到坏 JSON 应回「格式不对，没有写盘」", text)
        _note("loadBots/saveBots")

        # 7) 未实现的方法名
        code, text = _bridge(base, "nopeMethod")
        obj = _parse_json(text)
        _expect(code == 200, "未实现的方法也必须回 200，实际 %s" % code, text)
        _expect(obj.get("ok") is False, "未实现方法的 ok 应为 false", text)
        _expect(obj.get("err") == "宿主未提供 nopeMethod", "未实现方法的 err 文案不对", text)
        _note("未知方法 -> ok:false")

        # 8) 没配命令的 action：纯文本中文失败消息
        code, text = _bridge(base, "action", "svc:napcat:on")
        _expect(code == 200, "action 状态码应为 200", text)
        _expect("失败" in text and "svc:napcat:on" in text,
                "没配命令的 action 应回一句中文失败消息", text)
        _note("action 未配置")

        # 9) 其它路径 404
        code, text = _http(base, "/api/qqbot/nope")
        _expect(code == 404, "未知路径应 404，实际 %s" % code, text)
        _note("404")

        # 10) 请求体上限
        big = "x" * (MAX_BODY + 10)
        code, text = _bridge(base, "exec", big)
        obj = _parse_json(text)
        _expect(obj.get("ok") is False and "太大" in str(obj.get("err")),
                "超过 1 MB 的请求体应被拒绝", text[:200])
        _note("请求体上限")

        # 11) token
        guarded = Agent({"name": "selftest-token", "root": root, "token": "s3cret"})
        httpd2, base2 = _start_test_server(guarded)
        servers.append(httpd2)
        code, text = _bridge(base2, "state")
        obj = _parse_json(text)
        _expect(obj.get("ok") is False, "缺 token 时 bridge 应回 ok:false", text)
        _expect("token 不对" in str(obj.get("err")), "缺 token 的 err 应为「token 不对」", text)
        code, text = _http(base2, "/api/qqbot/caps", headers={"X-AstrBot-Key": "s3cret"})
        obj = _parse_json(text)
        _expect(obj.get("ok") is True, "带对 token 应放行", text)
        _note("token")
    except SelfTestError as exc:
        passed = False
        print("[FAIL] " + str(exc))
        if exc.body is not None:
            body = str(exc.body)
            print("       响应体：" + (body[:600] + "…" if len(body) > 600 else body))
    except Exception as exc:
        passed = False
        print("[FAIL] 自测过程中抛出异常：%r" % (exc,))
        import traceback
        traceback.print_exc()
    finally:
        for httpd in servers:
            try:
                httpd.shutdown()
                httpd.server_close()
            except Exception:
                pass
        shutil.rmtree(tmp, ignore_errors=True)
    return passed


# ---------------------------------------------------------------- 入口

def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    # Windows 下重定向到管道时 stdout 默认走 cp936，统一成 UTF-8 免得中文变乱码；
    # 接真实控制台时 Python 走 WriteConsoleW，不受影响。
    try:
        for stream in (sys.stdout, sys.stderr):
            encoding = (getattr(stream, "encoding", "") or "").lower().replace("-", "")
            if encoding != "utf8":
                stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    logging.basicConfig(
        level=logging.WARNING if args.selftest else logging.INFO,
        format="[%(asctime)s] %(message)s", datefmt="%H:%M:%S")
    if args.selftest:
        if selftest():
            print("SELFTEST OK")
            return 0
        print("SELFTEST FAILED")
        return 1
    return run_server(args)


if __name__ == "__main__":
    sys.exit(main())
