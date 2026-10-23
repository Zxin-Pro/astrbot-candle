#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""iOS 版前端验收：真起 mock 桥 + 真跑 Chrome 无头，逐条断言并写报告。

它做四件事（全部真跑，不是「应该能跑」）：

  1. 起 ios/tools/mock_bridge.py（子进程），等它把真实端口写进 ready 文件；
  2. Chrome --dump-dom 拿渲染后的 DOM，断言：四个页签、header 副标题已刷新、
     没有「读取状态失败」、window.Android 方法数 >= 40、状态卡片版本号、今日概览数字……
  3. Chrome --screenshot 出 430x932 与 390x844 两张主页图，存 ios/tools/shots/；
  4. 收页面内探针抓到的 JS 报错（抓不到就扫 chrome_debug.log，再抓不到就跳过），
     把真实结果写进 ios/tools/boot_report.md。

用法：

    python ios\\tools\\boot_check.py                 # 默认 Chrome 路径，全部断言，写报告
    python ios\\tools\\boot_check.py --chrome "C:\\...\\chrome.exe"
    python ios\\tools\\boot_check.py --keep-temp      # 保留临时目录，方便排查

退出码：0 = 全部断言 PASS；1 = 有断言 FAIL；2 = 环境问题（Chrome / mock 起不来）。
"""
from __future__ import annotations

import argparse
import html as htmllib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
IOS_DIR = os.path.dirname(TOOLS_DIR)
REPO_ROOT = os.path.dirname(IOS_DIR)
MOCK_PY = os.path.join(TOOLS_DIR, "mock_bridge.py")
SHOTS_DIR = os.path.join(TOOLS_DIR, "shots")
REPORT_MD = os.path.join(TOOLS_DIR, "boot_report.md")
DEFAULT_CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

# 两档验收尺寸：430x932（Pro Max 级别）、390x844（标准 6.1 寸）
SHOT_SIZES = ((430, 932), (390, 844))

VIRTUAL_TIME_BUDGET = 9000


def _utf8_stdout():
    """Windows 控制台默认 cp936，中文断言输出会变乱码：能改就改成 UTF-8。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def find_free_port():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]
    finally:
        sock.close()


def make_temp(prefix):
    """建临时目录：环境变量可能没设，逐级兜底。"""
    for base in (os.environ.get("TEMP"), os.environ.get("TMP"), tempfile.gettempdir()):
        if not base:
            continue
        try:
            os.makedirs(base, exist_ok=True)
            return tempfile.mkdtemp(prefix=prefix, dir=base)
        except OSError:
            continue
    return tempfile.mkdtemp(prefix=prefix)


def http_get(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.status, resp.read()


def bridge(base, method, args=None):
    body = json.dumps({"m": method, "a": list(args or [])}).encode("utf-8")
    req = urllib.request.Request(base + "/__bridge", data=body,
                                 headers={"Content-Type": "text/plain;charset=UTF-8"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.read().decode("utf-8")


# ---------------------------------------------------------------- DOM 小工具
def strip_tags(fragment):
    return htmllib.unescape(re.sub(r"<[^>]+>", "", fragment or "")).strip()


def elem_text(doc, elem_id):
    """取某个 id 元素的纯文本（只认成对标签，够用且不引第三方解析器）。"""
    pattern = r'<([a-zA-Z][\w-]*)[^>]*\bid="' + re.escape(elem_id) + r'"[^>]*>(.*?)</\1>'
    hit = re.search(pattern, doc, re.S)
    return strip_tags(hit.group(2)) if hit else None


def elem_tag(doc, elem_id):
    """取某个 id 对应元素的开始标签。

    不能只用一条从文档头扫过去的正则：页面自己的 <script> 源码里也会出现 id="..." 字样，
    那样会先命中脚本正文。这里先定位 id="..."，再往两边扩到最近的 < 和 >。
    """
    needle = 'id="' + elem_id + '"'
    idx = doc.find(needle)
    if idx < 0:
        return ""
    start = doc.rfind("<", 0, idx)
    end = doc.find(">", idx)
    if start < 0 or end < 0:
        return ""
    return doc[start:end + 1]


def render_only(doc):
    """去掉 <script>/<style>：--dump-dom 会把页面源码一起带出来，源码里也有
    「读取状态失败」这种字样，直接全文搜会误判。"""
    text = re.sub(r"<script\b[\s\S]*?</script>", "", doc, flags=re.I)
    text = re.sub(r"<style\b[\s\S]*?</style>", "", text, flags=re.I)
    return text


def attr_value(tag, name):
    hit = re.search(r'\b' + re.escape(name) + r'="([^"]*)"', tag or "")
    return htmllib.unescape(hit.group(1)) if hit else ""


def probe_json(doc):
    """mock 注入的验收探针：方法数 / 视口 / 横向溢出 / 页面 JS 报错。"""
    raw = attr_value(elem_tag(doc, "astrios-probe"), "content")
    if not raw:
        return {}
    try:
        obj = json.loads(raw)
    except ValueError:
        return {}
    return obj if isinstance(obj, dict) else {}


def png_size(path):
    try:
        with open(path, "rb") as fh:
            head = fh.read(24)
    except OSError:
        return None
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


# ---------------------------------------------------------------- Chrome
def chrome_args(chrome, profile_dir, extra):
    return [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "--user-data-dir=" + profile_dir,
        "--virtual-time-budget=" + str(VIRTUAL_TIME_BUDGET),
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--lang=zh-CN",
        "--disable-extensions",
        "--disable-background-networking",
    ] + list(extra)


def run_chrome(chrome, profile_dir, extra, timeout=180):
    cmd = chrome_args(chrome, profile_dir, extra)
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        return proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired as exc:
        return -1, exc.stdout or b"", b"Chrome timeout: " + str(exc).encode("utf-8", "replace")


def decode(data):
    if isinstance(data, bytes):
        return data.decode("utf-8", "replace")
    return data or ""


# ---------------------------------------------------------------- 报告
class Report(object):
    def __init__(self):
        self.items = []

    def add(self, name, ok, detail):
        self.items.append({"name": name, "ok": bool(ok), "detail": str(detail)})
        mark = "PASS" if ok else "FAIL"
        print("[%s] %s —— %s" % (mark, name, detail))

    @property
    def failed(self):
        return [it for it in self.items if not it["ok"]]

    @property
    def passed(self):
        return [it for it in self.items if it["ok"]]


def write_report(path, ctx, rep, shots, console_notes):
    bt = chr(96)  # 反引号：用 chr 拼，免得源文件里出现一大片行内代码记号
    lines = []
    lines.append("# iOS 版前端验收报告（boot_check 自动生成）")
    lines.append("")
    lines.append("> 本文件由 " + bt + "ios/tools/boot_check.py" + bt + " 每次运行时重写，内容是那一次真跑的结果，不是手写。")
    lines.append("")
    lines.append("- 生成时间：" + ctx.get("time", ""))
    lines.append("- 仓库：" + bt + REPO_ROOT + bt)
    lines.append("- 页面：" + bt + "ui/assets/qqbot/index.html" + bt + "（%s）" % ctx.get("index_size", "?"))
    lines.append("- 垫片：" + bt + "ios/shared/AndroidShim.js" + bt + "（%s）" % ctx.get("shim_size", "?"))
    lines.append("- mock 服务：" + bt + "http://127.0.0.1:%s/" % ctx.get("port", "?") + bt)
    lines.append("- Python：%s" % ctx.get("python", ""))
    lines.append("- Chrome：" + bt + ctx.get("chrome", "") + bt)
    lines.append("- 无头参数：" + bt + "--headless=new --disable-gpu --no-first-run --no-default-browser-check "
                 "--user-data-dir=<临时目录> --virtual-time-budget=%d" % VIRTUAL_TIME_BUDGET + bt)
    lines.append("")
    total = len(rep.items)
    ok_count = len(rep.passed)
    if rep.failed:
        lines.append("## 结论：**FAIL**（%d/%d 通过）" % (ok_count, total))
    else:
        lines.append("## 结论：**全部 PASS**（%d/%d）" % (ok_count, total))
    lines.append("")
    lines.append("## 断言明细")
    lines.append("")
    lines.append("| # | 断言 | 结果 | 实测 |")
    lines.append("| --- | --- | --- | --- |")
    for idx, it in enumerate(rep.items, 1):
        detail = it["detail"].replace("|", "\\|").replace("\n", " ")
        lines.append("| %d | %s | %s | %s |" % (idx, it["name"], "PASS" if it["ok"] else "FAIL", detail))
    lines.append("")
    lines.append("## 截图")
    lines.append("")
    if shots:
        lines.append("| 文件 | 尺寸 | 大小 |")
        lines.append("| --- | --- | --- |")
        for shot in shots:
            lines.append("| " + bt + shot["rel"] + bt + " | %s | %s |" % (shot["size"], shot["bytes"]))
    else:
        lines.append("（这次没拿到截图，见上面的失败项）")
    lines.append("")
    lines.append("## 控制台报错")
    lines.append("")
    for note in console_notes:
        lines.append("- " + note)
    lines.append("")
    lines.append("## 复跑")
    lines.append("")
    lines.append(bt * 3)
    lines.append("python ios\\tools\\boot_check.py")
    lines.append(bt * 3)
    lines.append("")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))
    print("[report] 已写入 " + path)


# ---------------------------------------------------------------- 主流程
def parse_args(argv):
    ap = argparse.ArgumentParser(description="iOS 版前端验收：mock 桥 + Chrome 无头")
    ap.add_argument("--chrome", default=DEFAULT_CHROME, help="Chrome 可执行文件路径")
    ap.add_argument("--port", type=int, default=0, help="mock 端口，0 = 自动挑空闲端口")
    ap.add_argument("--keep-temp", action="store_true", help="保留临时目录（排查用）")
    ap.add_argument("--timeout", type=int, default=180, help="单次 Chrome 调用超时（秒）")
    return ap.parse_args(argv)


def main(argv=None):
    _utf8_stdout()
    args = parse_args(argv if argv is not None else sys.argv[1:])
    started_at = time.strftime("%Y-%m-%d %H:%M:%S %z")

    print("=" * 72)
    print("iOS 版前端验收：mock 桥 + Chrome 无头")
    print("=" * 72)

    if not os.path.isfile(args.chrome):
        print("找不到 Chrome：" + args.chrome)
        rep = Report()
        rep.add("Chrome 可执行文件存在", False, args.chrome)
        write_report(REPORT_MD, {"time": started_at, "chrome": args.chrome}, rep, [], ["未执行：Chrome 不存在"])
        return 2
    if not os.path.isfile(MOCK_PY):
        print("找不到 mock 桥：" + MOCK_PY)
        return 2

    os.makedirs(SHOTS_DIR, exist_ok=True)
    tmp = make_temp("astrios-boot-")
    port = args.port or find_free_port()
    ready_file = os.path.join(tmp, "ready.txt")
    mock_log = os.path.join(tmp, "mock.log")

    env = dict(os.environ)
    if os.name == "nt" and not env.get("SystemRoot"):
        env["SystemRoot"] = r"C:\Windows"
    if os.name == "nt" and not env.get("TEMP"):
        env["TEMP"] = env.get("SystemRoot", r"C:\Windows") + r"\Temp"
    if os.name == "nt" and not env.get("TMP"):
        env["TMP"] = env["TEMP"]

    creationflags = 0
    if os.name == "nt":
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    logfh = open(mock_log, "w", encoding="utf-8")
    mock = subprocess.Popen(
        [sys.executable, MOCK_PY, "--port", str(port), "--ready-file", ready_file, "--quiet"],
        stdout=logfh, stderr=subprocess.STDOUT, cwd=REPO_ROOT, env=env,
        creationflags=creationflags,
    )
    base = None
    try:
        deadline = time.time() + 20
        while time.time() < deadline:
            if os.path.isfile(ready_file):
                try:
                    port = int(open(ready_file, encoding="utf-8").read().strip())
                    base = "http://127.0.0.1:%d" % port
                    break
                except ValueError:
                    pass
            if mock.poll() is not None:
                break
            time.sleep(0.1)
        if not base:
            logfh.flush()
            tail = ""
            try:
                tail = open(mock_log, encoding="utf-8", errors="replace").read()[-2000:]
            except OSError:
                pass
            print("mock 桥没起来。日志：\n" + tail)
            return 2

        # 等 HTTP 真的能应答（光有 ready 文件还不够，监听可能还没就绪）
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                status, _ = http_get(base + "/__caps", timeout=2)
                if status == 200:
                    break
            except (urllib.error.URLError, OSError):
                time.sleep(0.1)
        print("[mock] %s 已就绪（pid %s）" % (base, mock.pid))

        state = json.loads(bridge(base, "state"))
        app_version = str(state.get("appVersion") or "")
        profile_root = os.path.join(tmp, "chrome")
        os.makedirs(profile_root, exist_ok=True)

        rep = Report()
        page_url = base + "/"

        def preview_url(width, height):
            """无头验收的包装页：iframe 的视口才是精确的 width×height。
            Windows 上 Chrome 无头窗口最小 500px，直接用 --window-size=430 会把页面
            按 500 布局再裁成 430（右侧被切掉），详见 mock_bridge.PREVIEW_HTML 的注释。"""
            return base + "/__preview?w=%d&h=%d" % (width, height)

        # ---------- 1) dump-dom：直接开页面，断言内容 ----------
        print("[chrome] dump-dom @ 430x932 …")
        code, out, err = run_chrome(args.chrome, os.path.join(profile_root, "dump430"),
                                    ["--window-size=430,932", "--dump-dom", page_url],
                                    timeout=args.timeout)
        doc = decode(out)
        plain = render_only(doc) if doc else ""
        probe_direct = probe_json(doc)
        if not doc.strip():
            print("[chrome] dump-dom 没输出，stderr：" + decode(err)[:800])

        # ---------- 1b) dump-dom：包装页，量真实 430 视口下的布局 ----------
        print("[chrome] dump-dom（430 宽包装页）…")
        code_p, out_p, err_p = run_chrome(args.chrome, os.path.join(profile_root, "probe430"),
                                          ["--window-size=430,932", "--dump-dom",
                                           preview_url(430, 932)],
                                          timeout=args.timeout)
        probe430 = probe_json(decode(out_p))
        if not probe430:
            print("[chrome] 430 包装页探针没读到，stderr：" + decode(err_p)[:500])

        nav = ""
        nav_hit = re.search(r"<nav[\s>][\s\S]*?</nav>", plain)
        if nav_hit:
            nav = nav_hit.group(0)
        nav_text = strip_tags(nav)
        missing_tabs = [lb for lb in ("主页", "机器人", "日志", "更多") if lb not in nav_text]
        tabs_attr = all(('data-tab="%s"' % k) in nav for k in ("home", "bots", "logs", "more"))
        rep.add("四个页签都在（主页 / 机器人 / 日志 / 更多）",
                bool(nav) and not missing_tabs and tabs_attr,
                "导航文本：" + (nav_text or "（没找到 <nav>）"))

        hdr_sub = elem_text(plain, "hdrSub") or ""
        rep.add("header 副标题不是「正在读取状态…」",
                hdr_sub != "" and "正在读取状态" not in hdr_sub,
                "hdrSub = " + (hdr_sub or "（空）"))

        rep.add("页面里没有「读取状态失败」", "读取状态失败" not in plain,
                "命中 0 处" if "读取状态失败" not in plain
                else "渲染文本里命中 %d 处" % plain.count("读取状态失败"))

        methods = int(probe430.get("methods") or 0)
        rep.add("window.Android 方法数 >= 40", methods >= 40, "实测 %d 个方法" % methods)

        v_app = elem_text(plain, "v-appver") or ""
        v_foot = elem_text(plain, "pfv-home") or ""
        want_v = app_version
        rep.add("状态卡片里的版本号文本出现了（state.appVersion=%s）" % want_v,
                bool(want_v) and v_app == want_v and v_foot == "v" + want_v,
                "#v-appver = %s，落款 #pfv-home = %s" % (v_app or "（空）", v_foot or "（空）"))

        # 首屏「现在正常吗」的判定：mock 数据对了才会是「一切正常」
        hero = elem_text(plain, "h3v") or ""
        rep.add("主页状态卡显示「一切正常」", hero == "一切正常", "h3v = " + (hero or "（空）"))

        nap = elem_text(plain, "v-napcat") or ""
        astr = elem_text(plain, "v-astrbot") or ""
        one = elem_text(plain, "v-onebot") or ""
        h3s = elem_text(plain, "h3s") or ""
        rep.add("NapCat / AstrBot 都显示为在线",
                nap == "在线" and astr == "在线" and "都在线" in h3s,
                "状态详情：NapCat %s · AstrBot %s · OneBot %s；副标题：%s"
                % (nap or "（空）", astr or "（空）", one or "（空）", h3s or "（空）"))

        t_online = elem_text(plain, "tdOnline") or ""
        t_msgs = elem_text(plain, "tdMsgs") or ""
        t_errs = elem_text(plain, "tdErrs") or ""
        t_traffic = elem_text(plain, "tdTraffic") or ""
        nums_ok = (re.search(r"\d+\s*[hm]", t_online) is not None
                   and t_msgs.isdigit() and int(t_msgs) > 0
                   and t_errs.isdigit() and t_traffic not in ("", "—"))
        rep.add("今日概览有真实数字（在线 / 消息 / 异常 / 流量）", nums_ok,
                "在线 %s · 消息 %s · 异常 %s · 流量 %s" % (t_online, t_msgs, t_errs, t_traffic))

        deploy_hint = elem_text(plain, "deployHint") or ""
        deploy_bar = attr_value(elem_tag(plain, "deployBar"), "style")
        rep.add("部署进度 100%（部署台写着「部署已完成」）",
                "部署已完成" in deploy_hint and "100%" in deploy_bar,
                "deployHint = %s；#deployBar style = %s" % (deploy_hint or "（空）", deploy_bar or "（空）"))

        title = probe_direct.get("title") or ""
        if not title:
            hit = re.search(r"<title>(.*?)</title>", plain, re.S)
            title = strip_tags(hit.group(1)) if hit else ""
        rep.add("<title> 抓到了", bool(title), "title = " + (title or "（空）"))

        over430 = int(probe430.get("overflow") or 0)
        rep.add("430 宽下没有横向溢出", bool(probe430) and over430 <= 0,
                "scrollWidth %s / innerWidth %s，溢出 %s px"
                % (probe430.get("scrollWidth"), probe430.get("viewport"), over430))

        page_errors = (probe430.get("errors") or probe_direct.get("errors") or [])
        rep.add("页面没有未捕获的 JS 报错", len(page_errors) == 0,
                "0 条" if not page_errors else "；".join(str(e)[:120] for e in page_errors[:5]))

        # ---------- 2) dump-dom @ 390x844（只看窄屏溢出）----------
        print("[chrome] dump-dom（390 宽包装页）…")
        code2, out2, err2 = run_chrome(args.chrome, os.path.join(profile_root, "dump390"),
                                       ["--window-size=390,844", "--dump-dom",
                                        preview_url(390, 844)],
                                       timeout=args.timeout)
        probe390 = probe_json(decode(out2))
        over390 = int(probe390.get("overflow") or 0)
        rep.add("390 宽下没有横向溢出", bool(probe390) and over390 <= 0,
                "scrollWidth %s / innerWidth %s，溢出 %s px"
                % (probe390.get("scrollWidth"), probe390.get("viewport"), over390))

        # ---------- 3) 截图 ----------
        shots = []
        for width, height in SHOT_SIZES:
            name = "home-%dx%d.png" % (width, height)
            path = os.path.join(SHOTS_DIR, name)
            print("[chrome] 截图 %dx%d …" % (width, height))
            # 截图也走包装页：图片左 width×height 就是 iframe 里的真实手机视口
            rc, sout, serr = run_chrome(args.chrome, os.path.join(profile_root, "shot%d" % width),
                                        ["--window-size=%d,%d" % (width, height),
                                         "--screenshot=" + path, preview_url(width, height)],
                                        timeout=args.timeout)
            size = png_size(path)
            exists = os.path.isfile(path)
            bytes_len = os.path.getsize(path) if exists else 0
            ok = exists and size == (width, height) and bytes_len > 30000
            rep.add("截图 %dx%d 生成且不是空白页" % (width, height), ok,
                    "%s，%s，%s" % (path if exists else "（没生成）",
                                   "%dx%d" % size if size else "尺寸读不到",
                                   "%.1f KB" % (bytes_len / 1024.0)))
            if exists:
                shots.append({"rel": "ios/tools/shots/" + name,
                              "size": ("%dx%d" % size) if size else "?",
                              "bytes": "%.1f KB" % (bytes_len / 1024.0)})

        # ---------- 4) 控制台报错兜底 ----------
        console_notes = []
        if page_errors:
            console_notes.append("页面内探针抓到 %d 条 JS 报错：%s"
                                 % (len(page_errors), "；".join(str(e)[:160] for e in page_errors[:5])))
        else:
            console_notes.append("页面内探针：没有未捕获的 JS 报错"
                                 "（window.onerror / unhandledrejection / console.error 都没触发）")
        log_path = os.path.join(profile_root, "dump430", "chrome_debug.log")
        if os.path.isfile(log_path):
            hits = []
            scanned = 0
            try:
                with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        scanned += 1
                        if "ERROR:CONSOLE" in line or "Uncaught" in line:
                            hits.append(line.strip()[:200])
            except OSError:
                pass
            if hits:
                console_notes.append("chrome_debug.log 里发现 %d 条控制台错误：%s"
                                     % (len(hits), "；".join(hits[:5])))
            else:
                console_notes.append("chrome_debug.log：扫了 %d 行，没有 ERROR:CONSOLE / Uncaught" % scanned)
        else:
            console_notes.append("chrome_debug.log 没生成（Chrome 版本差异），控制台报错以页面内探针为准")

        ctx = {
            "time": started_at,
            "port": port,
            "chrome": args.chrome,
            "python": sys.version.split()[0],
            "index_size": "%.0f KB" % (os.path.getsize(
                os.path.join(REPO_ROOT, "ui", "assets", "qqbot", "index.html")) / 1024.0),
            "shim_size": "%.1f KB" % (os.path.getsize(
                os.path.join(IOS_DIR, "shared", "AndroidShim.js")) / 1024.0),
        }
        write_report(REPORT_MD, ctx, rep, shots, console_notes)

        print("")
        print("-" * 72)
        print("断言：%d 条，PASS %d，FAIL %d" % (len(rep.items), len(rep.passed), len(rep.failed)))
        for it in rep.failed:
            print("  FAIL  " + it["name"] + " —— " + it["detail"])
        print("报告：" + REPORT_MD)
        print("截图：" + SHOTS_DIR)
        print("-" * 72)
        return 0 if not rep.failed else 1

    finally:
        try:
            logfh.close()
        except Exception:
            pass
        if mock.poll() is None:
            mock.terminate()
            try:
                mock.wait(timeout=10)
            except subprocess.TimeoutExpired:
                mock.kill()
        if not args.keep_temp:
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            print("[temp] 保留临时目录：" + tmp)


if __name__ == "__main__":
    _utf8_stdout()
    sys.exit(main())
