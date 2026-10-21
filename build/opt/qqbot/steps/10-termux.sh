#!/data/data/com.termux/files/usr/bin/bash
# 步骤 1：准备 Termux 侧工具（proot / tar / xz / curl）
. "$PREFIX/opt/qqbot/common.sh"

# ---------------------------------------------------------------------------
# 1.2.0 内置版：Termux bootstrap 本身就带了 tar / gzip / xz / zstd / unzip / curl，
# 唯独没有 proot。所以「离线装机」只差 proot 这一个二进制（arm64 约 255 KB），
# 构建时随包塞进 $PREFIX（build/bin/proot-arm64 + build/lib/libtalloc.so.2 和
# libandroid-shmem.so）。装了内置版就不再碰 apt —— 一个包都不需要联网。
# ---------------------------------------------------------------------------
if [ "$QQBOT_ARCH" = "arm64" ] && [ -x "$PREFIX/bin/proot-arm64" ] && [ ! -x "$PREFIX/bin/proot" ]; then
  qqlog "使用 APK 内置的 arm64 proot"
  cp -f "$PREFIX/bin/proot-arm64" "$PREFIX/bin/proot" 2>/dev/null || true
  chmod 755 "$PREFIX/bin/proot" 2>/dev/null || true
fi

# bootstrap 自带的工具够用就不联网。proot 按架构分别判定：
#   amd64 用随包内置的静态 proot-x86_64（common.sh 里已经把 QQBOT_PROOT 指过去）；
#   arm64 用刚才从 APK 里装出来的 $PREFIX/bin/proot。
qq_termux_ready() {
  for _t in tar gzip xz curl unzip; do
    command -v "$_t" >/dev/null 2>&1 || return 1
  done
  [ -x "$QQBOT_PROOT" ] || return 1
  return 0
}
if qq_termux_ready; then
  qqok "Termux 侧工具已齐备（proot / tar / xz / curl / unzip），跳过 apt"
  if ! qq_proot_selftest; then
    qqerr "proot 自检失败：$QQBOT_PROOT（架构 $QQBOT_ARCH）"
    qq_proot_selftest_log
    exit 1
  fi
  qqok "proot 自检通过：$QQBOT_PROOT"
  exit 0
fi

mkdir -p "$PREFIX/etc/apt"
printf 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n' > "$PREFIX/etc/apt/sources.list"

# apt/dpkg 必须完全非交互。bootstrap 里自带 etc/tls/openssl.cnf，升级 openssl 时
# dpkg 会弹 conffile 提示，而我们的 stdin 是 </dev/null，会以
# "end of file on stdin at conffile prompt" 失败（实测踩过）。
mkdir -p "$PREFIX/etc/apt/apt.conf.d"
cat > "$PREFIX/etc/apt/apt.conf.d/99-qqbot-noninteractive" <<'APTEOF'
Dpkg::Options { "--force-confdef"; "--force-confold"; }
APT::Get::Assume-Yes "true";
APT::Get::allow-downgrades "true";
APTEOF
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true

qqlog "更新 Termux 软件源索引"
if ! apt-get update -y </dev/null; then
  qqwarn "清华镜像不可用，回退 Termux 官方源"
  printf 'deb https://packages-cf.termux.dev/apt/termux-main stable main\n' > "$PREFIX/etc/apt/sources.list"
  apt-get update -y </dev/null || { qqerr "apt-get update 失败，请检查网络"; exit 1; }
fi

# 上次中断可能留下半配置状态的包，先补 configure，再修断裂的依赖关系。
# 只做 configure 不够：依赖关系被中断破坏时，下面那句 apt-get install 一样会硬失败，
# 而这一步在 Termux 侧，失败信息和沙盒里的 30-apt 长得一样，很容易被当成同一处问题。
dpkg --configure -a </dev/null >/dev/null 2>&1 || true
DEBIAN_FRONTEND=noninteractive apt-get -f install -y </dev/null >/dev/null 2>&1 || true

# arm64 用 Termux 仓库里的 proot；x86_64 模拟器上 Termux 的 proot 是 arm64 二进制，
# 经 houdini 翻译后 ptrace 寄存器布局错配会永久挂死，所以改用随包内置的静态 x86_64 proot。
PKGS="tar xz-utils curl unzip coreutils findutils ncurses-utils"
if [ "$QQBOT_ARCH" != "amd64" ]; then
  PKGS="proot $PKGS"
fi
qqlog "安装 Termux 依赖：$PKGS"
if ! apt-get install -y -o Dpkg::Options::="--force-confold" -o Dpkg::Options::="--force-confdef" \
     $PKGS </dev/null; then
  qqerr "Termux 依赖安装失败"
  exit 1
fi

if [ "$QQBOT_ARCH" = "amd64" ]; then
  [ -f "$QQBOT_PROOT" ] || { qqerr "内置 proot-x86_64 缺失：$QQBOT_PROOT"; exit 1; }
  chmod 755 "$QQBOT_PROOT" 2>/dev/null
else
  command -v proot >/dev/null 2>&1 || { qqerr "proot 未安装成功"; exit 1; }
fi
command -v curl >/dev/null 2>&1 || { qqerr "curl 未安装成功"; exit 1; }

if ! qq_proot_selftest; then
  qqerr "proot 自检失败：$QQBOT_PROOT（架构 $QQBOT_ARCH）"
  qq_proot_selftest_log
  exit 1
fi
qqok "proot 自检通过：$QQBOT_PROOT"
qqok "curl 已就绪"
