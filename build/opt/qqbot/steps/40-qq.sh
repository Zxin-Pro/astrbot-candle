#!/data/data/com.termux/files/usr/bin/bash
# 步骤 4：下载并安装 Linux QQ（按设备架构自动选择 amd64 / arm64）
. "$PREFIX/opt/qqbot/common.sh"

DEB="$QQBOT_CACHE/$QQBOT_QQ_DEB_NAME"
if [ ! -s "$DEB" ]; then
  qqlog "下载 Linux QQ $QQBOT_ARCH 版（约 198 MB）"
  qq_dl_mirror "$DEB" "$QQBOT_QQ_DEB_BASE/$QQBOT_QQ_DEB_NAME" || { qqerr "QQ 下载失败"; exit 1; }
fi

# 把精确包名当参数传进沙盒：缓存里可能同时有两个架构的 deb，只按 ls 顺序取会拿错
if ! qq_sandbox 40-qq.sh "$QQBOT_QQ_DEB_NAME"; then
  qqerr "QQ 解包安装失败"
  exit 1
fi
qqok "Linux QQ 已就位"
