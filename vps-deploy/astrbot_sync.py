#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把官网 data/astrbot.json 刷成「AstrBot烛版 下载页」。

设计要点：
  1) 只改 data/astrbot.json 一个文件，不碰 JS / CSS / HTML。
     站点本身是「内容与渲染分离」的（assets/astrbot.<hash>.js 顶部注释写明了
     data/astrbot.json 的结构就是唯一消费契约），所以改内容只要改这个 JSON。
     不动 JS 也就不需要改 HTML 里的 ?v= 缓存指纹。
  2) 结构校验照抄渲染器的约束，保证写出去的一定能被渲染：
       - 子栏 key 必须匹配 ^[A-Za-z_][A-Za-z0-9_]{0,63}$ 且不重复
       - 最多 12 个子栏、每栏最多 200 张卡
  3) 原子写：先写 .tmp 再 os.replace，避免访客读到半截 JSON。
  4) 纯标准库，无第三方依赖。任何一步失败都不动线上文件（exit 1），
     线上保留上一次的好数据。

用法：
  python3 astrbot_sync.py              # 正常同步
  python3 astrbot_sync.py --dry-run    # 只打印，不写文件
"""

import json
import os
import re
import sys
import time
import urllib.request

REPO = "Zxin-Pro/astrbot-candle"
HERE = os.path.dirname(os.path.abspath(__file__))
# 目标文件：默认是「脚本目录/data/astrbot.json」，装到别处时用环境变量指定。
TARGET = os.environ.get("ASTRBOT_JSON") or os.path.join(HERE, "data", "astrbot.json")
GH_API = "https://api.github.com/repos/" + REPO + "/releases/latest"
GH_ALL = "https://api.github.com/repos/" + REPO + "/releases?per_page=8"
RELEASES_PAGE = "https://github.com/" + REPO + "/releases"
MIRROR_PREFIX = "https://ghfast.top/"

KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
MAX_TABS = 12
MAX_CARDS = 200
UA = "astrbot-candle-site-sync/1.0"
NL = chr(10)


def http_json(url, timeout=20):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def human_size(n):
    n = int(n or 0)
    if n >= 1048576:
        return "%.1f MB" % (n / 1048576.0)
    if n >= 1024:
        return "%d KB" % round(n / 1024.0)
    return "%d B" % n


def pick_apk(rel):
    for a in (rel.get("assets") or []):
        if str(a.get("name", "")).lower().endswith(".apk"):
            return a
    return None


def validate(doc):
    """照抄 assets/astrbot.js 的约束，返回 (ok, 错误说明)。"""
    if not isinstance(doc, dict):
        return False, "top level is not an object"
    tabs = doc.get("tabs")
    if not isinstance(tabs, list) or not tabs:
        return False, "tabs is empty"
    if len(tabs) > MAX_TABS:
        return False, "too many tabs: %d" % len(tabs)
    seen = set()
    for i, t in enumerate(tabs):
        if not isinstance(t, dict):
            return False, "tab %d is not an object" % i
        k = str(t.get("key", ""))
        if not KEY_RE.match(k):
            return False, "tab %d has bad key %r" % (i, k)
        if k in seen:
            return False, "duplicate tab key %r" % k
        seen.add(k)
        for f in ("label", "icon", "desc"):
            if not isinstance(t.get(f, ""), str):
                return False, "tab %s field %s is not a string" % (k, f)
        cards = t.get("cards")
        if not isinstance(cards, list):
            return False, "tab %s cards is not a list" % k
        if len(cards) > MAX_CARDS:
            return False, "tab %s has too many cards" % k
        for j, c in enumerate(cards):
            if not isinstance(c, dict):
                return False, "tab %s card %d is not an object" % (k, j)
            for f in ("icon", "title", "desc", "link", "text"):
                if not isinstance(c.get(f, ""), str):
                    return False, "tab %s card %d field %s is not a string" % (k, j, f)
    return True, ""


def build(latest, all_rels):
    tag = latest.get("tag_name") or "v0.0.0"
    ver = tag.lstrip("vV")
    apk = pick_apk(latest)
    apk_size = human_size(apk["size"]) if apk else ""
    dl = apk["browser_download_url"] if apk else RELEASES_PAGE
    digest = (apk or {}).get("digest") or ""
    sha = digest.split(":", 1)[1] if digest.startswith("sha256:") else ""
    pub = (latest.get("published_at") or "")[:10]
    now = time.strftime("%Y-%m-%d %H:%M:%S")

    d_cards = [
        {
            "icon": "📦",
            "title": "AstrBot烛版 %s（最新版）" % ver,
            "desc": "安卓安装包 · %s%s" % (apk_size, (" · " + pub) if pub else "") + NL +
                    "安卓 8.0 及以上 · 仅 arm64-v8a（现在绝大多数手机）" + NL +
                    "包名 com.termux，如果装过官方 Termux 要先卸载",
            "link": dl,
        },
        {
            "icon": "🚀",
            "title": "国内加速下载",
            "desc": "GitHub 直连慢或者打不开时用这个。" + NL + "内容与上面的安装包完全一样。",
            "link": MIRROR_PREFIX + dl,
        },
        {
            "icon": "🕘",
            "title": "所有历史版本",
            "desc": "想装旧版、或者想看每一版改了什么，都在这里。",
            "link": RELEASES_PAGE,
        },
    ]
    if sha:
        d_cards.append({
            "icon": "🔐",
            "title": "SHA-256 校验值（%s）" % tag,
            "desc": "点这张卡复制，然后和下载到的文件比对，一样就说明没被改过。",
            "text": sha,
        })

    what_cards = [
        {
            "icon": "🤖",
            "title": "这是什么",
            "desc": "一个安卓应用，把两样东西打包在一起：" + NL + NL +
                    "· AstrBot —— 机器人框架，负责接大模型、管插件、发消息" + NL +
                    "· NapCat —— QQ 协议端，让机器人能收能发 QQ 消息" + NL + NL +
                    "装上打开之后，它会在手机里自动建一个 Linux 沙盒，把这两样都装好、对接好，"
                    "你只要扫码登录 QQ 就能用。",
        },
        {
            "icon": "📵",
            "title": "不需要电脑、不需要服务器",
            "desc": "整个过程都在手机上跑，不用买服务器、不用连电脑、不用 Root。",
        },
        {
            "icon": "🧩",
            "title": "装完之后你能做什么",
            "desc": "· 在应用里看 NapCat 与 AstrBot 的实时日志" + NL +
                    "· 一键把 NapCat 和 AstrBot 对接起来" + NL +
                    "· 复制登录地址和 Token，换设备扫码" + NL +
                    "· 配多个 QQ 号，每个号独立开关容器" + NL +
                    "· 进 AstrBot 控制台装插件、改人格",
        },
    ]

    howto_cards = [
        {
            "icon": "1️⃣",
            "title": "下载安装包",
            "desc": "点「下载」栏里最上面那张卡。下完点开，系统会提示「未知来源」，允许一次即可。",
        },
        {
            "icon": "2️⃣",
            "title": "打开应用，等它自动部署",
            "desc": "第一次打开会自动下载并安装运行环境，一共要下 250 MB 左右，大概 20 到 60 分钟，"
                    "取决于网速。期间请保持应用在前台、别锁屏。" + NL +
                    "中途断了也不要紧，下次打开会接着上次的进度继续。",
        },
        {
            "icon": "3️⃣",
            "title": "扫码登录 QQ",
            "desc": "部署完成后到「日志」页，会出现一张二维码。用自己的手机 QQ 扫它"
                    "（用本机扫不了，得用另一台设备打开 http://手机IP:6099 ）。",
        },
        {
            "icon": "4️⃣",
            "title": "开始用",
            "desc": "回到「主页」点「自动连接 NapCat ↔ AstrBot」，指示灯变绿就好了。" + NL +
                    "想改人格、装插件，去「更多 → AstrBot 控制台」。",
        },
    ]

    ch_cards = []
    for rel in (all_rels or [])[:6]:
        if rel.get("draft"):
            continue
        rtag = rel.get("tag_name") or ""
        rpub = (rel.get("published_at") or "")[:10]
        rbody = (rel.get("body") or "").strip()
        if len(rbody) > 1200:
            rbody = rbody[:1200] + "…"
        ch_cards.append({
            "icon": "📝",
            "title": "%s%s" % (rtag, ("  ·  " + rpub) if rpub else ""),
            "desc": rbody or "（这一版没写更新说明）",
            "link": rel.get("html_url") or RELEASES_PAGE,
            "text": rbody,
        })
    if not ch_cards:
        ch_cards.append({
            "icon": "📝",
            "title": "暂无更新日志",
            "desc": "去 GitHub 看：" + RELEASES_PAGE,
            "link": RELEASES_PAGE,
        })

    return {
        "title": "AstrBot烛版",
        "subtitle": "手机上一键跑起 AstrBot + NapCat",
        "intro": "把 AstrBot 和 NapCat 打包成一个安卓安装包，安装打开后自动在手机里建好 Linux 沙盒并部署完成，扫码登录即可用。",
        "updated": now,
        "tabs": [
            {"key": "download",  "label": "下载",     "icon": "⬇️", "desc": "最新安装包与校验值", "cards": d_cards},
            {"key": "what",      "label": "这是什么", "icon": "🤖", "desc": "它到底装了什么",     "cards": what_cards},
            {"key": "howto",     "label": "怎么用",   "icon": "📱", "desc": "从下载到能用共四步", "cards": howto_cards},
            {"key": "changelog", "label": "更新日志", "icon": "📝", "desc": "每一版改了什么",     "cards": ch_cards},
        ],
    }


def main():
    dry = "--dry-run" in sys.argv
    latest = http_json(GH_API)
    try:
        all_rels = http_json(GH_ALL)
    except Exception as e:
        print("warn: release list failed (%s), changelog will be thin" % e, file=sys.stderr)
        all_rels = [latest]
    doc = build(latest, all_rels)

    ok, why = validate(doc)
    if not ok:
        print("refusing to write, schema check failed: %s" % why, file=sys.stderr)
        return 1

    out = json.dumps(doc, ensure_ascii=False, indent=2) + NL
    if dry:
        print(out)
        return 0

    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    tmp = TARGET + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(out)
    os.replace(tmp, TARGET)
    print("wrote %s (%d bytes, %d tabs)" % (TARGET, len(out.encode("utf-8")), len(doc["tabs"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
