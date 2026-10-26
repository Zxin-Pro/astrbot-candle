#!/bin/bash
# 沙盒内服务守护：NapCat 与 AstrBot 各自常驻，谁退出就单独把谁拉起来。
#
# 与旧版的区别：旧版把两个服务当成一个整体（任一退出就全部重启），无法单独关掉其中一个。
# 现在循环每 3 秒读一次 /qqbot-cache/svc/*.off：
#   napcat.off  存在 -> 停掉 NapCat 且不再拉起（AstrBot 继续跑）
#   astrbot.off 存在 -> 停掉 AstrBot 且不再拉起（NapCat 继续跑）
# 标记文件由控制台的「组件开关」写/删（宿主机侧同一个目录就是 $HOME/.qqbot/cache/svc），
# 所以开关状态跨应用重启、跨容器重启都保留。
#
# 这一层还负责三件事：
#   1. 时区 —— 从 /qqbot-cache/tz 取手机系统时区，保证日志时间与手机一致
#   2. 心跳 —— 每轮把时间戳写进 /qqbot-cache/heartbeat，Termux 侧的 watchdog.sh
#      靠它判断「守护循环本身」是活着还是卡死了
#   3. 存活探测 —— 进程还在但端口掉了（容器卡死）也要能发现并重启，
#      光看 kill -0 是看不出来的
set -m   # 开启作业控制：每个后台服务自成一个进程组，停止时能把整棵进程树（xvfb/QQ）一起收干净
mkdir -p /qqbot-cache/logs /qqbot-cache/svc
export HOME=/root

# ---------- 时区 ----------
# start-sandbox.sh 已经把 TZ 传进来了；直接跑本脚本时从桥接文件补。
if [ -z "${TZ:-}" ] && [ -s /qqbot-cache/tz ]; then
  TZ="$(head -n 1 /qqbot-cache/tz 2>/dev/null)"
fi
[ -n "${TZ:-}" ] && export TZ

SVC=/qqbot-cache/svc
off() { [ -f "$SVC/$1.off" ]; }
HB=/qqbot-cache/heartbeat
beat() { date +%s > "$HB" 2>/dev/null; }
BOTS_JSON=/qqbot-cache/bots.json

# ---------- 降载 / 单实例 ----------
# 原生层写 $QQBOT_HOME/state/degrade 表示电量或温度降载；start-sandbox.sh 把那个
# 目录绑到 /qqbot-state，所以沙盒里直接读得到。降载时这里只停「非关键」的活：
# 日志轮转（trim_logs）—— 它只是省磁盘，攒着不致命；两个服务照常守护。
# 没有加「降载就不重启服务」这类逻辑：那是把降载做成了故障，服务该活着就得活着。
degraded() { [ -f /qqbot-state/degrade ]; }

# 单实例保护：原生 NetworkWatch 在网络恢复后会直接调本脚本（协议第 3 节），
# 而 start-sandbox.sh 同一时间可能刚拉过一个；两个 supervisor 同时跑会各自去抢
# 6099/6199，还会互相覆盖心跳文件。pid 记在 /qqbot-cache/run/ 下 ——
# stop-sandbox.sh 与 run-napcat.sh 停止时都会清掉该目录里的 *.pid，
# 所以「停止 = 允许重新拉起」不用额外处理。
RUNPID=/qqbot-cache/run/run-all.pid
# 用 mkdir 抢锁，而不是「先读 pid 再写 pid」：两个启动同时到达时旧写法都能读到
# 空 pid 然后各写各的，结果是两套 AstrBot/NapCat 一起抢 6099/6199。真机
# supervisor.log（2026-10-27 01:24:25 和 01:24:29）里两行「开始守护」就是这个问题，
# 紧接着 NapCat 反复「登录服务已断开」重出二维码。mkdir 是原子的，只有一个能成功。
RUNLOCK=/qqbot-cache/run/run-all.lock
mkdir -p /qqbot-cache/run 2>/dev/null
_try=0
while ! mkdir "$RUNLOCK" 2>/dev/null; do
  _op=$(cat "$RUNLOCK/pid" 2>/dev/null)
  case "$_op" in
    ''|*[!0-9]*)
      # 对方可能刚 mkdir，还没把 pid 写进去：等两轮再清，否则会把刚到手的锁删掉。
      _try=$((_try + 1))
      if [ "$_try" -ge 3 ]; then rm -rf "$RUNLOCK" 2>/dev/null; _try=0; fi
      sleep 1
      continue ;;
  esac
  if [ ! -d "/proc/$_op" ]; then
    rm -rf "$RUNLOCK" 2>/dev/null   # 持有者已死（SIGKILL 残留），清锁重抢
    continue
  fi
  _oc=$(tr '\0' ' ' < "/proc/$_op/cmdline" 2>/dev/null)
  case "$_oc" in
    *run-all.sh*) echo "[supervisor] 已有守护在跑（pid $_op），本次退出"; exit 0 ;;
  esac
  rm -rf "$RUNLOCK" 2>/dev/null     # pid 被别的进程复用了
done
echo $$ > "$RUNLOCK/pid"
echo $$ > "$RUNPID"

# ---------- 端口探测 ----------
# bash 的 /dev/tcp 在没编译 net 重定向时会报 "No such file or directory"；
# 端口没人监听时报的是 "Connection refused"（说明探测可用）。靠错误文本区分。
_pe="$( ( exec 3<>"/dev/tcp/127.0.0.1/1" ) 2>&1 )"
PORT_PROBE=1
case "$_pe" in *"No such file"*|*"not supported"*) PORT_PROBE=0 ;; esac
port_open() {
  [ "$PORT_PROBE" = 1 ] || return 0
  ( exec 3<>"/dev/tcp/127.0.0.1/$1" ) >/dev/null 2>&1
}

# HTTP 探活：TCP 连通只说明内核把连接收进了 accept 队列。AstrBot 的事件循环被
# 卡住时，内核照样完成三次握手，旧的 port_open 永远返回「活着」—— 真机日志里
# 2026-10-27 01:06~01:32 连续 lag 20/116/136/298/92/51/136 秒，supervisor 一次都
# 没判出卡死。这里发一个最小 GET，N 秒内读不到状态行就算不健康。
# 读不到 /dev/tcp 时直接返回 0（测不了就别重启，不能把探测能力的缺失当成故障）。
http_alive() {
  local port="$1" secs="${2:-6}" line rc
  [ "$PORT_PROBE" = 1 ] || return 0
  exec 9<>"/dev/tcp/127.0.0.1/$port" 2>/dev/null || return 1
  printf 'GET / HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&9 2>/dev/null
  if [ $? -ne 0 ]; then exec 9<&- 9>&- 2>/dev/null; return 1; fi
  IFS= read -r -t "$secs" -u 9 line
  rc=$?
  exec 9<&- 9>&- 2>/dev/null
  [ "$rc" = 0 ] || return 1
  case "$line" in HTTP/*) return 0 ;; esac
  return 1
}

# bots.json 里所有实例的 WebUI 端口（可能多个）。只要还有任意一个在监听，
# 就认为 NapCat 这一层是活的。
napcat_ports() {
  [ -s "$BOTS_JSON" ] || { echo 6099; return; }
  grep -o '"napcatPort"[[:space:]]*:[[:space:]]*[0-9]\+' "$BOTS_JSON" 2>/dev/null | grep -o '[0-9]\+$' | sort -u
  [ "${PIPESTATUS[0]:-0}" = "0" ] || echo 6099
}

# 日志限长：单个日志超过 20MB 就原地截断成末尾 2MB。
# 必须原地截断（cat 覆盖同一个 inode），不能 mv 新文件，否则 AstrBot/QQ 持有的
# 旧文件描述符会继续写进已被删除的 inode，界面里就再也看不到新日志了。
trim_logs() {
  for f in /qqbot-cache/logs/*.log; do
    [ -f "$f" ] || continue
    sz=$(stat -c %s "$f" 2>/dev/null || echo 0)
    if [ "$sz" -gt 20971520 ]; then
      if tail -c 2097152 "$f" > "$f.trim" 2>/dev/null; then
        cat "$f.trim" > "$f" 2>/dev/null
      fi
      rm -f "$f.trim"
    fi
  done
}

# 幂等停止一个服务：先 TERM 整个进程组，等它自己退（最多 10 秒），赖着不走才 KILL。
# 不能上来就 -9：run-napcat.sh 的 TERM trap 会顺手把它名下的 QQ 实例组一起收干净，
# 直接 KILL 会跳过这段清理，留下占着端口的孤儿进程。
sweep_napcat() {
  local sig="$1" p c
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    c=$(<"$p/cmdline") 2>/dev/null || continue
    case "$c" in
      *xvfb-run*|*Xvfb*|*/opt/qqbot-inst/*|*/opt/QQ/qq*) kill -"$sig" "${p#/proc/}" 2>/dev/null ;;
    esac
  done
  return 0
}

stop_svc() {
  local pg="$1" name="$2" kind="$3" i=0
  [ -n "$pg" ] || return 0
  echo "[supervisor] 停止 $name（pid $pg）"
  kill -TERM -"$pg" 2>/dev/null
  while [ -d "/proc/$pg" ] && [ "$i" -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
  if [ -d "/proc/$pg" ]; then
    echo "[supervisor] $name 没有响应 TERM，强制结束"
    kill -KILL -"$pg" 2>/dev/null
    sleep 1
    if [ "$kind" = napcat ]; then
      echo "[supervisor] 清扫 NapCat 残留进程"
      sweep_napcat TERM
      sleep 2
      sweep_napcat KILL
      sleep 1
    fi
  fi
  wait "$pg" 2>/dev/null
  return 0
}

# 等服务退出并记录「它是怎么死的」：wait 返回 128+N 表示被信号 N 干掉
# （143 = TERM、137 = KILL、134 = ABRT）。有这条日志，下次再遇到
# 「莫名其妙掉线」就能直接看出是谁发的信号，而不是只剩一句「已退出」。
reap() {
  local pid="$1" name="$2" rc
  wait "$pid" 2>/dev/null
  rc=$?
  if [ "$rc" -gt 128 ]; then
    echo "[supervisor] $name 结束：被信号 $((rc - 128)) 终止（rc=$rc）"
  else
    echo "[supervisor] $name 退出：rc=$rc"
  fi
  # AstrBot「一起来就退」（rc=1）时，真正的原因（ImportError、端口占用、配置坏了）
  # 只在 astrbot.log 里；这份 supervisor.log 才是错误报告默认收集的日志。
  # 所以异常退出时把它末尾抄进来 —— 前 3 次抄，避免死循环时把日志撑爆。
  if [ "$name" = "AstrBot" ] && [ "$rc" -ne 0 ]; then
    _adump=$(( ${_adump:-0} + 1 ))
    if [ "$_adump" -le 3 ]; then
      echo "[supervisor] AstrBot 异常退出，抄送 astrbot.log 末尾 30 行（第 $_adump 次）："
      tail -n 30 /qqbot-cache/logs/astrbot.log 2>/dev/null | sed -e 's/\x1b\[[0-9;]*m//g' -e 's/^/[astrbot.log] /'
    fi
  fi
}

# 收到 TERM（qqbot-stop / 停止容器）时先把两个服务收干净再退，
# 免得 proot 已经退出、服务进程却还挂在半路。
cleanup() {
  local p
  for p in "$NP" "$AB"; do [ -n "$p" ] && kill -TERM -"$p" 2>/dev/null; done
  sleep 1
  for p in "$NP" "$AB"; do [ -n "$p" ] && kill -KILL -"$p" 2>/dev/null; done
  # 退出时把单实例的 pid/锁都删掉，否则下一次启动会误判「已有守护在跑」直接退出。
  rm -f "$RUNPID" 2>/dev/null
  rm -rf "${RUNLOCK:-/qqbot-cache/run/run-all.lock}" 2>/dev/null
  exit 0
}
trap cleanup TERM INT

echo "[supervisor] 开始守护（TZ=${TZ:-未设置}，当前时间 $(date '+%F %T %Z')）"
beat
trim_logs
( while true; do sleep 60; degraded || trim_logs; done ) &

NP=""
AB=""
NAP_WAIT=0
NAP_STARTED=0
NAP_DOWN=0
NAP_PROBE=0
AB_STARTED=0
AB_DOWN=0
AB_PROBE=0
# AstrBot 卡死用 HTTP 探活判定：连续 3 次（45 秒一轮，约 2 分钟）无响应才重启。
# 宁可多等一轮，也不要因为手机正忙、一次请求慢了点就把正在回话的服务重启掉。
AB_MAXDOWN=3

# 宽限期：xvfb + QQ + NapCat 注入本来就要几十秒，刚起来的 180 秒内不判卡死。
GRACE=180
# 连续探测失败 3 次（每次间隔 45 秒）才判卡死，避免网络抖动式误判。
MAXDOWN=3
PROBE_EVERY=45

# ---------- 网络现场采样 / 登录服务抖动检测 ----------
# 真机 09:13 那次 AstrBot 出网全部 EBUSY（Cannot connect to host … [Device or resource busy]），
# 而沙盒里事后一点线索都没有；同一份日志里 NapCat 的登录服务又在 15~20 秒一轮地掉线重连。
# 这里补两件事，都是只读、很轻的：
#   1. netdiag —— 启动时一次、之后每 10 分钟一次，把 socket/fd/代理/errno 采样进 netdiag.log
#   2. flap_check —— 每 30 秒数一次 NapCat 日志尾部「登录服务已断开」的条数，30 秒涨 3 条以上
#      就说明登录服务在打转，打一行警告，并顺带抓一次 netdiag（10 分钟内最多一次）
NETDIAG=/qqbot-opt/sandbox/netdiag.sh
netdiag() {
  [ -f "$NETDIAG" ] || return 0
  bash "$NETDIAG" >> /qqbot-cache/logs/netdiag.log 2>&1
}
FLAP_PREV=""
FLAP_AT=0
flap_check() {
  local n=0 f c
  for f in /qqbot-cache/logs/qq-*.log; do
    [ -f "$f" ] || continue
    c=$(tail -n 400 "$f" 2>/dev/null | grep -c "登录服务已断开" 2>/dev/null)
    n=$((n + ${c:-0}))
  done
  if [ -n "$FLAP_PREV" ] && [ "$n" -gt "$FLAP_PREV" ] && [ $((n - FLAP_PREV)) -ge 3 ]; then
    echo "[supervisor] 警告：NapCat 登录服务在反复断开（30 秒内新增 $((n - FLAP_PREV)) 次），疑似网络层抖动"
    if [ $(( $(date +%s) - FLAP_AT )) -ge 600 ]; then
      FLAP_AT=$(date +%s)
      echo "[supervisor] 抓取网络现场 -> /qqbot-cache/logs/netdiag.log"
      netdiag
    fi
  fi
  FLAP_PREV=$n
}
LAST_NET=0
LAST_FLAP=0

while true; do
  beat
  NOW=$(date +%s)

  # 每 10 分钟采一次网络现场；每 30 秒查一次登录服务是否在打转。
  # LAST_* 初值 0，所以启动后第一轮就会各跑一次。
  if [ $((NOW - LAST_NET)) -ge 600 ]; then LAST_NET=$NOW; netdiag; fi
  if [ $((NOW - LAST_FLAP)) -ge 30 ]; then LAST_FLAP=$NOW; flap_check; fi

  # ---------- NapCat ----------
  if off napcat; then
    if [ -n "$NP" ]; then stop_svc "$NP" NapCat napcat; NP=""; fi
    NAP_STARTED=0; NAP_DOWN=0; NAP_PROBE=0
  elif [ -z "$NP" ] || ! kill -0 "$NP" 2>/dev/null; then
    if [ -n "$NP" ]; then echo "[supervisor] NapCat 已退出，重新拉起"; reap "$NP" NapCat; fi
    bash /qqbot-opt/sandbox/run-napcat.sh &
    NP=$!
    NAP_WAIT=1
    NAP_STARTED=$NOW
    NAP_DOWN=0
    NAP_PROBE=$NOW
    echo "[supervisor] NapCat 启动 pid=$NP"
  elif [ "$NAP_STARTED" -gt 0 ] && [ $((NOW - NAP_STARTED)) -gt "$GRACE" ] && [ $((NOW - NAP_PROBE)) -ge "$PROBE_EVERY" ]; then
    NAP_PROBE=$NOW
    if napcat_ports | while read -r _pp; do port_open "$_pp" && exit 7; done; [ "$?" = "7" ]; then
      NAP_DOWN=0
    else
      NAP_DOWN=$((NAP_DOWN + 1))
      echo "[supervisor] NapCat 端口探测不通（连续 $NAP_DOWN/$MAXDOWN 次）"
      if [ "$NAP_DOWN" -ge "$MAXDOWN" ]; then
        echo "[supervisor] NapCat 进程还在但端口一直不通，判定卡死，重启"
        stop_svc "$NP" NapCat napcat
        NP=""
        NAP_DOWN=0
      fi
    fi
  fi

  # 刚拉起 NapCat 时多等一会：xvfb + QQ + NapCat 注入要几秒才把 WebUI 端口监听起来。
  if [ "$NAP_WAIT" = 1 ]; then sleep 10; NAP_WAIT=0; fi

  # ---------- AstrBot ----------
  if off astrbot; then
    if [ -n "$AB" ]; then stop_svc "$AB" AstrBot astrbot; AB=""; fi
    AB_STARTED=0; AB_DOWN=0; AB_PROBE=0
  elif [ -z "$AB" ] || ! kill -0 "$AB" 2>/dev/null; then
    if [ -n "$AB" ]; then echo "[supervisor] AstrBot 已退出，重新拉起"; reap "$AB" AstrBot; fi
    # 退避：连续「起来不到 20 秒就退」时把重启间隔拉开，最慢 60 秒一次。
    # 否则坏掉的 AstrBot 会 3 秒一次地重启，既耗电又刷日志，用户还只看到一堆重复行。
    if [ -n "${AB_STARTED:-}" ] && [ "$AB_STARTED" -gt 0 ] && [ $((NOW - AB_STARTED)) -lt 20 ]; then
      AB_FAST=$(( ${AB_FAST:-0} + 1 ))
    else
      AB_FAST=0
    fi
    if [ "${AB_FAST:-0}" -ge 3 ]; then
      _bo=$(( AB_FAST > 12 ? 60 : AB_FAST * 5 ))
      echo "[supervisor] AstrBot 连续快速退出 ${AB_FAST} 次，等待 ${_bo} 秒再试"
      sleep "$_bo"
      NOW=$(date +%s)
    fi
    bash /qqbot-opt/sandbox/run-astrbot.sh &
    AB=$!
    AB_STARTED=$NOW
    AB_DOWN=0
    AB_PROBE=$NOW
    echo "[supervisor] AstrBot 启动 pid=$AB"
  elif [ "$AB_STARTED" -gt 0 ] && [ $((NOW - AB_STARTED)) -gt "$GRACE" ] && [ $((NOW - AB_PROBE)) -ge "$PROBE_EVERY" ]; then
    AB_PROBE=$NOW
    ABPORT=$(sed -n 's/.*"astrbotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' "$BOTS_JSON" 2>/dev/null | head -n 1)
    case "${ABPORT:-}" in ''|0|*[!0-9]*) ABPORT=6185 ;; esac
    if http_alive "$ABPORT" 10; then
      AB_DOWN=0
    else
      AB_DOWN=$((AB_DOWN + 1))
      echo "[supervisor] AstrBot 控制台 $ABPORT 无 HTTP 响应（连续 $AB_DOWN/$AB_MAXDOWN 次）"
      if [ "$AB_DOWN" -ge "$AB_MAXDOWN" ]; then
        echo "[supervisor] AstrBot 进程还在但事件循环无响应，判定卡死，重启"
        stop_svc "$AB" AstrBot astrbot
        AB=""
        AB_DOWN=0
      fi
    fi
  fi

  sleep 3
done
