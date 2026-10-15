#!/data/data/com.termux/files/usr/bin/bash
# 步骤 5：安装 NapCat 并注入 Linux QQ
. "$PREFIX/opt/qqbot/common.sh"

NC="$QQBOT_CACHE/NapCat.Shell.zip"
if [ ! -s "$NC" ]; then
  if [ -s "$QQBOT_HOME/payload/NapCat.Shell.zip" ]; then
    qqlog "使用安装包内置的 NapCat 离线包"
    cp -f "$QQBOT_HOME/payload/NapCat.Shell.zip" "$NC"
  else
    qqlog "下载 NapCat.Shell.zip（约 34 MB）"
    qq_gh_dl "$NC" "https://github.com/NapNeko/NapCatQQ/releases/download/$QQBOT_NAPCAT_VER/NapCat.Shell.zip" || { qqerr "NapCat 下载失败"; exit 1; }
  fi
fi

if ! qq_sandbox 50-napcat.sh; then
  qqerr "NapCat 安装失败"
  exit 1
fi
qqok "NapCat 已注入 Linux QQ"
