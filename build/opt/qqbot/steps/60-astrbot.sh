#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 步骤 6：安装 AstrBot
. "$PREFIX/opt/qqbot/common.sh"

# 版本自动获取（v1.1.31）：源码包与控制台前端必须用**同一个 tag**，否则前后端
# 版本错位、控制台会白屏或接口 404。所以只查一次、两个下载都用它。
AV="$(qq_latest_ver astrbot 2>/dev/null)"
[ -n "$AV" ] || AV="$QQBOT_ASTRBOT_VER"
if [ "$AV" != "$QQBOT_ASTRBOT_VER" ]; then
  qqlog "AstrBot 有新版本：$QQBOT_ASTRBOT_VER → $AV（内置版本作为回退保留）"
fi

SRC="$QQBOT_CACHE/AstrBot-src.tar.gz"
DASH="$QQBOT_CACHE/AstrBot-dashboard.zip"

# 换版本时要清掉旧缓存：文件名不带版本号，留着就会把上一个版本的包装进去。
if [ -s "$QQBOT_CACHE/astrbot-ver" ] && [ "$(cat "$QQBOT_CACHE/astrbot-ver" 2>/dev/null)" != "$AV" ]; then
  qqlog "检测到缓存里的 AstrBot 是旧版本，清掉重新下载"
  rm -f "$SRC" "$DASH" 2>/dev/null
fi

# 1.3.1：两个包都在后台预取过了，先按版本取件；取不到再自己下（控制台前端常比源码慢一倍）
_PF_SRC="$(qq_pf_key astrbot "$AV" AstrBot-src.tar.gz)"
_PF_DASH="$(qq_pf_key astrbot "$AV" AstrBot-dashboard.zip)"
if [ ! -s "$SRC" ]; then
  qq_pf_wait "$_PF_SRC" 150 && qq_pf_take "$_PF_SRC" "$SRC" || true
fi
if [ ! -s "$DASH" ]; then
  qq_pf_wait "$_PF_DASH" 150 && qq_pf_take "$_PF_DASH" "$DASH" || true
fi

if [ ! -s "$SRC" ]; then
  qqlog "下载 AstrBot $AV 源码（约 4 MB）"
  if ! qq_gh_dl "$SRC" "https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/$AV.tar.gz"; then
    if [ "$AV" != "$QQBOT_ASTRBOT_VER" ]; then
      qqlog "自动版本 $AV 源码下载失败，退回内置 $QQBOT_ASTRBOT_VER"
      AV="$QQBOT_ASTRBOT_VER"
      rm -f "$SRC" 2>/dev/null
      qq_gh_dl "$SRC" "https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/$AV.tar.gz" || { qqerr "AstrBot 源码下载失败"; exit 1; }
    else
      qqerr "AstrBot 源码下载失败"; exit 1
    fi
  fi
fi
if [ ! -s "$DASH" ]; then
  qqlog "下载 AstrBot 控制台前端（约 5 MB，版本 $AV）"
  if ! qq_gh_dl "$DASH" "https://github.com/AstrBotDevs/AstrBot/releases/download/$AV/AstrBot-$AV-dashboard.zip"; then
    if [ "$AV" != "$QQBOT_ASTRBOT_VER" ]; then
      qqlog "自动版本 $AV 控制台下载失败，退回内置 $QQBOT_ASTRBOT_VER"
      AV="$QQBOT_ASTRBOT_VER"
      rm -f "$DASH" 2>/dev/null
      qq_gh_dl "$DASH" "https://github.com/AstrBotDevs/AstrBot/releases/download/$AV/AstrBot-$AV-dashboard.zip" || { qqerr "AstrBot 控制台下载失败"; exit 1; }
    else
      qqerr "AstrBot 控制台下载失败"; exit 1
    fi
  fi
fi

# 记下这次装的是哪个版本，下次换版本时用来清缓存（沙盒里的 60-astrbot.sh 会读它）
mkdir -p "$QQBOT_CACHE" 2>/dev/null
printf '%s\n' "$AV" > "$QQBOT_CACHE/astrbot-ver" 2>/dev/null

if ! qq_sandbox 60-astrbot.sh; then
  qqerr "AstrBot 安装失败"
  exit 1
fi
qqok "AstrBot 已安装（$AV）"
