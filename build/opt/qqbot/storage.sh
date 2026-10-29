#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 存储统计 / 缓存清理（功能 54，协议见 docs/接口约定-1.1.30.md 第 7 节）。
#
# 用法: storage.sh stats|clean|logs|prune [--days N] [--max-mb N]
#   stats  → 写 $QQBOT_HOME/cache/storage.json：
#            {"ts":epoch,"total":N,"dirs":[{"key","name","path","bytes"}...],
#             "top":[{"path","bytes"}...最多 10 条]}
#            并在 stderr 打一行中文摘要
#   clean  → 只清缓存类文件（apt/pip/npm 缓存、/root/.cache、proot 临时目录、旧日志），
#            stdout 输出一行 FREED=<bytes>，绝不动登录数据 / 配置 / 插件 / 数据库
#   logs   → stdout 一行 JSON：{"ok":true,"files":N,"bytes":N,"oldest":"YYYY-MM-DD","newest":"YYYY-MM-DD"}
#            只统计两个日志目录，stderr 附一行中文摘要
#   prune  → 删过期日志 + 截断超大日志（默认 --days 7 --max-mb 20），
#            stdout 一行 JSON：{"ok":true,"freed":N,"removed":N}
#            红线同 clean：绝不删登录数据 / 配置 / 插件 / 数据库
#            运行期由应用在可用空间低于预留值时提示用户来清（部署门槛 3 GB）
#
# total 的算法：rootfs 本身（它已经包含 AstrBot、插件、登录数据）+ 日志。
# 五个 dirs 是「分类展示」，加起来会大于 total（插件在 AstrBot 里、AstrBot 在容器里），
# 这里按真实磁盘占用算 total，避免重复计入让界面数字虚高一倍。

. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || { echo "ERR=common.sh 加载失败"; exit 1; }

OUT="$QQBOT_CACHE/storage.json"

say() { printf '[storage] %s\n' "$*" >&2; }

# 字节数转成人看的字符串（只用于摘要，精确值一律走 JSON）
human() {
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1073741824 ]; then
    printf '%s.%s GB' $((b / 1073741824)) $((b % 1073741824 * 10 / 1073741824))
  elif [ "$b" -ge 1048576 ]; then
    printf '%s.%s MB' $((b / 1048576)) $((b % 1048576 * 10 / 1048576))
  elif [ "$b" -ge 1024 ]; then
    printf '%s KB' $((b / 1024))
  else
    printf '%s B' "$b"
  fi
}

# 最大的 10 个文件。只扫「用户数据」那几个目录：整个 rootfs 动辄十几万文件，
# 在手机上 du -a 一遍要几十秒，而用户真正关心的就是 AstrBot / 插件 / 登录数据。
top_json() {
  local adir="$1" base sz pth n=0 sep=""
  {
    for base in "$adir" "$QQBOT_CACHE/logs" "$QQBOT_ROOTFS/root/qqinst" \
                "$QQBOT_ROOTFS/opt/qqbot-inst" "$QQBOT_CACHE/crashes"; do
      [ -d "$base" ] || continue
      if [ "${QQ_DU_B:-1}" = "1" ]; then
        du -ab "$base" 2>/dev/null
      else
        du -ak "$base" 2>/dev/null | while IFS=$'\t' read -r _s _p; do
          printf '%s\t%s\n' $((_s * 1024)) "$_p"
        done
      fi
    done
  } | while IFS=$'\t' read -r sz pth; do
        case "$sz" in ''|*[!0-9]*) continue ;; esac
        [ -f "$pth" ] || continue
        printf '%s\t%s\n' "$sz" "$pth"
      done | sort -rn | head -n 10 | while IFS=$'\t' read -r sz pth; do
        printf '%s{"path":"%s","bytes":%s}' "$sep" "$(qq_json_esc "$pth")" "$sz"
        sep=","
      done
}

cmd_stats() {
  local ts adir r a p l q total tmp
  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  ts=$(date +%s)
  adir="$(qq_astrbot_data_dir)"
  r=$(qq_du_bytes "$QQBOT_ROOTFS")
  a=$(qq_du_bytes "$adir")
  p=$(qq_du_bytes "$adir/plugins")
  l=$(qq_du_bytes "$QQBOT_CACHE/logs")
  q=$(qq_du_bytes "$QQBOT_ROOTFS/root/qqinst")
  total=$((r + l))

  tmp="$OUT.$$"
  {
    printf '{"ts":%s,"total":%s,"dirs":[' "$ts" "$total"
    printf '{"key":"rootfs","name":"容器","path":"%s","bytes":%s},' "$(qq_json_esc "$QQBOT_ROOTFS")" "$r"
    printf '{"key":"astrbot","name":"AstrBot","path":"%s","bytes":%s},' "$(qq_json_esc "$adir")" "$a"
    printf '{"key":"plugins","name":"插件","path":"%s","bytes":%s},' "$(qq_json_esc "$adir/plugins")" "$p"
    printf '{"key":"logs","name":"日志","path":"%s","bytes":%s},' "$(qq_json_esc "$QQBOT_CACHE/logs")" "$l"
    printf '{"key":"qq","name":"登录数据","path":"%s","bytes":%s}' "$(qq_json_esc "$QQBOT_ROOTFS/root/qqinst")" "$q"
    printf '],"top":['
    top_json "$adir"
    printf ']}\n'
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$OUT" 2>/dev/null
  rm -f "$tmp" 2>/dev/null

  if [ -s "$OUT" ]; then
    say "统计完成：总计 $(human "$total")（容器 $(human "$r") / AstrBot $(human "$a") / 插件 $(human "$p") / 日志 $(human "$l") / 登录数据 $(human "$q")）"
    return 0
  fi
  say "统计失败：写不出 $OUT"
  return 1
}

cmd_clean() {
  local adir before after freed x f pid
  adir="$(qq_astrbot_data_dir)"

  # 清理目标（before/after 用同一份清单算差值，保证 FREED 可信）
  set -- "$QQBOT_ROOTFS/var/cache/apt/archives" \
         "$QQBOT_ROOTFS/root/.cache" \
         "$QQBOT_ROOTFS/root/.npm/_cacache" \
         "$QQBOT_CACHE/logs" \
         "$adir/logs"
  before=$(qq_du_bytes "$@")

  # 1) apt 下载下来的 .deb（装完就没用了）
  rm -f "$QQBOT_ROOTFS/var/cache/apt/archives/"*.deb 2>/dev/null
  # 2) pip / npm 的下载缓存
  rm -rf "$QQBOT_ROOTFS/root/.cache/pip" "$QQBOT_ROOTFS/root/.npm/_cacache" 2>/dev/null
  # 3) /root/.cache 下剩下的（uv / huggingface 之类，全是缓存，删了只会让下次装慢一点）
  if [ -d "$QQBOT_ROOTFS/root/.cache" ]; then
    for x in "$QQBOT_ROOTFS/root/.cache"/*; do
      [ -e "$x" ] || continue
      rm -rf "$x" 2>/dev/null
    done
  fi
  # 4) 轮转出来的旧日志（trim_logs 会原地 Truncate，这里清 .log.1 这类历史遗留）
  rm -f "$QQBOT_CACHE/logs/"*.log.1 2>/dev/null
  # 5) AstrBot 自己的日志：保留最新 1 个，其余删掉
  if [ -d "$adir/logs" ]; then
    ls -1t "$adir/logs"/*.log 2>/dev/null | tail -n +2 | while IFS= read -r f; do
      [ -n "$f" ] && rm -f "$f" 2>/dev/null
    done
  fi
  # 6) proot 临时目录：沙盒在跑的时候绝对不能动（那是活着的 proot 的临时文件，
  #    删掉会让正在运行的容器出奇怪的错），只有确认没在跑时才清。
  pid="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
  if [ -z "$pid" ] || [ ! -d "/proc/$pid" ]; then
    for x in "$QQBOT_PTMP"/*; do
      [ -e "$x" ] || continue
      rm -rf "$x" 2>/dev/null
    done
  else
    say "沙盒正在运行（supervisor pid $pid），跳过 proot 临时目录"
  fi

  after=$(qq_du_bytes "$@")
  freed=$((before - after))
  [ "$freed" -ge 0 ] 2>/dev/null || freed=0
  say "清理完成：释放 $(human "$freed")（登录数据 / 配置 / 插件 / 数据库一律没动）"
  printf 'FREED=%s\n' "$freed"
  return 0
}

# ---------------------------------------------------------------------------
# 日志统计 / 清理
#
# 只认下面这两个目录，且都只扫一层、不跟软链接：
#   $QQBOT_CACHE/logs  沙盒里的 NapCat / AstrBot 往这写（qq-*.log、astrbot.log）
#   $QQBOT_LOGS        $HOME/.qqbot/logs，部署 / 守护 / 预取自己的日志
# 为什么不递归：日志都是平铺的；递归会把 AstrBot 的 data 目录卷进来，而那里正是
# 登录数据、配置、插件、数据库 —— 本文件头部写死的红线。
# 为什么不跟软链接：有人把 data 软链到 logs 下的话，跟进去就等于删用户数据。
# 路径一律取 common.sh 的变量，不在这里硬编码。
LOG_DIRS="$QQBOT_CACHE/logs $QQBOT_LOGS"

# 文件大小（字节）。stat 不认 -c 的 toybox 版本退回 wc -c，再不行按 0 算。
qq_file_bytes() {
  _qb="$(stat -c %s "$1" 2>/dev/null)"
  case "$_qb" in ''|*[!0-9]*) _qb="$(wc -c < "$1" 2>/dev/null | tr -d ' ')" ;; esac
  case "$_qb" in ''|*[!0-9]*) _qb=0 ;; esac
  printf '%s' "$_qb"
}

# 最后写入时间（epoch 秒）。算「几天前」用它，不碰格式化输出。
qq_file_mtime() {
  _qm="$(stat -c %Y "$1" 2>/dev/null)"
  case "$_qm" in ''|*[!0-9]*) _qm="$(date -r "$1" +%s 2>/dev/null)" ;; esac
  case "$_qm" in ''|*[!0-9]*) _qm=0 ;; esac
  printf '%s' "$_qm"
}

# 日期（YYYY-MM-DD），logs 的 oldest / newest 用。
# 直接取 stat -c %y 的第一个字段：不依赖 date -d @epoch（busybox 的 date 未必认），
# 也绕开时区解析差异。
qq_file_day() {
  set -- $(stat -c %y "$1" 2>/dev/null)
  case "$1" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) printf '%s' "$1"; return 0 ;;
  esac
  set -- $(date -r "$1" +%F 2>/dev/null)
  case "$1" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) printf '%s' "$1"; return 0 ;;
  esac
  printf ''
}

cmd_logs() {
  local d f files=0 bytes=0 oldest="" newest="" sz day dnum onum=0 nnum=0
  for d in $LOG_DIRS; do
    [ -d "$d" ] || continue
    for f in "$d"/*; do
      [ -f "$f" ] || continue
      [ -L "$f" ] && continue
      files=$((files + 1))
      sz="$(qq_file_bytes "$f")"
      bytes=$((bytes + sz))
      day="$(qq_file_day "$f")"
      [ -n "$day" ] || continue
      # 日期是定宽 YYYY-MM-DD，去掉横线当数字比大小即可。
      # 不用 [ a \< b ]：那是 bash 的扩展，dash/busybox 的 test 不认。
      dnum="$(printf '%s' "$day" | tr -d '-')"
      case "$dnum" in ''|*[!0-9]*) continue ;; esac
      if [ -z "$oldest" ] || [ "$dnum" -lt "$onum" ]; then oldest="$day"; onum="$dnum"; fi
      if [ -z "$newest" ] || [ "$dnum" -gt "$nnum" ]; then newest="$day"; nnum="$dnum"; fi
    done
  done
  if [ "$files" -gt 0 ]; then
    say "日志 $files 个文件，共 $(human "$bytes")（$oldest ~ $newest）"
  else
    say "日志目录还是空的"
  fi
  printf '{"ok":true,"files":%s,"bytes":%s,"oldest":"%s","newest":"%s"}\n' \
    "$files" "$bytes" "$oldest" "$newest"
  return 0
}

cmd_prune() {
  local d f days=7 maxmb=20 now cut keep freed=0 removed=0 truncated=0 tmp sz mt sz2
  while [ $# -gt 0 ]; do
    case "$1" in
      --days)
        if [ $# -ge 2 ]; then days="$2"; shift; fi
        shift
        ;;
      --max-mb)
        if [ $# -ge 2 ]; then maxmb="$2"; shift; fi
        shift
        ;;
      *) say "忽略不认识的参数 $1"; shift ;;
    esac
  done
  # 参数写错时退回默认值而不是报错退出：这命令多半是被「一键清理」按钮调起来的，
  # 用户等的是一个结果，不是一句用法。
  case "$days" in ''|*[!0-9]*) say "天数不是数字（$days），按默认 7 天处理"; days=7 ;; esac
  case "$maxmb" in ''|*[!0-9]*) say "上限不是数字（$maxmb），按默认 20 MB 处理"; maxmb=20 ;; esac
  now="$(date +%s)"
  case "$now" in ''|*[!0-9]*) now=0 ;; esac
  cut=$((days * 86400))
  keep=$((maxmb * 1048576))
  say "开始清理：保留 $days 天，单文件上限 $maxmb MB"

  for d in $LOG_DIRS; do
    [ -d "$d" ] || continue
    for f in "$d"/*; do
      [ -f "$f" ] || continue
      [ -L "$f" ] && continue
      sz="$(qq_file_bytes "$f")"
      mt="$(qq_file_mtime "$f")"
      # 1) 过期就整份删。days=0 解释为「不按天数删」—— 免得手滑传个 0 把今天的日志也清了。
      if [ "$cut" -gt 0 ] && [ "$now" -gt 0 ] && [ "$mt" -gt 0 ] \
         && [ $((now - mt)) -gt "$cut" ]; then
        if rm -f "$f" 2>/dev/null; then
          freed=$((freed + sz))
          removed=$((removed + 1))
          say "删除过期日志 $f（$(human "$sz")）"
        fi
        continue
      fi
      # 2) 超大就只留末尾 keep 字节（日志越新越有用）。
      #    先写同目录临时文件，再用 cat 覆盖回原文件：inode 不变，
      #    正在往这个文件追加的 deploy / supervisor 不会写进已删除的旧 inode 里。
      if [ "$keep" -gt 0 ] && [ "$sz" -gt "$keep" ]; then
        tmp="$d/.prune.$$"
        if tail -c "$keep" "$f" > "$tmp" 2>/dev/null; then
          if cat "$tmp" > "$f" 2>/dev/null; then
            sz2="$(qq_file_bytes "$f")"
            if [ "$sz2" -lt "$sz" ]; then freed=$((freed + sz - sz2)); fi
            truncated=$((truncated + 1))
            say "截断超大日志 $f（$(human "$sz") → $(human "$sz2")）"
          fi
        fi
        rm -f "$tmp" 2>/dev/null
      fi
    done
  done
  say "清理完成：删掉 $removed 个过期日志、截断 $truncated 个超大日志，释放 $(human "$freed")（登录数据 / 配置 / 插件 / 数据库一律没动）"
  printf '{"ok":true,"freed":%s,"removed":%s}\n' "$freed" "$removed"
  return 0
}

case "${1:-}" in
  stats) cmd_stats ;;
  clean) cmd_clean ;;
  logs)  cmd_logs ;;
  prune) shift; cmd_prune "$@" ;;
  *) printf '用法: storage.sh stats|clean|logs|prune [--days N] [--max-mb N]\n' >&2; exit 2 ;;
esac
