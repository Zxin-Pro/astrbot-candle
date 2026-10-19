#!/data/data/com.termux/files/usr/bin/bash
# 一键恢复（功能 50，协议见 docs/接口约定-1.1.30.md 第 8 节）。
#
# 用法: restore.sh <all|config|plugins> <in.tar.gz> [SHA256=<hex> | <snapshot.tar.gz>] [snapshot.tar.gz]
#   scope   all=登录数据+配置+插件  config=只配置  plugins=只插件
#   arg3/4  可给一个 SHA256=<hex> 做完整性校验，也可给一个快照包路径当回滚源
#           （参数位置不敏感，识别到 SHA256= 就当校验值，识别到存在的文件就当快照）
#   成功：stdout 只有一行 OK，exit 0
#   失败：stdout 只有一行 ERR=<原因>，exit 1；能回滚时先按快照回滚
#   进度：$QQBOT_HOME/state/backup.pct 写 0/25/50/75/90/done（失败写 error）
#
# 顺序（每一步失败都会先回滚再报错）：
#   校验 sha256 → 做当前状态快照（没给就现打一个 all）→ 停服务（--no-offline）
#   → 解到 $QQBOT_PTMP 下的临时目录 → 按 scope 过滤后 cp -a 覆盖 → 恢复原来在跑的服务
#
# 为什么先解到临时目录：tar 直接往生产目录解，中断会留下半包；cp -a 是「整体覆盖」，
# 而 stop-sandbox.sh --no-offline 保证没有进程在写这些目录，不会抢出损坏文件。

. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || { echo "ERR=common.sh 加载失败"; exit 1; }

SCOPE="${1:-}"
IN="${2:-}"
ARG3="${3:-}"
ARG4="${4:-}"
PCT="$QQBOT_STATE/backup.pct"
EXT=""
SHA_WANT=""
SNAP=""
WAS_RUNNING=0

say() { printf '[restore] %s\n' "$*" >&2; }
pct() { mkdir -p "$QQBOT_STATE" 2>/dev/null; printf '%s\n' "$1" > "$PCT" 2>/dev/null; }
usage() { printf '用法: restore.sh <all|config|plugins> <in.tar.gz> [SHA256=<hex>] [snapshot.tar.gz]\n' >&2; }

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

# 把恢复前的服务状态还原：原来在跑就重新拉起来（原来停着就别自作主张启动）。
restart_if_needed() {
  [ "$WAS_RUNNING" = "1" ] || return 0
  mkdir -p "$QQBOT_LOGS" 2>/dev/null
  nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" >> "$QQBOT_LOGS/supervisor.log" 2>&1 < /dev/null &
}

# 从快照整体回滚。回滚本身再失败也没别的办法了：只在 stderr 说明，stdout 仍由
# 调用方输出 ERR=，让原生层至少显示原始原因。
rollback() {
  printf '[restore] 失败，开始按快照回滚…\n' >&2
  if [ -z "$SNAP" ] || [ ! -s "$SNAP" ]; then
    printf '[restore] 没有可用快照，无法回滚\n' >&2
    restart_if_needed
    return 1
  fi
  local r="$QQBOT_PTMP/rollback.$$"
  rm -rf "$r" 2>/dev/null
  if ! mkdir -p "$r" 2>/dev/null || ! tar -xzf "$SNAP" -C "$r" 2>/dev/null; then
    printf '[restore] 快照解包失败：%s\n' "$SNAP" >&2
    rm -rf "$r" 2>/dev/null
    restart_if_needed
    return 1
  fi
  local area dst
  for area in rootfs cache home; do
    [ -d "$r/$area" ] || continue
    case "$area" in
      rootfs) dst="$QQBOT_ROOTFS" ;;
      cache)  dst="$QQBOT_CACHE" ;;
      home)   dst="$QQBOT_HOME" ;;
    esac
    mkdir -p "$dst" 2>/dev/null
    cp -a "$r/$area/." "$dst/" 2>/dev/null
  done
  rm -rf "$r" 2>/dev/null
  printf '[restore] 已回滚到快照\n' >&2
  restart_if_needed
  return 0
}

die() {
  pct error
  rollback
  [ -n "$EXT" ] && rm -rf "$EXT" 2>/dev/null
  printf 'ERR=%s\n' "$1"
  exit 1
}

case "$SCOPE" in
  all|config|plugins) ;;
  *) usage; printf 'ERR=scope 必须是 all/config/plugins\n'; exit 1 ;;
esac
[ -n "$IN" ] || { usage; printf 'ERR=缺少备份文件路径\n'; exit 1; }
IN="$(qq_host_path "$IN")"
case "$IN" in /*) ;; *) IN="$PWD/$IN" ;; esac
[ -f "$IN" ] && [ -r "$IN" ] || { printf 'ERR=备份文件不存在或不可读\n'; exit 1; }
mkdir -p "$QQBOT_STATE" "$QQBOT_PTMP" 2>/dev/null

# 第 3/4 个参数：SHA256=... 是校验值，能读到的文件是快照，其余忽略。
# 快照路径同样做沙盒前缀归一化（原生层可能传 /qqbot-cache/...）。
for _a in "$ARG3" "$ARG4"; do
  case "$_a" in
    '') ;;
    SHA256=*) SHA_WANT="${_a#SHA256=}" ;;
    *) _a="$(qq_host_path "$_a")"; [ -f "$_a" ] && SNAP="$_a" ;;
  esac
done

pct 0
# 1) 校验：给了 sha256 就必须匹配，避免「恢复了一个拷坏的包」。
if [ -n "$SHA_WANT" ]; then
  _got="$(sha_of "$IN")"
  [ -n "$_got" ] || { printf 'ERR=校验失败（没有 sha256sum / openssl）\n'; exit 1; }
  [ "$_got" = "$SHA_WANT" ] || { printf 'ERR=校验失败\n'; exit 1; }
  say "sha256 校验通过"
fi

# 2) 先停服务、再做快照：运行中的 AstrBot 还在写 sqlite 和日志，边写边 tar 打出来的
#    快照可能是半截的，真出事时回滚过去反而更糟。--no-offline 表示这是内部重启流程，
#    别写「紧急下线」标记（否则界面会一闪而过地显示「已停止」）。
_pid="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
if [ -n "$_pid" ] && [ -d "/proc/$_pid" ]; then WAS_RUNNING=1; fi
say "停止服务（原来是否在跑：$WAS_RUNNING）"
bash "$QQBOT_OPT/stop-sandbox.sh" --no-offline >/dev/null 2>&1
pct 25

# 3) 恢复前快照：没给就现打一个 all。快照打不出来就别往下走 —— 后面出错就回不去了。
if [ -z "$SNAP" ]; then
  mkdir -p "$QQBOT_CACHE/tmp" 2>/dev/null
  SNAP="$QQBOT_CACHE/tmp/pre-restore-$(date +%s).tar.gz"
  _so="$(bash "$QQBOT_OPT/backup.sh" all "$SNAP" 2>/dev/null)"
  case "$_so" in
    *SHA256=*) say "已生成恢复前快照：$SNAP" ;;
    *) die "快照失败" ;;
  esac
fi
[ -s "$SNAP" ] || die "快照失败（快照文件为空）"

# 4) 解包到临时目录。按 scope 过滤压缩包成员，其它范围的东西一个字节都不碰。
pct 50
EXT="$QQBOT_PTMP/restore.$$"
rm -rf "$EXT" 2>/dev/null
mkdir -p "$EXT" 2>/dev/null || die "临时目录建不出来：$EXT"
if [ "$SCOPE" = "all" ]; then
  tar -xzf "$IN" -C "$EXT" 2>/dev/null || die "解包失败"
else
  _members="$EXT/.members"
  tar -tzf "$IN" 2>/dev/null > "$_members" || die "读不了压缩包（不是 tar.gz？）"
  # 只提取文件、不列目录项：GNU tar 用 -T 按名字提取时，目录成员（带尾斜杠）
  # 会和清单里的名字对不上，报 "Not found in archive" 并且整条命令失败。
  # 文件成员的父目录 tar 会自己建，所以去掉目录项最稳。
  if [ "$SCOPE" = "config" ]; then
    # 配置 = 去掉登录数据与插件之后的全部
    grep -v -e '^rootfs/root/qqinst' -e '/plugins/' -e '/plugins$' "$_members" | grep -v '/$' > "$_members.sel" 2>/dev/null
  else
    # 插件 = 只保留 /plugins/ 下的成员
    grep -e '/plugins/' -e '/plugins$' "$_members" | grep -v '/$' > "$_members.sel" 2>/dev/null
  fi
  if [ ! -s "$_members.sel" ]; then
    die "压缩包里没有 $SCOPE 范围的内容"
  fi
  tar -xzf "$IN" -C "$EXT" -T "$_members.sel" 2>/dev/null || die "解包失败"
  rm -f "$_members" "$_members.sel" 2>/dev/null
fi

# 5) 覆盖。stop-sandbox.sh 已经保证没有进程在写这些目录，cp -a 是整体覆盖。
pct 75
for _area in rootfs cache home; do
  [ -d "$EXT/$_area" ] || continue
  case "$_area" in
    rootfs) _dst="$QQBOT_ROOTFS" ;;
    cache)  _dst="$QQBOT_CACHE" ;;
    home)   _dst="$QQBOT_HOME" ;;
  esac
  mkdir -p "$_dst" 2>/dev/null
  cp -a "$EXT/$_area/." "$_dst/" 2>/dev/null || die "覆盖失败（$_area）"
done

# 6) 收尾：清临时目录、恢复原来的运行状态、写 done。
pct 90
rm -rf "$EXT" 2>/dev/null
EXT=""
restart_if_needed
pct done
say "完成：$SCOPE ← $IN"
printf 'OK\n'
exit 0
