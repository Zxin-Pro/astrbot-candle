#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 步骤 4：下载并安装 Linux QQ（按设备架构自动选择 amd64 / arm64）
. "$PREFIX/opt/qqbot/common.sh"

DEB="$QQBOT_CACHE/$QQBOT_QQ_DEB_NAME"
# 1.3.1：部署一开始就在后台预取这个包，这里先尝试直接取件（同一文件系统 mv，瞬时）。
# 取不到（预取失败 / 还没下完）就走下面的正常下载，行为与 1.3.0 一致。
_PF_DEB="$(qq_pf_key deb "$QQBOT_QQ_VER" "$QQBOT_QQ_DEB_NAME")"
if [ ! -s "$DEB" ]; then
  qq_pf_wait "$_PF_DEB" 150 && qq_pf_take "$_PF_DEB" "$DEB" || true
fi
if [ ! -s "$DEB" ]; then
  qqlog "下载 Linux QQ $QQBOT_ARCH 版（约 198 MB）"
  # 体积下限 150 MB：198 MB 的包被截断时 curl 可能仍然返回 0，坏 deb 一旦落到正式路径
  # 就会被永久复用，故障点跑到「解包」那一步，很难查（重装也不会清掉它）。
  QQBOT_DL_MIN=$((150 * 1024 * 1024))
  qq_dl_mirror "$DEB" "$QQBOT_QQ_DEB_BASE/$QQBOT_QQ_DEB_NAME" \
    || { qqerr "QQ 下载失败（可能只下到一半，已丢弃，请重试）"; rm -f "$DEB" "$DEB.part"; QQBOT_DL_MIN=1024; exit 1; }
  QQBOT_DL_MIN=1024
fi

# 把精确包名当参数传进沙盒：缓存里可能同时有两个架构的 deb，只按 ls 顺序取会拿错
if ! qq_sandbox 40-qq.sh "$QQBOT_QQ_DEB_NAME"; then
  qqerr "QQ 解包安装失败"
  exit 1
fi
qqok "Linux QQ 已就位"
