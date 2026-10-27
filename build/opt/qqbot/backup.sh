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

# ===========================================================================
# beta.3：每日 / 手动 / 重启前「配置快照」（与上面的一次性打包是两条独立路径）
#
# 为什么要另起一条：上面那条是「用户点备份 → 出一个 tar.gz 给用户自己存」，包大、
# 要摊平目录、要算 sha256，适合手动、不适合每天自动跑。这里要解决的是另一件事：
# 用户把 bots.json / cmd_config.json 改坏了、插件把配置写崩了，要能一键退回上一次
# 能用的配置。所以只存「配置与清单」，不打包插件本体（插件动辄几十 MB，一天一份
# 七天就把手机塞满了），放 $QQBOT_HOME/backup/<YYYYmmdd-HHMMSS>/，保留最近 7 份。
#
# 子命令（供 QqbotHealthBridge 与 watchdog.sh 调用，不复用上面 tar 那套协议）：
#   backup.sh daily|manual|pre-restart   生成一份快照，stdout 打 SNAP=<目录>
#   backup.sh list                       列快照，stdout 一行 JSON 数组
#   backup.sh restore <id>               先把当前配置再快照一份，再按 manifest 覆盖；
#                                        中途失败就用这份「回滚点」还原回去
# ===========================================================================
SNAPDIR="$QQBOT_HOME/backup"
SNAP_KEEP="${SNAP_KEEP:-7}"
SNAP_LAST="$QQBOT_STATE/backup-last.json"

snap_ts() { date '+%Y%m%d-%H%M%S'; }
snap_iso() { date '+%Y-%m-%dT%H:%M:%S%z'; }

# 快照里的相对文件名要能当文件名用：把配置名里的怪字符换掉（config 目录里的
# 文件名是用户/插件写的，理论上有中文和空格，cp 没问题，但 manifest 里不能带 /）
snap_name() { printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_' | cut -c 1-80; }

# 收集一个待备份文件。$1 源路径 $2 快照内文件名 $3 是否参与恢复(1/0)
snap_add() {
  local src="$1" name="$2" rst="$3"
  [ -f "$src" ] || return 0
  cp -a "$src" "$SNAP/files/$name" 2>/dev/null || return 0
  printf '%s\t%s\t%s\t%s\n' "$src" "$name" "$(qq_du_bytes "$src")" "$rst" >> "$SNAP/entries.tsv"
}

# 生成一份快照。$1 = 原因（daily / manual / pre-restart / pre-restore）
# 成功时把目录路径写进 $SNAP 并 echo 到 stdout（调用方靠它做回滚）。
snap_make() {
  local reason="$1" adir id n=0 total=0
  id=$(snap_ts)
  SNAP="$SNAPDIR/$id"
  # 同一秒内被点两次（用户连点 / 定时与手动撞上）：加 pid 后缀，绝不覆盖已有快照
  [ -d "$SNAP" ] && SNAP="$SNAPDIR/$id-$$"
  mkdir -p "$SNAP/files" 2>/dev/null || { printf 'ERR=快照目录建不出来：%s\n' "$SNAP"; return 1; }
  : > "$SNAP/entries.tsv" 2>/dev/null || { printf 'ERR=快照目录不可写：%s\n' "$SNAP"; return 1; }
  adir="$(qq_astrbot_data_dir)"

  # 1) 实例表：界面写的那份 + 沙盒缓存里那份（两边都可能独立被改过，都留着才救得回来）
  snap_add "$QQBOT_HOME/bots.json"  "home-bots.json" 1
  snap_add "$QQBOT_CACHE/bots.json" "cache-bots.json" 1
  # 2) AstrBot 主配置与 config/*.json
  snap_add "$adir/cmd_config.json" "cmd_config.json" 1
  for f in "$adir"/config/*.json; do
    [ -f "$f" ] || continue
    n=$((n + 1))
    snap_add "$f" "config-$(snap_name "$(basename "$f")")" 1
  done
  # 3) 插件清单：只记名字 / 版本 / 大小 / 改时间，不拷插件本体（几十 MB 一天一份撑爆手机）。
  #    恢复时它不参与覆盖（第 4 列 0），只用来对照「当时装了哪些插件」。
  if [ -d "$adir/plugins" ]; then
    {
      printf '# 插件清单（名字\t字节\t修改时间）\n'
      for d in "$adir/plugins"/*; do
        [ -e "$d" ] || continue
        printf '%s\t%s\t%s\n' "$(basename "$d")" "$(qq_du_bytes "$d")" \
          "$(date -r "$d" '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)"
      done
    } > "$SNAP/files/plugins.list" 2>/dev/null \
      && printf '%s\t%s\t%s\t0\n' "$adir/plugins" "plugins.list" \
           "$(qq_du_bytes "$SNAP/files/plugins.list")" >> "$SNAP/entries.tsv"
  fi

  total=$(wc -l < "$SNAP/entries.tsv" 2>/dev/null | tr -d ' ')
  case "${total:-}" in ''|*[!0-9]*) total=0 ;; esac
  # manifest.json：restore 就按它逐条覆盖，将来加字段也不用改恢复逻辑
  {
    printf '{"id":"%s","at":"%s","ts":%s,"reason":"%s","files":%s,"bytes":%s,"entries":[' \
      "$(basename "$SNAP")" "$(snap_iso)" "$(date +%s)" "$reason" "$total" "$(qq_du_bytes "$SNAP")"
    n=0
    while IFS='	' read -r p f b r; do
      [ -n "$p" ] || continue
      [ "$n" = 0 ] || printf ','
      n=$((n + 1))
      printf '{"path":"%s","file":"%s","bytes":%s,"restore":%s}' \
        "$(qq_json_esc "$p")" "$(qq_json_esc "$f")" "${b:-0}" "${r:-1}"
    done < "$SNAP/entries.tsv"
    printf ']}\n'
  } > "$SNAP/manifest.json" 2>/dev/null || { printf 'ERR=manifest 写不出来\n'; return 1; }

  # 最近一次备份（Java 的 status() 与错误报告都读这一份，省得每次去扫目录）
  printf '{"at":"%s","file":"%s","count":%s,"reason":"%s","bytes":%s}\n' \
    "$(snap_iso)" "$(basename "$SNAP")" "$total" "$reason" "$(qq_du_bytes "$SNAP")" \
    > "$SNAP_LAST.tmp" 2>/dev/null && mv -f "$SNAP_LAST.tmp" "$SNAP_LAST" 2>/dev/null

  snap_rotate
  printf 'SNAP=%s\n' "$SNAP"
  return 0
}

# 轮转：按名字（= 时间戳）倒序，只留最近 SNAP_KEEP 份。名字排序等价于时间排序，
# 因为快照目录名就是 YYYYmmdd-HHMMSS；带 pid 后缀的同一秒快照会排在后面（更新）。
snap_rotate() {
  local keep="${SNAP_KEEP:-7}" i=0 d
  [ -d "$SNAPDIR" ] || return 0
  for d in $(ls -1d "$SNAPDIR"/*/ 2>/dev/null | sort -r); do
    i=$((i + 1))
    [ "$i" -le "$keep" ] && continue
    rm -rf "$d" 2>/dev/null
  done
  return 0
}

# 列出快照（JSON 数组，给 restoreList() 用）。只列有 manifest 的目录，
# 半途失败留下的空目录不算一份备份。
snap_list() {
  local d first=1
  printf '['
  for d in $(ls -1d "$SNAPDIR"/*/ 2>/dev/null | sort -r); do
    [ -f "$d/manifest.json" ] || continue
    [ "$first" = 1 ] || printf ','
    first=0
    # manifest 本身就是一行 JSON，直接内嵌它的 id/at/reason/bytes，再补 size
    printf '{"id":"%s","at":"%s","size":%s,"reason":"%s","files":%s}' \
      "$(basename "$d")" \
      "$(sed -n 's/.*"at":"\([^"]*\)".*/\1/p' "$d/manifest.json" 2>/dev/null | head -n 1)" \
      "$(sed -n 's/.*"bytes":\([0-9]*\).*/\1/p' "$d/manifest.json" 2>/dev/null | head -n 1)" \
      "$(sed -n 's/.*"reason":"\([^"]*\)".*/\1/p' "$d/manifest.json" 2>/dev/null | head -n 1)" \
      "$(sed -n 's/.*"files":\([0-9]*\).*/\1/p' "$d/manifest.json" 2>/dev/null | head -n 1)"
  done
  printf ']\n'
  return 0
}

# 把一份快照里的配置覆盖回原位。$1 = 快照目录。返回 1 = 有文件没覆盖成功。
snap_apply() {
  local dir="$1" p f b r rc=0
  [ -f "$dir/manifest.json" ] || return 1
  while IFS='	' read -r p f b r; do
    [ -n "$p" ] || continue
    [ "${r:-1}" = "1" ] || continue
    [ -f "$dir/files/$f" ] || continue
    mkdir -p "$(dirname "$p")" 2>/dev/null
    # 先写临时文件再 mv：cp 直接覆盖时如果中途断电，目标文件会变成半截配置，
    # AstrBot 起来就读到坏 JSON —— 那比不恢复还糟。
    if cp -a "$dir/files/$f" "$p.tmp-restore" 2>/dev/null && mv -f "$p.tmp-restore" "$p" 2>/dev/null; then
      :
    else
      rm -f "$p.tmp-restore" 2>/dev/null
      rc=1
    fi
  done < "$dir/entries.tsv"
  return "$rc"
}

# 恢复：先把「当前状态」快照一份当回滚点，再覆盖；失败就用回滚点还原。
snap_restore() {
  local id="$1" dir="$2" rollback="" pctv
  case "$id" in ''|*/*|*..*) printf 'ERR=快照 id 不合法\n'; return 1 ;; esac
  dir="$SNAPDIR/$id"
  [ -d "$dir" ] || { printf 'ERR=没有这份快照：%s\n' "$id"; return 1; }
  [ -f "$dir/manifest.json" ] || { printf 'ERR=快照缺少 manifest.json：%s\n' "$id"; return 1; }
  mkdir -p "$QQBOT_STATE" 2>/dev/null
  # 回滚点：reason 用 pre-restore，跟日常备份区分开（它也算一份常规快照，会被轮转）
  pct 0
  rollback=$(snap_make pre-restore 2>/dev/null | sed -n 's/^SNAP=//p')
  pct 50
  if [ -z "$rollback" ]; then
    printf 'ERR=备份当前配置失败，没有恢复（避免把现在的配置弄丢）\n'
    return 1
  fi
  if snap_apply "$dir"; then
    pct done
    printf 'RESTORED=%s\n' "$id"
    return 0
  fi
  # 恢复失败：把回滚点按同样的规则套回去，再报错。这里即使失败也只能尽力而为，
  # 但至少备份目录本身是完好的 —— 用户还能再恢复一次。
  snap_apply "$rollback" >/dev/null 2>&1
  pct error
  printf 'ERR=恢复失败，已回滚到恢复前的配置（回滚点 %s）\n' "$(basename "$rollback")"
  return 1
}

# 快照模式入口：抢同一把锁（与 tar 备份互斥），跑完把进度写 done。
snap_main() {
  local reason="$1" rc
  mkdir -p "$QQBOT_STATE" "$SNAPDIR" 2>/dev/null
  if ! mkdir "$LOCK" 2>/dev/null; then
    _lp=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$_lp" ] && kill -0 "$_lp" 2>/dev/null; then
      printf 'ERR=已有备份在跑（pid %s）\n' "$_lp"
      return 1
    fi
    rm -rf "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || { printf 'ERR=拿不到备份锁\n'; return 1; }
  fi
  echo $$ > "$LOCK/pid" 2>/dev/null
  pct 25
  rc=0
  snap_make "$reason" || rc=1
  [ "$rc" = 0 ] && pct done || pct error
  rm -rf "$LOCK" 2>/dev/null
  return "$rc"
}

case "$SCOPE" in
  daily|manual|pre-restart|pre-restore)
    snap_main "$SCOPE"; exit $? ;;
  list|ls)
    snap_list; exit 0 ;;
  restore)
    snap_restore "${2:-}" ""; exit $? ;;
esac

case "$SCOPE" in
  all|config|plugins) ;;
  *) usage; printf 'ERR=scope 必须是 all/config/plugins（或 daily/manual/pre-restart/list/restore）\n'; exit 1 ;;
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
