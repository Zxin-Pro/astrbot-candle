#!/bin/bash
# ---------------------------------------------------------------------------
# AstrBot烛版 官网「下载页」一键部署（自包含版：脚本里已内嵌全部需要的文件）
#
# 它做五件事：
#   1) 找到站点目录（该目录下同时有 assets/core.js 和 data/astrbot.json）
#   2) 把原来的 data/astrbot.json 备份成 data/astrbot.json.bak.<时间戳>
#   3) 铺上新的「下载页」内容，并在 /opt/astrbot-sync/ 装上同步脚本
#   4) 立刻联网按真实线上数据同步一次
#   5) 建 systemd timer，每天 04:17 自动爬一次 GitHub Releases 刷新它
#
# 用法（在服务器上以 root 执行）：
#   bash deploy-astrbot-page.sh
#   bash deploy-astrbot-page.sh --site-dir=/www/wwwroot/example.com
#   bash deploy-astrbot-page.sh --sync-dir=/opt/astrbot-sync
#   bash deploy-astrbot-page.sh --no-timer     # 只装文件，不建定时任务
#   bash deploy-astrbot-page.sh --revert       # 还原成备份的那一份
#
# 一行命令装完（不用先下载）：
#   curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/Zxin-Pro/astrbot-candle/main/vps-deploy/deploy-astrbot-page.sh | sudo bash
#
# 想还原：再跑一次加 --revert；或者脚本会打印备份文件名，cp 回去即可。
# ---------------------------------------------------------------------------
set -eu

SITE_DIR=""
SYNC_DIR="/opt/astrbot-sync"
NO_TIMER=0
REVERT=0
TAG="[astrbot-sync]"
REPO="https://github.com/Zxin-Pro/astrbot-candle"

usage() {
  cat <<'USAGE_EOF'
AstrBot烛版 官网「下载页」一键部署

  --site-dir=DIR   指定站点根目录（默认自动探测）
  --sync-dir=DIR   同步脚本安装位置（默认 /opt/astrbot-sync）
  --no-timer       只装文件，不建 systemd 定时任务
  --revert         还原成最近一次备份的 data/astrbot.json
  -h, --help       显示这段说明
USAGE_EOF
}

for arg in "$@"; do
  case "$arg" in
    --site-dir=*) SITE_DIR="${arg#--site-dir=}" ;;
    --sync-dir=*) SYNC_DIR="${arg#--sync-dir=}" ;;
    --no-timer)   NO_TIMER=1 ;;
    --revert)     REVERT=1 ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "$TAG 不认识的参数：$arg"; usage; exit 1 ;;
  esac
done

if [ "$(id -u)" != "0" ]; then
  echo "$TAG 需要 root：请用 sudo bash deploy-astrbot-page.sh"
  exit 1
fi

# 站点目录里必须同时有 assets/core.js 与 data/astrbot.json，
# 这两个文件一起出现才说明是「烛心」这个站点，避免误伤别的目录。
looks_like_site() {
  [ -f "$1/assets/core.js" ] && [ -f "$1/data/astrbot.json" ]
}

detect_site() {
  find /var/www /www/wwwroot /usr/share/nginx/html /srv /home /opt /data 2>/dev/null \
    -maxdepth 7 -type f -path '*/data/astrbot.json' -printf '%h\n' 2>/dev/null \
  | sed 's|/data$||' | sort -u | while read -r d; do
      if looks_like_site "$d"; then printf '%s\n' "$d"; fi
    done
}

if [ "$REVERT" = "1" ]; then
  if [ -z "$SITE_DIR" ]; then SITE_DIR="$(detect_site | head -n 1)" || true; fi
  [ -n "$SITE_DIR" ] || { echo "$TAG 找不到站点目录，请用 --site-dir= 指定"; exit 1; }
  LAST="$(ls -1t "$SITE_DIR"/data/astrbot.json.bak.* 2>/dev/null | head -n 1)" || true
  [ -n "$LAST" ] || { echo "$TAG 没有备份可还原"; exit 1; }
  cp -f "$LAST" "$SITE_DIR/data/astrbot.json"
  echo "$TAG 已还原：$LAST -> $SITE_DIR/data/astrbot.json"
  exit 0
fi

if [ -z "$SITE_DIR" ]; then
  SITE_DIR="$(detect_site | head -n 1)" || true
fi
if [ -z "$SITE_DIR" ]; then
  echo "$TAG 自动探测失败，请用 --site-dir=/你的站点根目录 指定（该目录下应有 assets/core.js）"
  exit 1
fi
if ! looks_like_site "$SITE_DIR"; then
  echo "$TAG $SITE_DIR 看起来不是这个站点（缺 assets/core.js 或 data/astrbot.json）"
  exit 1
fi
SITE_DIR="${SITE_DIR%/}"
echo "$TAG 站点目录：$SITE_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"

# 1) 备份
cp -f "$SITE_DIR/data/astrbot.json" "$SITE_DIR/data/astrbot.json.bak.$STAMP"
echo "$TAG 已备份原文件 -> data/astrbot.json.bak.$STAMP"

# 2) 装同步脚本
install -d -m 0755 "$SYNC_DIR"
cat > "$SYNC_DIR/astrbot_sync.py" <<'__ASTRBOT_SYNC_PY__'
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
__ASTRBOT_SYNC_PY__
chmod 0755 "$SYNC_DIR/astrbot_sync.py"
echo "$TAG 已安装 $SYNC_DIR/astrbot_sync.py"

# 3) 铺新版内容（先把文件放进去，万一这台机器连不上 GitHub 也有东西看）
cat > "$SITE_DIR/data/astrbot.json" <<'__ASTRBOT_JSON__'
{
  "title": "AstrBot烛版",
  "subtitle": "手机上一键跑起 AstrBot + NapCat",
  "intro": "把 AstrBot 和 NapCat 打包成一个安卓安装包，安装打开后自动在手机里建好 Linux 沙盒并部署完成，扫码登录即可用。",
  "updated": "2026-10-16 10:25:53",
  "tabs": [
    {
      "key": "download",
      "label": "下载",
      "icon": "⬇️",
      "desc": "最新安装包与校验值",
      "cards": [
        {
          "icon": "📦",
          "title": "AstrBot烛版 1.1.2（最新版）",
          "desc": "安卓安装包 · 24.0 MB · 2026-10-16\n安卓 8.0 及以上 · 仅 arm64-v8a（现在绝大多数手机）\n包名 com.termux，如果装过官方 Termux 要先卸载",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/download/v1.1.2/AstrBot.apk"
        },
        {
          "icon": "🚀",
          "title": "国内加速下载",
          "desc": "GitHub 直连慢或者打不开时用这个。\n内容与上面的安装包完全一样。",
          "link": "https://ghfast.top/https://github.com/Zxin-Pro/astrbot-candle/releases/download/v1.1.2/AstrBot.apk"
        },
        {
          "icon": "🕘",
          "title": "所有历史版本",
          "desc": "想装旧版、或者想看每一版改了什么，都在这里。",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases"
        },
        {
          "icon": "🔐",
          "title": "SHA-256 校验值（v1.1.2）",
          "desc": "点这张卡复制，然后和下载到的文件比对，一样就说明没被改过。",
          "text": "40c07b500a7c0fc62c93a08572ba766f3b906c3315b4cc5625c1c59b51746fd1"
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
          "desc": "一个安卓应用，把两样东西打包在一起：\n\n· AstrBot —— 机器人框架，负责接大模型、管插件、发消息\n· NapCat —— QQ 协议端，让机器人能收能发 QQ 消息\n\n装上打开之后，它会在手机里自动建一个 Linux 沙盒，把这两样都装好、对接好，你只要扫码登录 QQ 就能用。"
        },
        {
          "icon": "📵",
          "title": "不需要电脑、不需要服务器",
          "desc": "整个过程都在手机上跑，不用买服务器、不用连电脑、不用 Root。"
        },
        {
          "icon": "🧩",
          "title": "装完之后你能做什么",
          "desc": "· 在应用里看 NapCat 与 AstrBot 的实时日志\n· 一键把 NapCat 和 AstrBot 对接起来\n· 复制登录地址和 Token，换设备扫码\n· 配多个 QQ 号，每个号独立开关容器\n· 进 AstrBot 控制台装插件、改人格"
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
          "desc": "点「下载」栏里最上面那张卡。下完点开，系统会提示「未知来源」，允许一次即可。"
        },
        {
          "icon": "2️⃣",
          "title": "打开应用，等它自动部署",
          "desc": "第一次打开会自动下载并安装运行环境，一共要下 250 MB 左右，大概 20 到 60 分钟，取决于网速。期间请保持应用在前台、别锁屏。\n中途断了也不要紧，下次打开会接着上次的进度继续。"
        },
        {
          "icon": "3️⃣",
          "title": "扫码登录 QQ",
          "desc": "部署完成后到「日志」页，会出现一张二维码。用自己的手机 QQ 扫它（用本机扫不了，得用另一台设备打开 http://手机IP:6099 ）。"
        },
        {
          "icon": "4️⃣",
          "title": "开始用",
          "desc": "回到「主页」点「自动连接 NapCat ↔ AstrBot」，指示灯变绿就好了。\n想改人格、装插件，去「更多 → AstrBot 控制台」。"
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
          "title": "v1.1.2  ·  2026-10-16",
          "desc": "### AstrBot 烛版 v1.1.2\n\n### 修复\n\n- **v1.1.1 那个补丁其实没生效，真正的原因在这里：AstrBot 把 `cmd_config.json` 写成带 UTF-8 BOM 的文件，而我们读它时按纯 UTF-8 解析，直接抛 `JSONDecodeError: Unexpected UTF-8 BOM (decode using utf-8-sig)`**（沙盒内实测报错原文）。异常被 `link.sh` 里的 `except Exception: a = {}` 吞掉，于是「当前 AstrBot 已经监听的端口」永远算成空列表，跟「需要的端口」永远对不上 → `CHANGED` 永远是 1 → 每次对接都会 `kill -TERM` 掉 AstrBot 再让守护循环拉回来（约 30 秒，期间所有 QQ 号的 OneBot 通道一起断）。表现就是：**在机器人页停掉任意一个实例，AstrBot 也跟着重启一次**。现在所有读 JSON 的地方统一改用 `utf-8-sig` 解码（它同时兼容带 BOM 和不带 BOM 的文件），比对逻辑这才真正跑起来。\n- 顺带把沙盒里所有读 JSON 的脚本都换成了 `utf-8-sig`：`link.sh`（bots.json、cmd_config.json）、`run-napcat.sh`（实例的 onebot11/webui 配置、bots.json）、`50-napcat.sh`（QQ 的 `package.json`、NapCat 配置）、`40-qq.sh`（QQ 的 `versions/config.json`）、`remove-instance.sh`（bots.json）。这些文件分别由 AstrBot、NapCat、QQ 和 App 自己写，谁加不加 BOM 不由我们决定——`utf-8-sig` 对两种情况都能读，写出去的一律不带 BOM。\n\n---",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.1.2",
          "text": "### AstrBot 烛版 v1.1.2\n\n### 修复\n\n- **v1.1.1 那个补丁其实没生效，真正的原因在这里：AstrBot 把 `cmd_config.json` 写成带 UTF-8 BOM 的文件，而我们读它时按纯 UTF-8 解析，直接抛 `JSONDecodeError: Unexpected UTF-8 BOM (decode using utf-8-sig)`**（沙盒内实测报错原文）。异常被 `link.sh` 里的 `except Exception: a = {}` 吞掉，于是「当前 AstrBot 已经监听的端口」永远算成空列表，跟「需要的端口」永远对不上 → `CHANGED` 永远是 1 → 每次对接都会 `kill -TERM` 掉 AstrBot 再让守护循环拉回来（约 30 秒，期间所有 QQ 号的 OneBot 通道一起断）。表现就是：**在机器人页停掉任意一个实例，AstrBot 也跟着重启一次**。现在所有读 JSON 的地方统一改用 `utf-8-sig` 解码（它同时兼容带 BOM 和不带 BOM 的文件），比对逻辑这才真正跑起来。\n- 顺带把沙盒里所有读 JSON 的脚本都换成了 `utf-8-sig`：`link.sh`（bots.json、cmd_config.json）、`run-napcat.sh`（实例的 onebot11/webui 配置、bots.json）、`50-napcat.sh`（QQ 的 `package.json`、NapCat 配置）、`40-qq.sh`（QQ 的 `versions/config.json`）、`remove-instance.sh`（bots.json）。这些文件分别由 AstrBot、NapCat、QQ 和 App 自己写，谁加不加 BOM 不由我们决定——`utf-8-sig` 对两种情况都能读，写出去的一律不带 BOM。\n\n---"
        },
        {
          "icon": "📝",
          "title": "v1.1.0  ·  2026-10-16",
          "desc": "### AstrBot 烛版 v1.1.0\n\n这一版加了一个「检查更新」，并顺手把版本号推到 1.1.0。\n\n### 新增\n\n- **「更多 → 关于与支持 → 检查更新」**：以前想知道有没有新版，只能自己去 GitHub 翻 release 列表。现在应用里点一下「检查更新」，它会去 GitHub 查最新版本，直接告出「有新版本 1.x.x 可以装」，并给出「下载新版本」和「查看更新内容」两个按钮——前者把安装包交给浏览器下载，下完点开就能覆盖安装（覆盖装不会动沙盒里的 QQ 登录状态）。\n- **检查更新对国内网络做了兜底**：手机直连 `api.github.com` 经常超时，所以内置了直连 + 三个反代共四个地址，依次尝试，第一个能用的就用。四十五秒还没结果会明确说「检查超时」而不是一直转圈。\n- **启动后静默自检**：打开应用九秒后会在后台查一次（同一台设备十二小时内只查一次），有新版本才弹提示，其余情况不打扰。\n\n### 说明\n\n- 查询全程在后台线程进行，前端每 500ms 取一次结果，所以点「检查更新」不会把界面卡住——这一点和「运行命令」不同，「运行命令」卡的是命令本身。\n- 「查看更新内容」是在应用内展开的：新版说明在检查更新的同时就已经一起取回来了，点一下就地展开、再点收起，不用跳浏览器（跳过去还得自己登 GitHub，国内多半打不开）。只有那一版确实没写更新说明时才会退回打开 GitHub 页面。\n- 「下载新版本」交给系统浏览器下载，应用本身不内置下载器；如果你的手机装了多个浏览器，会由默认浏览器接管。",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.1.0",
          "text": "### AstrBot 烛版 v1.1.0\n\n这一版加了一个「检查更新」，并顺手把版本号推到 1.1.0。\n\n### 新增\n\n- **「更多 → 关于与支持 → 检查更新」**：以前想知道有没有新版，只能自己去 GitHub 翻 release 列表。现在应用里点一下「检查更新」，它会去 GitHub 查最新版本，直接告出「有新版本 1.x.x 可以装」，并给出「下载新版本」和「查看更新内容」两个按钮——前者把安装包交给浏览器下载，下完点开就能覆盖安装（覆盖装不会动沙盒里的 QQ 登录状态）。\n- **检查更新对国内网络做了兜底**：手机直连 `api.github.com` 经常超时，所以内置了直连 + 三个反代共四个地址，依次尝试，第一个能用的就用。四十五秒还没结果会明确说「检查超时」而不是一直转圈。\n- **启动后静默自检**：打开应用九秒后会在后台查一次（同一台设备十二小时内只查一次），有新版本才弹提示，其余情况不打扰。\n\n### 说明\n\n- 查询全程在后台线程进行，前端每 500ms 取一次结果，所以点「检查更新」不会把界面卡住——这一点和「运行命令」不同，「运行命令」卡的是命令本身。\n- 「查看更新内容」是在应用内展开的：新版说明在检查更新的同时就已经一起取回来了，点一下就地展开、再点收起，不用跳浏览器（跳过去还得自己登 GitHub，国内多半打不开）。只有那一版确实没写更新说明时才会退回打开 GitHub 页面。\n- 「下载新版本」交给系统浏览器下载，应用本身不内置下载器；如果你的手机装了多个浏览器，会由默认浏览器接管。"
        },
        {
          "icon": "📝",
          "title": "v1.0.4  ·  2026-10-16",
          "desc": "# AstrBot烛版 v1.0.4\n\n安装包：`AstrBot.apk`（Android 8.0+，仅 arm64-v8a）\n\n覆盖安装即可，无需卸载。首次打开请先同意免责声明。\n\n---\n\n## [1.0.4] - 2026-10-16\n\n这一版是对整个应用做了一次逐行审计之后的集中修复：前端与原生两侧共 20 处缺陷，其中 4 处会让人以为「功能坏了」。\n\n### 修复\n\n- **执行一条不产生输出的命令，会让整个页面永久卡住**（本版最严重的一个）：「运行命令」里执行 `sleep 300`、或者任何等待输入的命令，超时判断形同虚设——命令不会自己结束，界面也不再刷新，之后所有按钮都像失灵，只能重启应用。现在超时真正生效，超时会终止命令并提示「命令超时没返回」。\n- **失败也会提示成功**：删除实例、自动对接 AstrBot、停止/重启服务这几件事，失败时此前同样提示成功。更糟的是「删除实例」失败时界面已经把卡片去掉了，看着像删干净了，其实没删。现在这些都按命令的真实退出码回报，失败时把最后一行输出一起显示出来。\n- **实例开关点了自己弹回去**：在机器人页把某个实例的开关打开，提示「已启动」，4 秒后它自己变回关闭。这是写盘失败时仍然回报成功造成的（开关其实是按磁盘状态显示的，磁盘没变）。现在写盘失败会明确提示「写入失败，改动没有落盘」，开关不会假开。\n- **重启手机或应用被清理后，沙盒显示「运行中」但其实是死的**：进程号标记文件是残留的，而重启后那个号很可能被别的进程占用，于是界面显示运行中、点「启动」回一句「已在运行」、自动拉起也直接跳过，服务却一直没起来。现在会核对进程号对应的命令确实是沙盒本身，不对就重新拉起。\n- **实例配置读坏时会静默换成单实例默认值**：如果配置文件损坏，程序此前会悄悄改用一份「主号 / 6099 / 6199」的默认配置显示出来；用户在这个假配置上点一次保存，真实的多实例配置就被覆盖了。现在会先把损坏的文件备份成 `bots.json.bad`，并提示「再点一次保存就用当前内容覆盖」，给你一次反悔的机会。\n- **「自动分配空闲端口」的结果会被随后的保存覆盖**：点完自动分配、接着点「保存并重启服务」，输入框里还是你原来手填的旧端口，保存时按输入框的值回写，自动分配的结果就白分了。现在自动分配后输入框会立刻显示真正生效的端口。\n- **错误提示会让页面整页冻结**：状态读取失败时，此前会直接跳过本轮全部渲染，磁贴、实例卡、端口、开关全部停在上一次的数值上，页面上只有标题旁一行小字。现在还是一样的文字，但会注明「显示的是上一次数据」，并且其余内容继续正常渲染。\n- **部分按钮点了没反应**：这些按钮在底层调用抛异常时会中断整个点击处理，例如「全部对接 AstrBot」已经提示「正在对接 N 个实例…」却什么…",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.0.4",
          "text": "# AstrBot烛版 v1.0.4\n\n安装包：`AstrBot.apk`（Android 8.0+，仅 arm64-v8a）\n\n覆盖安装即可，无需卸载。首次打开请先同意免责声明。\n\n---\n\n## [1.0.4] - 2026-10-16\n\n这一版是对整个应用做了一次逐行审计之后的集中修复：前端与原生两侧共 20 处缺陷，其中 4 处会让人以为「功能坏了」。\n\n### 修复\n\n- **执行一条不产生输出的命令，会让整个页面永久卡住**（本版最严重的一个）：「运行命令」里执行 `sleep 300`、或者任何等待输入的命令，超时判断形同虚设——命令不会自己结束，界面也不再刷新，之后所有按钮都像失灵，只能重启应用。现在超时真正生效，超时会终止命令并提示「命令超时没返回」。\n- **失败也会提示成功**：删除实例、自动对接 AstrBot、停止/重启服务这几件事，失败时此前同样提示成功。更糟的是「删除实例」失败时界面已经把卡片去掉了，看着像删干净了，其实没删。现在这些都按命令的真实退出码回报，失败时把最后一行输出一起显示出来。\n- **实例开关点了自己弹回去**：在机器人页把某个实例的开关打开，提示「已启动」，4 秒后它自己变回关闭。这是写盘失败时仍然回报成功造成的（开关其实是按磁盘状态显示的，磁盘没变）。现在写盘失败会明确提示「写入失败，改动没有落盘」，开关不会假开。\n- **重启手机或应用被清理后，沙盒显示「运行中」但其实是死的**：进程号标记文件是残留的，而重启后那个号很可能被别的进程占用，于是界面显示运行中、点「启动」回一句「已在运行」、自动拉起也直接跳过，服务却一直没起来。现在会核对进程号对应的命令确实是沙盒本身，不对就重新拉起。\n- **实例配置读坏时会静默换成单实例默认值**：如果配置文件损坏，程序此前会悄悄改用一份「主号 / 6099 / 6199」的默认配置显示出来；用户在这个假配置上点一次保存，真实的多实例配置就被覆盖了。现在会先把损坏的文件备份成 `bots.json.bad`，并提示「再点一次保存就用当前内容覆盖」，给你一次反悔的机会。\n- **「自动分配空闲端口」的结果会被随后的保存覆盖**：点完自动分配、接着点「保存并重启服务」，输入框里还是你原来手填的旧端口，保存时按输入框的值回写，自动分配的结果就白分了。现在自动分配后输入框会立刻显示真正生效的端口。\n- **错误提示会让页面整页冻结**：状态读取失败时，此前会直接跳过本轮全部渲染，磁贴、实例卡、端口、开关全部停在上一次的数值上，页面上只有标题旁一行小字。现在还是一样的文字，但会注明「显示的是上一次数据」，并且其余内容继续正常渲染。\n- **部分按钮点了没反应**：这些按钮在底层调用抛异常时会中断整个点击处理，例如「全部对接 AstrBot」已经提示「正在对接 N 个实例…」却什么…"
        },
        {
          "icon": "📝",
          "title": "v1.0.2  ·  2026-10-16",
          "desc": "## AstrBot烛版 v1.0.2\n\n一个 Android 安装包：装上、打开，它会在手机里自动建好一个 Linux 沙盒（Ubuntu 24.04），并在里面部署 **NapCat**（QQ 协议端）与 **AstrBot**（机器人框架），两者已预置对接。界面是一个图形化控制台（标题「烛之控制台」），**全程不需要输入任何命令**，也不需要 root。\n\n| 项目 | 值 |\n| --- | --- |\n| 安装包 | 本页下方的 `AstrBot.apk`（25,156,782 字节） |\n| SHA-256 | `3E6EFE4C721447969D1C3B8DF461FE4284A691EE2431772231D677BC1F476432` |\n| 使用说明 | 本页下方的 `USAGE.md`，或 [仓库内 使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)（GitHub 不支持中文资源文件名，所以下载资源的名字是 ASCII 的） |\n| 版本 | `1.0.2`（versionCode `10002`） |\n| 系统要求 | Android 8.0 及以上，仅 arm64-v8a |\n| 包名 | `com.termux`（与官方 Termux 相同） |\n| 官方网站 | [烛心.xyz](https://烛心.xyz) ｜ 官方群 `1127997343` |\n\n> ⚠️ **安装前必须先卸载官方 / 应用商店 / F-Droid 版 Termux** —— 本应用与它包名相同，两者无法共存。\n\n**首次打开**会看到免责声明弹窗，同意后沙盒开始自动部署：需要下载约 250 MB（Ubuntu 根文件系统、Linux QQ、NapCat、依赖），大约 20–60 分钟，期间请保持应用在前台。整个过程可以在「主页」看到 8 个步骤的进度，中断了也能续跑。\n\n### 本版修了什么\n\n- **「自动分配空闲端口」会把正常运行的配置改坏**（本版最严重的一个）：点一次这个按钮，配置里每个实例的端口就会被换成出厂默认的 6099 / 6199 / 6185。原因是它用「端口能不能连上」判断占用，而每个实例**自己正在监听的端口当然也连得上**，于是全被当成「被别人占了」跳过，从 6099 往下找必然落到默认值；更糟的是它只把新端口写进 `bots.json` 却**不重启容器**，主页随即按新端口去探测，NapCat 与 AstrBot 两个状态灯立刻变红「离线」而且一直不恢复，看起来就像把服务点坏了。现在改为**先沿用每个实例当前的端口**，只有和本次已分配的端口撞车时才重新挑；端口一个都没…",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.0.2",
          "text": "## AstrBot烛版 v1.0.2\n\n一个 Android 安装包：装上、打开，它会在手机里自动建好一个 Linux 沙盒（Ubuntu 24.04），并在里面部署 **NapCat**（QQ 协议端）与 **AstrBot**（机器人框架），两者已预置对接。界面是一个图形化控制台（标题「烛之控制台」），**全程不需要输入任何命令**，也不需要 root。\n\n| 项目 | 值 |\n| --- | --- |\n| 安装包 | 本页下方的 `AstrBot.apk`（25,156,782 字节） |\n| SHA-256 | `3E6EFE4C721447969D1C3B8DF461FE4284A691EE2431772231D677BC1F476432` |\n| 使用说明 | 本页下方的 `USAGE.md`，或 [仓库内 使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)（GitHub 不支持中文资源文件名，所以下载资源的名字是 ASCII 的） |\n| 版本 | `1.0.2`（versionCode `10002`） |\n| 系统要求 | Android 8.0 及以上，仅 arm64-v8a |\n| 包名 | `com.termux`（与官方 Termux 相同） |\n| 官方网站 | [烛心.xyz](https://烛心.xyz) ｜ 官方群 `1127997343` |\n\n> ⚠️ **安装前必须先卸载官方 / 应用商店 / F-Droid 版 Termux** —— 本应用与它包名相同，两者无法共存。\n\n**首次打开**会看到免责声明弹窗，同意后沙盒开始自动部署：需要下载约 250 MB（Ubuntu 根文件系统、Linux QQ、NapCat、依赖），大约 20–60 分钟，期间请保持应用在前台。整个过程可以在「主页」看到 8 个步骤的进度，中断了也能续跑。\n\n### 本版修了什么\n\n- **「自动分配空闲端口」会把正常运行的配置改坏**（本版最严重的一个）：点一次这个按钮，配置里每个实例的端口就会被换成出厂默认的 6099 / 6199 / 6185。原因是它用「端口能不能连上」判断占用，而每个实例**自己正在监听的端口当然也连得上**，于是全被当成「被别人占了」跳过，从 6099 往下找必然落到默认值；更糟的是它只把新端口写进 `bots.json` 却**不重启容器**，主页随即按新端口去探测，NapCat 与 AstrBot 两个状态灯立刻变红「离线」而且一直不恢复，看起来就像把服务点坏了。现在改为**先沿用每个实例当前的端口**，只有和本次已分配的端口撞车时才重新挑；端口一个都没…"
        },
        {
          "icon": "📝",
          "title": "v1.0.1  ·  2026-10-16",
          "desc": "## 修复\n\n- **扫码登录提示里的端口是写死的**：主页「访问地址」卡的扫码提示原本固定写 `http://手机IP:6099`，但主号自动分配到的 WebUI 端口是 **6098**。照着提示用电脑浏览器打开会连不上，而扫码登录恰恰是最常用的一条路径。现在提示里的端口跟随当前实例的实际 WebUI 端口。\n\n## 变更\n\n- 版本号改由仓库根目录的 `VERSION` 文件统一提供，`build-ui.ps1` 打包时自动换算成 `versionCode` 写进 APK。此前版本号只存在于本地的 `_apkwork/apktool.yml` 里，而该目录不进版本库。\n\n## 安装\n\n下载下面的 `AstrBot.apk` 直接安装。**安装前请先卸载官方 / 应用商店 / F-Droid 版 Termux** —— 包名同为 `com.termux`，两者不能共存。\n\n| 项 | 值 |\n| --- | --- |\n| 应用名 | AstrBot烛版（控制台标题「烛之控制台」） |\n| 版本 | **1.0.1**（versionCode `10001`） |\n| 支持 | Android 8.0 及以上，**仅 arm64-v8a 真机** |\n| 大小 | 约 24 MB |\n\n首次打开会自动建立 Linux 沙盒并部署 NapCat + AstrBot，约需下载 250 MB、耗时 20~60 分钟，期间请保持应用在前台。\n\n完整使用说明见仓库里的 [使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)。",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.0.1",
          "text": "## 修复\n\n- **扫码登录提示里的端口是写死的**：主页「访问地址」卡的扫码提示原本固定写 `http://手机IP:6099`，但主号自动分配到的 WebUI 端口是 **6098**。照着提示用电脑浏览器打开会连不上，而扫码登录恰恰是最常用的一条路径。现在提示里的端口跟随当前实例的实际 WebUI 端口。\n\n## 变更\n\n- 版本号改由仓库根目录的 `VERSION` 文件统一提供，`build-ui.ps1` 打包时自动换算成 `versionCode` 写进 APK。此前版本号只存在于本地的 `_apkwork/apktool.yml` 里，而该目录不进版本库。\n\n## 安装\n\n下载下面的 `AstrBot.apk` 直接安装。**安装前请先卸载官方 / 应用商店 / F-Droid 版 Termux** —— 包名同为 `com.termux`，两者不能共存。\n\n| 项 | 值 |\n| --- | --- |\n| 应用名 | AstrBot烛版（控制台标题「烛之控制台」） |\n| 版本 | **1.0.1**（versionCode `10001`） |\n| 支持 | Android 8.0 及以上，**仅 arm64-v8a 真机** |\n| 大小 | 约 24 MB |\n\n首次打开会自动建立 Linux 沙盒并部署 NapCat + AstrBot，约需下载 250 MB、耗时 20~60 分钟，期间请保持应用在前台。\n\n完整使用说明见仓库里的 [使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E.md)。"
        },
        {
          "icon": "📝",
          "title": "v1.0.0  ·  2026-10-15",
          "desc": "## AstrBot烛版 v1.0.0\n\n把 **Linux QQ + NapCat + AstrBot** 装进 Android 手机的开箱即用包。装上 APK、打开、等它自己跑完，手机里就有了一台 7×24 在线的 QQ 机器人服务器 —— 全程不需要输入任何命令，也不需要 root。\n\n### 下载安装包\n\n| 项目 | 值 |\n| --- | --- |\n| 文件名 | `AstrBot.apk` |\n| 大小 | 25,156,782 字节（约 24.0 MB） |\n| SHA-256 | `9014807FF6DFAA1C038E0AF0B54A260B2BCD988678682B8DFC522E0EBB23B992` |\n| 包名 | `com.termux` |\n| 版本 | versionName `1.0.0` / versionCode `10000` |\n| 系统要求 | **Android 8.0+**，仅 **arm64-v8a** |\n| 签名 | debug 签名（安装时需允许「未知来源应用」） |\n\n> **安装前必须先卸载官方 / 应用商店 / F-Droid 版 Termux**：本应用包名同样是 `com.termux`，两者不能共存。\n\n### 本版功能\n\n- 打开应用自动建立 **Ubuntu 24.04 proot 沙盒**（无需 root、无需刷机、无需命令行）\n- 沙盒内自动部署 **Linux QQ 3.2.34-53644 + NapCat v4.18.38 + AstrBot v4.28.2**，并出厂预接线 OneBot 反向 WS\n- 原生 HTML 控制台（**主页 / 机器人 / 日志 / 更多** 四个页签）：一键对接、扫码登录、实时日志、多 NapCat 实例管理、端口配置\n- **三层防掉线守护**：沙盒 `run-all.sh` + Termux 侧 `watchdog.sh` + Android 前台服务 `QqbotGuardService`\n- 沙盒时区跟随 Android 系统时区，日志时间不再差 8 小时\n- 首次打开显示免责声明弹窗\n\n### 首次部署须知\n\n首次打开会自动开始部署，需要下载约 **250 MB**（Linux QQ 就占约 198 MB），耗时 **20~60 分钟**，请保持在**前台**。中途退出也没关系，下次打开会从断点继续。\n\n详细说明见仓库 [README](https://github.com/Zxin-Pro/astrbot-candle/blob/main/README.md) 与 [使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8…",
          "link": "https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.0.0",
          "text": "## AstrBot烛版 v1.0.0\n\n把 **Linux QQ + NapCat + AstrBot** 装进 Android 手机的开箱即用包。装上 APK、打开、等它自己跑完，手机里就有了一台 7×24 在线的 QQ 机器人服务器 —— 全程不需要输入任何命令，也不需要 root。\n\n### 下载安装包\n\n| 项目 | 值 |\n| --- | --- |\n| 文件名 | `AstrBot.apk` |\n| 大小 | 25,156,782 字节（约 24.0 MB） |\n| SHA-256 | `9014807FF6DFAA1C038E0AF0B54A260B2BCD988678682B8DFC522E0EBB23B992` |\n| 包名 | `com.termux` |\n| 版本 | versionName `1.0.0` / versionCode `10000` |\n| 系统要求 | **Android 8.0+**，仅 **arm64-v8a** |\n| 签名 | debug 签名（安装时需允许「未知来源应用」） |\n\n> **安装前必须先卸载官方 / 应用商店 / F-Droid 版 Termux**：本应用包名同样是 `com.termux`，两者不能共存。\n\n### 本版功能\n\n- 打开应用自动建立 **Ubuntu 24.04 proot 沙盒**（无需 root、无需刷机、无需命令行）\n- 沙盒内自动部署 **Linux QQ 3.2.34-53644 + NapCat v4.18.38 + AstrBot v4.28.2**，并出厂预接线 OneBot 反向 WS\n- 原生 HTML 控制台（**主页 / 机器人 / 日志 / 更多** 四个页签）：一键对接、扫码登录、实时日志、多 NapCat 实例管理、端口配置\n- **三层防掉线守护**：沙盒 `run-all.sh` + Termux 侧 `watchdog.sh` + Android 前台服务 `QqbotGuardService`\n- 沙盒时区跟随 Android 系统时区，日志时间不再差 8 小时\n- 首次打开显示免责声明弹窗\n\n### 首次部署须知\n\n首次打开会自动开始部署，需要下载约 **250 MB**（Linux QQ 就占约 198 MB），耗时 **20~60 分钟**，请保持在**前台**。中途退出也没关系，下次打开会从断点继续。\n\n详细说明见仓库 [README](https://github.com/Zxin-Pro/astrbot-candle/blob/main/README.md) 与 [使用说明.md](https://github.com/Zxin-Pro/astrbot-candle/blob/main/%E4%BD%BF%E7%94%A8…"
        }
      ]
    }
  ]
}

__ASTRBOT_JSON__
chmod 0644 "$SITE_DIR/data/astrbot.json"
echo "$TAG 已写入新版 data/astrbot.json"

# 4) 立刻按真实线上数据同步一次（失败不算致命，保留刚铺好的静态版本）
if ! command -v python3 >/dev/null 2>&1; then
  echo "$TAG 警告：这台机器没有 python3，每日同步会跑不起来（Debian/Ubuntu: apt install -y python3）"
elif ASTRBOT_JSON="$SITE_DIR/data/astrbot.json" python3 "$SYNC_DIR/astrbot_sync.py"; then
  echo "$TAG 首次联网同步成功"
else
  echo "$TAG 首次联网同步失败（多半是这台机器连不上 api.github.com）"
  echo "$TAG 已保留刚铺好的静态版本，稍后定时任务会再试"
fi

# 5) systemd timer
if [ "$NO_TIMER" = "1" ]; then
  echo "$TAG 按 --no-timer 跳过定时任务"
elif ! command -v systemctl >/dev/null 2>&1; then
  echo "$TAG 这台机器没有 systemd。请自行加一条 cron：

    17 4 * * * ASTRBOT_JSON=$SITE_DIR/data/astrbot.json /usr/bin/python3 $SYNC_DIR/astrbot_sync.py >> /var/log/astrbot-sync.log 2>&1"
else
  cat > /etc/systemd/system/astrbot-sync.service <<'__SVC__'
[Unit]
Description=AstrBot烛版 下载页数据同步（每日爬 GitHub Releases 刷新 data/astrbot.json）
Documentation=https://github.com/Zxin-Pro/astrbot-candle
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=root
# 站点目录由 install.sh 自动探测后写进下面这行；也可以手动改。
Environment=ASTRBOT_JSON=__SITE_DIR__/data/astrbot.json
ExecStart=/usr/bin/python3 /opt/astrbot-sync/astrbot_sync.py
# 失败就重试两次（网络抖动），仍然失败保留上一次的好数据。
Restart=on-failure
RestartSec=30
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
__SVC__
  cat > /etc/systemd/system/astrbot-sync.timer <<'__TMR__'
[Unit]
Description=每天同步一次 AstrBot烛版 下载页数据

[Timer]
# 每天 04:17 跑（错开整点，避免和其它定时任务撞在一起）
OnCalendar=*-*-* 04:17:00
# 服务器关机错过了就开机后补一次
Persistent=true
# 再加一道保险：随机延迟最多 10 分钟，避免多台机器同时打 GitHub
RandomizedDelaySec=600
Unit=astrbot-sync.service

[Install]
WantedBy=timers.target
__TMR__
  sed -i "s|__SITE_DIR__|$SITE_DIR|g" /etc/systemd/system/astrbot-sync.service
  systemctl daemon-reload
  systemctl enable --now astrbot-sync.timer >/dev/null 2>&1
  echo "$TAG 已启用 astrbot-sync.timer（每天 04:17）"
  systemctl list-timers astrbot-sync.timer --no-pager 2>/dev/null | head -n 3 || true
fi

echo "$TAG 完成。"
echo "$TAG 想还原就再跑一次：bash $0 --revert"
