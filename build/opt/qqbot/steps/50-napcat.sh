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
if [ ! -s "$NC" ]; then
  if [ -s "$QQBOT_HOME/payload/NapCat.Shell.zip" ]; then
    qqlog "使用安装包内置的 NapCat 离线包"
    cp -f "$QQBOT_HOME/payload/NapCat.Shell.zip" "$NC"
  else
    qqlog "下载 NapCat.Shell.zip（约 34 MB，版本 $NV）"
    # 自动取到的版本下载失败时退回内置版本再试一次：新版可能刚发、镜像还没同步，
    # 因为这种事让用户重装一遍太亏。
    # 必须用显式 if/else 判结果。旧写法是
    #   qq_gh_dl … || { if [ "$NV" != 内置 ]; then 回退; fi } || { qqerr; exit 1; }
    # 而 NV 恰好等于内置版本时 if 为假，整个 {} 组以 0 退出，后面那个兜底分支
    # 永远不会执行 —— 下载失败被当成成功，第 5 步会带着空文件继续往下走。
    if qq_gh_dl "$NC" "https://github.com/NapNeko/NapCatQQ/releases/download/$NV/NapCat.Shell.zip"; then
      : # 自动版本下载成功，直接用
    elif [ "$NV" != "$QQBOT_NAPCAT_VER" ]; then
      qqlog "自动版本 $NV 下载失败，退回内置 $QQBOT_NAPCAT_VER"
      rm -f "$NC" 2>/dev/null
      if ! qq_gh_dl "$NC" "https://github.com/NapNeko/NapCatQQ/releases/download/$QQBOT_NAPCAT_VER/NapCat.Shell.zip"; then
        qqerr "NapCat 下载失败（自动版本 $NV 与内置版本 $QQBOT_NAPCAT_VER 都没下成）"
        exit 1
      fi
    else
      qqerr "NapCat 下载失败（版本 $NV）"
      exit 1
    fi
  fi
fi

if ! qq_sandbox 50-napcat.sh; then
  qqerr "NapCat 安装失败"
  exit 1
fi
qqok "NapCat 已注入 Linux QQ"
