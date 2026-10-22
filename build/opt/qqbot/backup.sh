#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 一键备份（功能 50，协议见 docs/接口约定-1.1.30.md 第 8 节）。
#
# 用法: backup.sh <all|config|plugins> <out.tar.gz>
#   成功：stdout 只有一行 SHA256=<hex>，exit 0
#   失败：stdout 只有一行 ERR=<原因>，exit 1
#   进度：$QQBOT_HOME/state/backup.pct 依次写 0 / 25 / 50 / 75 / 90 / done
#         （失败写 error，原生层就是读这个文件来驱动进度条）
#
# 归档内部布局（restore.sh 依赖它，改之前先改两个脚本）：
#   rootfs/...  -> $QQBOT_ROOTFS/...    AstrBot data、插件、NapCat 登录数据
#   cache/...   -> $QQBOT_CACHE/...     bots.json / bots.d / qq-env.txt / qq-args.txt
#   home/...    -> $QQBOT_HOME/...      界面写的那份 bots.json
#
# 为什么先摊到临时目录再打包：tar 一次只能从一个 -C 起根，而我们要把 rootfs /
# cache / home 三类路径装进同一个包；摊平时用 cp -al（硬链接）几乎不占空间，
# 数据也不会被读两遍。链路不支持硬链接时自动退回 cp -a，只是慢一点。
# 排除项一律交给 tar --exclude 在打包时完成，绝不做「先打包再删包内文件」。
#
# 注意：本脚本在宿主侧（Termux）跑，路径都要带 $QQBOT_ROOTFS 前缀。

. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || { echo "ERR=common.sh 加载失败"; exit 1; }

SCOPE="${1:-}"
OUT="${2:-}"
PCT="$QQBOT_STATE/backup.pct"
LOCK="$QQBOT_STATE/backup.lock"
STAGE=""
ERRF=""

say()  { printf '[backup] %s\n' "$*" >&2; }
pct()  { mkdir -p "$QQBOT_STATE" 2>/dev/null; printf '%s\n' "$1" > "$PCT" 2>/dev/null; }
usage() { printf '用法: backup.sh <all|config|plugins> <out.tar.gz>\n' >&2; }

cleanup() {
  [ -n "$STAGE" ] && rm -rf "$STAGE" 2>/dev/null
  [ -n "$ERRF" ] && rm -f "$ERRF" 2>/dev/null
  rm -rf "$LOCK" 2>/dev/null
}

# 失败出口：进度写 error、清临时目录、stdout 只留 ERR= 一行。
fail() {
  pct error
  say "失败：$1"
  cleanup
  printf 'ERR=%s\n' "$1"
  exit 1
}

# 硬链接摊平一个目录（同盘几乎零成本）；跨设备或不支持硬链接时退回真复制。
stage_dir() {
  local src="$1" dst="$2"
  [ -d "$src" ] || return 0
  mkdir -p "$(dirname "$dst")" 2>/dev/null
  rm -rf "$dst" 2>/dev/null
  cp -al "$src" "$dst" 2>/dev/null || cp -a "$src" "$dst" 2>/dev/null
}

stage_file() {
  local src="$1" dst="$2"
  [ -e "$src" ] || return 0
  mkdir -p "$(dirname "$dst")" 2>/dev/null
  cp -a "$src" "$dst" 2>/dev/null
}

# 路径归一化：原生层可能按沙盒视角传 /qqbot-cache/... 这样的路径，而本脚本跑在
# 宿主侧，真实位置是 $QQBOT_CACHE/...。这里把三个沙盒挂载点换回宿主前缀，
# 其余路径原样返回（不是这三个前缀的一律不碰）。
qq_host_path() {
  local p="${1:-}"
  case "$p" in
    /qqbot-cache|/qqbot-cache/*) p="$QQBOT_CACHE${p#/qqbot-cache}" ;;
    /qqbot-state|/qqbot-state/*) p="$QQBOT_STATE${p#/qqbot-state}" ;;
    /qqbot-opt|/qqbot-opt/*)     p="$QQBOT_OPT${p#/qqbot-opt}" ;;
  esac
  printf '%s' "$p"
}

sha_of() {
  local f="$1" h=""
  if command -v sha256sum >/dev/null 2>&1; then
    h=$(sha256sum "$f" 2>/dev/null | sed 's/[[:space:]].*//')
  elif command -v openssl >/dev/null 2>&1; then
    h=$(openssl dgst -sha256 "$f" 2>/dev/null | sed 's/.*=[[:space:]]*//')
  fi
  printf '%s' "$h"
}

case "$SCOPE" in
  all|config|plugins) ;;
  *) usage; printf 'ERR=scope 必须是 all/config/plugins\n'; exit 1 ;;
esac
[ -n "$OUT" ] || { usage; printf 'ERR=缺少输出路径\n'; exit 1; }
OUT="$(qq_host_path "$OUT")"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

mkdir -p "$QQBOT_STATE" "$QQBOT_CACHE" 2>/dev/null
mkdir -p "$(dirname "$OUT")" 2>/dev/null || fail "输出目录建不出来：$(dirname "$OUT")"

# 单实例：同一时间只允许一个备份（原生层的按钮会被连点）。锁目录里记 pid，
# 持有者已经死了就抢过来，避免上次被强杀后永远「已有备份在跑」。
if ! mkdir "$LOCK" 2>/dev/null; then
  _lp=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$_lp" ] && kill -0 "$_lp" 2>/dev/null; then
    printf 'ERR=已有备份在跑（pid %s）\n' "$_lp"
    exit 1
  fi
  rm -rf "$LOCK" 2>/dev/null
  mkdir "$LOCK" 2>/dev/null || { printf 'ERR=拿不到备份锁\n'; exit 1; }
fi
echo $$ > "$LOCK/pid" 2>/dev/null
trap cleanup EXIT INT TERM

STAGE="$QQBOT_PTMP/backup.$$"
ERRF="$QQBOT_PTMP/.backup-err.$$"
rm -rf "$STAGE" 2>/dev/null
mkdir -p "$STAGE/rootfs" "$STAGE/cache" "$STAGE/home" 2>/dev/null || fail "临时目录建不出来：$STAGE"

ADIR="$(qq_astrbot_data_dir)"
# AstrBot data 相对 rootfs 的路径（opt/AstrBot/data 或 root/astrbot/data）
case "$ADIR" in
  "$QQBOT_ROOTFS"/*) REL="${ADIR#"$QQBOT_ROOTFS"/}" ;;
  *) REL="opt/AstrBot/data" ;;
esac

pct 0
# 1) AstrBot data（all / config；plugins 范围时只单独拷插件目录）
if [ "$SCOPE" = "all" ] || [ "$SCOPE" = "config" ]; then
  [ -d "$ADIR" ] || fail "AstrBot 数据目录不存在：$ADIR"
  stage_dir "$ADIR" "$STAGE/rootfs/$REL" || fail "复制 AstrBot data 失败"
fi
pct 25

# 2) 插件目录（all / plugins）
if [ "$SCOPE" = "all" ] || [ "$SCOPE" = "plugins" ]; then
  [ -d "$ADIR/plugins" ] && stage_dir "$ADIR/plugins" "$STAGE/rootfs/$REL/plugins" \
    || { [ "$SCOPE" = "plugins" ] && fail "插件目录不存在：$ADIR/plugins"; }
fi
pct 50

# 3) NapCat 登录数据（只有 all 才含：它最大，config/plugins 备份不该背着它）
if [ "$SCOPE" = "all" ]; then
  stage_dir "$QQBOT_ROOTFS/root/qqinst" "$STAGE/rootfs/root/qqinst"
fi
pct 75

# 4) 实例配置（all / config）：沙盒读的 cache 侧 + 界面写的 home 侧
if [ "$SCOPE" = "all" ] || [ "$SCOPE" = "config" ]; then
  for _f in "$QQBOT_CACHE/bots.json" "$QQBOT_CACHE/qq-env.txt" "$QQBOT_CACHE/qq-args.txt"; do
    stage_file "$_f" "$STAGE/cache/${_f##*/}"
  done
  stage_dir "$QQBOT_CACHE/bots.d" "$STAGE/cache/bots.d"
  stage_file "$QQBOT_HOME/bots.json" "$STAGE/home/bots.json"
fi
pct 90

# 5) 打包。排除项：
#    */logs、*/cache —— 日志和缓存不值得进备份（协议明确排除）
#    __pycache__ / *.log —— 插件跑出来的字节码和日志
#    config 范围还要排掉 plugins（协议：config 不含插件）
set -- --exclude='*/__pycache__' --exclude='*/__pycache__/*' \
       --exclude='*/logs' --exclude='*/logs/*' \
       --exclude='*/cache' --exclude='*/cache/*' --exclude='*.log'
if [ "$SCOPE" = "config" ]; then
  set -- "$@" --exclude="rootfs/$REL/plugins" --exclude="rootfs/$REL/plugins/*"
fi
tar -czf "$OUT" -C "$STAGE" "$@" rootfs cache home 2>"$ERRF"
_tar_rc=$?
if [ "$_tar_rc" != "0" ] || [ ! -s "$OUT" ]; then
  fail "打包失败：$(head -n 1 "$ERRF" 2>/dev/null)"
fi
set --   # 清掉上面那串 tar 参数，别影响后面的解析

# 6) 校验和 + done。sha256sum 不在就用 openssl（Termux 一般两者都有）。
SHA="$(sha_of "$OUT")"
[ -n "$SHA" ] || fail "算不出 sha256（没有 sha256sum / openssl）"
printf '%s\n' "$SHA" > "$QQBOT_STATE/backup.sha256" 2>/dev/null
pct done
_sz="$(qq_du_bytes "$OUT")"
say "完成：$OUT（${_sz} 字节，sha256=$SHA）"
cleanup
trap - EXIT INT TERM
printf 'SHA256=%s\n' "$SHA"
exit 0
