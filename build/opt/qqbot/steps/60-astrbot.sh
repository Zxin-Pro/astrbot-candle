#!/data/data/com.termux/files/usr/bin/bash
# 步骤 6：安装 AstrBot
. "$PREFIX/opt/qqbot/common.sh"

SRC="$QQBOT_CACHE/AstrBot-src.tar.gz"
DASH="$QQBOT_CACHE/AstrBot-dashboard.zip"

if [ ! -s "$SRC" ]; then
  qqlog "下载 AstrBot $QQBOT_ASTRBOT_VER 源码（约 4 MB）"
  qq_gh_dl "$SRC" "https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/$QQBOT_ASTRBOT_VER.tar.gz" || { qqerr "AstrBot 源码下载失败"; exit 1; }
fi
if [ ! -s "$DASH" ]; then
  qqlog "下载 AstrBot 控制台前端（约 5 MB）"
  qq_gh_dl "$DASH" "https://github.com/AstrBotDevs/AstrBot/releases/download/$QQBOT_ASTRBOT_VER/AstrBot-$QQBOT_ASTRBOT_VER-dashboard.zip" || { qqerr "AstrBot 控制台下载失败"; exit 1; }
fi

if ! qq_sandbox 60-astrbot.sh; then
  qqerr "AstrBot 安装失败"
  exit 1
fi
qqok "AstrBot 已安装"
