#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""iOS 版前端验收用的 mock 桥服务（只用 Python 标准库，不装任何第三方包）。

它把 iOS 原生侧该做的事情在 Windows 上模拟出来，让 ui/assets/qqbot/index.html
能在 Chrome 里跑完整条前端链路（服务静态资源 -> 注入垫片 -> 页面渲染）：

    GET  /                -> ui/assets/qqbot/index.html（在 </head> 前注入两段内联脚本）
    GET  /<静态资源>       -> ui/assets/qqbot/<静态资源>（appicon.png 等；路径穿越一律 400）
    GET  /__caps          -> {"ok":true,"version":"0.1.0","methods":[...]}
    POST /__bridge        -> 按 CONTRACT.md 第 2 节的线格式，body 就是前端拿到的那个字符串

注入的两段脚本（顺序固定，对应 CONTRACT.md 第 0 节）：

    1. window.__ASTRIOS__ = {base, methods}   —— 告诉垫片回环服务在哪、能调哪些方法
    2. ios/shared/AndroidShim.js 全文          —— 真正的垫片，一行都不改（不另写一份）

第一段脚本里还顺带挂了一个「验收探针」：把垫片实际挂上的方法数、视口宽度、
横向溢出、页面 JS 报错写进 <meta id="astrios-probe">。它不是页面逻辑，只为了
让 Chrome 的 --dump-dom 能断言这些量（index.html 本身一个字节都不改）。

用法：

    python ios\\tools\\mock_bridge.py                 # 监听 127.0.0.1:17870（占用则顺延）
    python ios\\tools\\mock_bridge.py --port 0        # 让系统挑端口
    python ios\\tools\\mock_bridge.py --ready-file x   # 启动后把真实端口写进 x（boot_check 用）
    python ios\\tools\\mock_bridge.py --quiet         # 不逐条打印请求

启动后第一行固定打印 `READY <port>`，方便脚本/人工确认端口。
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import sys
import threading
import time
import urllib.parse
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# iOS 版自己的版本号（CONTRACT.md 第 5 节：从 0.1.0 起，与 Android 的 1.3.x 不同轨）
APP_VERSION = "0.1.0"

# CONTRACT.md 第 1 节：首选 17870，占用则 17871..17899 顺延
DEFAULT_PORT = 17870
PORT_TRIES = 30

# 本文件在 <仓库>/ios/tools/ 下，往上三层就是仓库根
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
IOS_DIR = os.path.dirname(TOOLS_DIR)
REPO_ROOT = os.path.dirname(IOS_DIR)
DEFAULT_WEB_ROOT = os.path.join(REPO_ROOT, "ui", "assets", "qqbot")
DEFAULT_SHIM = os.path.join(IOS_DIR, "shared", "AndroidShim.js")

# 静态资源类型表：不用 mimetypes（Windows 上会去查注册表，.js 常被认成 text/plain）
CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".htm": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
    ".ttf": "font/ttf",
    ".txt": "text/plain; charset=utf-8",
}

# 方法清单 = CONTRACT.md 第 3 节的分派表（L/R/X/V 都算），前端用
# `typeof Android.xxx === "function"` 做能力探测，所以这里必须列全。
METHODS = (
    # ---- 本地原生（L/V）----
    "toast", "copy", "openUrl", "openQqGroup", "openPanel", "openTerminal", "appVer",
    "disclaimerSeen", "disclaimerAck", "notiAsk", "notiState",
    "powerInfo", "powerState", "powerGuide", "powerAck",
    "pickBg", "bgImage", "clearBg", "avatarInfo", "avatarPath", "avatarIconPin",
    "statsOn", "statsSet", "statsId", "statsState", "statsReset", "statsEvents",
    "crashReportOn", "crashUploadSet", "crashReportState", "crashUploadNow",
    "crashInfo", "crashBoard", "crashClear",
    "ann", "announcement", "annRefresh", "annRefreshStart",
    # ---- 远端状态（R）----
    "state", "instancesInfo", "bootInfo", "bundleInfo", "deployInfo", "reinstallInfo",
    "envVers", "astrInfo", "netInfo", "todayStats", "riskInfo", "riskClear",
    "action", "exec", "readLog", "clearLog", "rmLog", "uploadLog",
    "saveBots", "loadBots", "toggleBot", "removeBot", "qqDetect",
    "qr", "qrShare", "astrSave", "astrSavePoll", "astrModelsStart", "astrModelsPoll",
    "proxyGet", "proxySet", "checkUpdateStart", "checkUpdatePoll", "siteVerStart", "siteVerPoll",
    "backupStart", "backupStartScope", "backupPoll", "backupInfo", "backupPick",
    "restorePick", "restorePickScope", "restoreApply", "restorePoll",
    "storageStats", "storageInfo", "storageRefresh", "storageRefreshStart", "storageClean",
    "doctorStart", "doctorText", "emergencyStop", "emergencyResume",
)


def _j(obj):
    """把对象压成一行 JSON（UTF-8、不转义中文）。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _png_bytes(width, height, pixel):
    """用 zlib + struct 现造一张 RGBA PNG（不装 pillow，也不引外链）。"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # 每行过滤器类型：0 = None
        for x in range(width):
            raw.extend(pixel(x, y))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    head = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8 位 RGBA
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def _qr_png_uri():
    """8×8 的极小占位二维码：三个角画定位块，其余按花纹铺，远看像二维码。"""
    def pixel(x, y):
        corner = (x < 3 and y < 3) or (x > 4 and y < 3) or (x < 3 and y > 4)
        if corner:
            return (15, 23, 42, 255) if (x + y) % 2 == 0 else (255, 255, 255, 255)
        return (15, 23, 42, 255) if (x * 3 + y * 5) % 7 < 3 else (255, 255, 255, 255)

    return "data:image/png;base64," + base64.b64encode(_png_bytes(8, 8, pixel)).decode("ascii")


def _avatar_png_uri():
    """32×32 的占位头像：左上到右下的蓝紫渐变，中间一圈浅色。"""
    def pixel(x, y):
        t = (x + y) / 62.0
        r = int(37 + 96 * t)
        g = int(99 + 58 * t)
        b = int(235 - 46 * t)
        if 10 <= x <= 21 and 10 <= y <= 21:
            r, g, b = min(255, r + 60), min(255, g + 60), min(255, b + 20)
        return (r, g, b, 255)

    return "data:image/png;base64," + base64.b64encode(_png_bytes(32, 32, pixel)).decode("ascii")


DEPLOY_STEPS = (
    ("10-termux", "准备 Termux 工具 (proot / tar / xz / curl)"),
    ("20-rootfs", "下载并解包 Ubuntu 24.04 根文件系统"),
    ("30-apt", "在沙盒内安装运行依赖 (Python / 图形库)"),
    ("40-qq", "安装 Linux QQ"),
    ("50-napcat", "安装 NapCat 并注入 QQ"),
    ("60-astrbot", "安装 AstrBot 与 WebUI"),
    ("65-link", "把 NapCat 与 AstrBot 对接起来"),
    ("70-launcher", "安装 qqbot-* 命令"),
)


PREVIEW_HTML = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>iOS 预览 __W__x__H__</title>
<style>
  html,body{margin:0;padding:0;background:#f3f6fc;overflow:hidden}
  iframe{border:0;display:block;position:fixed;left:0;top:0;width:__W__px;height:__H__px;background:#f3f6fc}
</style>
</head>
<body>
<iframe id="fr" src="/index.html"></iframe>
<meta id="astrios-probe" name="astrios-probe" content="{}">
<script>
/* 无头验收专用（真机 iOS 没有这个路由）：把 index.html 套进一个精确尺寸的 iframe。
   Windows 上 Chrome 无头窗口有 500px 最小宽度，直接 --window-size=430 会让页面按 500 布局
   再裁成 430（右侧被切掉）；套一层 iframe 之后，iframe 的视口才是精确的 430x932。 */
(function () {
  var fr = document.getElementById("fr");
  var meta = document.getElementById("astrios-probe");
  var errs = [];
  function probe() {
    try {
      var win = fr.contentWindow, doc = fr.contentDocument;
      if (!win || !doc) return;
      var de = doc.documentElement || {};
      var sw = de.scrollWidth || 0;
      var iw = fr.clientWidth || 0;
      var n = (win.Android && typeof win.Android === "object") ? Object.keys(win.Android).length : 0;
      meta.setAttribute("content", JSON.stringify({
        methods: n,
        title: doc.title || "",
        viewport: iw,
        scrollWidth: sw,
        overflow: sw - iw,
        errors: errs.slice(0, 20)
      }));
    } catch (e) {}
  }
  function hook() {
    try {
      var win = fr.contentWindow;
      win.addEventListener("error", function (e) { errs.push(String((e && e.message) || "error")); probe(); });
      win.addEventListener("unhandledrejection", function () { errs.push("unhandledrejection"); probe(); });
      var ce = win.console && win.console.error;
      if (ce) {
        win.console.error = function () {
          try { errs.push("console.error: " + Array.prototype.join.call(arguments, " ")); } catch (x) {}
          probe();
          return ce.apply(win.console, arguments);
        };
      }
    } catch (e) {}
    probe();
  }
  fr.addEventListener("load", function () {
    hook();
    setTimeout(probe, 500);
    setTimeout(probe, 3000);
    setTimeout(probe, 7000);
  });
  if (fr.contentDocument && fr.contentDocument.readyState === "complete") hook();
})();
</script>
</body>
</html>
"""


class MockBridge(object):
    """一套拟真的假数据：主页看起来必须像真的在跑。"""

    def __init__(self, port, web_root, shim_path):
        self.port = port
        self.web_root = web_root
        self.shim_path = shim_path
        self.started = int(time.time())
        self.qr_data = _qr_png_uri()
        self.avatar_data = _avatar_png_uri()
        self._handlers = {}
        for name in METHODS:
            fn = getattr(self, "m_" + name, None)
            if fn is None:
                raise RuntimeError("mock 桥缺少方法实现：" + name)
            self._handlers[name] = fn

    # ---------- 公共小工具 ----------
    def _bots(self):
        return {
            "active": "bot1",
            "bots": [
                {"id": "bot1", "name": "主号", "qq": "10001", "token": "zhuxin",
                 "napcatPort": 6099, "onebotPort": 6199, "enable": True, "up": True},
                {"id": "bot2", "name": "小号", "qq": "10002", "token": "zhuxin2",
                 "napcatPort": 6098, "onebotPort": 6198, "enable": True, "up": True},
            ],
        }

    def _ok_empty(self, **extra):
        obj = {"ok": True}
        obj.update(extra)
        return _j(obj)

    def _unsupported(self, why):
        return _j({"ok": False, "err": "iOS 版不支持 " + why})

    # ---------- 线格式 ----------
    def call(self, name, args):
        """返回前端拿到的那一个字符串（JSON / 纯文本 / 空串三种形态）。"""
        fn = self._handlers.get(name)
        if fn is None:
            return _j({"ok": False, "err": "mock 桥没有实现方法 " + str(name)})
        try:
            return fn(args)
        except Exception as exc:  # 兜底：桥永远不把异常甩给页面
            return _j({"ok": False, "err": "mock 桥内部错误：" + str(exc)})

    # ---------- 状态类（R）----------
    def m_state(self, args):
        now = int(time.time())
        return _j({
            "ok": True,
            "appVersion": APP_VERSION,
            "hostName": "vps-01",
            "os": "linux",
            "online": True,
            "napcatOn": True,
            "astrbotOn": True,
            "guardOn": True,
            "deployed": True,
            "napcatPort": 6099,
            "astrPort": 6185,
            "astrPortOn": True,
            "botCount": 2,
            "activeBot": "bot1",
            "uin": "10001",
            "nick": "烛心",
            "uptimeSec": now - self.started + 7320,
            "diskFree": 12.6,
            "ts": now,
            # ---- 以下是 Android 版同义字段：index.html 直接读这几个名字 ----
            "deployDone": True,
            "supervisorAlive": True,
            "napcatUp": True,
            "astrbotUp": True,
            "onebotUp": True,
            "napcatOff": False,
            "astrbotOff": False,
            "astrbotPort": 6185,
            "astrbotPortAuto": False,
            "onebotPort": 6199,
            "guardWake": True,
            "guardRunning": True,
            "guardNote": "守护运行中，每 3 秒巡检一次",
            "notiGranted": True,
            "offline": False,
            "lanIp": "192.168.1.23",
            "prefix": "/data/data/com.termux/files/usr",
            "logFiles": [
                {"name": "astrbot.log", "size": 482113, "mtime": now - 12},
                {"name": "napcat.log", "size": 265540, "mtime": now - 8},
                {"name": "qq-bot1.log", "size": 91822, "mtime": now - 30},
            ],
            "bots": self._bots(),
        })

    def m_todayStats(self, args):
        now = int(time.time())
        return _j({
            "ok": True,
            "day": time.strftime("%Y-%m-%d", time.localtime(now)),
            "onlineSec": 27540,
            "msgs": 1842,
            "errors": 3,
            "rxBytes": 19234567,
            "txBytes": 3280998,
            "warm": True,
        })

    def m_riskInfo(self, args):
        return _j({"ok": True, "risk": False, "instance": "", "word": "", "line": "",
                   "since": 0, "file": ""})

    def m_riskClear(self, args):
        return self._ok_empty(cleared=0)

    def m_netInfo(self, args):
        now = int(time.time())
        return _j({"ok": True, "online": True, "type": "wifi", "lastSwitch": now - 3600,
                   "recoveries": 2, "ts": now})

    def m_envVers(self, args):
        return _j({"ok": True, "astrbot": "4.29.1", "python": "3.12.3", "node": "20.18.0",
                   "napcat": "4.8.2", "os": "Ubuntu 24.04.1 LTS"})

    def m_astrInfo(self, args):
        return _j({
            "ok": True, "cfgExists": True, "hasKey": True,
            "base": "https://worldcodes.online/v1",
            "defaultBase": "https://worldcodes.online/v1",
            "model": "claude-sonnet-4-5", "pickedId": "claude-sonnet-4-5",
            "sourceId": "relay-main", "srcHost": "worldcodes.online",
            "keyTail": "9f2c", "persona": "烛心", "personaSel": "zhuxin",
            "personaId": "zhuxin", "defaultPersona": "烛心",
            "dashUser": "astrbot", "dashPassDefault": True,
            "dataDir": "/root/astrbot/data",
        })

    def m_instancesInfo(self, args):
        now = int(time.time())
        return _j({"ok": True, "instances": {
            "bot1": {"uin": "10001", "nick": "烛心", "alive": True,
                     "started": now - 7320, "lastOffline": now - 86400},
            "bot2": {"uin": "10002", "nick": "小号", "alive": True,
                     "started": now - 3600, "lastOffline": now - 90000},
        }})

    def m_bootInfo(self, args):
        now = int(time.time())
        return _j({"ok": True, "lastBoot": now - 86400, "lastStart": now - 7320,
                   "attempts": 1, "lastErr": ""})

    def m_deployInfo(self, args):
        steps = [{"key": k, "label": v, "done": True} for k, v in DEPLOY_STEPS]
        return _j({
            "ok": True, "done": True, "running": False, "steps": steps,
            "doneCount": len(steps), "total": len(steps),
            "logTail": "[deploy] 全部步骤完成，沙盒已就绪", "err": "",
            "logAge": 21600, "ts": int(time.time()),
        })

    def m_bundleInfo(self, args):
        size = 187654321
        return _j({"ok": True, "asset": True, "slim": False, "total": size, "size": size,
                   "ready": True, "pending": False, "done": False, "arch": "arm64-v8a"})

    def m_reinstallInfo(self, args):
        # state == "none" 时前端整块隐藏，这里就是「没在重装」
        return _j({"ok": True, "state": "none", "running": False, "pct": 0, "comp": ""})

    # ---------- 二维码 ----------
    def m_qr(self, args):
        now = int(time.time())
        qid = str(args[0]) if args else "bot1"
        # mtime 故意退到 10 分钟前：qrFresh() 只认 2.5 分钟内的图，
        # 否则 qrAutoCheck() 会自动弹扫码窗，把首屏截图整个盖住。
        return _j({"ok": True, "id": qid, "data": self.qr_data,
                   "mtime": now - 600, "until": now + 120})

    def m_qrShare(self, args):
        return self._ok_empty(shared=True)

    # ---------- 动作 / 命令（纯文本）----------
    def m_action(self, args):
        what = str(args[0]) if args else ""
        if what.startswith("svc:napcat:on"):
            return "已启动 NapCat（mock）"
        if what.startswith("svc:napcat:off"):
            return "已停止 NapCat（mock）"
        if what.startswith("svc:astrbot:on"):
            return "已启动 AstrBot（mock）"
        if what.startswith("svc:astrbot:off"):
            return "已停止 AstrBot（mock）"
        if what.startswith("guard:wake:on"):
            return "已开启守护唤醒（mock）"
        if what.startswith("deploy"):
            return "部署已完成，无需再跑（mock）"
        if what == "linkbg":
            return "已重建消息通道（mock）"
        return "已执行 " + (what or "action") + "（mock）"

    def m_exec(self, args):
        cmd = str(args[0]) if args else ""
        return ("（mock 桥）命令已受理：" + cmd + "\n"
                "宿主 vps-01 · 这一步只验证前端链路，不执行真实命令")

    def m_readLog(self, args):
        name = str(args[0]) if args else "astrbot.log"
        day = time.strftime("%Y-%m-%d", time.localtime())
        return "\n".join([
            day + " 17:52:01 [INFO] 读取日志 " + name + "（mock 数据）",
            day + " 17:52:03 [INFO] AstrBot v4.29.1 已就绪，WebUI 监听 0.0.0.0:6185",
            day + " 17:52:03 [INFO] [napcat] 已连接 OneBot v11：ws://127.0.0.1:6199",
            day + " 17:52:04 [INFO] [core] 已加载 12 个插件，当前人格：烛心",
            day + " 17:53:10 [INFO] [message] 群 872341001 <- 收到 3 条消息",
            day + " 17:53:12 [INFO] [message] 群 872341001 -> 已回复 3 条消息",
            day + " 17:54:40 [WARN] [napcat] 心跳延迟 320ms，已自动重连",
            day + " 17:54:41 [INFO] [napcat] 心跳恢复正常",
            day + " 17:55:02 [INFO] [core] 定时任务：清理临时文件完成，释放 18.4 MB",
            day + " 17:55:30 [INFO] [message] 私聊 10001 <- 收到 1 条消息",
        ])

    def m_clearLog(self, args):
        return "已清空日志（mock）"

    def m_rmLog(self, args):
        return "已删除日志文件（mock）"

    def m_uploadLog(self, args):
        return "已上传部署日志：deploy-20261023-1800.log（mock）"

    def m_saveBots(self, args):
        return "已保存 2 个实例（mock）"

    def m_loadBots(self, args):
        return _j(self._bots())

    def m_toggleBot(self, args):
        return "已切换实例状态（mock）"

    def m_removeBot(self, args):
        return "已删除实例（mock）"

    def m_qqDetect(self, args):
        return _j({"ok": True, "qq": "10001", "uin": "10001", "nick": "烛心"})

    def m_proxyGet(self, args):
        return "https://worldcodes.online/v1"

    def m_proxySet(self, args):
        return "ok"

    # ---------- AstrBot 配置 ----------
    def m_astrSave(self, args):
        return self._ok_empty(saved=True)

    def m_astrSavePoll(self, args):
        return _j({"ok": True, "state": "done", "pct": 100, "err": ""})

    def m_astrModelsStart(self, args):
        return _j({"ok": True, "started": True})

    def m_astrModelsPoll(self, args):
        return _j({"ok": True, "state": "done", "models": [
            {"id": "claude-sonnet-4-5", "name": "Claude Sonnet 4.5"},
            {"id": "gpt-4.1-mini", "name": "GPT-4.1 mini"},
            {"id": "deepseek-chat", "name": "DeepSeek Chat"},
        ]})

    # ---------- 更新 / 官网版本 ----------
    def m_checkUpdateStart(self, args):
        return _j({"ok": True, "started": True})

    def m_checkUpdatePoll(self, args):
        # hasUpdate=false：不能让 mock 触发更新弹窗，否则首屏被遮住
        return _j({"ok": True, "current": APP_VERSION, "latest": APP_VERSION,
                   "hasUpdate": False, "newer": False, "notes": "", "url": "",
                   "htmlUrl": "https://github.com/Zxin-Pro/astrbot-candle/releases"})

    def m_siteVerStart(self, args):
        return _j({"ok": True, "started": True})

    def m_siteVerPoll(self, args):
        return _j({"ok": True, "site": APP_VERSION, "updated": "2026-10-20"})

    # ---------- 备份 / 恢复 ----------
    def m_backupStart(self, args):
        return self._backup_started(args)

    def m_backupStartScope(self, args):
        return self._backup_started(args)

    def _backup_started(self, args):
        scope = str(args[0]) if args else "all"
        return _j({"ok": True, "started": True, "scope": scope,
                   "name": "astrbot-backup-20261023-1800.tar.gz"})

    def m_backupPoll(self, args):
        return _j({"ok": True, "state": "idle", "pct": 0, "name": "", "file": "",
                   "size": 0, "sha256": "", "scope": "all", "saved": False,
                   "product": False, "err": "", "ts": int(time.time())})

    def m_backupInfo(self, args):
        return _j({"ok": True, "last": None, "restoreLast": None, "backupState": "idle",
                   "restoreState": "idle", "pct": 0, "srcBytes": 0, "srcKnown": False,
                   "busy": "", "ts": int(time.time())})

    def m_backupPick(self, args):
        return _j({"ok": True, "started": True})

    def m_restorePick(self, args):
        return _j({"ok": True, "started": True})

    def m_restorePickScope(self, args):
        return _j({"ok": True, "started": True})

    def m_restoreApply(self, args):
        return _j({"ok": True, "started": True})

    def m_restorePoll(self, args):
        return _j({"ok": True, "state": "idle", "pct": 0, "name": "", "size": 0,
                   "sha256": "", "scope": "all", "needConfirm": False, "picked": False,
                   "err": "", "ts": int(time.time())})

    # ---------- 存储 ----------
    def m_storageStats(self, args):
        return self._storage()

    def m_storageInfo(self, args):
        return self._storage()

    def _storage(self):
        now = int(time.time())
        dirs = [
            {"key": "rootfs", "name": "容器", "path": "/root/ubuntu", "bytes": 1932735283},
            {"key": "astrbot", "name": "AstrBot", "path": "/root/astrbot", "bytes": 650117120},
            {"key": "plugins", "name": "插件", "path": "/root/astrbot/data/plugins", "bytes": 92274688},
            {"key": "logs", "name": "日志", "path": "/qqbot-cache/logs", "bytes": 44040192},
            {"key": "qq", "name": "登录数据", "path": "/root/qqinst", "bytes": 325058560},
        ]
        total = sum(d["bytes"] for d in dirs)
        return _j({"ok": True, "warm": True, "stale": False, "ts": now, "total": total,
                   "dirs": dirs,
                   "top": [
                       {"path": "/root/astrbot/data/data_v4.db", "bytes": 188743680},
                       {"path": "/root/qqinst/nt_db/nt_msg.db", "bytes": 96468992},
                       {"path": "/root/ubuntu/var/cache/apt/archives", "bytes": 52428800},
                   ],
                   "src": "shell", "lastErr": ""})

    def m_storageRefresh(self, args):
        return _j({"ok": True, "started": True})

    def m_storageRefreshStart(self, args):
        return _j({"ok": True, "started": True})

    def m_storageClean(self, args):
        return _j({"ok": True, "freed": 0, "kept": ["登录数据", "配置", "插件"],
                   "log": "[storage] mock 环境不做真实清理，可清理量 0"})

    # ---------- 体检 ----------
    def m_doctorStart(self, args):
        return ""

    def m_doctorText(self, args):
        return ("体检完成（mock）：\n"
                "- 宿主 vps-01：连通\n"
                "- NapCat 6099 / OneBot 6199：监听中\n"
                "- AstrBot 6185：监听中\n"
                "- 守护进程：运行中\n"
                "- 磁盘剩余：12.6 GB")

    # ---------- 紧急下线 ----------
    def m_emergencyStop(self, args):
        return _j({"ok": True, "stopped": True})

    def m_emergencyResume(self, args):
        return _j({"ok": True, "resumed": True})

    # ---------- 本地原生（L/X/V）----------
    def m_toast(self, args):
        return ""

    def m_copy(self, args):
        return ""

    def m_openUrl(self, args):
        return "ok"

    def m_openQqGroup(self, args):
        return "ok"

    def m_openPanel(self, args):
        return ""

    def m_openTerminal(self, args):
        return self._unsupported("本地终端")

    def m_appVer(self, args):
        return APP_VERSION

    def m_disclaimerSeen(self, args):
        # 必须返回 "1"：否则 index.html 启动时会自动弹免责声明，挡住首屏
        return "1"

    def m_disclaimerAck(self, args):
        return "1"

    def m_notiAsk(self, args):
        return ""

    def m_notiState(self, args):
        return self._ok_empty(on=True, enabled=True)

    def m_powerInfo(self, args):
        return self._ok_empty(on=False, enabled=False, lowPower=False)

    def m_powerState(self, args):
        return self._ok_empty(on=False, enabled=False, lowPower=False)

    def m_powerGuide(self, args):
        return self._ok_empty(opened=True)

    def m_powerAck(self, args):
        return self._ok_empty()

    def m_pickBg(self, args):
        return ""

    def m_bgImage(self, args):
        return _j({"ok": False, "err": "没有自定义背景"})

    def m_clearBg(self, args):
        return self._ok_empty()

    def m_avatarInfo(self, args):
        return _j({"ok": True, "uin": "10001", "data": self.avatar_data})

    def m_avatarPath(self, args):
        # 空 path：前端会退回「首字母圆形占位」，不会出现破图
        return _j({"ok": True, "path": "", "cached": False})

    def m_avatarIconPin(self, args):
        return self._unsupported("更换应用图标")

    def m_statsOn(self, args):
        return self._stats_off()

    def m_statsSet(self, args):
        return self._stats_off()

    def _stats_off(self):
        return _j({"ok": True, "on": False, "enabled": False, "id": "", "installId": "",
                   "day": time.strftime("%Y-%m-%d")})

    def m_statsId(self, args):
        return self._stats_state()

    def m_statsState(self, args):
        return self._stats_state()

    def _stats_state(self):
        return _j({"ok": True, "on": False, "enabled": False, "id": "", "installId": "",
                   "day": time.strftime("%Y-%m-%d"), "ts": 0, "atomOn": True,
                   "lastSentDay": "", "lastErr": "", "lastAt": 0})

    def m_statsReset(self, args):
        return self._stats_off()

    def m_statsEvents(self, args):
        return _j({"ok": True, "on": False, "events": [], "todaySec": 27540,
                   "day": time.strftime("%Y-%m-%d")})

    def m_crashReportOn(self, args):
        return self._ok_empty(on=False, enabled=False)

    def m_crashUploadSet(self, args):
        return self._ok_empty(on=False, enabled=False)

    def m_crashReportState(self, args):
        return _j({"ok": True, "on": False, "enabled": False, "ts": 0, "atomOn": True,
                   "lastSentDay": "", "lastErr": "", "lastAt": 0})

    def m_crashUploadNow(self, args):
        return _j({"ok": False, "err": "崩溃上报开关是关的", "queued": False})

    def m_crashInfo(self, args):
        return self._crash_empty()

    def m_crashBoard(self, args):
        return self._crash_empty()

    def _crash_empty(self):
        return _j({"ok": True, "on": False, "ts": 0, "classes": [], "items": [],
                   "total": 0, "kinds": 0})

    def m_crashClear(self, args):
        return self._ok_empty(cleared=0)

    def m_ann(self, args):
        return self._ann()

    def m_announcement(self, args):
        return self._ann()

    def _ann(self):
        return _j({"ok": True, "warm": True, "cached": True, "stale": False,
                   "ts": int(time.time()), "items": [], "ver": 1, "minVer": "0.1.0",
                   "lastErr": ""})

    def m_annRefresh(self, args):
        return _j({"ok": True, "started": True})

    def m_annRefreshStart(self, args):
        return _j({"ok": True, "started": True})

    # ---------- 页面 ----------
    def _inject_html(self):
        base = "http://127.0.0.1:" + str(self.port)
        config = _j({"base": base, "methods": list(METHODS)})
        probe = """/* 验收探针（mock 专用，index.html 本身不改）：把垫片挂上的方法数、视口、
   横向溢出、页面 JS 报错写进 <meta id="astrios-probe">，Chrome --dump-dom 就能断言。 */
(function () {
  var errs = [];
  function probe() {
    try {
      var n = (window.Android && typeof window.Android === "object") ? Object.keys(window.Android).length : 0;
      var de = document.documentElement || {};
      var sw = de.scrollWidth || 0;
      var iw = window.innerWidth || 0;
      var meta = document.getElementById("astrios-probe");
      if (!meta) {
        meta = document.createElement("meta");
        meta.id = "astrios-probe";
        meta.setAttribute("name", "astrios-probe");
        (document.head || de).appendChild(meta);
      }
      meta.setAttribute("content", JSON.stringify({
        methods: n,
        title: document.title || "",
        viewport: iw,
        scrollWidth: sw,
        overflow: sw - iw,
        errors: errs.slice(0, 20)
      }));
    } catch (e) {}
  }
  window.__ASTRIOS_PROBE__ = probe;
  window.addEventListener("error", function (e) {
    errs.push(String((e && (e.message || e.type)) || "error"));
    probe();
  });
  window.addEventListener("unhandledrejection", function (e) {
    errs.push("unhandledrejection: " + String((e && e.reason) || ""));
    probe();
  });
  var ce = window.console && console.error;
  if (ce) {
    console.error = function () {
      try { errs.push("console.error: " + Array.prototype.join.call(arguments, " ")); } catch (x) {}
      probe();
      return ce.apply(console, arguments);
    };
  }
  document.addEventListener("DOMContentLoaded", function () {
    probe();
    setTimeout(probe, 500);
    setTimeout(probe, 3000);
    setTimeout(probe, 8000);
  });
})();"""
        shim = ""
        try:
            with open(self.shim_path, "r", encoding="utf-8") as fh:
                shim = fh.read()
        except OSError as exc:
            shim = "/* 垫片读取失败：" + str(exc) + " */"
        # 垫片里绝不能出现 </script>，出现了就会把整段提前截断
        shim = shim.replace("</script>", "<\\/script>")
        return ("\n<script>\n"
                "/* mock 桥注入 1/2：回环服务地址 + 本机可用方法名清单（真实 iOS 由原生在 documentStart 注入） */\n"
                "window.__ASTRIOS__ = " + config + ";\n" + probe + "\n</script>\n"
                "<script>\n/* mock 桥注入 2/2：ios/shared/AndroidShim.js 全文（原样复用，不另写一份） */\n"
                + shim + "\n</script>\n")

    def page_html(self):
        index = os.path.join(self.web_root, "index.html")
        with open(index, "r", encoding="utf-8-sig") as fh:
            html = fh.read()
        marker = "</head>"
        pos = html.lower().find(marker)
        if pos < 0:
            raise RuntimeError("index.html 里找不到 </head>，没法注入垫片")
        return html[:pos] + self._inject_html() + html[pos:]

    def preview_html(self, width, height):
        """无头验收用的包装页：精确尺寸的 iframe（详见 PREVIEW_HTML 的注释）。"""
        return PREVIEW_HTML.replace("__W__", str(width)).replace("__H__", str(height))


class BridgeHandler(BaseHTTPRequestHandler):
    """一次性连接、不 keep-alive（CONTRACT.md 第 1 节）。"""

    server_version = "AstrBotMockBridge/0.1"
    protocol_version = "HTTP/1.1"

    # 由 make_server 挂上来
    bridge = None
    quiet = False

    def log_message(self, fmt, *args):
        if not self.quiet:
            sys.stderr.write("[mock] %s - %s\n" % (self.address_string(), fmt % args))

    # ---------- 发送 ----------
    def _send(self, code, ctype, body, extra=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
        self.close_connection = True

    def _send_json(self, code, obj):
        self._send(code, "application/json; charset=utf-8", _j(obj))

    def _send_text(self, code, text):
        # /__bridge 的响应体就是前端拿到的那一个字符串（CONTRACT.md 第 1、2 节）
        self._send(code, "text/plain; charset=utf-8", text)

    # ---------- 路由 ----------
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        if path in ("/", "/index.html"):
            return self._serve_index()
        if path == "/__preview":
            qs = urllib.parse.parse_qs(parsed.query)
            try:
                width = int(qs.get("w", ["430"])[0])
                height = int(qs.get("h", ["932"])[0])
            except ValueError:
                return self._send_text(400, "w / h 必须是整数")
            if not (200 <= width <= 2000 and 200 <= height <= 4000):
                return self._send_text(400, "w / h 超出范围")
            return self._send(200, "text/html; charset=utf-8",
                              self.bridge.preview_html(width, height))
        if path == "/__caps":
            return self._send_json(200, {"ok": True, "version": APP_VERSION,
                                         "host": "windows", "methods": list(METHODS)})
        return self._serve_static(path)

    def do_HEAD(self):
        return self.do_GET()

    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        if path != "/__bridge":
            return self._send_text(404, _j({"ok": False, "err": "没有这个接口：" + path}))
        length = 0
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            req = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self._send_text(400, _j({"ok": False, "err": "请求体不是合法 JSON"}))
        if not isinstance(req, dict):
            return self._send_text(400, _j({"ok": False, "err": "请求体必须是对象"}))
        name = str(req.get("m") or "")
        args = req.get("a")
        if not isinstance(args, list):
            args = []
        if not self.quiet:
            sys.stderr.write("[mock] bridge %s %s\n" % (name, _j(args)))
        return self._send_text(200, self.bridge.call(name, args))

    # ---------- 静态资源 ----------
    def _serve_index(self):
        try:
            page = self.bridge.page_html()
        except OSError as exc:
            return self._send_text(500, "读取 index.html 失败：" + str(exc))
        except RuntimeError as exc:
            return self._send_text(500, str(exc))
        return self._send(200, "text/html; charset=utf-8", page)

    def _resolve(self, url_path):
        """把 URL 路径安全地映射到磁盘文件：返回 (状态码, 绝对路径)。

        穿越（..）、反斜杠、盘符、空字节一律 400；文件不在就 404。
        """
        rel = url_path.lstrip("/")
        if not rel or "\x00" in rel or "\\" in rel or ":" in rel:
            return 400, None
        parts = [p for p in rel.split("/") if p not in ("", ".")]
        if not parts or any(p == ".." for p in parts):
            return 400, None
        root = os.path.abspath(self.bridge.web_root)
        full = os.path.abspath(os.path.join(root, *parts))
        if os.path.normcase(os.path.commonpath([full, root])) != os.path.normcase(root):
            return 400, None
        if not os.path.isfile(full):
            return 404, None
        return 200, full

    def _serve_static(self, url_path):
        code, full = self._resolve(url_path)
        if code == 400:
            return self._send_text(400, "400 路径不合法")
        if code == 404:
            return self._send_text(404, "404 没有这个文件：" + url_path)
        ext = os.path.splitext(full)[1].lower()
        ctype = CONTENT_TYPES.get(ext, "application/octet-stream")
        try:
            with open(full, "rb") as fh:
                data = fh.read()
        except OSError as exc:
            return self._send_text(500, "读取失败：" + str(exc))
        return self._send(200, ctype, data)


def build_server(host, port):
    """按 CONTRACT.md 第 1 节挑端口：首选 17870，占用就 17871..17899 顺延。"""
    candidates = [0] if port == 0 else list(range(port, port + PORT_TRIES))
    last_err = None
    for cand in candidates:
        try:
            httpd = ThreadingHTTPServer((host, cand), BridgeHandler)
        except OSError as exc:
            last_err = exc
            continue
        httpd.daemon_threads = True
        return httpd, httpd.server_address[1]
    raise SystemExit("端口都占用了，起不来：" + str(last_err))


def parse_args(argv):
    ap = argparse.ArgumentParser(description="iOS 版前端验收 mock 桥（标准库 HTTP 服务）")
    ap.add_argument("--host", default="127.0.0.1", help="监听地址，默认只绑回环")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="首选端口；0 表示让系统挑")
    ap.add_argument("--web-root", default=DEFAULT_WEB_ROOT, help="index.html 所在目录")
    ap.add_argument("--shim", default=DEFAULT_SHIM, help="AndroidShim.js 路径")
    ap.add_argument("--ready-file", default="", help="启动后把真实端口写进这个文件")
    ap.add_argument("--quiet", action="store_true", help="不逐条打印请求")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])
    web_root = os.path.abspath(args.web_root)
    if not os.path.isfile(os.path.join(web_root, "index.html")):
        sys.stderr.write("找不到 index.html：" + os.path.join(web_root, "index.html") + "\n")
        return 2
    if not os.path.isfile(args.shim):
        sys.stderr.write("找不到垫片：" + args.shim + "\n")
        return 2

    httpd, real_port = build_server(args.host, args.port)
    bridge = MockBridge(real_port, web_root, os.path.abspath(args.shim))
    BridgeHandler.bridge = bridge
    BridgeHandler.quiet = bool(args.quiet)

    if args.ready_file:
        try:
            with open(args.ready_file, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(str(real_port))
        except OSError as exc:
            sys.stderr.write("写 ready 文件失败：" + str(exc) + "\n")

    print("READY %d" % real_port, flush=True)
    print("[mock] 静态资源：%s" % web_root, flush=True)
    print("[mock] 垫片：%s" % os.path.abspath(args.shim), flush=True)
    print("[mock] 浏览器打开：http://%s:%d/" % (args.host, real_port), flush=True)
    if not args.quiet:
        print("[mock] 方法数：%d（CONTRACT.md 第 3 节）" % len(METHODS), flush=True)
    try:
        httpd.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        print("\n[mock] 收到 Ctrl+C，退出", flush=True)
    finally:
        httpd.shutdown()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
