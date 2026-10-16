#!/bin/bash
# 在 Ubuntu 沙盒内安装运行时依赖
set -e
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true

# dpkg conffile 提示会让安装卡死/失败（stdin 不可交互），强制保留旧配置
mkdir -p /etc/apt/apt.conf.d
cat > /etc/apt/apt.conf.d/99-qqbot-noninteractive <<'APTEOF'
Dpkg::Options { "--force-confdef"; "--force-confold"; }
APT::Get::Assume-Yes "true";
APTEOF

# ---------------------------------------------------------------------------
# 下载代理：由 App 的设置页写进宿主的 <QQBOT_HOME>/proxy.txt，再经 qq_proot 的
# "-b <QQBOT_CACHE>:/qqbot-cache" 绑定挂进来（本脚本里就是 /qqbot-cache/proxy.txt）。
# 宿主侧没有把整个 QQBOT_HOME 挂进来，所以只能读这份副本；qq_proot 另外还会把
# 代理以 http_proxy/https_proxy/all_proxy 环境变量传进来，这里是兜底。
# 代理为空/文件不存在时一律不使用代理。
# ---------------------------------------------------------------------------
PROXY_FILE="/qqbot-cache/proxy.txt"
PROXY_URL="${http_proxy:-}"
if [ -z "$PROXY_URL" ] && [ -s "$PROXY_FILE" ]; then
  # 末尾的 || true 是给 set -e 兜底：读不到文件时不能让整个安装脚本中断。
  PROXY_URL="$(tr -d '\r' < "$PROXY_FILE" 2>/dev/null | head -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)"
  PROXY_URL="$(printf '%s' "$PROXY_URL" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/" || true)"
fi
if [ -n "$PROXY_URL" ]; then
  echo "[sandbox] 使用下载代理 $PROXY_URL"
  # apt 只认 socks5h://：源码 methods/http.cc 里判断 SOCKS 的唯一分支是
  #   if (Proxy.Access == "socks5h") { ... UnwrapSocks(...) }
  # 其它 scheme 一律走「当普通 HTTP 代理连」的分支。所以用户填 socks5:// 时
  # 必须在这里换成 socks5h://，否则 apt 会拿 socks5 当 HTTP 代理去连、必然失败。
  # （h 表示由代理做域名解析；对本地代理而言语义一致，还能避免本地 DNS 污染。）
  # 注意 apt 读的是小写 http_proxy/https_proxy/no_proxy，所以下面两种大小写都给。
  APT_PROXY_URL="$PROXY_URL"
  case "$APT_PROXY_URL" in
    socks5://*) APT_PROXY_URL="socks5h://${APT_PROXY_URL#socks5://}" ;;
  esac
  [ "$APT_PROXY_URL" = "$PROXY_URL" ] || echo "[sandbox] apt 改用 $APT_PROXY_URL（apt 不认 socks5://，只认 socks5h://）"
  export http_proxy="$APT_PROXY_URL" https_proxy="$APT_PROXY_URL" all_proxy="$APT_PROXY_URL"
  export HTTP_PROXY="$APT_PROXY_URL" HTTPS_PROXY="$APT_PROXY_URL" ALL_PROXY="$APT_PROXY_URL"
  export no_proxy="127.0.0.1,localhost,::1" NO_PROXY="127.0.0.1,localhost,::1"
else
  echo "[sandbox] 未配置下载代理，直连"
fi

# apt 自己还要吃一份代理配置：apt 会调用 root 下的 apt-get/apt-helper 子进程，
# 环境变量在这些路径上不一定都被继承，写进 apt.conf.d 最稳。
# 代理为空时必须删掉这个文件：上次部署留下的旧代理会让这次 apt 全量失败。
APT_PROXY_CONF="/etc/apt/apt.conf.d/95proxy"
if [ -n "$PROXY_URL" ]; then
  {
    echo "Acquire::http::Proxy \"$APT_PROXY_URL\";"
    echo "Acquire::https::Proxy \"$APT_PROXY_URL\";"
    echo "Acquire::ftp::Proxy \"$APT_PROXY_URL\";"
  } > "$APT_PROXY_CONF"
  echo "[sandbox] 已写入 $APT_PROXY_CONF"
else
  rm -f "$APT_PROXY_CONF"
fi

# Ubuntu 源分支必须与沙盒自身架构一致：amd64 用 /ubuntu/，arm64 必须用 /ubuntu-ports/
# （tuna 的 /ubuntu/ 不承载 arm64 的 Packages 索引，会 404）
GUEST_ARCH="$(dpkg --print-architecture 2>/dev/null)"
[ -n "$GUEST_ARCH" ] || GUEST_ARCH="${QQBOT_ARCH:-arm64}"
case "$GUEST_ARCH" in
  amd64) UB_SUFFIX="ubuntu" ;;
  *)     UB_SUFFIX="ubuntu-ports" ;;
esac
echo "[sandbox] 沙盒架构 $GUEST_ARCH，使用 /$UB_SUFFIX 源分支"
# 用 http：本步骤开始时沙盒里还没有 ca-certificates，https 会因证书不可信而全量失败。
# apt 依赖 GPG 校验包签名，http 不影响安全性；ca-certificates 装好后 pip 走 https。
QQBOT_APT_MIRRORS="http://mirrors.tuna.tsinghua.edu.cn/$UB_SUFFIX http://mirrors.ustc.edu.cn/$UB_SUFFIX http://mirrors.aliyun.com/$UB_SUFFIX"
if [ "$UB_SUFFIX" = "ubuntu-ports" ]; then
  QQBOT_APT_MIRRORS="$QQBOT_APT_MIRRORS http://ports.ubuntu.com/ubuntu-ports"
else
  QQBOT_APT_MIRRORS="$QQBOT_APT_MIRRORS http://archive.ubuntu.com/ubuntu"
fi
rm -f /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true
apt_update() {
  printf 'deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-backports main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n' "$1" "$1" "$1" "$1" > /etc/apt/sources.list
  echo "[sandbox] apt-get update（镜像 $1）"
  apt-get update -y
}
APT_OK=0
for M in $QQBOT_APT_MIRRORS; do
  if apt_update "$M"; then APT_OK=1; break; fi
  echo "[sandbox] 该镜像失败，尝试下一个"
done
if [ "$APT_OK" != "1" ]; then echo "[sandbox] 所有 apt 镜像均不可用"; exit 1; fi

# ---------------------------------------------------------------------------
# 自愈 + 重试。部署如果正好在 apt 装到一半时被打断（用户切走应用、手机休眠、进程被
# 系统回收），dpkg 会留下「解包完但没配置完」的包，之后**每一次** apt-get install 都会
# 在开头直接拒绝：
#   E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.
# 用户点一百次「继续部署」都会卡在第 3 步，因为部署脚本只是无脑重跑同一行命令。
# 所以每个安装动作之前都先跑一次自愈脚本（幂等、不联网），失败后再自愈重试一次。
# ---------------------------------------------------------------------------
qq_apt_install() {
  if apt-get install -y --no-install-recommends "$@"; then return 0; fi
  echo "[sandbox] 安装失败，先修复 dpkg 状态再重试：$*"
  bash /qqbot-opt/sandbox/fix-dpkg.sh || true
  apt-get install -y --no-install-recommends "$@"
}

echo "[sandbox] 检查并修复 dpkg 状态（上次部署中断可能留下半配置的包）"
bash /qqbot-opt/sandbox/fix-dpkg.sh || true

echo "[sandbox] 安装基础工具"
qq_apt_install ca-certificates curl wget unzip zip tar xz-utils procps psmisc jq tzdata locales fontconfig
qq_apt_install fonts-noto-cjk fonts-wqy-zenhei fonts-dejavu-core

echo "[sandbox] 安装 Python 运行时"
qq_apt_install python3 python3-venv python3-pip python3-dev build-essential pkg-config libffi-dev libssl-dev libsqlite3-dev

echo "[sandbox] 安装 Electron/QQ 图形依赖"
qq_apt_install xvfb libnss3 libgbm1 libasound2t64 libxss1 libxtst6 libxdamage1 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxcomposite1 libxfixes3 libpango-1.0-0 libcairo2 libgtk-3-0t64 libx11-xcb1 libxcb-dri3-0 libexpat1 libuuid1 libxshmfence1 libnotify4 xdg-utils libatspi2.0-0t64 libsecret-1-0

echo "[sandbox] 生成 locale"
if [ -f /etc/locale.gen ]; then
  sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen || true
  sed -i 's/^# *C.UTF-8 UTF-8/C.UTF-8 UTF-8/' /etc/locale.gen || true
  locale-gen || true
fi

python3 -V
echo "[sandbox] 依赖安装完成"
