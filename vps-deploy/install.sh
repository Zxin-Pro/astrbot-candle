#!/bin/bash
# ---------------------------------------------------------------------------
# 一键安装：把「AstrBot烛版 下载页」的同步程序装到这台服务器上。
#
# 做四件事：
#   1) 找到站点目录（里面同时有 assets/core.js 和 data/astrbot.json）
#   2) 备份原来的 data/astrbot.json
#   3) 装上新版 data/astrbot.json，并把同步脚本放到 /opt/astrbot-sync/
#   4) 建 systemd timer，每天 04:17 自动刷一次；装完立刻跑一次
#
# 用法：
#   sudo bash install.sh
#   sudo bash install.sh --site-dir=/www/wwwroot/example.com
#   sudo bash install.sh --no-timer          # 只装文件，不建定时任务
#   sudo bash install.sh --revert            # 还原成备份的那一份
# ---------------------------------------------------------------------------
set -eu

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SITE_DIR=""
SYNC_DIR="/opt/astrbot-sync"
NO_TIMER=0
REVERT=0
TAG="[astrbot-sync]"

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --site-dir=*) SITE_DIR="${arg#--site-dir=}" ;;
    --sync-dir=*) SYNC_DIR="${arg#--sync-dir=}" ;;
    --no-timer)   NO_TIMER=1 ;;
    --revert)     REVERT=1 ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "$TAG 不认识的参数：$arg"; exit 1 ;;
  esac
done

if [ "$(id -u)" != "0" ]; then
  echo "$TAG 需要 root：请用 sudo bash install.sh"
  exit 1
fi

# 反过来找：站点目录里必须同时有 assets/core.js 与 data/astrbot.json，
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
  if [ -z "$SITE_DIR" ]; then SITE_DIR="$(detect_site | head -n 1)"; fi
  [ -n "$SITE_DIR" ] || { echo "$TAG 找不到站点目录，请用 --site-dir= 指定"; exit 1; }
  LAST="$(ls -1t "$SITE_DIR"/data/astrbot.json.bak.* 2>/dev/null | head -n 1)"
  [ -n "$LAST" ] || { echo "$TAG 没有备份可还原"; exit 1; }
  cp -f "$LAST" "$SITE_DIR/data/astrbot.json"
  echo "$TAG 已还原：$LAST -> $SITE_DIR/data/astrbot.json"
  exit 0
fi

if [ -z "$SITE_DIR" ]; then
  SITE_DIR="$(detect_site | head -n 1)"
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

# 2) 安装同步脚本
install -d -m 0755 "$SYNC_DIR"
install -m 0755 "$SELF_DIR/astrbot_sync.py" "$SYNC_DIR/astrbot_sync.py"
echo "$TAG 已安装 $SYNC_DIR/astrbot_sync.py"

# 3) 铺新版内容（先把文件放进去，万一 GitHub 连不上也有东西看）
install -m 0644 "$SELF_DIR/data/astrbot.json" "$SITE_DIR/data/astrbot.json"
echo "$TAG 已写入新版 data/astrbot.json"

# 4) 立刻按真实线上数据同步一次（失败不算致命，回滚到刚铺的那份）
if ASTRBOT_JSON="$SITE_DIR/data/astrbot.json" python3 "$SYNC_DIR/astrbot_sync.py"; then
  echo "$TAG 首次联网同步成功"
else
  echo "$TAG 首次联网同步失败（多半是这台机器连不上 api.github.com）"
  echo "$TAG 已保留刚铺好的静态版本，稍后定时任务会再试"
fi

# 5) systemd timer
if [ "$NO_TIMER" = "1" ]; then
  echo "$TAG 按 --no-timer 跳过定时任务"
elif ! command -v systemctl >/dev/null 2>&1; then
  echo "$TAG 这台机器没有 systemd。请自行加一条 cron："
  echo "    17 4 * * * ASTRBOT_JSON=$SITE_DIR/data/astrbot.json /usr/bin/python3 $SYNC_DIR/astrbot_sync.py >> /var/log/astrbot-sync.log 2>&1"
else
  install -m 0644 "$SELF_DIR/astrbot-sync.service" /etc/systemd/system/astrbot-sync.service
  install -m 0644 "$SELF_DIR/astrbot-sync.timer"   /etc/systemd/system/astrbot-sync.timer
  sed -i "s|__SITE_DIR__|$SITE_DIR|g" /etc/systemd/system/astrbot-sync.service
  systemctl daemon-reload
  systemctl enable --now astrbot-sync.timer >/dev/null 2>&1
  echo "$TAG 已启用 astrbot-sync.timer（每天 04:17）"
  systemctl list-timers astrbot-sync.timer --no-pager 2>/dev/null | head -n 3 || true
fi

echo "$TAG 完成。"
