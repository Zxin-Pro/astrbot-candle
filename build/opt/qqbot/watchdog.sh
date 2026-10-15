#!/data/data/com.termux/files/usr/bin/bash
# 第一层看门狗（Termux 侧）—— 保证「沙盒」本身不会悄悄消失。
#
# 为什么需要它：沙盒内的 run-all.sh 只能看护它自己的两个子进程。一旦 proot 本体
# 被系统回收、被误杀、或者守护循环卡死（进程还在但不再轮转），沙盒里的一切都会
# 安静地停掉，而界面上只显示「掉线」，没有任何提示。
#
# 本脚本由 start-sandbox.sh / profile.d / 控制台三处幂等拉起（单实例 watchdog.pid），
# 每 15 秒检查一次：
#   1. supervisor.pid 指向的进程还在不在（并且确实是 proot 且带 run-all.sh）
#   2. 心跳文件 /qqbot-cache/heartbeat 是否还在更新（>90 秒不动 = 循环卡死）
# 任何一条不满足，就先把沙盒彻底停干净，再重新拉起。
#
# 停止开关：$QQBOT_CACHE/svc/watchdog.off 存在时本脚本什么都不做 —— 用户点
# 「停止」时 stop-sandbox.sh 会写这个文件，否则刚停掉就会立刻被自动拉起来。
. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || exit 1

LOG="$QQBOT_HOME/logs/watchdog.log"
WDPID="$QQBOT_HOME/watchdog.pid"
OFFMARK="$QQBOT_CACHE/svc/watchdog.off"

mkdir -p "$QQBOT_HOME/logs" "$QQBOT_CACHE/svc" 2>/dev/null

say() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null; }

# 单实例。pid 会被系统复用，所以除了 /proc 里有没有，还要确认 cmdline 真是本脚本。
if [ -f "$WDPID" ]; then
  _o=$(cat "$WDPID" 2>/dev/null)
  if [ -n "$_o" ] && [ -d "/proc/$_o" ]; then
    _c=$(tr '\0' ' ' < "/proc/$_o/cmdline" 2>/dev/null)
    case "$_c" in *watchdog.sh*) exit 0 ;; esac
  fi
fi
echo $$ > "$WDPID"

# 日志超过 512KB 就只留末尾 128KB（原地截断，见 run-all.sh 里同样的说明）
rotate() {
  _s=$(stat -c %s "$LOG" 2>/dev/null)
  case "$_s" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$_s" -gt 524288 ]; then
    tail -c 131072 "$LOG" > "$LOG.tmp" 2>/dev/null && cat "$LOG.tmp" > "$LOG" 2>/dev/null
    rm -f "$LOG.tmp" 2>/dev/null
  fi
}

# 沙盒本体还活着吗：pid 文件有效 + 进程存在 + cmdline 是 proot 跑 run-all.sh
sv_alive() {
  [ -f "$QQBOT_HOME/supervisor.pid" ] || return 1
  _p=$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)
  case "$_p" in ''|*[!0-9]*) return 1 ;; esac
  [ -d "/proc/$_p" ] || return 1
  _c=$(tr '\0' ' ' < "/proc/$_p/cmdline" 2>/dev/null)
  case "$_c" in *proot*run-all.sh*) return 0 ;; *) return 1 ;; esac
}

# 心跳文件多少秒没更新了
hb_age() {
  [ -f "$QQBOT_CACHE/heartbeat" ] || { echo 999999; return; }
  _m=$(stat -c %Y "$QQBOT_CACHE/heartbeat" 2>/dev/null)
  _n=$(date +%s)
  case "$_m" in ''|*[!0-9]*) echo 999999 ;; *) echo $((_n - _m)) ;; esac
}

restart_sandbox() {
  say "$1 —— 重启沙盒"
  rm -f "$OFFMARK" 2>/dev/null
  bash "$QQBOT_OPT/stop-sandbox.sh" >> "$LOG" 2>&1
  sleep 3
  nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" >> "$QQBOT_HOME/logs/supervisor.log" 2>&1 < /dev/null &
  sleep 25
  if sv_alive; then
    say "沙盒已恢复（supervisor PID $(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)）"
    return 0
  fi
  say "沙盒仍未起来"
  return 1
}

say "看门狗启动（pid $$，系统时区 $(qq_tz)）"
WAS_OFF=""
FAILS=0
while true; do
  rotate
  if [ -f "$OFFMARK" ]; then
    if [ -z "$WAS_OFF" ]; then say "收到停止开关，暂停守护"; WAS_OFF=1; fi
    sleep 15
    continue
  fi
  WAS_OFF=""
  if ! sv_alive; then
    restart_sandbox "supervisor 不存在或已退出" || FAILS=$((FAILS + 1))
    [ "$FAILS" -eq 0 ] && FAILS=0
    if [ "$FAILS" -gt 3 ]; then
      say "连续 $FAILS 次没能拉起沙盒，退避 120 秒再试"
      sleep 120
    fi
    continue
  fi
  _a=$(hb_age)
  if [ "$_a" -gt 90 ]; then
    restart_sandbox "守护循环心跳停了 ${_a:-?} 秒" || FAILS=$((FAILS + 1))
    continue
  fi
  FAILS=0
  sleep 15
done
