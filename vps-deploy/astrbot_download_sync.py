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

安装包顺便也在站点自己的 downloads/ 里存一份（最近 KEEP_VERSIONS 个版本）：页面上的
下载按钮直接指向本站文件，用户点一下就开始下载，不用被甩去 GitHub —— 国内直连
GitHub 经常打不开，加速镜像也时好时坏。顺便在 downloads/index.html 生成一个列表页。

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

import hashlib
import html
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
# 这几个子栏由本脚本每天重写。
# 其它子栏（例如早先写的人格分享）由 KEEP_EXTRA_TABS 决定要不要原样保留。
MANAGED_KEYS = ("download", "what", "howto", "changelog")

# ---- 站内下载：页面只指本站，用户点下载不会再被甩去 GitHub ----
DL_DIR = os.path.join(SITE, "downloads")     # 安装包就放这儿，nginx 直接从站点根目录发出去
DL_URL = "/downloads"                        # 页面里用同源地址
PUBLIC_BASE = "https://xn--15t839a.xyz"      # 复制「下载直链」时用的站点域名
KEEP_VERSIONS = 4                            # 站内最多留几个版本（每个约 24 MB）
APK_TPL = "AstrBot-%s.apk"                   # 站内文件名

RELEASES_PAGE = "https://github.com/" + REPO + "/releases"
MIRROR_PREFIX = "https://ghfast.top/"
# 服务器自己抓安装包时的加速镜像：直连 GitHub 的安装包地址会 302 跳到
# objects.githubusercontent.com，国内机房经常连不上（实测 timeout）。
# 用户那边看到的一直是站内地址，这里用镜像只是「进货渠道」，对用户无感。
MIRROR_FALLBACKS = ("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")

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
    """挑一个安装包镜像到站内：优先完整包（不是 -update.apk），其次优先 arm64。

    GitHub 的资产列表按 id 降序返回，最新上传的排在最前；1.2.0 起同一个 release
    里既有完整包也有更新包（"-update.apk"，只有 25 MB），直接取第一个会把更新包
    当成主下载，所以要按名字打分挑。
    """
    best, best_score = None, -1
    for a in (rel.get("assets") or []):
        name = str(a.get("name", "")).lower()
        if not name.endswith(".apk"):
            continue
        score = 4 if "-update" not in name else 0
        if "arm64" in name:
            score += 2
        if score > best_score:
            best, best_score = a, score
    return best


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _http_get_to(url, dest, timeout=180):
    """下载 url 到 dest。先写 .part 再改名，中途断了不会留下半个文件。返回字节数。"""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    tmp = dest + ".part"
    n = 0
    with urllib.request.urlopen(req, timeout=timeout) as r:
        with open(tmp, "wb") as f:
            for chunk in iter(lambda: r.read(1 << 20), b""):
                f.write(chunk)
                n += len(chunk)
    os.replace(tmp, dest)
    try:
        os.chmod(dest, 0o644)
    except Exception:
        pass
    return n


def _fetch_apk(url, dest, want=0, sha=""):
    """把安装包抓到 dest，返回实际字节数。

    直连 GitHub 的 asset 地址会 302 跳到 objects.githubusercontent.com，
    实测在这台机房的 VPS 上是直接超时（直连 302 之后 0 字节），加镜像前缀
    却有 600 KB/s。所以按「直连 → 各家加速镜像」依次重试。
    下完先核字节数，GitHub 给了 sha256 就再核一遍哈希 —— 镜像返回半截文件或
    被人换了包，都在这里拦下来，绝不让坏文件进站内目录。
    """
    last = None
    # 镜像排前面：直连不是「连不上」，而是「能连但只有 30 KB/s」，靠失败回退是救不了的，
    # 25 MB 要下十几分钟。完整性有 GitHub 官方给的 sha256 兜底（对不上就不收），所以先走快的。
    for src in [p + url for p in MIRROR_FALLBACKS] + [url]:
        host = src.split("/")[2]
        try:
            n = _http_get_to(src, dest)
            if want and n != want:
                raise ValueError("字节数不对（下到 %d，应该是 %d）" % (n, want))
            if sha:
                got = sha256_file(dest)
                if got != sha:
                    raise ValueError("哈希对不上（下到 %s…）" % got[:16])
            return n
        except Exception as e:
            last = e
            try:
                os.remove(dest + ".part")
            except Exception:
                pass
            print("[astrbot-dl] 从 %s 抓失败（%s），换下一个源" % (host, e), file=sys.stderr)
    raise last


def mirror_releases(rels, quiet=False):
    """把最近几个版本的安装包存一份到站点自己的 downloads/ 目录。

    为什么这么做：下载按钮直接指本站文件，用户点一下就开始下载，不用被甩去 GitHub。
    国内直连 GitHub 经常打不开，加速镜像也时好时坏，站内存一份最稳。
    返回 {版本号: 信息}，信息里的 url 是站内相对地址。
    """
    out = {}
    kept = []
    try:
        os.makedirs(DL_DIR, exist_ok=True)
    except Exception as e:
        print("[astrbot-dl] 建不了 %s，这次不做站内下载：%s" % (DL_DIR, e), file=sys.stderr)
        return out
    for rel in rels:
        if len(kept) >= KEEP_VERSIONS:
            break
        if rel.get("draft"):
            continue
        apk = pick_apk(rel)
        if not apk:
            continue
        ver = str(rel.get("tag_name") or "").lstrip("vV")
        if not ver or "/" in ver or ver in out:
            continue
        name = APK_TPL % ver
        dest = os.path.join(DL_DIR, name)
        want = int(apk.get("size") or 0)
        _dg = str(apk.get("digest") or "")
        sha_want = _dg.split(":", 1)[1] if _dg.startswith("sha256:") else ""
        have = os.path.getsize(dest) if os.path.isfile(dest) else -1
        fresh = bool(want) and have == want
        if fresh and sha_want:
            try:
                if sha256_file(dest) != sha_want:
                    fresh = False   # 字节数一样但内容换过（同版本重打包），必须重下
            except Exception:
                fresh = False
        if fresh:
            got = have          # 之前下过、字节数也对，不重复下
        elif have > 0 and not want:
            got = have
        else:
            try:
                if not quiet:
                    print("[astrbot-dl] 存一份到站内：%s（%s）"
                          % (name, human_size(want) if want else "大小未知"))
                got = _fetch_apk(apk["browser_download_url"], dest, want, sha_want)
            except Exception as e:
                print("[astrbot-dl] %s 存站内失败，这一版先用 GitHub 直链：%s" % (name, e),
                      file=sys.stderr)
                continue
        kept.append(name)
        out[ver] = {
            "ver": ver,
            "tag": str(rel.get("tag_name") or ver),
            "name": name,
            "url": DL_URL + "/" + name,
            "bytes": got,
            "size": human_size(got),
            "sha": sha256_file(dest),
            "date": (rel.get("published_at") or "")[:10],
            "notes": (rel.get("body") or "").strip(),
        }
    _prune_downloads(kept, quiet)
    return out


def _prune_downloads(kept, quiet=False):
    """清掉站内不再需要的老安装包。只动本脚本自己下的那些 AstrBot-*.apk。"""
    try:
        for fn in sorted(os.listdir(DL_DIR)):
            if fn.startswith("AstrBot-") and fn.endswith(".apk") and fn not in kept:
                os.remove(os.path.join(DL_DIR, fn))
                if not quiet:
                    print("[astrbot-dl] 清掉站内旧安装包：%s" % fn)
    except Exception as e:
        print("[astrbot-dl] 清理旧安装包失败（不影响这次同步）：%s" % e, file=sys.stderr)


DL_PAGE_TPL = """<!doctype html>
<html lang="zh-CN" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark">
<meta name="theme-color" content="#111725">
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🕯️</text></svg>">
<title>AstrBot烛版 · 下载</title>
<meta name="description" content="AstrBot烛版 APK 站内直接下载，不用跳 GitHub。手机上一键跑起 AstrBot + NapCat。">
<meta property="og:title" content="AstrBot烛版 · 下载">
<meta property="og:description" content="手机上一键跑起 AstrBot + NapCat · 站内直下">
<meta property="og:type" content="website">
<style>
/* 取色自站点 assets/theme.css 的 :root，跟站点同一套鎏金深空配色 */
:root{color-scheme:dark;
--void-1:#0d1119;--void-2:#111725;--void-3:#161d2b;
--gold-1:#7a5a1e;--gold-2:#c9982f;--gold-3:#e8a33d;--gold-4:#ffd68a;--gold-5:#fff4d6;
--tx-1:#e8edf6;--tx-2:#b0b8c6;--tx-3:#8b94a3;
--line:rgba(255,255,255,.075);--line-gold:rgba(232,163,61,.30);
--card:#141a26;--card-2:#161d2b;
--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,"Courier New",monospace}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;background-color:var(--void-2)}
body{margin:0;padding:24px 16px calc(54px + env(safe-area-inset-bottom));
background-color:var(--void-1);
background-image:
linear-gradient(180deg,rgba(232,163,61,.075),rgba(232,163,61,0) 320px),
radial-gradient(780px 430px at 6% -8%,rgba(232,163,61,.12),rgba(232,163,61,0) 62%),
radial-gradient(700px 420px at 103% 1%,rgba(74,124,255,.085),rgba(74,124,255,0) 58%);
background-repeat:no-repeat;overflow-x:hidden;
color:var(--tx-1);
font:15px/1.72 -apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif}
.wrap{max-width:880px;margin:0 auto}
a,button{-webkit-tap-highlight-color:transparent;touch-action:manipulation}

/* ---------- 页头 ---------- */
.hd{margin:2px 0 20px}
.hd h1{margin:0 0 9px;font-size:clamp(22px,5.8vw,30px);line-height:1.3;letter-spacing:.3px;font-weight:700}
.hd h1 b{font-weight:inherit;background:linear-gradient(180deg,var(--gold-4),var(--gold-2));
-webkit-background-clip:text;background-clip:text;color:transparent}
.sub{margin:0;color:var(--tx-2);font-size:14.5px}
.stat{margin:11px 0 0;color:var(--tx-3);font-size:12.5px}

/* ---------- 最新版大卡片 ---------- */
.hero{position:relative;overflow:hidden;padding:20px 18px 18px;border-radius:22px;
border:1px solid var(--line-gold);
background:linear-gradient(180deg,rgba(232,163,61,.105),rgba(232,163,61,.02) 44%),var(--card);
box-shadow:0 20px 48px -28px rgba(0,0,0,.92)}
.hero:before{content:"";position:absolute;left:0;right:0;top:0;height:1px;
background:linear-gradient(90deg,transparent,rgba(255,214,138,.7),transparent)}
.ribbon{display:inline-flex;align-items:center;gap:7px;padding:4px 12px;border-radius:999px;
font-size:12px;font-weight:700;letter-spacing:.5px;color:#2a1c02;
background:linear-gradient(135deg,var(--gold-4),var(--gold-2))}
.ribbon:before{content:"";width:6px;height:6px;border-radius:50%;background:#2a1c02;opacity:.5}
.hero-ver{margin:15px 0 3px;font-size:clamp(32px,9.6vw,46px);line-height:1.04;font-weight:800;
letter-spacing:-.6px;font-family:var(--mono);
background:linear-gradient(180deg,var(--gold-5),var(--gold-3) 60%,var(--gold-2));
-webkit-background-clip:text;background-clip:text;color:transparent}
.hero-file{margin:0;color:var(--tx-2);font-size:13px;word-break:break-all;font-family:var(--mono)}
.facts{display:flex;flex-wrap:wrap;gap:8px;margin:15px 0 17px;padding:0;list-style:none}
.facts li{display:flex;align-items:baseline;gap:6px;padding:5px 12px;border-radius:999px;
border:1px solid var(--line);background:rgba(255,255,255,.03);font-size:12.5px;white-space:nowrap}
.facts li span{color:var(--tx-3)}
.facts li b{color:var(--tx-1);font-weight:600}
.btn{display:flex;align-items:center;justify-content:center;gap:9px;width:100%;
min-height:54px;padding:0 20px;border-radius:15px;text-decoration:none;
font-size:16.5px;font-weight:700;letter-spacing:.3px;color:#2b1c02;
background:linear-gradient(135deg,var(--gold-4),var(--gold-3) 55%,var(--gold-2));
box-shadow:0 12px 28px -14px rgba(232,163,61,.8);
transition:transform .16s ease,filter .16s ease,box-shadow .16s ease}
.btn:hover{filter:brightness(1.07);box-shadow:0 15px 34px -14px rgba(232,163,61,.9)}
.btn:active{transform:translateY(1px) scale(.995)}
.hint{margin:11px 0 0;color:var(--tx-3);font-size:12.5px;text-align:center}

/* ---------- SHA-256 + 复制 ---------- */
.sha{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:8px;
margin:16px 0 0;padding:10px 12px;border-radius:13px;border:1px solid var(--line);background:rgba(0,0,0,.3)}
.sha-k{font-size:11.5px;letter-spacing:.6px;font-weight:700;color:var(--gold-3);white-space:nowrap}
.sha code{min-width:0;color:var(--tx-2);font-size:12px;line-height:1.55;
word-break:break-all;font-family:var(--mono)}
.copy{padding:6px 14px;border-radius:999px;cursor:pointer;
border:1px solid var(--line-gold);background:rgba(232,163,61,.10);color:var(--gold-4);
font-size:12.5px;font-weight:600;font-family:inherit;
transition:background .16s ease,color .16s ease,border-color .16s ease}
.copy:hover{background:rgba(232,163,61,.22)}
.copy.ok{background:rgba(46,230,200,.16);border-color:rgba(46,230,200,.5);color:#8ff3e2}
.copy.bad{background:rgba(255,140,140,.14);border-color:rgba(255,140,140,.45);color:#ffb9b9}
/* 窄屏：标签和「复制」并排一行，SHA-256 整行铺开，免得被挤成三行 */
@media (max-width:560px){
.sha{grid-template-columns:minmax(0,1fr) auto;row-gap:5px}
.sha code{grid-column:1/-1;grid-row:2}
.sha .copy{grid-column:2;grid-row:1;justify-self:end}
}

/* ---------- 历史版本 ---------- */
h2.sec{display:flex;align-items:center;gap:11px;margin:30px 0 13px;
font-size:15.5px;font-weight:600;color:var(--tx-2)}
h2.sec:after{content:"";flex:1;height:1px;
background:linear-gradient(90deg,var(--line-gold),rgba(232,163,61,0))}
.list{display:grid;gap:13px;grid-template-columns:repeat(auto-fit,minmax(288px,1fr))}
.card{padding:15px 15px 14px;border-radius:17px;border:1px solid var(--line);
background:var(--card-2);
transition:border-color .18s ease,transform .18s ease}
.card:hover{border-color:var(--line-gold);transform:translateY(-1px)}
.card-top{display:flex;align-items:center;gap:9px}
.tag{font-family:var(--mono);font-weight:700;font-size:16px;
color:var(--gold-4);white-space:nowrap}
.date{margin-left:auto;color:var(--tx-3);font-size:12px;white-space:nowrap}
.fname{margin:6px 0 12px;color:var(--tx-3);font-size:12px;word-break:break-all;font-family:var(--mono)}
.card-bot{display:flex;align-items:center;gap:10px}
.size{color:var(--tx-2);font-size:12.5px}
.btn-sm{margin-left:auto;flex:0 0 auto;display:inline-flex;align-items:center;gap:5px;
min-height:40px;padding:0 16px;border-radius:11px;text-decoration:none;
font-size:13.5px;font-weight:700;color:#2b1c02;
background:linear-gradient(135deg,var(--gold-4),var(--gold-3));
transition:filter .16s ease}
.btn-sm:hover{filter:brightness(1.07)}
.card .sha{margin-top:12px;padding:8px 10px;gap:7px}
.card .sha code{font-size:11.5px}
.card .copy{padding:5px 11px;font-size:12px}

/* ---------- 页脚 ---------- */
.back{display:inline-flex;align-items:center;gap:7px;margin:30px 0 0;padding:9px 16px;
border-radius:999px;border:1px solid var(--line);background:rgba(255,255,255,.03);
color:var(--tx-2);text-decoration:none;font-size:13.5px;transition:border-color .16s,color .16s}
.back:hover{border-color:var(--line-gold);color:var(--gold-4)}
footer{margin-top:24px;color:var(--tx-3);font-size:12px;line-height:1.85}
footer a{color:var(--tx-3);text-decoration:underline;text-underline-offset:2px}
footer a:hover{color:var(--gold-4)}

@media (max-width:360px){
body{padding-left:12px;padding-right:12px}
.hero{padding:17px 14px 15px;border-radius:19px}
.facts li{font-size:12px;padding:4px 10px}
.sha{padding:9px 10px}
}
@media (prefers-reduced-motion:reduce){*{transition:none!important}.card:hover{transform:none}}
</style>
</head>
<body><div class="wrap">
<header class="hd">
<h1>AstrBot<b>烛版</b> · 下载</h1>
<p class="sub">手机上一键跑起 AstrBot + NapCat —— 就在这儿下，不用跳 GitHub。</p>
<p class="stat">站内共 __COUNT__ 个版本 · 页面每天跟着 GitHub Releases 自动更新</p>
</header>
__HERO__
__REST__
<a class="back" href="/#/astrbot">← 回到 AstrBot烛版 页面</a>
<footer>
APK 全部由本站直接提供，点击即下。装完可以先核对上面的 SHA-256；安装时如提示风险，选择「仍要安装」即可。<br>
站内最多保留最近 __KEEP__ 个版本，更早的版本见 <a href="__RELEASES__" rel="noopener">GitHub Releases</a>。
</footer>
</div>
<script>
/* SHA-256 一键复制：优先进剪贴板 API，不行退到 execCommand，再不行就把文字选中让用户手动复制 */
(function () {
  var timer = null;
  function mark(btn, cls, text) {
    if (!btn.getAttribute('data-txt')) btn.setAttribute('data-txt', btn.textContent);
    btn.textContent = text;
    btn.classList.remove('ok', 'bad');
    if (cls) btn.classList.add(cls);
    clearTimeout(timer);
    timer = setTimeout(function () {
      btn.textContent = btn.getAttribute('data-txt');
      btn.classList.remove('ok', 'bad');
    }, 1600);
  }
  function legacy(t) {
    var ta = document.createElement('textarea');
    ta.value = t;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-2000px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, t.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }
  function selectText(node) {
    if (!node || !window.getSelection || !document.createRange) return;
    var r = document.createRange();
    r.selectNodeContents(node);
    var s = window.getSelection();
    s.removeAllRanges();
    s.addRange(r);
  }
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    var btn = t && t.closest ? t.closest('button.copy') : null;
    if (!btn) return;
    var box = btn.closest('.sha');
    var code = box ? box.querySelector('code') : null;
    var text = code ? code.textContent.replace(/[^0-9a-fA-F]/g, '') : '';
    if (!text) return;
    var done = function () { mark(btn, 'ok', '已复制 ✓'); };
    var fail = function () {
      if (legacy(text)) { done(); return; }
      selectText(code);
      mark(btn, 'bad', '请手动复制');
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fail);
    } else {
      fail();
    }
  });
})();
</script>
</body></html>
"""


def _esc(v):
    return html.escape(str(v if v is not None else ""))


def _dl_vlabel(it):
    """版本号显示成 v1.1.4 这个样子。"""
    tag = str(it.get("tag") or "").strip()
    if not tag:
        tag = str(it.get("ver") or "").strip()
    if not tag:
        return "未知版本"
    return tag if tag[:1] in ("v", "V") else "v" + tag


def _dl_sha(it):
    """一行 SHA-256 + 一键复制按钮。按钮从兄弟 code 里取文字，不用再存一份。"""
    return (
        '<div class="sha"><span class="sha-k">SHA-256</span>'
        '<code>%s</code>'
        '<button type="button" class="copy" aria-label="复制 SHA-256 校验值">复制</button>'
        '</div>' % _esc(it.get("sha") or "（这次没算出来）")
    )


def _dl_hero(it):
    """最新版：整页最显眼的那张大卡片。"""
    return (
        '<section class="hero">'
        '<span class="ribbon">最新版</span>'
        '<h2 class="hero-ver">%s</h2>'
        '<p class="hero-file">%s</p>'
        '<ul class="facts">'
        '<li><span>大小</span><b>%s</b></li>'
        '<li><span>发布</span><b>%s</b></li>'
        '<li><span>平台</span><b>Android</b></li>'
        '</ul>'
        '<a class="btn" href="%s" download>&#11015;&#65039; 下载最新版 APK</a>'
        '<p class="hint">点一下就开始下载，不用跳 GitHub。安装时若提示风险，选「仍要安装」即可。</p>'
        '%s'
        '</section>'
        % (_esc(_dl_vlabel(it)), _esc(it.get("name")), _esc(it.get("size") or "体积未知"),
           _esc(it.get("date") or "日期未知"), _esc(it.get("url")), _dl_sha(it))
    )


def _dl_card(it):
    """历史版本：一版一张小卡，版本 / 文件名 / 大小 / SHA-256 / 下载按钮都有。"""
    return (
        '<article class="card">'
        '<div class="card-top"><span class="tag">%s</span>'
        '<span class="date">%s</span></div>'
        '<p class="fname">%s</p>'
        '<div class="card-bot"><span class="size">%s</span>'
        '<a class="btn-sm" href="%s" download>&#11015;&#65039; 下载</a></div>'
        '%s'
        '</article>'
        % (_esc(_dl_vlabel(it)), _esc(it.get("date") or "日期未知"), _esc(it.get("name")),
           _esc(it.get("size") or "体积未知"), _esc(it.get("url")), _dl_sha(it))
    )


def write_download_index(items, quiet=False):
    """在 downloads/ 里放一个列表页：最新版一张大卡片，其余版本排在下面的网格里。

    版本少也不塌：只有 1 个版本时「历史版本」整段不渲染，页面上就一张大卡片。
    （站内最多留 KEEP_VERSIONS 个安装包，见 mirror_releases。）
    """
    if not items:
        return
    ordered = sorted(items.values(), key=lambda x: x.get("date") or "", reverse=True)
    hero, rest = ordered[0], ordered[1:]
    blocks = []
    if rest:
        blocks.append('<h2 class="sec">历史版本</h2>')
        blocks.append('<div class="list">%s</div>' % "".join(_dl_card(it) for it in rest))
    page = (DL_PAGE_TPL
            .replace("__HERO__", _dl_hero(hero))
            .replace("__REST__", chr(10).join(blocks))
            .replace("__COUNT__", str(len(ordered)))
            .replace("__KEEP__", str(KEEP_VERSIONS))
            .replace("__RELEASES__", RELEASES_PAGE))
    p = os.path.join(DL_DIR, "index.html")
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(page)
    os.replace(tmp, p)
    try:
        os.chmod(p, 0o644)
    except Exception:
        pass
    if not quiet:
        print("[astrbot-dl] 已生成站内下载列表页：%s（%d 个版本）" % (p, len(ordered)))


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


def build(latest, all_rels, extra=None, mirror=None):
    mirror = mirror or {}
    tag = latest.get("tag_name") or "v0.0.0"
    ver = tag.lstrip("vV")
    apk = pick_apk(latest)
    apk_size = human_size(apk["size"]) if apk else ""
    mine = mirror.get(ver) or {}
    gh_dl = apk["browser_download_url"] if apk else RELEASES_PAGE
    # 站内已经存了就用站内地址：用户点一下直接开始下载，不用跳 GitHub。
    # 只有站内那份没下来（网络问题、磁盘满）才退回 GitHub 直链，页面不至于没有下载按钮。
    local = bool(mine.get("url"))
    dl = mine.get("url") or gh_dl
    digest = (apk or {}).get("digest") or ""
    sha = mine.get("sha") or (digest.split(":", 1)[1] if digest.startswith("sha256:") else "")
    if mine.get("size"):
        apk_size = mine["size"]
    pub = (latest.get("published_at") or "")[:10]
    now = time.strftime("%Y-%m-%d %H:%M:%S")

    d_cards = [
        {
            "icon": "📦",
            "title": "AstrBot烛版 %s（最新版）" % ver,
            # 文件名放第一行：卡片里的说明会被样式截断，把「下载下来叫什么」摆在最前面才不会看不到。
            "desc": "文件名 AstrBot-%s.apk（下载下来就是这个名字）" % ver + NL +
                    "安卓安装包 · %s%s" % (apk_size, (" · " + pub) if pub else "") + NL +
                    "安卓 8.0 及以上 · 仅 arm64-v8a（现在绝大多数手机）" + NL +
                    "包名 com.termux，如果装过官方 Termux 要先卸载" + NL +
                    ("点这张卡直接下载 —— 就在本站，不用跳 GitHub。" if local
                     else "点这张卡下载（这一版本站没存住，暂时指向 GitHub）。"),
            "link": dl,
        },
    ]
    if local:
        # 可复制卡：把直链给出去，方便发群里、发给别人
        d_cards.append({
            "icon": "🔗",
            "title": "复制下载直链",
            "desc": "点这张卡就把地址复制下来，可以直接发给别人或贴进浏览器。",
            "text": PUBLIC_BASE + mine["url"],
        })
    d_cards.append({
        "icon": "🕘",
        "title": "所有历史版本",
        "desc": ("本站存着的几个版本，点进去直接下。" if local
                 else "想装旧版、或者想看每一版改了什么，都在这里。"),
        "link": (DL_URL + "/") if local else RELEASES_PAGE,
    })
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
        # 只要卡里有 text 就会被渲染成「可复制卡」，link 被直接丢掉。
        # 更新说明放进 desc（CSS 是 white-space:pre-wrap，换行照样在），整张卡都能点开。
        # 有站内包就把整张卡指到站内文件（点一下就能下这一版）；没有就不给链接，
        # 免得又把用户带去 GitHub。
        rlocal = (mirror.get(rtag.lstrip("vV")) or {}).get("url") or ""
        ch_cards.append({
            "icon": "📝",
            "title": "%s%s" % (rtag, ("  ·  " + rpub) if rpub else ""),
            "desc": rbody or "（这一版没写更新说明）",
            "link": rlocal,
        })
    if not ch_cards:
        ch_cards.append({
            "icon": "📝",
            "title": "暂无更新日志",
            "desc": "这一版还没有更新记录。",
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


# 站点 AstrBot 栏由本脚本管的四个子栏组成：download / what / howto / changelog。
# 除此之外，站点上原本就有的别的子栏（例如早先写的「人格」分享）一律原样保留 ——
# 那是站点主人自己的内容，本脚本只负责每天刷新下载/说明/更新日志，没有资格删。
# 它们会被排在受管子栏后面；总子栏数受站点 MAX_TABS=12 限制。
KEEP_EXTRA_TABS = True


def load_extra_tabs():
    """把现有内容里「不是我们管的」子栏原样捡回来（默认关闭，见上面的开关）。"""
    if not KEEP_EXTRA_TABS:
        return []
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
    mirror = {}
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
        # 先把安装包存一份到站内：下载按钮从此只指本站地址，用户不会再被甩去 GitHub。
        # --dry-run 不下 24 MB 的文件，只把页面结构打出来。
        if dry:
            print("[astrbot-dl] --dry-run：跳过把安装包存到站内这一步", file=sys.stderr)
        else:
            mirror = mirror_releases([latest] + list(all_rels or []), quiet=quiet)
            if mirror:
                print("[astrbot-dl] 站内已备好 %d 个版本：%s"
                      % (len(mirror), ", ".join(sorted(mirror.keys(), reverse=True))))
            else:
                print("[astrbot-dl] 站内没存到安装包，下载按钮暂时指向 GitHub", file=sys.stderr)
        doc = build(latest, all_rels, extra, mirror)
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
    try:
        write_download_index(mirror, quiet=quiet)
    except Exception as e:
        # 列表页只是顺手生成的，写不出来不该让整个同步算失败（下载卡片本身还好好的）
        print("[astrbot-dl] 站内下载列表页没生成：%s" % e, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
