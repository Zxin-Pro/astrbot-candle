#!/data/data/com.termux/files/usr/bin/bash
# qqbot-sandbox 公共函数库

QQBOT_HOME="$HOME/.qqbot"
QQBOT_CACHE="$QQBOT_HOME/cache"
QQBOT_LOGS="$QQBOT_HOME/logs"
QQBOT_STEPS="$QQBOT_HOME/steps"
QQBOT_OPT="$PREFIX/opt/qqbot"
QQBOT_ROOTFS="$HOME/ubuntu"
QQBOT_PTMP="$QQBOT_HOME/proot-tmp"
# 沙盒内 /dev 是宿主 /dev 的绑定，宿主 /dev 里没有可写的 shm，
# 而 Electron(QQ) 启动时必须能读写 /dev/shm，否则直接 FATAL 崩溃：
#   Unable to access(W_OK|X_OK) /dev/shm: No such file or directory (2)
# 所以自建一个目录，在 proot 参数里覆盖挂到 /dev/shm。
QQBOT_DEVDIR="$QQBOT_HOME/dev"
# 下载代理配置文件：由 App 的设置页写入（纯文本，一行 URL，可能为空或不存在）。
# 所有下载（rootfs / QQ / NapCat / AstrBot / apt / pip）都要走这个代理。
QQBOT_PROXY_FILE="$QQBOT_HOME/proxy.txt"
# proot 自检的输出落盘，自检失败时原样打印。老版本把 stderr 丢进 /dev/null，
# 真机上分不清到底是「文件不存在」还是「被系统拦了 ptrace」，只能瞎猜。
QQBOT_SELFTEST_LOG="$QQBOT_LOGS/proot-selftest.log"

QQBOT_QQ_DEB_BASE="https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef"
QQBOT_QQ_VER="3.2.34-53644"
QQBOT_NAPCAT_VER="v4.18.38"
QQBOT_ASTRBOT_VER="v4.28.2"

QQBOT_TUNA="https://mirrors.tuna.tsinghua.edu.cn"
QQBOT_PYPI="$QQBOT_TUNA/pypi/simple"
QQBOT_GH_MIRRORS="https://ghfast.top/ https://gh-proxy.com/ https://ghproxy.net/ https://github.moeyy.xyz/ https://gh.llkk.cc/ https://ghproxy.cc/ https://gh.xxooo.cf/ https://gh-proxy.net/ https://hub.gitmirror.com/"

mkdir -p "$QQBOT_HOME" "$QQBOT_CACHE" "$QQBOT_LOGS" "$QQBOT_STEPS" "$QQBOT_PTMP" "$QQBOT_DEVDIR/shm" 2>/dev/null
chmod 1777 "$QQBOT_DEVDIR/shm" 2>/dev/null

qqlog()  { printf '\033[36m[QQBot]\033[0m %s\n' "$*"; }
qqok()   { printf '\033[32m[QQBot]\033[0m %s\n' "$*"; }
# 注意：警告必须走 stderr。qq_astrbot_port / qq_napcat_port / qq_onebot_port
# 的调用方普遍写成 port=$(qq_xxx_port)，若警告走 stdout 就会被命令替换一并捕获，
# 端口变量里混进警告文本（"http://127.0.0.1:[QQBot] bots.json ...6099"）。
qqwarn() { printf '\033[33m[QQBot]\033[0m %s\n' "$*" >&2; }
qqerr()  { printf '\033[31m[QQBot]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# 端口配置（从 bots.json 读）
#
# 本文件在 Termux 侧执行，那里没有 python3（只有沙盒内的 Ubuntu 才有），所以
# 只能靠 sed / grep 解析。Java 侧写 bots.json 用的是无换行缩进的紧凑 JSON，
# 下面的模式都是按这种格式写的。
# ---------------------------------------------------------------------------

# bots.json 的位置：优先用控制台真正在写的 $QQBOT_HOME/bots.json，
# 它不存在或为空（首次部署时）就退回 $QQBOT_CACHE/bots.json 那份副本。
qq_bots_json() {
  if [ -s "$QQBOT_HOME/bots.json" ]; then
    printf '%s' "$QQBOT_HOME/bots.json"
  else
    printf '%s' "$QQBOT_CACHE/bots.json"
  fi
}

# AstrBot 控制台（dashboard）端口：bots.json 顶层键 astrbotPort。
# 0 / 缺失 / 解析失败一律回退 6185（AstrBot 自带的默认端口）。
#
# 用 sed 抓 "astrbotPort" 冒号后的第一个数字，而不是抓整段值：这样值里混进
# 别的字符也只取到端口号。模式里的 [0-9] 同时挡掉了 "astrbotPort": 0（0 表示
# "自动"）和 true / null 这类非数字值。
qq_astrbot_port() {
  local f v
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    v=$(sed -n 's/.*"astrbotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' "$f" 2>/dev/null | head -n 1)
  fi
  case "${v:-}" in
    ''|0) v=6185 ;;
    # >65535 也要挡住：sed 只取前 5 位数字，77777 这种值能原样通过，而
    # 沙盒里的 run-astrbot.sh 是靠自己再查一遍范围的，两边不一致就会出现
    # 「界面显示 77777、实际听 6185」。这里与 run-astrbot.sh 的规则对齐。
    *[!0-9]*) qqwarn "bots.json 的 astrbotPort 不是有效端口（$v），回退 6185"; v=6185 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 的 astrbotPort 超出范围（$v），回退 6185"; v=6185; } ;;
  esac
  printf '%s' "$v"
}

# 当前实例（bots.json 的 active，取不到就用第一个实例）的 NapCat WebUI 端口，
# 缺失 / 解析失败回退 6099。
#
# 先把 bots 数组整段抽出来，再在数组里找实例：不能拿整个文件去搜 "id":"bot1"，
# 顶层别的键里如果也出现同样的片段，就会抓到错误的那一个。
qq_napcat_port() {
  local f v ids act
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    ids=$(sed -n 's/.*"bots"[[:space:]]*:[[:space:]]*\[//p' "$f" 2>/dev/null)
    act=$(sed -n 's/.*"active"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" 2>/dev/null | head -n 1)
    # 单个实例对象里 "id" 与 "napcatPort" 之间没有别的引号字段，所以
    # "id"...napcatPort 这个跨字段模式只会落在同一个对象上（name / qq / token
    # 的值都是不含引号的字符串，不会在中间插进一个假的 "id"）。
    if [ -n "$act" ]; then
      v=$(printf '%s\n' "$ids" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$act"'"[^{}]*"napcatPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
    if [ -z "$v" ]; then
      # 回退到数组里第一个实例。必须先把范围收窄到第一个对象再匹配：
      # ids 是整行（多个实例对象挤在同一行），sed 的 .* 是贪婪的，直接匹配
      # "napcatPort" 会落到本行最后一个实例上（head -n 1 救不回来，因为
      # 整行只会产出一条匹配）。单实例对象内部没有嵌套 {}，所以先用
      # ^[^{]*{...} 把第一个对象的内容切出来，再在里面取值。
      v=$(printf '%s\n' "$ids" | sed -n 's/^[^{]*{\([^}]*\)}.*/\1/p' | sed -n 's/.*"napcatPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
  fi
  case "${v:-}" in
    '') v=6099 ;;
    0|*[!0-9]*) qqwarn "bots.json 里实例 $act 的 napcatPort 不是有效端口（$v），回退 6099"; v=6099 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 里实例 $act 的 napcatPort 超出范围（$v），回退 6099"; v=6099; } ;;
  esac
  printf '%s' "$v"
}

# 当前实例（bots.json 的 active，取不到就用第一个实例）的 OneBot v11 端口，
# 也就是 NapCat 提供给 AstrBot 的 aiocqhttp 适配器监听的那个端口，
# 缺失 / 解析失败回退 6199。
#
# 解析规则与 qq_napcat_port() 完全同构，只把字段名换成 onebotPort：
# 先整段抽出 bots 数组（不能拿整个文件搜 "id":"bot1"，顶层别的键里出现同样的
# 片段就会抓错），再按 "id" -> onebotPort 的跨字段模式在同一个对象里取值。
qq_onebot_port() {
  local f v ids act
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    ids=$(sed -n 's/.*"bots"[[:space:]]*:[[:space:]]*\[//p' "$f" 2>/dev/null)
    act=$(sed -n 's/.*"active"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" 2>/dev/null | head -n 1)
    # 同 qq_napcat_port()：单个实例对象里 "id" 与 "onebotPort" 之间没有别的引号
    # 字段，所以 [^{}] 这个跨字段模式只会落在同一个实例对象上。
    if [ -n "$act" ]; then
      v=$(printf '%s\n' "$ids" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$act"'"[^{}]*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
    if [ -z "$v" ]; then
      # 同 qq_napcat_port()：先切出第一个实例对象再取值。这里不能直接匹配
      # "onebotPort"，否则贪婪的 .* 会落到同一行最后一个实例上，回退值就不是
      # 「数组里第一个实例的 onebotPort」了。
      v=$(printf '%s\n' "$ids" | sed -n 's/^[^{]*{\([^}]*\)}.*/\1/p' | sed -n 's/.*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
  fi
  case "${v:-}" in
    '') v=6199 ;;
    0|*[!0-9]*) qqwarn "bots.json 里实例 $act 的 onebotPort 不是有效端口（$v），回退 6199"; v=6199 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 里实例 $act 的 onebotPort 超出范围（$v），回退 6199"; v=6199; } ;;
  esac
  printf '%s' "$v"
}

# ---------------------------------------------------------------------------
# 架构自适应
#
# 不能用 uname 判断：MuMu 等 x86_64 模拟器会把内核 uname 伪装成 aarch64
# （实测 /system/bin/uname -a 返回 "aarch64 Toybox"，但 ro.product.cpu.abi
#  是 x86_64）。这类设备靠 houdini 把 arm64 指令翻译成 x86_64 执行。
#
# 普通程序经 houdini 翻译没问题，但 proot 完全依赖 ptrace 读寄存器与系统调用号，
# 翻译层下 aarch64 的 user_regs_struct 布局与真实 x86_64 寄存器对不上，
# proot 会永久挂死（实测 proot --link2symlink -0 echo 挂死，
# PROOT_NO_SECCOMP=1 也挂死，timeout -s KILL 都杀不掉，还会拖垮整个模拟器）。
#
# 因此 x86_64 设备改用随包内置的静态 x86_64 proot（proot-me 5.5.0），
# 并配 amd64 的 Ubuntu rootfs / amd64 的 Linux QQ。
# 这样 proot 与其全部子进程都是原生 x86_64，ptrace 正常（已在 MuMu 上实测通过）。
# ---------------------------------------------------------------------------
qq_detect_arch() {
  local abi=""
  if [ -x /system/bin/getprop ]; then
    abi="$(/system/bin/getprop ro.product.cpu.abi 2>/dev/null)"
  fi
  if [ -z "$abi" ]; then
    abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
  fi
  case "$abi" in
    x86_64|x86|i686|amd64) QQBOT_ARCH="amd64" ;;
    arm64*|aarch64*)       QQBOT_ARCH="arm64" ;;
    *)
      case "$(uname -m 2>/dev/null)" in
        x86_64|amd64) QQBOT_ARCH="amd64" ;;
        *)            QQBOT_ARCH="arm64" ;;
      esac
      ;;
  esac
  QQBOT_ABI="$abi"
}
qq_detect_arch

# proot 可执行文件：arm64 用 Termux 仓库里的，x86_64 用内置静态版
QQBOT_PROOT="$PREFIX/bin/proot"
QQBOT_PROOT_BUNDLED="$PREFIX/bin/proot-x86_64"
if [ "$QQBOT_ARCH" = "amd64" ]; then
  # amd64 设备（含 MuMu 这类把 uname 伪装成 aarch64 的 x86_64 模拟器）必须用内置的
  # x86_64 proot。缺了它就只能落到 $PREFIX/bin/proot，而那多半是 Termux 仓库里的
  # arm64 版，在 houdini 翻译层下 ptrace 拿不到正确寄存器、会永久挂死。
  # 所以这里宁可明确报错退出，也不要静默降级成那个必然挂死的组合。
  if [ ! -f "$QQBOT_PROOT_BUNDLED" ]; then
    qqerr "缺少内置的 x86_64 proot：$QQBOT_PROOT_BUNDLED"
    qqerr "本机是 amd64 架构（ro.product.cpu.abi=$QQBOT_ABI），用 $QQBOT_PROOT 会挂死。"
    qqerr "请重新安装完整的 qqbot 包（bin/proot-x86_64 是随包内置的）。"
    exit 1
  fi
  chmod 755 "$QQBOT_PROOT_BUNDLED" 2>/dev/null
  QQBOT_PROOT="$QQBOT_PROOT_BUNDLED"
fi
# proot-me 的 proot 需要一个可写的临时目录（只设 TMPDIR 不够，
# 不设会报 "can't create temporary file: Permission denied"）
export PROOT_TMP_DIR="$QQBOT_PTMP"

# 按架构决定资源名
QQBOT_ROOTFS_TAR="ubuntu-base-24.04.5-base-$QQBOT_ARCH.tar.gz"
QQBOT_QQ_DEB_NAME="linuxqq_${QQBOT_QQ_VER}_${QQBOT_ARCH}.deb"

# Python 的平台标识必须钉死：MuMu 这类模拟器把 uname 伪装成 aarch64，于是
# Python 的 sysconfig.get_platform() 返回 linux-aarch64，pip 就去下 aarch64 的
# wheel，装进实际是 x86_64 的沙盒里，import 时必然报 invalid ELF header。
# 这里按 ro.product.cpu.abi 的判定结果覆盖，真实手机上也一样正确。
case "$QQBOT_ARCH" in
  amd64) QQBOT_GUEST_PLATFORM="linux-x86_64" ;;
  arm64) QQBOT_GUEST_PLATFORM="linux-aarch64" ;;
  *)     QQBOT_GUEST_PLATFORM="linux-$(uname -m 2>/dev/null)" ;;
esac
export QQBOT_GUEST_PLATFORM

# Ubuntu 源：amd64 在 /ubuntu/ 主分支，arm64 在 /ubuntu-ports/ 分支
# （tuna 的 /ubuntu/ 不承载 arm64 的 Packages 索引，会 404）
# 一律用 http：Ubuntu 基础 rootfs 不含 ca-certificates，首次 apt 走 https 会
# 报 "No system certificates available" 而完全拉不到索引。apt 靠 GPG 签名校验
# 包完整性，不依赖 TLS。等 ca-certificates 装好后，pip 等再用 https。
if [ "$QQBOT_ARCH" = "amd64" ]; then
  QQBOT_UBUNTU_PORTS="http://mirrors.tuna.tsinghua.edu.cn/ubuntu http://mirrors.ustc.edu.cn/ubuntu http://mirrors.aliyun.com/ubuntu http://archive.ubuntu.com/ubuntu"
else
  QQBOT_UBUNTU_PORTS="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports http://mirrors.ustc.edu.cn/ubuntu-ports http://mirrors.aliyun.com/ubuntu-ports http://ports.ubuntu.com/ubuntu-ports"
fi

# ---------------------------------------------------------------------------
# 下载代理
#
# 用户在 App 的设置页填一个代理地址，App 把它写进 $QQBOT_PROXY_FILE
# （$QQBOT_HOME/proxy.txt，纯文本，一行 URL，例如 http://127.0.0.1:7890 或
#  socks5://127.0.0.1:7891）。文件可能不存在或为空，那就表示不使用代理。
#
# qq_proxy 的输出会被 $(...) 捕获后直接当 URL 用，所以：
#   - 只输出 URL 本身，任何提示/调试信息一律走 stderr（>&2），
#   - 空值必须输出零个字符，绝不能是空行。
# ---------------------------------------------------------------------------
qq_proxy() {
  local _f _p
  _f="${QQBOT_PROXY_FILE:-$QQBOT_HOME/proxy.txt}"
  [ -s "$_f" ] || return 0
  # tr -d '\r' 先干掉 CRLF 的 \r（Windows 端写文件很容易带进来）；
  # sed 去掉行首尾空白，再去掉成对的首尾引号（'...' 或 "..."）；
  # head -n 1 只取第一行：文件里若误留了多行，也只有第一行是代理地址。
  _p=$(tr -d '\r' < "$_f" 2>/dev/null | head -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  _p=$(printf '%s' "$_p" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")
  [ -n "$_p" ] || return 0
  printf '%s' "$_p"
}

# 代理非空时输出 curl 用的 "-x <url>"，为空时输出零个字符。
# 调用方必须让结果参与 word-splitting（curl 需要两个独立参数）：
#   # shellcheck disable=SC2046
#   curl ... $(qq_proxy_args) -o out url
# 注意不能用 "$(qq_proxy_args)"：加引号会把它变成单个参数 "-x http://..."，curl 会报错。
qq_proxy_args() {
  local _p
  _p="$(qq_proxy)"
  [ -n "$_p" ] || return 0
  printf '%s' "-x $_p"
}

# 下载完整性下限（字节）。默认 1 KB 只能挡住「0 字节 / 极短错误页」；
# 调用方要按真实体积把 QQBOT_DL_MIN 调大（rootfs 25 MB、QQ deb 150 MB）。
# 为什么需要：curl 在「连接被截断」时不一定会返回非 0（--max-time 到点、CDN 半包都可能退出 0），
# 而坏文件一旦落到正式缓存路径就会被永久复用，之后每次重装都失败在「解包」那一步——
# 报错指向解包、真因在下载，还清不掉。
QQBOT_DL_MIN="${QQBOT_DL_MIN:-1024}"

qq_dl() {
  _u="$1"
  _o="$2"
  _t="$3"
  if [ -z "$_t" ]; then _t=1800; fi
  rm -f "$_o.part"
  # $(qq_proxy_args) 不加引号：代理为空时展开成零个参数，curl 行为与改动前完全一致。
  # shellcheck disable=SC2046
  if ! curl -L --fail --retry 3 --retry-delay 3 --connect-timeout 20 --max-time "$_t" -A "Mozilla/5.0 (Linux; Android 13) qqbot-sandbox/1.0" $(qq_proxy_args) -o "$_o.part" "$_u"; then
    rm -f "$_o.part"
    return 1
  fi
  _sz="$(wc -c < "$_o.part" 2>/dev/null || echo 0)"
  if [ "${_sz:-0}" -lt "${QQBOT_DL_MIN:-1024}" ]; then
    qqwarn "下载体积异常（$_sz 字节 < ${QQBOT_DL_MIN:-1024}），丢弃重来"
    rm -f "$_o.part"
    return 1
  fi
  # 有些镜像对失败请求会返回 200 + 一个 HTML 错误页，小文件靠体积挡不住，这里再看文件头。
  case "$(head -c 15 "$_o.part" 2>/dev/null)" in
    '<!DOCTYPE html>'|'<html>'|'<HTML>')
      qqwarn "下载到的是网页而不是文件，丢弃"
      rm -f "$_o.part"
      return 1
      ;;
  esac
  mv -f "$_o.part" "$_o"
}

qq_dl_mirror() {
  _o="$1"
  shift
  for _u in "$@"; do
    qqlog "下载 $(basename "$_u")"
    if qq_dl "$_u" "$_o"; then
      qqok "已保存 $(du -h "$_o" 2>/dev/null | cut -f1)"
      return 0
    fi
    rm -f "$_o" "$_o.part"
    qqwarn "该地址失败，换下一个镜像"
  done
  return 1
}

qq_gh_dl() {
  _o="$1"
  _url="$2"
  # 配了代理时先直连真地址：代理存在的意义就是能访问 GitHub，
  # 再套一层镜像反而多一跳，还可能撞上失效的镜像。
  # 没配代理时这段不执行，行为与改动前完全一致（零回归）。
  if [ -n "$(qq_proxy)" ]; then
    qqlog "下载 $(basename "$_url")"
    # 注意参数顺序：qq_dl 的签名是 qq_dl <url> <输出文件>，
    # 写反了会变成「-o <url>.part <输出路径>」，curl 直接报错、白跑一趟。
    if qq_dl "$_url" "$_o" 2>/dev/null; then
      qqok "已保存 $(du -h "$_o" 2>/dev/null | cut -f1)"
      return 0
    fi
    qqwarn "直连失败，改用镜像"
    rm -f "$_o" "$_o.part"
  fi
  _list=""
  for _m in $QQBOT_GH_MIRRORS; do
    _list="$_list $_m$_url"
  done
  _list="$_list $_url"
  qq_dl_mirror "$_o" $_list
}

# proot 自检：能跑通返回 0。带超时保护，避免坏 proot 把进程挂死。
# ---------------------------------------------------------------------------
# 时区
# 沙盒 rootfs 里的 /etc/localtime 原本指向 Etc/UTC，glibc 就按 UTC 走，于是
# UTC+8 的手机上本地 00:00 的日志会写成 16:00。这里从 Android 取系统时区，
# 既写进 rootfs 的 /etc/{timezone,localtime}，又通过 TZ 环境变量传进沙盒。
# 每次启动都重新同步，用户改系统时区后重启服务即可生效。
qq_tz() {
  _t=""
  if command -v getprop >/dev/null 2>&1; then
    _t="$(getprop persist.sys.timezone 2>/dev/null)"
  fi
  # 时区名一定带斜杠（Asia/Shanghai）；取不到或不合法就走下面的兜底。
  case "$_t" in */*) : ;; *) _t="" ;; esac
  if [ -z "$_t" ]; then
    # 兜底：POSIX 形式的偏移符号与 date +%z 相反（+0800 -> UTC-8）。
    # 这个写法不依赖 tzdata，任何 glibc 都能认。
    _o="$(date +%z 2>/dev/null)"
    case "$_o" in
      +*|-*)
        _s="${_o:0:1}"
        _r="${_o#?}"
        _h="${_r%??}"
        _m="${_r#??}"
        _h=$((10#${_h:-0}))
        if [ "$_s" = "+" ]; then _s="-"; else _s="+"; fi
        if [ "$_m" = "00" ]; then _t="UTC$_s$_h"; else _t="UTC$_s$_h:$_m"; fi
        ;;
    esac
  fi
  [ -n "$_t" ] || _t="UTC"
  printf '%s' "$_t"
}

# 把时区落到沙盒看得见的两处：$QQBOT_CACHE/tz（沙盒内是 /qqbot-cache/tz，
# 供沙盒脚本 export TZ 兜底）和 rootfs 的 /etc/timezone + /etc/localtime。
qq_tz_sync() {
  _t="$(qq_tz)"
  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  printf '%s\n' "$_t" > "$QQBOT_CACHE/tz" 2>/dev/null
  if [ -d "$QQBOT_ROOTFS/etc" ]; then
    printf '%s\n' "$_t" > "$QQBOT_ROOTFS/etc/timezone" 2>/dev/null
    if [ -f "$QQBOT_ROOTFS/usr/share/zoneinfo/$_t" ]; then
      ln -sf "/usr/share/zoneinfo/$_t" "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
    else
      # rootfs 里没有这个时区（tzdata 范围外，或上面用的是 POSIX 兜底值）：
      # 保留 UTC 文件，实际生效靠 TZ 环境变量。
      rm -f "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
      cp -f "$QQBOT_ROOTFS/usr/share/zoneinfo/Etc/UTC" "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
    fi
  fi
  printf '%s' "$_t"
}

qq_proot_selftest() {
  unset LD_PRELOAD
  export PROOT_TMP_DIR="$QQBOT_PTMP"
  mkdir -p "$QQBOT_PTMP" 2>/dev/null
  # proot 不存在时先挡下来：否则 timeout 会返回 127，而上层只看退出码，
  # 会把「文件都还没装」误报成「架构不匹配」。
  if [ ! -x "$QQBOT_PROOT" ]; then
    printf 'proot 不存在或不可执行：%s\n' "$QQBOT_PROOT" > "$QQBOT_SELFTEST_LOG" 2>/dev/null
    return 1
  fi
  if command -v timeout >/dev/null 2>&1; then
    timeout 25 "$QQBOT_PROOT" -0 /system/bin/echo qqbot-proot-ok > "$QQBOT_SELFTEST_LOG" 2>&1
  else
    "$QQBOT_PROOT" -0 /system/bin/echo qqbot-proot-ok > "$QQBOT_SELFTEST_LOG" 2>&1
  fi
}

# 自检失败时把 proot 自己的报错原样打到日志里（缩进四格，便于和 [QQBot] 行区分）。
qq_proot_selftest_log() {
  if [ -s "$QQBOT_SELFTEST_LOG" ]; then
    sed 's/^/    /' "$QQBOT_SELFTEST_LOG" >&2
  fi
  return 0
}

# 注意：绝不能加 --link2symlink。它会把 dpkg 的硬链接换成指向宿主绝对路径
# （/data/data/.../ubuntu/usr/lib/...）的 .l2s.* 符号链接，沙盒内解析不到，
# 使 libc.so.6 / ld-linux-x86-64.so.2 变悬空，之后所有 execve 都返回 ENOENT
# （dpkg 报 "unable to execute rm command for cleanup"、apt 报 "Could not exec dpkg!"）。
# Android 的 /data 是 ext4/f2fs，本来就支持硬链接。
qq_proot() {
  unset LD_PRELOAD
  export PROOT_TMP_DIR="$QQBOT_PTMP"
  # /usr/bin/env -i 会清空全部环境变量，所以代理必须在这里显式补上：
  # 沙盒内的 apt（30-apt.sh）和 pip（60-astrbot.sh）都依赖这几个变量。
  # socks5:// 也要给 http_proxy/https_proxy：curl / requests 都识别 socks5://
  # 前缀并按 SOCKS5 走，只给 all_proxy 反而会被部分工具忽略。
  # 例外是 apt：它只认 socks5h://（源码 methods/http.cc 里唯一判断 SOCKS 的
  # 分支是 Proxy.Access == "socks5h"），所以 30-apt.sh 里会再换一次 scheme。
  # no_proxy 固定加上本机地址：NapCat 与 AstrBot 之间的 aiocqhttp 是
  # 127.0.0.1 上的 WebSocket，绝不能因为设了代理而被转发出本机。
  _px_env=""
  _px="$(qq_proxy)"
  if [ -n "$_px" ]; then
    _px_env="http_proxy=$_px https_proxy=$_px all_proxy=$_px HTTP_PROXY=$_px HTTPS_PROXY=$_px ALL_PROXY=$_px no_proxy=127.0.0.1,localhost,::1 NO_PROXY=127.0.0.1,localhost,::1"
  fi
  # 时区每次进沙盒都重新同步：只写 rootfs 文件不够，TZ 环境变量优先级更高。
  _tz="$(qq_tz_sync)"
  "$QQBOT_PROOT" --kill-on-exit -0 -r "$QQBOT_ROOTFS" -b /dev -b "$QQBOT_DEVDIR/shm:/dev/shm" -b /proc -b /sys -b "$QQBOT_CACHE:/qqbot-cache" -b "$QQBOT_OPT:/qqbot-opt" -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin QQBOT_ARCH="$QQBOT_ARCH" _PYTHON_HOST_PLATFORM="$QQBOT_GUEST_PLATFORM" TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp TZ="$_tz" $_px_env /bin/bash -lc "$*"
}

qq_sandbox() {
  # 必须转发多余参数：qq_sandbox delete-instance.sh "$ID" 要把 ID 传进沙盒内的脚本，
  # 否则沙盒里跑的是无参版本，脚本只会打印用法然后什么都不做。
  local s="$1"; shift
  qq_proot "bash /qqbot-opt/sandbox/$s $*"
}
