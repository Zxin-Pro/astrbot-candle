#!/data/data/com.termux/files/usr/bin/bash
# 步骤 1：准备 Termux 侧工具（proot / tar / xz / curl）
. "$PREFIX/opt/qqbot/common.sh"

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

# 上次中断可能留下半配置状态的包，先补 configure
dpkg --configure -a </dev/null >/dev/null 2>&1 || true

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
