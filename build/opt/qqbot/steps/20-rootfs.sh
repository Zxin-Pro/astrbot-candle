#!/data/data/com.termux/files/usr/bin/bash
# 步骤 2：下载并解压 Ubuntu 24.04 rootfs（按设备架构自动选择 amd64 / arm64）
. "$PREFIX/opt/qqbot/common.sh"

ROOTFS="$QQBOT_ROOTFS"
TAR="$QQBOT_CACHE/$QQBOT_ROOTFS_TAR"
STAMP="$QQBOT_HOME/rootfs.arch"
CUR=""
[ -f "$STAMP" ] && CUR="$(cat "$STAMP" 2>/dev/null)"

if [ "$CUR" = "$QQBOT_ARCH" ] && [ -x "$ROOTFS/usr/bin/env" ] && [ -x "$ROOTFS/bin/bash" ]; then
  qqok "rootfs 已存在（$QQBOT_ARCH），跳过解压"
else
  if [ ! -s "$TAR" ]; then
    qqlog "下载 Ubuntu 24.04 $QQBOT_ARCH 基础 rootfs（约 29 MB）"
    qq_dl_mirror "$TAR" \
      "$QQBOT_TUNA/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      || { qqerr "rootfs 下载失败"; exit 1; }
  fi
  qqlog "解压 rootfs 到 $ROOTFS"
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  # 这里必须用 Termux 自带的 tar 直接解包，不能走 proot：
  # proot 的 tracee 若是经 houdini 翻译的 arm64 程序，ptrace 寄存器布局会错配而挂死。
  # 直接解包还能避开设备节点（--exclude=dev），且不需要 root。
  set +e
  tar -xzf "$TAR" -C "$ROOTFS" --no-same-owner --exclude=dev --exclude='dev/*'
  RC=$?
  set -e
  if [ ! -x "$ROOTFS/bin/bash" ] && [ ! -x "$ROOTFS/usr/bin/env" ]; then
    qqerr "rootfs 解压失败（tar 返回 $RC）"
    exit 1
  fi
  [ "$RC" != "0" ] && qqwarn "tar 返回 $RC（部分条目被跳过属正常）"
  echo "$QQBOT_ARCH" > "$STAMP"
fi

qqlog "写入 DNS / apt 源 / 沙盒基础配置"
mkdir -p "$ROOTFS/etc/apt/apt.conf.d" "$ROOTFS/etc/apt/sources.list.d"
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf"
printf 'APT::Sandbox::User "root";\nAcquire::Retries "3";\n' > "$ROOTFS/etc/apt/apt.conf.d/99proot-sandbox.conf"
rm -f "$ROOTFS"/etc/apt/sources.list.d/ubuntu.sources "$ROOTFS"/etc/apt/sources.list.d/*.list 2>/dev/null
# CA 证书：Ubuntu 基础 rootfs 不带 ca-certificates，首次 apt 走 https 会报
# "No system certificates available"，pip/curl 也会证书校验失败。
# 这里先用 Termux 自带的 CA 捆绑包垫上，30-apt 装上 ca-certificates 后会被覆盖。
if [ -f "$PREFIX/etc/tls/cert.pem" ]; then
  mkdir -p "$ROOTFS/etc/ssl/certs"
  cp -f "$PREFIX/etc/tls/cert.pem" "$ROOTFS/etc/ssl/certs/ca-certificates.crt"
  cp -f "$PREFIX/etc/tls/cert.pem" "$ROOTFS/etc/ssl/cert.pem"
  qqok "已注入 CA 证书捆绑包（$(wc -c < "$ROOTFS/etc/ssl/certs/ca-certificates.crt" 2>/dev/null) 字节）"
else
  qqwarn "未找到 $PREFIX/etc/tls/cert.pem，本次仅使用 http 源"
fi
# amd64 用 /ubuntu/，arm64 必须用 /ubuntu-ports/（tuna 的 /ubuntu/ 不承载 arm64 的 Packages 索引，会 404）
UB_PORTS="${QQBOT_UBUNTU_PORTS%% *}"
printf 'deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-backports main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n' "$UB_PORTS" "$UB_PORTS" "$UB_PORTS" "$UB_PORTS" > "$ROOTFS/etc/apt/sources.list"
qqlog "apt 源：$UB_PORTS"
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/tmp" "$ROOTFS/root" "$ROOTFS/opt"
chmod 1777 "$ROOTFS/tmp" 2>/dev/null
printf 'LANG=C.UTF-8\n' > "$ROOTFS/etc/environment"
qqok "proot 沙盒已建立：$ROOTFS（$QQBOT_ARCH）"
