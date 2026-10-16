#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
烛心站点 · AstrBot 栏「下载页」每日同步
------------------------------------------------------------
把站上 AstrBot 那一栏的内容，刷成「AstrBot烛版 的下载页」，并且每天自动跟着
GitHub Releases 走：发了新版本，下载直链、版本号、体积、SHA-256 自己就变了。

为什么写两份文件：
  站点本来的 AstrBot 栏是「内容与渲染分离」的，而且分母本/公开两份 ——
    data/astrbot_content.json   母本，后台编辑器读它
    data/astrbot.json           前端 fetch 的公开那份
  两者由站点自己的 scripts/astrbot_content.py 的 save() 一起写（写前会备份到
  data/astrbot_bak/，最多留 30 份）。本脚本**优先调用那个 save()**，于是：
    · 裁剪规则、备份位置、文件权限与站点完全一致
    · 后台编辑器里看到的也是同一份内容，不会在你下次在后台点保存时被打回去
  万一那个脚本不在（比如站点目录被挪走），就退回自己把两份都原子写一遍。

不碰 JS / CSS / HTML：assets/astrbot.<hash>.js 顶部注释写明了这个 JSON 结构就是
唯一的消费契约，所以只改内容，不用去改 HTML 里的 ?v= 缓存指纹。

结构校验照抄渲染器（assets/astrbot.js）与站点 save() 的约束，写出去的一定能被渲染：
  · 子栏 key 必须匹配 ^[A-Za-z_][A-Za-z0-9_]{0,63}$ 且不重复
  · 最多 12 个子栏、每栏最多 200 张卡
  · 字段长度上限 icon 8 / title 80 / desc 2000 / link 500 / text 20000
任何一步失败都不动线上文件（exit 1），线上保留上一次的好数据。

用法：
  python3 astrbot_download_sync.py                  # 同步（站点目录默认 /data/www/zhuxin-site）
  python3 astrbot_download_sync.py --dry-run        # 只打印，不写文件
  python3 astrbot_download_sync.py --site-dir=/path # 指定站点根目录
"""

import json
import os
import re
import sys
import time
import urllib.request

REPO = "Zxin-Pro/astrbot-candle"
DEFAULT_SITE = "/data/www/zhuxin-site"


def _arg(prefix):
    for a in sys.argv[1:]:
        if a.startswith(prefix):
            return a.split("=", 1)[1]
    return ""


SITE = (_arg("--site-dir=") or os.environ.get("ZHUXIN_SITE") or DEFAULT_SITE).rstrip("/")
DATA = os.path.join(SITE, "data")
SCRIPTS = os.path.join(SITE, "scripts")
CFG = os.path.join(DATA, "astrbot_content.json")   # 母本（后台编辑）
PUB = os.path.join(DATA, "astrbot.json")           # 前端读取
BAK_DIR = os.path.join(DATA, "astrbot_bak")
FALLBACK = os.path.join(SCRIPTS, "astrbot_download_sync.fallback.json")
# 这几个子栏由本脚本每天重写；其它子栏（比如你自己写的人格分享）原样保留。
MANAGED_KEYS = ("download", "what", "howto", "changelog")

RELEASES_PAGE = "https://github.com/" + REPO + "/releases"
MIRROR_PREFIX = "https://ghfast.top/"

KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
MAX_TABS = 12
MAX_CARDS = 200
# 站点 save() 的字段长度上限，照抄，免得写出去被它截一刀。
MAX_LEN = {"icon": 8, "title": 80, "desc": 2000, "link": 500, "text": 20000,
           "label": 20, "key": 40, "subtitle": 200, "intro": 4000}
UA = "astrbot-candle-site-sync/2.0"
NL = chr(10)

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
            # 站点 front 端 contentCard() 里 text 的优先级高于 link：两者同时非空时
            # 卡片会渲染成「可复制卡」，link 静默失效。这里直接当成错误拦下来，
            # 免得以后再写出「看着有链接、点不动」的卡片。
            if str(c.get("text") or "").strip() and str(c.get("link") or "").strip():
                return False, ("tab %s card %d 同时有 text 和 link：站点会按 text 渲染，"
                               "link 会丢掉。更新说明请放 desc。") % (k, j)
    return True, ""


def build(latest, all_rels, extra=None):
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
        # 这里千万不要加 text：站点的 contentCard() 判断顺序是「先看 text，再看 link」，
        # 只要卡里有 text 就会被渲染成「可复制卡」，link 被直接丢掉 —— 用户点不进 release 页。
        # 更新说明放进 desc（CSS 是 white-space:pre-wrap，换行照样在），整张卡都能点开。
        ch_cards.append({
            "icon": "📝",
            "title": "%s%s" % (rtag, ("  ·  " + rpub) if rpub else ""),
            "desc": rbody or "（这一版没写更新说明）",
            "link": rel.get("html_url") or RELEASES_PAGE,
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
        ] + list(extra or [])[:max(0, MAX_TABS - 4)],
    }

def _clip(s, field, default=""):
    """按站点的字段上限截断（站点 save() 也会截，这里先截一次，保证 --dry-run 打印的就是最终结果）。

    default 与站点 astrbot_content._clip 的第三参数同义：值是 None 时用它兜底，
    子栏的 label 就是这么兜的（没写名字就显示它的 key）。
    """
    s = (default if s is None else str(s)).strip()
    n = MAX_LEN.get(field)
    if n and len(s) > n:
        return s[:n]
    return s


def load_extra_tabs():
    """把现有内容里「不是我们管的」子栏原样捡回来。

    这样每天同步只刷新下载/介绍/教程/更新日志这四栏，你之前写在里面的东西
    （比如人格分享）不会被我覆盖掉；你在后台改它们也不会被冲掉。
    """
    try:
        with open(CFG, encoding="utf-8") as f:
            d = json.load(f)
    except Exception:
        return []
    out = []
    for t in (d.get("tabs") or []):
        if isinstance(t, dict) and str(t.get("key") or "") not in MANAGED_KEYS:
            out.append(t)
    return out


def normalize(d):
    """按站点 save() 的规则把内容归一化。

    站点 save() 会逐字段裁剪、给子栏 key 去重，字段上限见 MAX_LEN。这里先做一遍，
    好处是 --dry-run 打印出来的就是最终会被写进文件的样子（一个字节都不差）。
    注意：文档的 title 在站点那边是按 label 的上限（20 字）裁的，别写太长。
    """
    out = {
        "title": _clip(d.get("title"), "label") or "AstrBot",
        "subtitle": _clip(d.get("subtitle"), "subtitle"),
        "intro": _clip(d.get("intro"), "intro"),
        "tabs": [],
        "updated": d.get("updated") or time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    used = set()
    for t in (d.get("tabs") or [])[:MAX_TABS]:
        if not isinstance(t, dict):
            continue
        k = re.sub(r"[^A-Za-z0-9_]", "", str(t.get("key") or ""))[:40] or "tab"
        base, i = k, 2
        while k in used:
            k = "%s%d" % (base, i)
            i += 1
        used.add(k)
        cards = []
        for c in (t.get("cards") or [])[:MAX_CARDS]:
            if not isinstance(c, dict):
                continue
            cards.append({
                "icon": _clip(c.get("icon"), "icon"),
                "title": _clip(c.get("title"), "title"),
                "desc": _clip(c.get("desc"), "desc"),
                "link": _clip(c.get("link"), "link"),
                "text": _clip(c.get("text"), "text"),
            })
        out["tabs"].append({
            "key": k,
            "label": _clip(t.get("label"), "label", k) or k,
            "icon": _clip(t.get("icon"), "icon"),
            "desc": _clip(t.get("desc"), "desc"),
            "cards": cards,
        })
    return out


def load_fallback():
    """安装时随包放的离线内容：万一这台机器连不上 GitHub，第一次也能有东西可看。"""
    try:
        with open(FALLBACK, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def site_saver():
    """借站点自己的 scripts/astrbot_content.py 来写。

    它能一起写母本 astrbot_content.json 与公开的 astrbot.json，并且按站点约定备份到
    data/astrbot_bak/（最多 30 份）、写入时原子替换、权限 0644。拿不到就返回 None。
    """
    try:
        if SCRIPTS not in sys.path:
            sys.path.insert(0, SCRIPTS)
        import astrbot_content as ac
        # 万一站点装在别处，把它写死的路径改成我们探测到的
        ac.ROOT = SITE
        ac.DATA = DATA
        ac.CFG = CFG
        ac.PUB = PUB
        ac.BAK_DIR = BAK_DIR
        ac.MAX_TABS = MAX_TABS
        ac.MAX_CARDS = MAX_CARDS
        return ac
    except Exception:
        return None


def write_both(doc):
    """退路：自己把母本与公开两份都原子写一遍。"""
    os.makedirs(DATA, exist_ok=True)
    txt = json.dumps(doc, ensure_ascii=False, indent=2)
    for p in (CFG, PUB):
        tmp = p + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(txt)
        os.replace(tmp, p)
        try:
            os.chmod(p, 0o644)
        except Exception:
            pass


def main():
    dry = "--dry-run" in sys.argv
    quiet = "--quiet" in sys.argv
    print("[astrbot-dl] 站点目录：%s" % SITE)
    if not os.path.isdir(DATA):
        print("[astrbot-dl] 找不到 %s" % DATA, file=sys.stderr)
        print("[astrbot-dl] 用 --site-dir=/站点/根目录 指定，或设环境变量 ZHUXIN_SITE", file=sys.stderr)
        return 1

    ver = ""
    try:
        latest = http_json(GH_API)
        ver = str(latest.get("tag_name") or "")
        try:
            all_rels = http_json(GH_ALL)
        except Exception as e:
            print("[astrbot-dl] 历史版本列表拉取失败（只用最新版）：%s" % e, file=sys.stderr)
            all_rels = [latest]
        extra = load_extra_tabs()
        if extra:
            print("[astrbot-dl] 保留原有子栏：%s" % ", ".join(str(t.get("key")) for t in extra))
        doc = build(latest, all_rels, extra)
    except Exception as e:
        print("[astrbot-dl] 连不上 GitHub：%s" % e, file=sys.stderr)
        doc = load_fallback()
        if doc is None:
            print("[astrbot-dl] 也没有离线兜底内容，本次不写文件（线上保留上一次的好数据）", file=sys.stderr)
            return 1
        print("[astrbot-dl] 改用离线兜底内容：%s" % FALLBACK, file=sys.stderr)
    doc = normalize(doc)
    ok, why = validate(doc)
    if not ok:
        print("[astrbot-dl] 结构校验没过，不写文件：%s" % why, file=sys.stderr)
        return 1

    tabs = doc.get("tabs") or []
    ncard = sum(len(t.get("cards") or []) for t in tabs)
    if dry:
        print(json.dumps(doc, ensure_ascii=False, indent=2))
        print("[astrbot-dl] --dry-run：没有写任何文件", file=sys.stderr)
        return 0

    ac = site_saver()
    try:
        if ac is not None:
            ac.save(doc, do_backup=True)
            how = "站点 astrbot_content.save()"
        else:
            write_both(doc)
            how = "直接写两份（站点脚本不可用）"
    except Exception as e:
        print("[astrbot-dl] 写入失败：%s" % e, file=sys.stderr)
        return 1

    print("[astrbot-dl] 已更新：%s 与 %s（%d 个子栏 / %d 张卡）" % (CFG, PUB, len(tabs), ncard))
    if not quiet:
        print("[astrbot-dl] 写入方式：%s" % how)
        print("[astrbot-dl] 内容版本：%s（线上最新 %s）" % (doc.get("title", ""), ver or "未知"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
