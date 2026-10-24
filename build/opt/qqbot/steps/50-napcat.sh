#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 步骤 5：安装 NapCat 并注入 Linux QQ
. "$PREFIX/opt/qqbot/common.sh"

# 版本自动获取（v1.1.31）：问一次 GitHub 的 releases/latest，缓存在
# $QQBOT_STATE/versions.json 里 24 小时。查不到就用内置常量，绝不会因为
# 联网失败把部署搞挂；只采信与内置常量同大版本的 tag。
NV="$(qq_latest_ver napcat 2>/dev/null)"
[ -n "$NV" ] || NV="$QQBOT_NAPCAT_VER"
if [ "$NV" != "$QQBOT_NAPCAT_VER" ]; then
  qqlog "NapCat 有新版本：$QQBOT_NAPCAT_VER → $NV（内置版本作为回退保留）"
fi

NC="$QQBOT_CACHE/NapCat.Shell.zip"
# 1.3.1：先取后台预取好的那一份（键里带版本号，版本不一致时自然取不到、走正常下载）
_PF_NC="$(qq_pf_key napcat "$NV" NapCat.Shell.zip)"
if [ ! -s "$NC" ]; then
  qq_pf_wait "$_PF_NC" 120 && qq_pf_take "$_PF_NC" "$NC" || true
fi
if [ ! -s "$NC" ]; then
  if [ -s "$QQBOT_HOME/payload/NapCat.Shell.zip" ]; then
    qqlog "使用安装包内置的 NapCat 离线包"
    cp -f "$QQBOT_HOME/payload/NapCat.Shell.zip" "$NC"
  else
    qqlog "下载 NapCat.Shell.zip（约 34 MB，版本 $NV）"
    # 自动取到的版本下载失败时退回内置版本再试一次：新版可能刚发、镜像还没同步，
    # 因为这种事让用户重装一遍太亏。
    qq_gh_dl "$NC" "https://github.com/NapNeko/NapCatQQ/releases/download/$NV/NapCat.Shell.zip" || {
      if [ "$NV" != "$QQBOT_NAPCAT_VER" ]; then
        qqlog "自动版本 $NV 下载失败，退回内置 $QQBOT_NAPCAT_VER"
        rm -f "$NC" 2>/dev/null
        qq_gh_dl "$NC" "https://github.com/NapNeko/NapCatQQ/releases/download/$QQBOT_NAPCAT_VER/NapCat.Shell.zip"
      fi
    } || { qqerr "NapCat 下载失败"; exit 1; }
  fi
fi

if ! qq_sandbox 50-napcat.sh; then
  qqerr "NapCat 安装失败"
  exit 1
fi
qqok "NapCat 已注入 Linux QQ"
