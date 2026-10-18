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
APT::Install-Recommends "false";
// Use-Pty 关掉：dpkg 默认会给维护者脚本开伪终端，
// 容器里 pty 与 proot 的组合偶尔会让脚本卡在 read 上，
// 而部署是无人值守的，卡住就等于第 3 步永远不结束。
Dpkg::Use-Pty "false";

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

# 空间预检：apt 解包 libc6 / gcc 这类大包时缓存与解包空间同时占用，手机上的常见
# 失败就是这里，而报错是一长串 "No space left on device"，用户根本看不出是空间问题。
# 先量一次，不够就直说。900 MB 是实测装完全部依赖所需的下限（缓存清理前）。
FREE_MB=$(df -k / 2>/dev/null | awk 'NR==2 {print int($4/1024)}')
case "$FREE_MB" in ''|*[!0-9]*) FREE_MB=0 ;; esac
echo "[sandbox] 沙盒可用空间 ${FREE_MB} MB"
if [ "$FREE_MB" -gt 0 ] && [ "$FREE_MB" -lt 900 ]; then
  echo "[sandbox] E: 沙盒所在分区只剩 ${FREE_MB} MB，装运行依赖至少要 900 MB 可用空间"
  echo "[sandbox] E: 请先清理手机存储（相册/下载/其它应用），再回主页点「继续部署」"
  exit 1
fi

# ---------------------------------------------------------------------------
# dpkg 数据库预检 + 加固（真机实测过 /var/lib/dpkg 写不进去的失败形态）。
# 用户真机上报的原始错误是这两句：
#   dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
#   dpkg-deb (subprocess): cannot copy archive member ... to decompressor pipe: failed to write (Broken pipe)
#   dpkg-deb: error: <decompress> subprocess returned error exit status 2
# 它们都指向同一件事：dpkg 往 /var/lib/dpkg 里写东西被拒。可能的原因有三类，
# 这个函数逐个探测并把结果写进日志（日志会进「上次失败原因」红框，出问题能直接看到）：
#   1) 目录/文件属主或权限不对（rootfs 被别的用户解出来过、权限位丢了）→ chmod 修
#   2) status / status-old 被旧版 proot --link2symlink 换成了指向宿主的符号链接 → 换回普通文件
#   3) 文件系统根本不支持硬链接（dpkg 备份 status 用的就是 link()）→ 只能重建根文件系统
# 任何一步修不好都立刻失败退出：早退比下载 200 MB 之后再炸好得多。
# ---------------------------------------------------------------------------
qq_db_say() { echo "[sandbox] $*"; }
qq_db_precheck() {
  DB=/var/lib/dpkg
  if [ ! -d "$DB" ]; then
    qq_db_say "E: $DB 不存在，根文件系统不完整"
    return 1
  fi
  qq_db_say "dpkg 预检：身份 $(id -u 2>/dev/null):$(id -gn 2>/dev/null)，$DB $(ls -ld "$DB" 2>&1 | tr -s ' ')"
  qq_db_say "dpkg 预检：status $(ls -l "$DB/status" 2>&1 | tr -s ' ')"
  qq_db_say "dpkg 预检：空间 $(df -h / 2>/dev/null | tail -n 1 | tr -s ' ')；inode $(df -i / 2>/dev/null | tail -n 1 | tr -s ' ')"

  # (2) status 必须是普通文件：旧版 proot --link2symlink 会把它换成指向宿主绝对路径的符号链接，
  # 那种链接在沙盒里解析不到，dpkg 会一直报错。cp -L 顺着链接把内容读出来再覆盖成普通文件。
  if [ -L "$DB/status" ]; then
    qq_db_say "status 是符号链接（旧版 link2symlink 留下的），换成普通文件"
    rm -f "$DB/status.qqbottmp" 2>/dev/null || true
    if cp -fL "$DB/status" "$DB/status.qqbottmp" 2>/dev/null && [ -s "$DB/status.qqbottmp" ]; then
      mv -f "$DB/status.qqbottmp" "$DB/status" 2>/dev/null || true
      qq_db_say "status 已换回普通文件"
    else
      rm -f "$DB/status.qqbottmp" 2>/dev/null || true
      qq_db_say "E: status 读不出来（链接指向的文件不存在），需要重建根文件系统"
      return 1
    fi
  fi

  # status-old / status-new 是 dpkg 自己的备份，留着反而会挡住 link()，先清掉重来。
  rm -f "$DB/status-old" "$DB/status-new" 2>/dev/null || true
  # 不碰别的目录的权限：apt/log/tmp 那几个下面的 chmod 放在后面失败分支里做，
  # 免得在一切都正常的时候也去动全树权限（慢，而且没必要）。

  # 写测试：**先原样试写一次**，这样日志里才能记下「本来为什么写不进去」；
  # 补权限放在失败之后做，成功路径不打印无关内容。
  # 注意写法：本脚本开着 set -e，`var=$(会失败的命令)` 会直接终止脚本，
  # 所以失败路径必须放在 if 条件里（条件里的失败不影响 set -e）。
  _werr=""
  if ! { : > "$DB/.qqbot-wtest"; } 2>/tmp/.qqbot-wtest.err; then
    _werr=$(cat /tmp/.qqbot-wtest.err 2>/dev/null | tail -n 1)
  fi
  rm -f /tmp/.qqbot-wtest.err 2>/dev/null || true
  if [ -f "$DB/.qqbot-wtest" ]; then
    rm -f "$DB/.qqbot-wtest" 2>/dev/null || true
    qq_db_say "dpkg 预检：$DB 可写"
  else
    qq_db_say "E: $DB 不可写（${_werr:-Permission denied}），补回权限后再试"
    # 能 chmod 成功就说明我们就是属主，补完之后一般就能写了。
    chmod u+rwx "$DB" 2>/dev/null || true
    chmod -R u+rwX "$DB" /var/lib/apt /var/cache/apt /var/log/apt /tmp /run 2>/dev/null || true
    if ( : > "$DB/.qqbot-wtest" ) 2>/dev/null; then
      rm -f "$DB/.qqbot-wtest" 2>/dev/null || true
      qq_db_say "加固后 $DB 已可写，继续"
    else
      qq_db_say "E: 沙盒里 $DB 连 chmod 777 都修不好 —— 这个根文件系统的文件不属于当前应用，必须重建"
      : > /qqbot-cache/fs-nowrite 2>/dev/null || true
      return 1
    fi
  fi

  # (3) 硬链接测试：dpkg 备份 status 用的是 link()，不支持硬链接的文件系统上必然 EACCES。
  if ln "$DB/status" "$DB/.qqbot-htest" 2>/dev/null; then
    rm -f "$DB/.qqbot-htest" 2>/dev/null || true
    qq_db_say "dpkg 预检：硬链接可用"
  else
    _herr=""
    if ! ln "$DB/status" "$DB/.qqbot-htest" 2>/tmp/.qqbot-hlink.err; then
      _herr=$(cat /tmp/.qqbot-hlink.err 2>/dev/null | tail -n 1)
    fi
    rm -f /tmp/.qqbot-hlink.err 2>/dev/null || true
    rm -f "$DB/.qqbot-htest" 2>/dev/null || true
    qq_db_say "E: 沙盒所在文件系统建不了硬链接（${_herr:-Operation not permitted}）"
    qq_db_say "E: dpkg 备份 status 用的就是硬链接，这个盘上装不了依赖"
    # 留个标记给宿主的 deploy.sh：这种情况重建根文件系统没用，别再白下 30 MB。
    # /qqbot-cache 是宿主 <QQBOT_HOME>/cache 的绑定挂载，宿主侧能看到这个文件。
    : > /qqbot-cache/fs-nolink 2>/dev/null || true
    return 1
  fi
  return 0
}

# apt 缓存里下到一半的 .deb 一定要先清掉：真机上 libc6 的包就是半个，
# dpkg-deb 解它时报 "Broken pipe / <decompress> subprocess returned error exit status 2"，
# 而 apt 认为包已在本地、不会重下，于是每次重试都撞同一个坏包。
rm -f /var/cache/apt/archives/*.deb 2>/dev/null || true
rm -rf /var/cache/apt/archives/partial 2>/dev/null || true
mkdir -p /var/cache/apt/archives/partial 2>/dev/null || true

if ! qq_db_precheck; then
  echo "[sandbox] E: 沙盒里安装不了东西（dpkg 数据库写不进去），需要重建根文件系统"
  exit 4
fi

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
if [ "$APT_OK" != "1" ]; then
  # 四个镜像全挂时，真正的根因几乎总是 DNS 或空间，而不是「镜像挂了」。
  # 这里把判定做在前面，界面上的报错才能说人话（用户看到的会是最后这几行）。
  echo "[sandbox] 所有 apt 镜像均不可用，开始诊断"
  echo "[sandbox] --- /etc/resolv.conf ---"
  sed -e "s/^/[sandbox]   /" /etc/resolv.conf 2>&1 || true
  for _h in mirrors.tuna.tsinghua.edu.cn mirrors.ustc.edu.cn mirrors.aliyun.com ports.ubuntu.com; do
    if getent hosts "$_h" >/dev/null 2>&1; then
      echo "[sandbox] DNS 正常：$_h"
    else
      echo "[sandbox] E: 沙盒内解析不了 $_h（DNS 不通，apt 无法工作）"
    fi
  done
  echo "[sandbox] E: 可用空间 $(df -h / 2>/dev/null | tail -n 1 | awk '{print $4" 剩余 / "$2" 总量"}')"
  echo "[sandbox] E: 所有 apt 镜像均不可用（上面几行就是原因）"
  exit 1
fi

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
# libgl1 / libglx-mesa0 / libgl1-mesa-dri / xkb-data 是给 Xvfb 用的：没有 GLX 模块时
# Xvfb 加 +extension GLX 会直接 'could not enable GLX extension' 退出；没有 xkb-data 时
# 它加载键位表会报错。这几包装上，run-napcat.sh 的第一档参数（1x1x8 +GLX）才有机会成功。
qq_apt_install libgl1 libglx-mesa0 libgl1-mesa-dri xkb-data x11-xkb-utils
qq_apt_install xvfb libnss3 libgbm1 libasound2t64 libxss1 libxtst6 libxdamage1 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxcomposite1 libxfixes3 libpango-1.0-0 libcairo2 libgtk-3-0t64 libx11-xcb1 libxcb-dri3-0 libexpat1 libuuid1 libxshmfence1 libnotify4 xdg-utils libatspi2.0-0t64 libsecret-1-0

echo "[sandbox] 生成 locale"
if [ -f /etc/locale.gen ]; then
  sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen || true
  sed -i 's/^# *C.UTF-8 UTF-8/C.UTF-8 UTF-8/' /etc/locale.gen || true
  locale-gen || true
fi

python3 -V

# ---------------------------------------------------------------------------
# 预热字体缓存。QQ 是 Electron 应用，第一次启动会自己扫一遍系统字体建 fc-cache；
# 手机上字体多、CPU 慢，这一步能吃掉好几分钟，而它发生在「启动容器」的时候，
# 表现出来就是「NapCat 十分钟都没起来」。装机时先扫一遍，启动时就只剩读缓存。
# 失败无所谓，不影响后面的步骤。
# ---------------------------------------------------------------------------
echo "[sandbox] 预热字体缓存（让 QQ 首次启动不用自己扫字体）"
if command -v fc-cache >/dev/null 2>&1; then
  fc-cache -f >/dev/null 2>&1 || true
  echo "[sandbox] 字体缓存已生成"
fi

echo "[sandbox] 依赖安装完成"
