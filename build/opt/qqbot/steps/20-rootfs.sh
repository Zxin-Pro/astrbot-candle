#!/data/data/com.termux/files/usr/bin/bash
# 步骤 2：下载并解压 Ubuntu 24.04 rootfs（按设备架构自动选择 amd64 / arm64）
. "$PREFIX/opt/qqbot/common.sh"

ROOTFS="$QQBOT_ROOTFS"
TAR="$QQBOT_CACHE/$QQBOT_ROOTFS_TAR"
STAMP="$QQBOT_HOME/rootfs.arch"
CUR=""
[ -f "$STAMP" ] && CUR="$(cat "$STAMP" 2>/dev/null)"

# 「已完成」的判据必须够强：只看两个可执行文件的话，tar 解到一半被杀、但 bash 已经解出来的
# 残缺 rootfs 会被判成已完成，之后带着残缺树进 30-apt 再炸一次；
# 而失败点显示在第 3 步、真因其实在第 2 步，极难排查。
if [ "$CUR" = "$QQBOT_ARCH" ] && [ -x "$ROOTFS/bin/bash" ] && [ -x "$ROOTFS/usr/bin/env" ] \
   && [ -d "$ROOTFS/var/lib/dpkg" ] && [ -d "$ROOTFS/usr/lib" ] \
   && [ "$(ls -1 "$ROOTFS/usr/bin" 2>/dev/null | wc -l)" -gt 200 ]; then
  qqok "rootfs 已存在（$QQBOT_ARCH），跳过解压"
else
  if [ ! -s "$TAR" ]; then
    qqlog "下载 Ubuntu 24.04 $QQBOT_ARCH 基础 rootfs（约 29 MB）"
    # 体积下限 25 MB：rootfs 约 29 MB，低于这个数一定是错误页或被截断的残包。
    QQBOT_DL_MIN=$((25 * 1024 * 1024))
    qq_dl_mirror "$TAR" \
      "$QQBOT_TUNA/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      || { qqerr "rootfs 下载失败（可能只下到一半）"; rm -f "$TAR" "$TAR.part"; QQBOT_DL_MIN=1024; exit 1; }
    QQBOT_DL_MIN=1024
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
  # 这里原来是 &&（两个都不存在才算失败）—— 写反了：只要 bash 解出来就放过了残缺 rootfs。
  if [ ! -x "$ROOTFS/bin/bash" ] || [ ! -x "$ROOTFS/usr/bin/env" ]; then
    qqerr "rootfs 解压失败（tar 返回 $RC）"
    rm -rf "$ROOTFS"
    exit 1
  fi
  # tar 非 0 大多是「个别条目被跳过」（--exclude=dev 之类）；但关键目录都没了就绝不能
  # 让 stamp 落盘 —— 那会把一次失败的解包永久钉成「已完成」，之后每次重跑都直接跳过解压。
  if [ "$RC" != "0" ] && { [ ! -d "$ROOTFS/var/lib/dpkg" ] || [ ! -d "$ROOTFS/usr/lib" ]; }; then
    qqerr "rootfs 解压不完整（tar 返回 $RC），已删除残留目录，请重新部署"
    rm -rf "$ROOTFS"
    exit 1
  fi
  [ "$RC" != "0" ] && qqwarn "tar 返回 $RC（部分条目被跳过属正常）"
  echo "$QQBOT_ARCH" > "$STAMP"
fi

# ---------------------------------------------------------------------------
# 可写性 / 硬链接自检（真机上炸过一次，代价是用户卡在第 3 步一整晚）。
# dpkg 备份 status 用的是 link(2)：文件系统不支持硬链接时，apt 装任何包都会报
#   dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
# 而这个报错看起来像权限问题，实际是磁盘格式问题（FAT/exFAT、把应用数据放外置存储、
# 某些 FUSE 挂载都会这样）。在这里先试一次，早失败早说清楚。
# ---------------------------------------------------------------------------
# 具体哪一项不过由 qq_fsprobe 分开报（写不进去 / 不能建硬链接是两种毛病），
# 完整诊断落在 ~/.qqbot/fs-probe.txt，界面的「上传错误日志」会带上它。
qq_fsprobe "$ROOTFS" || exit 1
# 权限归一：rootfs 可能是旧版应用留下的、或从别处拷来的，属主/权限位一旦不对，
# 后面 dpkg 就会一路 Permission denied。只修关键目录，不做全树 chmod（手机上太慢）。
chmod -R u+rwX "$ROOTFS/var/lib/dpkg" "$ROOTFS/var/lib/apt" "$ROOTFS/var/cache/apt" \
  "$ROOTFS/var/log" "$ROOTFS/tmp" "$ROOTFS/root" "$ROOTFS/run" 2>/dev/null || true

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
