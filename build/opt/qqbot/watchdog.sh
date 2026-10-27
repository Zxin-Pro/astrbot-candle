#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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
# 紧急下线标记（原生层「停止」写的就是它）。看门狗必须也认它：
# 停止脚本从「发 TERM」到「写开关」之间有几秒空窗，光认 OFFMARK 的话，
# 看门狗正好在这几秒里判断「supervisor 没了」就会把沙盒重新拉起来 ——
# 用户看到的是「点了停止，过一会儿又自己活了」。两个标记任一存在就暂停守护。
USEROFF="$QQBOT_STATE/offline"
# beta.3：沙盒里的 run-all.sh 跑到 03:30 / 定时重启前会写这个「备份请求」文件
# （内容：原因 + 日期两列）。备份本体读不到沙盒里的 rootfs，所以只能在宿主侧执行，
# 由本看门狗接手 —— 见主循环里「每日 / 重启前备份」那一段。
BACKUP_REQ="$QQBOT_STATE/backup-request"
BACKUP_LOG="$QQBOT_HOME/logs/backup.log"

mkdir -p "$QQBOT_HOME/logs" "$QQBOT_CACHE/svc" 2>/dev/null

# 日志级别：say 是 info，sayw 是 warn。降载（battery / thermal）期间 say 直接不写，
# 只留 sayw 那几行真正要事后追查的（重启、风控命中、降载切换），省 CPU 也省闪存写入。
# 现有脚本只有这一个写日志的地方，没有完整的分级系统，所以这里做的是「按需过滤」。
say()  { [ -n "${DEGRADE:-}" ] && return 0; printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null; }
sayw() { printf '[%s] [warn] %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null; }

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
  # 三种都算“沙盒在”：proot 已经跑起来（*proot*run-all.sh*）、容器里的守护进程、
  # 以及 start-sandbox.sh 自己（supervisor.pid 是在 exec proot 之前写的，写完到 exec
  # 之间要跑 loader 探测、残留清扫、resolv.conf 兜底，慢机上一不留神就超过 15 秒）。
  # 少了第三种，看门狗会把“正在启动”判成“已经死了”，接着 stop-sandbox.sh 把那个
  # 还在启动的 supervisor（当时还是 bash）TERM + KILL —— 真机日志里 deploy.sh: line 290
  # 的「23963 Killed」就是这么来的，紧接着又是一路新的 start-sandbox.sh，日志里
  # 看到两遍「[start] 自动启动」和一遍「服务已在运行（PID …）」。
  case "$_c" in *proot*run-all.sh*|*sandbox/run-all.sh*|*start-sandbox.sh*) return 0 ;; *) return 1 ;; esac
}

# 启动流程是否正在进行（start-sandbox.sh 用 mkdir 原子锁 $QQBOT_HOME/start.lock，
# 并把持有者 pid 写在锁目录里）。持有者还活着就说明有人在启动，这一轮什么都别做：
# 上面 sv_alive 的第三种情形只覆盖“supervisor.pid 已经写出来了”的那一段，
# 而从 mkdir 锁到写 supervisor.pid 之间还有好几秒（qq_tz_sync、loader 探测、清扫），
# 这段时间里 supervisor.pid 还是上一轮的旧值或干脆不存在。
start_in_progress() {
  [ -f "$QQBOT_HOME/start.lock/pid" ] || return 1
  _sp=$(cat "$QQBOT_HOME/start.lock/pid" 2>/dev/null)
  case "$_sp" in ''|*[!0-9]*) return 1 ;; esac
  [ -d "/proc/$_sp" ] || return 1
  _sc=$(tr '\0' ' ' < "/proc/$_sp/cmdline" 2>/dev/null)
  case "$_sc" in *start-sandbox.sh*) return 0 ;; *) return 1 ;; esac
}

# 部署是否正在进行中。部署期间必须暂停守护，理由不是洁癖：
# 首次部署时 supervisor 还不存在（它由部署最后一步拉起），sv_alive 必然为假，
# 于是看门狗会判定「沙盒没起来」→ stop-sandbox.sh + start-sandbox.sh。
# 而此刻沙盒里正跑着 apt，被硬停就会在 dpkg 数据库里留下半配置的包，
# 之后每次重跑都撞同一句 E: dpkg was interrupted —— 用户看到的就是「第 3 步永远装不上」。
# 判活同样要核对 cmdline：PID 会被复用，光看 /proc 里有没有是不够的。
deploy_running() {
  [ -f "$QQBOT_HOME/deploy.done" ] && return 1
  [ -f "$QQBOT_HOME/deploy.pid" ] || return 1
  _d=$(cat "$QQBOT_HOME/deploy.pid" 2>/dev/null)
  case "$_d" in ''|*[!0-9]*) return 1 ;; esac
  [ -d "/proc/$_d" ] || return 1
  _dc=$(tr '\0' ' ' < "/proc/$_d/cmdline" 2>/dev/null)
  case "$_dc" in *deploy.sh*) return 0 ;; *) return 1 ;; esac
}

# 心跳文件多少秒没更新了
hb_age() {
  [ -f "$QQBOT_CACHE/heartbeat" ] || { echo 999999; return; }
  _m=$(stat -c %Y "$QQBOT_CACHE/heartbeat" 2>/dev/null)
  _n=$(date +%s)
  case "$_m" in ''|*[!0-9]*) echo 999999 ;; *) echo $((_n - _m)) ;; esac
}

restart_sandbox() {
  sayw "$1 —— 重启沙盒"
  # 注意：这里故意不删 OFFMARK / USEROFF。
  # 老写法在重启前 rm -f OFFMARK，本意是「这次重启是我自己发起的，别被自己的
  # 停止开关挡住」，但它同时会把用户刚按下的停止开关一起删掉 —— 于是用户点停止、
  # 看门狗恰好在这一轮判活失败，开关就被清掉，接着沙盒被拉起来，重启还失败。
  # 真正需要「放行」的地方在下面：只有两个开关都不存在时才会走到这里，所以不用删。
  # --no-offline：这是看门狗自己的重启（几秒后就会 start 回来），不是用户要下线，
  # 不能写紧急下线标记。
  bash "$QQBOT_OPT/stop-sandbox.sh" --no-offline >> "$LOG" 2>&1
  sleep 3
  qq_spawn_detached "$QQBOT_HOME/logs/supervisor.log" bash "$QQBOT_OPT/start-sandbox.sh"
  sleep 25
  if sv_alive; then
    sayw "沙盒已恢复（supervisor PID $(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)）"
    return 0
  fi
  sayw "沙盒仍未起来"
  return 1
}

say "看门狗启动（pid $$，系统时区 $(qq_tz)）"
WAS_OFF=""
WAS_DEPLOY=""
WAS_STARTING=""
FAILS=0
DEGRADE=""
RISK_BLK=""
SLEEP=15
CRASH_LAST=0
CRASH_EVERY=1800
while true; do
  # ---------- 降载（原生层写 $QQBOT_HOME/state/degrade） ----------
  # 幂等、可逆：文件在就少干活，文件一消失下一轮就恢复常规节奏，不用重启看门狗。
  # 降载期间：轮询 15 秒→30 秒、停掉日志轮转、跳过崩溃聚合、日志只留 warn
  # （say 被吞掉，sayw 照写）。下面这些被暂停的都是「只省磁盘/CPU、攒着不致命」的活，
  # 服务本身照常守护 —— 降载不是降级成不能用。
  if qq_degrade_state; then
    if [ -z "$DEGRADE" ]; then
      DEGRADE=1
      sayw "进入降载（原因：$(qq_degrade_reason)）—— 轮询 15→30 秒、暂停日志轮转、只记 warn"
    fi
    SLEEP=30
  else
    if [ -n "$DEGRADE" ]; then
      DEGRADE=""
      sayw "退出降载，恢复常规轮询与日志轮转"
    fi
    SLEEP=15
  fi
  [ -z "$DEGRADE" ] && rotate

  # ---------- 风控识别（协议第 2 节） ----------
  # 扫一遍各实例日志的新增部分。命中后照常重启沙盒（AstrBot 得活着才能把
  # 「需要验证」告诉用户），但沙盒里的 run-napcat.sh 看到 risk-<id>.flag
  # 就不会再拉起那个实例了。
  qq_risk_scan_all >/dev/null 2>&1
  _bl=$(qq_risk_blocked_list 2>/dev/null | tr '\n' ' ')
  _bl=${_bl% }
  if [ -n "$_bl" ] && [ "$_bl" != "$RISK_BLK" ]; then
    for _id in $_bl; do
      sayw "[risk] 实例 $_id 命中风控词「$(head -n 1 "$QQBOT_STATE/risk-$_id.word" 2>/dev/null)」，已暂停自动重启"
    done
  fi
  RISK_BLK="$_bl"

  # ---------- 崩溃聚合（协议第 9 节） ----------
  # 每 30 分钟扫一次 crashwatch 的现场文件，归并成 crash-counts.json 给看板用。
  # 降载时跳过 —— 这个活要遍历一堆崩溃文件，正是「非关键任务」。
  # 启动后第一轮就会跑（CRASH_LAST 初值 0），这样刚升级完立刻有数据。
  _now=$(date +%s)
  if [ -z "$DEGRADE" ] && [ $((_now - CRASH_LAST)) -ge "$CRASH_EVERY" ]; then
    CRASH_LAST=$_now
    _ca=$(qq_crash_agg 2>&1)
    [ -n "$_ca" ] && sayw "$_ca"
  fi

  if [ -f "$OFFMARK" ] || [ -f "$USEROFF" ]; then
    if [ -z "$WAS_OFF" ]; then say "收到停止开关，暂停守护"; WAS_OFF=1; fi
    sleep "$SLEEP"
    continue
  fi
  WAS_OFF=""
  # 部署期间什么都别做，只等着。见 deploy_running() 上面的说明。
  if deploy_running; then
    if [ -z "$WAS_DEPLOY" ]; then say "部署正在进行，暂停守护（部署期间重启沙盒会打断 apt，留下半配置的包）"; WAS_DEPLOY=1; fi
    sleep "$SLEEP"
    continue
  fi
  if [ -n "$WAS_DEPLOY" ]; then say "部署已结束，恢复守护"; WAS_DEPLOY=""; fi

  # ---------- 每日 / 重启前备份（beta.3） ----------
  # 沙盒里的 run-all.sh 只能写 /qqbot-state（= 这里的 $QQBOT_STATE），而备份本体必须
  # 在宿主侧跑：要读 rootfs 里的 AstrBot data、写 $QQBOT_HOME/backup，还要用 Termux 的 tar。
  # 请求文件是一行「原因 日期」。先删请求再起后台任务：宁可丢一次备份，也不能让同一份
  # 请求每 15 秒被执行一次，把手机写满。失败只进日志 —— 备份挂了不该影响服务守护。
  if [ -f "$BACKUP_REQ" ]; then
    _breq=$(head -n 1 "$BACKUP_REQ" 2>/dev/null | tr -d '\r')
    _bwhy=${_breq%% *}
    case "$_bwhy" in
      daily|pre-restart|manual) ;;
      *) _bwhy="" ;;
    esac
    rm -f "$BACKUP_REQ" 2>/dev/null
    if [ -n "$_bwhy" ]; then
      say "收到备份请求（$_bwhy），在后台生成快照"
      ( bash "$QQBOT_OPT/backup.sh" "$_bwhy" >> "$BACKUP_LOG" 2>&1; \
        _brc=$?; [ "$_brc" = 0 ] || printf '[%s] [warn] 备份失败（%s，rc=%s）\n' \
          "$(date '+%F %T')" "$_bwhy" "$_brc" >> "$BACKUP_LOG" ) &
    fi
  fi
  # 启动中：既不判活也不重启。这里必须放在 sv_alive 之前 —— 见 start_in_progress 的说明。
  if start_in_progress; then
    if [ -z "$WAS_STARTING" ]; then
      say "沙盒正在启动（start.lock 持有者 $(cat "$QQBOT_HOME/start.lock/pid" 2>/dev/null)），本轮不判活"
      WAS_STARTING=1
    fi
    sleep "$SLEEP"
    continue
  fi
  WAS_STARTING=""
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
  sleep "$SLEEP"
done
