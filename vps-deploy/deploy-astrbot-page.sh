#!/usr/bin/env bash
# ============================================================
#  烛心站点 · AstrBot 栏「下载页」一键部署
# ============================================================
#
#  做什么：
#    1) 把爬虫装到 <站点>/scripts/astrbot_download_sync.py
#    2) 装 systemd 单元 zhuxin-astrbot-dl.{service,timer}（每天同步一次）
#    3) 立刻同步一次，把 AstrBot 那一栏换成「AstrBot烛版 下载页」
#
#  怎么用（在服务器上以 root 执行）：
#    bash deploy-astrbot-page.sh
#    bash deploy-astrbot-page.sh --site-dir=/data/www/zhuxin-site
#    bash deploy-astrbot-page.sh --no-timer      # 只装一次，不建定时任务
#    bash deploy-astrbot-page.sh --revert        # 后悔了，还原成本次安装前的样子
#
#  写哪些文件：
#    <站点>/data/astrbot_content.json   母本（后台编辑器读它）
#    <站点>/data/astrbot.json           前端 fetch 的公开那份
#  两份一起写，所以你在后台点保存不会把下载页打回去。
#  原文件会先备份成同目录下的 *.bak-deploy-<时间戳>。
#  AstrBot 栏只由「下载 / 这是什么 / 怎么用 / 更新日志」四栏组成；
#  以前分享栏留下的子栏（人格之类）不再带过来（爬虫里 KEEP_EXTRA_TABS=False）。
#
#  连不上 GitHub 也不会白来一趟：安装包里自带一份离线内容，会先顶上，
#  等哪次定时任务联网成功了再自动换成真的最新版。
# ============================================================
set -eu

SITE=""
NO_TIMER=0
REVERT=0

log() { printf "[astrbot-dl] %s\n" "$*"; }
die() { printf "[astrbot-dl] 错误：%s\n" "$*" >&2; exit 1; }

usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

for a in "$@"; do
  case "$a" in
    --site-dir=*) SITE="${a#--site-dir=}" ;;
    --no-timer)   NO_TIMER=1 ;;
    --revert)     REVERT=1 ;;
    -h|--help)    usage ;;
    *)            die "不认识的参数：$a（用 --help 看用法）" ;;
  esac
done

command -v python3 >/dev/null 2>&1 || die "没装 python3，先 apt install python3"

detect_site() {
  # 站点自己的脚本把路径写死成 /data/www/zhuxin-site，先试它
  for d in /data/www/zhuxin-site /var/www/zhuxin-site; do
    if [ -f "$d/data/astrbot.json" ] && [ -f "$d/assets/core.js" ]; then
      printf "%s" "$d"; return 0
    fi
  done
  # 找不到就按特征找：assets/core.js 与 data/astrbot.json 同在一个目录树里
  for root in /data /var/www /www/wwwroot /usr/share/nginx/html /srv /home /opt; do
    [ -d "$root" ] || continue
    hit=$(find "$root" -maxdepth 7 -type f -name core.js -path "*/assets/*" 2>/dev/null | head -n 1 || true)
    if [ -n "$hit" ]; then
      d=$(dirname "$(dirname "$hit")")
      if [ -f "$d/data/astrbot.json" ]; then printf "%s" "$d"; return 0; fi
    fi
  done
  return 1
}

if [ -z "$SITE" ]; then
  SITE=$(detect_site || true)
  [ -n "$SITE" ] || die "没找到站点目录，请用 --site-dir=/你的/站点/根目录 指定"
fi
SITE="${SITE%/}"
DATA="$SITE/data"
SCRIPTS="$SITE/scripts"
CFG="$DATA/astrbot_content.json"
PUB="$DATA/astrbot.json"
[ -d "$DATA" ] || die "$DATA 不存在，站点目录是不是写错了？"
log "站点目录：$SITE"

if [ "$REVERT" = "1" ]; then
  n=0
  for f in "$CFG" "$PUB"; do
    last=$(ls -1t "$f".bak-deploy-* 2>/dev/null | head -n 1 || true)
    if [ -n "$last" ]; then
      cp -p "$last" "$f"
      log "已还原 $f <- $last"
      n=$((n + 1))
    fi
  done
  [ "$n" -gt 0 ] || die "没找到 *.bak-deploy-* 备份，可能没在本站装过"
  log "还原完成（定时任务没动，不要了就 systemctl disable --now zhuxin-astrbot-dl.timer）"
  exit 0
fi

mkdir -p "$SCRIPTS"
TS=$(date +%Y%m%d-%H%M%S)
for f in "$CFG" "$PUB"; do
  if [ -f "$f" ]; then cp -p "$f" "$f.bak-deploy-$TS"; log "已备份 $f -> $f.bak-deploy-$TS"; fi
done

cat > "$SCRIPTS/astrbot_download_sync.py" <<'__ASTRBOT_DL_PY__'
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
# 其它历史子栏（早先分享栏留下的人格之类）不再保留：见下面的 KEEP_EXTRA_TABS。
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
    for a in (rel.get("assets") or []):
        if str(a.get("name", "")).lower().endswith(".apk"):
            return a
    return None


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
        if want and have == want:
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
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AstrBot烛版 · 下载</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;padding:28px 18px 60px;background:#0b0f19;color:#e8eaed;
font:15px/1.7 -apple-system,"Segoe UI","Noto Sans SC",sans-serif}
.wrap{max-width:720px;margin:0 auto}
h1{margin:0 0 6px;font-size:26px;letter-spacing:.5px}
.sub{margin:0 0 20px;color:#8b939e}
a.row{display:flex;align-items:center;gap:12px;padding:14px 16px;margin:10px 0;
border:1px solid #1e2635;border-radius:14px;background:#111827;color:inherit;text-decoration:none}
a.row:hover{border-color:#3b82f6;background:#141d2e}
.v{font-weight:600;font-family:ui-monospace,Menlo,Consolas,monospace;white-space:nowrap}
.m{flex:1;color:#8b939e;font-size:13px}
.go{padding:7px 14px;border-radius:999px;background:#3b82f6;color:#fff;font-size:13px;white-space:nowrap}
h2{margin:30px 0 10px;font-size:16px;color:#a8b3c4}
.s{padding:10px 12px;margin:8px 0;border:1px solid #1e2635;border-radius:12px;background:#0e1420}
code{display:block;margin-top:6px;word-break:break-all;color:#9fb4d8;font-size:12.5px}
a.back{display:inline-block;margin-top:26px;color:#8b939e;text-decoration:none}
</style>
</head>
<body><div class="wrap">
<h1>AstrBot烛版 · 下载</h1>
<p class="sub">就在这里下，不用跳 GitHub。页面每天自动跟着新版本更新。</p>
__ROWS__
<h2>SHA-256 校验值</h2>
__SHAS__
<a class="back" href="/#/astrbot">← 回到 AstrBot烛版 页面</a>
</div></body></html>
"""


def write_download_index(items, quiet=False):
    """在 downloads/ 里放一个列表页：站内就能翻到所有留着的版本。"""
    if not items:
        return
    ordered = sorted(items.values(), key=lambda x: x.get("date") or "", reverse=True)
    rows = []
    for it in ordered:
        rows.append(
            '<a class="row" href="%s" download>'
            '<span class="v">%s</span>'
            '<span class="m">%s · %s · %s</span>'
            '<span class="go">下载</span></a>'
            % (html.escape(it["url"]), html.escape(it["tag"]),
               html.escape(it["date"] or "日期未知"), html.escape(it["size"]),
               html.escape(it["name"])))
    shas = []
    for it in ordered:
        shas.append('<div class="s"><b>%s</b><code>%s</code></div>'
                    % (html.escape(it["tag"]), html.escape(it["sha"])))
    page = DL_PAGE_TPL.replace("__ROWS__", "\n".join(rows)).replace("__SHAS__", "\n".join(shas))
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
        print("[astrbot-dl] 已生成站内下载列表页：%s" % p)


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
            "desc": "安卓安装包 · %s%s" % (apk_size, (" · " + pub) if pub else "") + NL +
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


# 站点 AstrBot 栏现在的定位是「下载页」，内容只由本脚本管的四个子栏组成：
# download / what / howto / changelog。历史遗留的别的子栏（例如早先的人格分享）
# 不再往新内容里带 —— 否则它们会一直粘在下载页后面，页面上就有第五个「人格」栏。
# 想恢复「保留你自己写的子栏」这个老行为，把下面这行改成 True 即可。
KEEP_EXTRA_TABS = False


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
__ASTRBOT_DL_PY__
chmod 755 "$SCRIPTS/astrbot_download_sync.py"
log "爬虫已装到 $SCRIPTS/astrbot_download_sync.py"

cat > "$SCRIPTS/astrbot_download_sync.fallback.json" <<'__ASTRBOT_DL_STATIC__'
{
  "title": "AstrBot烛版",
  "subtitle": "手机上一键跑起 AstrBot + NapCat",
  "intro": "把 AstrBot 和 NapCat 打包成一个安卓安装包，安装打开后自动在手机里建好 Linux 沙盒并部署完成，扫码登录即可用。",
  "tabs": [
    {
      "key": "download",
      "label": "下载",
      "icon": "⬇️",
      "desc": "最新安装包与校验值",
      "cards": [
        {
          "icon": "📦",
          "title": "AstrBot烛版 1.1.4（最新版）",
          "desc": "安卓安装包 · 24.0 MB · 2026-10-16\n安卓 8.0 及以上 · 仅 arm64-v8a（现在绝大多数手机）\n包名 com.termux，如果装过官方 Termux 要先卸载\n点这张卡直接下载 —— 就在本站，不用跳 GitHub。",
          "link": "/downloads/AstrBot-1.1.4.apk",
          "text": ""
        },
        {
          "icon": "🔗",
          "title": "复制下载直链",
          "desc": "点这张卡就把地址复制下来，可以直接发给别人或贴进浏览器。",
          "link": "",
          "text": "https://xn--15t839a.xyz/downloads/AstrBot-1.1.4.apk"
        },
        {
          "icon": "🕘",
          "title": "所有历史版本",
          "desc": "本站存着的几个版本，点进去直接下。",
          "link": "/downloads/",
          "text": ""
        },
        {
          "icon": "🔐",
          "title": "SHA-256 校验值（v1.1.4）",
          "desc": "点这张卡复制，然后和下载到的文件比对，一样就说明没被改过。",
          "link": "",
          "text": "cf2cc1d02327ac124bbb913ae038f6cff5907e84f791db97171ae176ae7b791c"
        }
      ]
    },
    {
      "key": "what",
      "label": "这是什么",
      "icon": "🤖",
      "desc": "它到底装了什么",
      "cards": [
        {
          "icon": "🤖",
          "title": "这是什么",
          "desc": "一个安卓应用，把两样东西打包在一起：\n\n· AstrBot —— 机器人框架，负责接大模型、管插件、发消息\n· NapCat —— QQ 协议端，让机器人能收能发 QQ 消息\n\n装上打开之后，它会在手机里自动建一个 Linux 沙盒，把这两样都装好、对接好，你只要扫码登录 QQ 就能用。",
          "link": "",
          "text": ""
        },
        {
          "icon": "📵",
          "title": "不需要电脑、不需要服务器",
          "desc": "整个过程都在手机上跑，不用买服务器、不用连电脑、不用 Root。",
          "link": "",
          "text": ""
        },
        {
          "icon": "🧩",
          "title": "装完之后你能做什么",
          "desc": "· 在应用里看 NapCat 与 AstrBot 的实时日志\n· 一键把 NapCat 和 AstrBot 对接起来\n· 复制登录地址和 Token，换设备扫码\n· 配多个 QQ 号，每个号独立开关容器\n· 进 AstrBot 控制台装插件、改人格",
          "link": "",
          "text": ""
        }
      ]
    },
    {
      "key": "howto",
      "label": "怎么用",
      "icon": "📱",
      "desc": "从下载到能用共四步",
      "cards": [
        {
          "icon": "1️⃣",
          "title": "下载安装包",
          "desc": "点「下载」栏里最上面那张卡。下完点开，系统会提示「未知来源」，允许一次即可。",
          "link": "",
          "text": ""
        },
        {
          "icon": "2️⃣",
          "title": "打开应用，等它自动部署",
          "desc": "第一次打开会自动下载并安装运行环境，一共要下 250 MB 左右，大概 20 到 60 分钟，取决于网速。期间请保持应用在前台、别锁屏。\n中途断了也不要紧，下次打开会接着上次的进度继续。",
          "link": "",
          "text": ""
        },
        {
          "icon": "3️⃣",
          "title": "扫码登录 QQ",
          "desc": "部署完成后到「日志」页，会出现一张二维码。用自己的手机 QQ 扫它（用本机扫不了，得用另一台设备打开 http://手机IP:6099 ）。",
          "link": "",
          "text": ""
        },
        {
          "icon": "4️⃣",
          "title": "开始用",
          "desc": "回到「主页」点「自动连接 NapCat ↔ AstrBot」，指示灯变绿就好了。\n想改人格、装插件，去「更多 → AstrBot 控制台」。",
          "link": "",
          "text": ""
        }
      ]
    },
    {
      "key": "changelog",
      "label": "更新日志",
      "icon": "📝",
      "desc": "每一版改了什么",
      "cards": [
        {
          "icon": "📝",
          "title": "v1.1.4  ·  2026-10-16",
          "desc": "### AstrBot 烛版 v1.1.4\n\n### 修复\n\n- **在 arm64 手机上点「重新部署」，只会弹一句英文的「部署：start failed」，完全看不出为什么失败**（用户真机截图反馈）。以前 `startDeploy()` 无论成功失败都只回一个内部状态串，失败时既不写日志也不留原因，看起来就像按钮坏了。现在所有提示都换成了中文，并且**失败时会把部署日志（或服务日志）的最后一行一起显示出来**——那一行通常是 apt、pip 或 NapCat 真正报的错，照着它就能判断是没网、是磁盘满、还是某个包下载失败。日志里的 ANSI 颜色码会先剥掉，不会在提示里变成一串乱码。\n- 对应的几句话也一并中文化：已经是「已经部署完成，不用重新开始」「部署正在跑，稍等一下」，没解包好是「Termux 基础环境还没解包好，退出重进一次应用再试」，脚本丢了是「找不到部署脚本，覆盖安装一次应用可以恢复」。服务启动按钮同理（「服务已经在跑了」「服务已启动」）。\n- 顺手把按钮回显里 `start -> started` 这种只有开发者看得懂的字符串去掉：现在直接显示中文结果，没有结果就显示「操作 完成」。\n\n### 变更\n\n- **安装包文件名开始带版本号**：从这一版起，发布出去的安装包叫 `AstrBot-1.1.4.apk`（而不是以前的 `AstrBot.apk`）。下载目录里躺着好几个包的时候，一眼就能看出哪个是哪个，不用再去比对文件大小或哈希。构建脚本在签名完成后会按 `VERSION` 自动复制一份带版本号的副本到仓库根目录，官网「下载」栏与 Releases 页面的资产也统一用这个名字。\n\n---",
          "link": "/downloads/AstrBot-1.1.4.apk",
          "text": ""
        },
        {
          "icon": "📝",
          "title": "v1.1.3  ·  2026-10-16",
          "desc": "### AstrBot 鐑涚増 v1.1.3\n\n### 修复\n\n- **arm64 真机上部署一步都跑不起来，日志顶死在「proot 自检失败 / 该设备的架构与 proot 不匹配」**（本版唯一一处，但 100% 影响所有手机，模拟器上反而完全看不出来）：部署主脚本在**第一步开始之前**先做了一次 proot 自检，而 Termux 的 bootstrap 里**根本没有 proot**——它是第一步用 `apt` 装进来的。x86_64 模拟器走的是随包内置的 `proot-x86_64`（从装机第一秒就存在），所以自检通过、这个问题一直没暴露；arm64 手机上则是拿一个**还不存在的文件**去自检，`timeout` 返回 127，被当成「架构不匹配」直接 `exit 1`，八个步骤一个都不会执行。现在只有在 proot 确实存在时才做前置自检，不存在就记一句「proot 尚未安装…跳过前置自检，步骤 10 装好后会再检一次」，装好之后那一次才是真正有意义的检查。\n- 自检失败时把 proot 自己的报错原样打进部署日志（缩进四格，与 `[QQBot]` 行区分）。以前这些都丢进了 `/dev/null`，失败时只能说一句笼统的「自检失败」，分不清到底是文件不存在、还是被系统拦了 `ptrace`。\n\n---",
          "link": "/downloads/AstrBot-1.1.3.apk",
          "text": ""
        },
        {
          "icon": "📝",
          "title": "v1.1.2  ·  2026-10-16",
          "desc": "### AstrBot 烛版 v1.1.2\n\n### 修复\n\n- **v1.1.1 那个补丁其实没生效，真正的原因在这里：AstrBot 把 `cmd_config.json` 写成带 UTF-8 BOM 的文件，而我们读它时按纯 UTF-8 解析，直接抛 `JSONDecodeError: Unexpected UTF-8 BOM (decode using utf-8-sig)`**（沙盒内实测报错原文）。异常被 `link.sh` 里的 `except Exception: a = {}` 吞掉，于是「当前 AstrBot 已经监听的端口」永远算成空列表，跟「需要的端口」永远对不上 → `CHANGED` 永远是 1 → 每次对接都会 `kill -TERM` 掉 AstrBot 再让守护循环拉回来（约 30 秒，期间所有 QQ 号的 OneBot 通道一起断）。表现就是：**在机器人页停掉任意一个实例，AstrBot 也跟着重启一次**。现在所有读 JSON 的地方统一改用 `utf-8-sig` 解码（它同时兼容带 BOM 和不带 BOM 的文件），比对逻辑这才真正跑起来。\n- 顺带把沙盒里所有读 JSON 的脚本都换成了 `utf-8-sig`：`link.sh`（bots.json、cmd_config.json）、`run-napcat.sh`（实例的 onebot11/webui 配置、bots.json）、`50-napcat.sh`（QQ 的 `package.json`、NapCat 配置）、`40-qq.sh`（QQ 的 `versions/config.json`）、`remove-instance.sh`（bots.json）。这些文件分别由 AstrBot、NapCat、QQ 和 App 自己写，谁加不加 BOM 不由我们决定——`utf-8-sig` 对两种情况都能读，写出去的一律不带 BOM。\n\n---",
          "link": "/downloads/AstrBot-1.1.2.apk",
          "text": ""
        },
        {
          "icon": "📝",
          "title": "v1.1.0  ·  2026-10-16",
          "desc": "### AstrBot 烛版 v1.1.0\n\n这一版加了一个「检查更新」，并顺手把版本号推到 1.1.0。\n\n### 新增\n\n- **「更多 → 关于与支持 → 检查更新」**：以前想知道有没有新版，只能自己去 GitHub 翻 release 列表。现在应用里点一下「检查更新」，它会去 GitHub 查最新版本，直接告出「有新版本 1.x.x 可以装」，并给出「下载新版本」和「查看更新内容」两个按钮——前者把安装包交给浏览器下载，下完点开就能覆盖安装（覆盖装不会动沙盒里的 QQ 登录状态）。\n- **检查更新对国内网络做了兜底**：手机直连 `api.github.com` 经常超时，所以内置了直连 + 三个反代共四个地址，依次尝试，第一个能用的就用。四十五秒还没结果会明确说「检查超时」而不是一直转圈。\n- **启动后静默自检**：打开应用九秒后会在后台查一次（同一台设备十二小时内只查一次），有新版本才弹提示，其余情况不打扰。\n\n### 说明\n\n- 查询全程在后台线程进行，前端每 500ms 取一次结果，所以点「检查更新」不会把界面卡住——这一点和「运行命令」不同，「运行命令」卡的是命令本身。\n- 「查看更新内容」是在应用内展开的：新版说明在检查更新的同时就已经一起取回来了，点一下就地展开、再点收起，不用跳浏览器（跳过去还得自己登 GitHub，国内多半打不开）。只有那一版确实没写更新说明时才会退回打开 GitHub 页面。\n- 「下载新版本」交给系统浏览器下载，应用本身不内置下载器；如果你的手机装了多个浏览器，会由默认浏览器接管。",
          "link": "/downloads/AstrBot-1.1.0.apk",
          "text": ""
        },
        {
          "icon": "📝",
          "title": "v1.0.4  ·  2026-10-16",
          "desc": "# AstrBot烛版 v1.0.4\n\n安装包：`AstrBot.apk`（Android 8.0+，仅 arm64-v8a）\n\n覆盖安装即可，无需卸载。首次打开请先同意免责声明。\n\n---\n\n## [1.0.4] - 2026-10-16\n\n这一版是对整个应用做了一次逐行审计之后的集中修复：前端与原生两侧共 20 处缺陷，其中 4 处会让人以为「功能坏了」。\n\n### 修复\n\n- **执行一条不产生输出的命令，会让整个页面永久卡住**（本版最严重的一个）：「运行命令」里执行 `sleep 300`、或者任何等待输入的命令，超时判断形同虚设——命令不会自己结束，界面也不再刷新，之后所有按钮都像失灵，只能重启应用。现在超时真正生效，超时会终止命令并提示「命令超时没返回」。\n- **失败也会提示成功**：删除实例、自动对接 AstrBot、停止/重启服务这几件事，失败时此前同样提示成功。更糟的是「删除实例」失败时界面已经把卡片去掉了，看着像删干净了，其实没删。现在这些都按命令的真实退出码回报，失败时把最后一行输出一起显示出来。\n- **实例开关点了自己弹回去**：在机器人页把某个实例的开关打开，提示「已启动」，4 秒后它自己变回关闭。这是写盘失败时仍然回报成功造成的（开关其实是按磁盘状态显示的，磁盘没变）。现在写盘失败会明确提示「写入失败，改动没有落盘」，开关不会假开。\n- **重启手机或应用被清理后，沙盒显示「运行中」但其实是死的**：进程号标记文件是残留的，而重启后那个号很可能被别的进程占用，于是界面显示运行中、点「启动」回一句「已在运行」、自动拉起也直接跳过，服务却一直没起来。现在会核对进程号对应的命令确实是沙盒本身，不对就重新拉起。\n- **实例配置读坏时会静默换成单实例默认值**：如果配置文件损坏，程序此前会悄悄改用一份「主号 / 6099 / 6199」的默认配置显示出来；用户在这个假配置上点一次保存，真实的多实例配置就被覆盖了。现在会先把损坏的文件备份成 `bots.json.bad`，并提示「再点一次保存就用当前内容覆盖」，给你一次反悔的机会。\n- **「自动分配空闲端口」的结果会被随后的保存覆盖**：点完自动分配、接着点「保存并重启服务」，输入框里还是你原来手填的旧端口，保存时按输入框的值回写，自动分配的结果就白分了。现在自动分配后输入框会立刻显示真正生效的端口。\n- **错误提示会让页面整页冻结**：状态读取失败时，此前会直接跳过本轮全部渲染，磁贴、实例卡、端口、开关全部停在上一次的数值上，页面上只有标题旁一行小字。现在还是一样的文字，但会注明「显示的是上一次数据」，并且其余内容继续正常渲染。\n- **部分按钮点了没反应**：这些按钮在底层调用抛异常时会中断整个点击处理，例如「全部对接 AstrBot」已经提示「正在对接 N 个实例…」却什么…",
          "link": "",
          "text": ""
        },
        {
          "icon": "📝",
          "title": "v1.0.2  ·  2026-10-16",
          "desc": "## AstrBot烛版 v1.0.2\n\n一个 Android 安装包：装上、打开，它会在手机里自动建好一个 Linux 沙盒（Ubuntu 24.04），并在里面部署 **NapCat**（QQ 协议端）与 **AstrBot**（机器人框架），两者已预置对接。界面是一个图形化控制台（标题「烛之控制台」），**全程不需要输入任何命令**，也不需要 root。\n\n| 项目 | 值 |\n| --- | --- |\n| 安装包 | 本页下方的 `AstrBot.apk`（25,156,782 字节） |\n| SHA-256 | `3E6EFE4C721447969D1C3B8DF461FE4284A691EE2431772231D677BC1F476432` |\n| 使用说明 | 本页下方的 `USAGE.md`，或 [仓库内 使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)（GitHub 不支持中文资源文件名，所以下载资源的名字是 ASCII 的） |\n| 版本 | `1.0.2`（versionCode `10002`） |\n| 系统要求 | Android 8.0 及以上，仅 arm64-v8a |\n| 包名 | `com.termux`（与官方 Termux 相同） |\n| 官方网站 | [烛心.xyz](https://烛心.xyz) ｜ 官方群 `1127997343` |\n\n> ⚠️ **安装前必须先卸载官方 / 应用商店 / F-Droid 版 Termux** —— 本应用与它包名相同，两者无法共存。\n\n**首次打开**会看到免责声明弹窗，同意后沙盒开始自动部署：需要下载约 250 MB（Ubuntu 根文件系统、Linux QQ、NapCat、依赖），大约 20–60 分钟，期间请保持应用在前台。整个过程可以在「主页」看到 8 个步骤的进度，中断了也能续跑。\n\n### 本版修了什么\n\n- **「自动分配空闲端口」会把正常运行的配置改坏**（本版最严重的一个）：点一次这个按钮，配置里每个实例的端口就会被换成出厂默认的 6099 / 6199 / 6185。原因是它用「端口能不能连上」判断占用，而每个实例**自己正在监听的端口当然也连得上**，于是全被当成「被别人占了」跳过，从 6099 往下找必然落到默认值；更糟的是它只把新端口写进 `bots.json` 却**不重启容器**，主页随即按新端口去探测，NapCat 与 AstrBot 两个状态灯立刻变红「离线」而且一直不恢复，看起来就像把服务点坏了。现在改为**先沿用每个实例当前的端口**，只有和本次已分配的端口撞车时才重新挑；端口一个都没…",
          "link": "",
          "text": ""
        }
      ]
    }
  ],
  "updated": "2026-10-16 21:48:28"
}
__ASTRBOT_DL_STATIC__
log "离线兜底内容已装到 $SCRIPTS/astrbot_download_sync.fallback.json"

if [ "$NO_TIMER" = "1" ]; then
  log "--no-timer：跳过定时任务，只做这一次同步"
elif command -v systemctl >/dev/null 2>&1 && [ -d /etc/systemd/system ]; then
cat > /etc/systemd/system/zhuxin-astrbot-dl.service <<'__ASTRBOT_DL_SVC__'
[Unit]
Description=烛心站点 · AstrBot 栏「下载页」每日同步
Documentation=https://github.com/Zxin-Pro/astrbot-candle
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=__SITE__/scripts
ExecStart=/usr/bin/python3 __SITE__/scripts/astrbot_download_sync.py --site-dir=__SITE__ --quiet
User=root

[Install]
WantedBy=multi-user.target
__ASTRBOT_DL_SVC__
cat > /etc/systemd/system/zhuxin-astrbot-dl.timer <<'__ASTRBOT_DL_TMR__'
[Unit]
Description=每天同步一次 AstrBot 栏的下载页（跟着 GitHub Releases 走）

[Timer]
OnBootSec=3min
OnUnitActiveSec=1d
AccuracySec=1s
Persistent=true
Unit=zhuxin-astrbot-dl.service

[Install]
WantedBy=timers.target
__ASTRBOT_DL_TMR__
  sed -i "s|__SITE__|$SITE|g" /etc/systemd/system/zhuxin-astrbot-dl.service
  systemctl daemon-reload
  systemctl enable --now zhuxin-astrbot-dl.timer >/dev/null 2>&1 || true
  log "定时任务：$(systemctl is-enabled zhuxin-astrbot-dl.timer 2>/dev/null || echo 未知) / $(systemctl is-active zhuxin-astrbot-dl.timer 2>/dev/null || echo 未知)"
  log "下次触发：$(systemctl list-timers zhuxin-astrbot-dl.timer --no-pager 2>/dev/null | sed -n 2p | tr -s " " || true)"
else
  log "这台机器没有 systemd，自己加一条 cron（下面这行每天 04:17 同步一次）："
  echo "17 4 * * * /usr/bin/python3 $SCRIPTS/astrbot_download_sync.py --site-dir=$SITE --quiet >> /var/log/astrbot-dl.log 2>&1"
fi

if python3 "$SCRIPTS/astrbot_download_sync.py" --site-dir="$SITE"; then
  log "首次同步成功"
else
  log "首次同步失败（多半是这台机器连不上 GitHub），先用安装包自带的离线内容顶上"
  python3 - "$SCRIPTS/astrbot_download_sync.fallback.json" "$CFG" "$PUB" <<'__ASTRBOT_DL_OFFLINE__'
import json, os, sys
src, cfg, pub = sys.argv[1], sys.argv[2], sys.argv[3]
doc = json.load(open(src, encoding="utf-8"))
txt = json.dumps(doc, ensure_ascii=False, indent=2)
for pth in (cfg, pub):
    tmp = pth + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(txt)
    os.replace(tmp, pth)
    os.chmod(pth, 0o644)
print("[astrbot-dl] 已写入离线内容：%s 与 %s" % (cfg, pub))
__ASTRBOT_DL_OFFLINE__
fi

log "搞定，收个尾："
python3 - "$PUB" <<'__ASTRBOT_DL_SUM__'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
print("[astrbot-dl] 现在这一栏：%s / %s" % (d.get("title", ""), d.get("subtitle", "")))
for t in (d.get("tabs") or []):
    print("[astrbot-dl]   %s %s：%d 张卡" % (t.get("icon", ""), t.get("label", ""), len(t.get("cards") or [])))
print("[astrbot-dl] 浏览器打开站点，进「分享 → AstrBot」就能看到了")
__ASTRBOT_DL_SUM__

log "要反悔：bash $0 --revert"
log "要看定时任务：systemctl list-timers zhuxin-astrbot-dl.timer"
log "要立刻再同步一次：systemctl start zhuxin-astrbot-dl.service"
