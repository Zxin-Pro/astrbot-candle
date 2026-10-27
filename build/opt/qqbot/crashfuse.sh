#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 崩溃熔断（1.3.4-beta.3，P0）—— 把「崩了就无限拉起」改成「崩到第 3 次就停手」。
#
# 真机证据：out/errlogs/20261019-121753-28222.txt 里 24 秒内 5 次 SIGSEGV，
# 而 run-all.sh 每 3 秒看一次进程，看它没了就再拉一次 —— 于是崩溃循环可以跑一整夜，
# 既耗电又把日志与崩溃现场刷爆，用户只看到「一直在重启」。
#
# 本脚本只做熔断判定，不负责拉起：
#   record  <name> <rc> [日志文件]   记一次异常退出；5 分钟窗口内累计 >=3 次即熔断
#   allow   <name>                   返回 0 = 允许拉起，1 = 已熔断（调用方别拉）
#   status                           打印 crash-fuse.json（一行 JSON）
#   summary [name]                   人读的熔断摘要（错误报告 / crashSummary() 用）
#   clear   [name|all]               清零熔断与计数（Java 的 clearFuse() 调它）
#
# 为什么状态放在 $HOME/.qqbot/state：沙盒里那个目录是 /qqbot-state（start-sandbox.sh
# 用 -b 绑进去的），宿主侧同一份文件叫 $QQBOT_HOME/state —— 原生层与错误报告都读得到。
# 绝不能放 /qqbot-cache：那是缓存，用户「清理缓存」会顺手把熔断状态清掉。
#
# 本脚本要能在两种环境里跑，所以不 source common.sh（那是 Termux 路径，沙盒里看不见）：
#   * 宿主侧（Java 桥、watchdog.sh）：common.sh 已经把 QQBOT_STATE / QQBOT_CACHE 设好
#   * 沙盒内（run-all.sh）：只有 /qqbot-state、/qqbot-cache 两个挂载点
# 两套路径下面统一解析，其余逻辑完全一致。

CF_STATE="${QQBOT_STATE:-}"
if [ -z "$CF_STATE" ]; then
  if [ -d /qqbot-state ]; then CF_STATE=/qqbot-state; else CF_STATE="$HOME/.qqbot/state"; fi
fi
CF_CACHE="${QQBOT_CACHE:-}"
if [ -z "$CF_CACHE" ]; then
  if [ -d /qqbot-cache ]; then CF_CACHE=/qqbot-cache; else CF_CACHE="$HOME/.qqbot/cache"; fi
fi
mkdir -p "$CF_STATE" 2>/dev/null

EVENTS="$CF_STATE/crash-fuse.events"
FUSEJSON="$CF_STATE/crash-fuse.json"
NOTIFY="$CF_STATE/notify-events.jsonl"
# 窗口与阈值：5 分钟 / 3 次。允许用环境变量覆写，方便按机型调（不影响默认行为）。
CF_WINDOW="${CF_WINDOW:-300}"
CF_THRESHOLD="${CF_THRESHOLD:-3}"
# 恢复时留的尾巴：一次熔断里最多记 30 行日志、4000 字，够看出崩在哪，又不至于把
# state 目录撑大（这个文件会被错误报告整段贴出去）。
CF_TAIL_LINES="${CF_TAIL_LINES:-30}"
CF_TAIL_CHARS="${CF_TAIL_CHARS:-4000}"

# 进程名归一：只留字母数字与 ._-，其余变下划线（名字会进文件名与 JSON）
cf_name() {
  printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_' | cut -c 1-32
}

# 去 ANSI 颜色与除换行/制表外的控制字符：崩溃摘要里常混着进度条 ${BS} \r 之类
cf_clean() {
  sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g' 2>/dev/null | tr -d '\000-\010\013\014\016-\037\177' 2>/dev/null
}

# JSON 字符串转义：先清控制字符，再转义反斜杠与引号，最后把换行写成字面量 \n。
# 不能用 common.sh 的 qq_json_esc —— 它会把换行整个删掉，多行摘要会挤成一行。
cf_esc() {
  printf '%s' "${1:-}" | cf_clean | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    | awk 'NR > 1 { printf "\\n" } { printf "%s", $0 }'
}

# 纪元秒 -> ISO 时间（带时区）。date -r 是 GNU 扩展，沙盒里与 Termux 里都有；
# 万一没有就退回 date -d @，再不行就打原始秒数（宁可难看，不能报错）。
cf_iso() {
  local t="${1:-0}" out=""
  out=$(date -r "$t" '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null)
  [ -n "$out" ] || out=$(date -d "@$t" '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null)
  [ -n "$out" ] || out="$t"
  printf '%s' "$out"
}

# 某个进程在窗口内的崩溃次数
cf_count() {
  local n="$1" now
  now=$(date +%s)
  awk -v now="$now" -v w="$CF_WINDOW" -v n="$n" \
    '$2 == n && (now - $1) <= w { c++ } END { printf "%d", c + 0 }' "$EVENTS" 2>/dev/null
}

# 某个进程最后一次崩溃的时间戳与退出码（没有就输出 "0 0"）
cf_last() {
  local n="$1"
  awk -v n="$n" '$2 == n { ts = $1; rc = $3 } END { printf "%s %s", ts + 0, rc + 0 }' "$EVENTS" 2>/dev/null
}

cf_marker() { printf '%s/crash-fuse.%s.fused' "$CF_STATE" "$1"; }

# 已知进程：写死两个常用名，其余从 events 与 marker 里补（以后加组件不用改脚本）
cf_names() {
  {
    printf 'napcat\nastrbot\n'
    awk '{ print $2 }' "$EVENTS" 2>/dev/null
    ls -1 "$CF_STATE"/crash-fuse.*.fused 2>/dev/null | sed -e 's#.*/crash-fuse\.##' -e 's/\.fused$//'
  } | grep -v '^$' 2>/dev/null | sort -u
}

# 重新生成 crash-fuse.json（每次 record / clear 都整体重写，临时文件 + mv 保证原子，
# 原生层任何时刻读到的都是一份完整 JSON，不会看到写了一半的文件）。
cf_write_json() {
  local tmp="$FUSEJSON.tmp.$$" first=1 n cnt last_ts last_rc fused summary
  {
    printf '{"ok":true,"window":%s,"threshold":%s,"updated":%s,"procs":{' \
      "$CF_WINDOW" "$CF_THRESHOLD" "$(date +%s)"
    for n in $(cf_names); do
      cnt=$(cf_count "$n")
      set -- $(cf_last "$n")
      last_ts="${1:-0}"; last_rc="${2:-0}"
      if [ -f "$(cf_marker "$n")" ]; then fused=true; else fused=false; fi
      summary=""
      if [ "$fused" = true ]; then
        summary=$(head -c "$CF_TAIL_CHARS" "$(cf_marker "$n")" 2>/dev/null)
      fi
      [ "$first" = 1 ] || printf ','
      first=0
      printf '"%s":{"fused":%s,"count":%s,"lastAt":"%s","lastRc":%s,"summary":"%s","marker":"%s"}' \
        "$n" "$fused" "$cnt" "$(cf_iso "$last_ts")" "$last_rc" "$(cf_esc "$summary")" \
        "$(cf_esc "$(cf_marker "$n")")"
    done
    printf '}}\n'
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$FUSEJSON" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  return 0
}

# 记一次异常退出。rc 由调用方从 wait 拿到（128+N = 被信号 N 打死）。
cf_record() {
  local name rc logf ts cnt tailtxt iso body
  name=$(cf_name "$1")
  rc="${2:-0}"
  logf="${3:-}"
  case "$rc" in ''|*[!0-9-]*) rc=0 ;; esac
  [ -n "$name" ] || return 2
  ts=$(date +%s)
  printf '%s %s %s\n' "$ts" "$name" "$rc" >> "$EVENTS" 2>/dev/null
  # 只留最近 300 条：异常退出的记录本身很小，但崩溃循环能刷出几千条
  tail -n 300 "$EVENTS" > "$EVENTS.tmp" 2>/dev/null && mv -f "$EVENTS.tmp" "$EVENTS" 2>/dev/null
  cnt=$(cf_count "$name")
  if [ "$cnt" -ge "$CF_THRESHOLD" ] && [ ! -f "$(cf_marker "$name")" ]; then
    iso=$(cf_iso "$ts")
    # 现场：优先用调用方给的组件日志，没有就退到 supervisor.log（服务接管者的日志）
    if [ -z "$logf" ] || [ ! -f "$logf" ]; then
      logf="$CF_CACHE/logs/supervisor.log"
    fi
    tailtxt=""
    if [ -f "$logf" ]; then
      tailtxt=$(tail -n "$CF_TAIL_LINES" "$logf" 2>/dev/null | cf_clean | cut -c 1-"$CF_TAIL_CHARS")
    fi
    body="进程 $name 在 5 分钟窗口内第 $cnt 次异常退出（最近一次 rc=$rc，时间 $iso）。
为避免崩溃循环耗电与刷爆日志，已停止自动拉起。处理办法：先看下面的日志尾部，
修不好就去控制台点「重启」或「清除熔断」，也可以上传错误日志让作者看。

最后 $CF_TAIL_LINES 行日志（$logf）：
${tailtxt:-（读不到：$logf 不存在）}"
    printf '%s\n' "$body" > "$(cf_marker "$name")" 2>/dev/null
    # 给原生层推通知用（Java 读 notify-events.jsonl，kind=crash 且 fused=true 就提醒用户）
    printf '{"kind":"crash","proc":"%s","fused":true,"at":"%s","count":%s,"rc":%s,"summary":"%s"}\n' \
      "$name" "$iso" "$cnt" "$rc" "$(cf_esc "$body")" >> "$NOTIFY" 2>/dev/null
    printf '[crashfuse] 熔断：%s 在 %s 秒内崩溃 %s 次（阈值 %s），不再自动拉起\n' \
      "$name" "$CF_WINDOW" "$cnt" "$CF_THRESHOLD"
  fi
  cf_write_json
  return 0
}

# 允许拉起吗：返回 0 = 允许，1 = 已熔断
cf_allow() {
  local name
  name=$(cf_name "$1")
  [ -f "$(cf_marker "$name")" ] && return 1
  return 0
}

cf_status() {
  [ -f "$FUSEJSON" ] || cf_write_json
  cat "$FUSEJSON" 2>/dev/null || printf '{"ok":false,"reason":"读不到 crash-fuse.json"}\n'
}

# 人读摘要：给错误报告与 crashSummary() 用。没有熔断也要说清楚「没熔断」，
# 否则作者看到一片空白会以为是报告缺段。
cf_summary() {
  local want n found=0
  want=$(cf_name "${1:-}")
  for n in $(cf_names); do
    if [ -n "$want" ] && [ "$n" != "$want" ]; then continue; fi
    if [ -f "$(cf_marker "$n")" ]; then
      found=1
      printf '[%s] 已熔断（窗口 %s 秒内 %s 次）\n' "$n" "$CF_WINDOW" "$(cf_count "$n")"
      cat "$(cf_marker "$n")" 2>/dev/null
      printf '\n'
    fi
  done
  if [ "$found" = 0 ]; then
    if [ -n "$want" ]; then
      printf '%s 没有熔断（窗口 %s 秒内 %s 次异常退出）\n' "$want" "$CF_WINDOW" "$(cf_count "$want")"
    else
      printf '没有任何进程处于熔断状态。\n'
    fi
  fi
  return 0
}

# 清零：删 marker + 删该进程的历史计数。all 就全清（含事件文件）。
cf_clear() {
  local want n
  want=$(cf_name "${1:-all}")
  if [ "$want" = "all" ] || [ -z "$1" ]; then
    rm -f "$CF_STATE"/crash-fuse.*.fused "$EVENTS" 2>/dev/null
  else
    rm -f "$(cf_marker "$want")" 2>/dev/null
    if [ -f "$EVENTS" ]; then
      awk -v n="$want" '$2 != n' "$EVENTS" > "$EVENTS.tmp" 2>/dev/null \
        && mv -f "$EVENTS.tmp" "$EVENTS" 2>/dev/null
      rm -f "$EVENTS.tmp" 2>/dev/null
    fi
  fi
  printf '{"kind":"crash","proc":"%s","fused":false,"at":"%s","summary":"用户解除了崩溃熔断"}\n' \
    "$want" "$(cf_iso "$(date +%s)")" >> "$NOTIFY" 2>/dev/null
  cf_write_json
  printf '[crashfuse] 已解除熔断：%s\n' "$want"
  return 0
}

CMD="${1:-}"
shift 2>/dev/null
case "$CMD" in
  record)  cf_record "$@" ;;
  allow)   cf_allow "${1:-}" ;;
  status)  cf_status ;;
  summary) cf_summary "${1:-}" ;;
  clear)   cf_clear "${1:-}" ;;
  ""|-h|--help|help)
    printf '%s\n' "用法: crashfuse.sh record <进程名> <rc> [日志文件] | allow <进程名> | status | summary [进程名] | clear [进程名|all]"
    exit 0 ;;
  *)
    printf '%s\n' "crashfuse.sh: 不认识的子命令 $CMD" >&2
    exit 2 ;;
esac
