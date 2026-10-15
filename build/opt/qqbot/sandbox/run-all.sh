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
}

# 收到 TERM（qqbot-stop / 停止容器）时先把两个服务收干净再退，
# 免得 proot 已经退出、服务进程却还挂在半路。
cleanup() {
  local p
  for p in "$NP" "$AB"; do [ -n "$p" ] && kill -TERM -"$p" 2>/dev/null; done
  sleep 1
  for p in "$NP" "$AB"; do [ -n "$p" ] && kill -KILL -"$p" 2>/dev/null; done
  exit 0
}
trap cleanup TERM INT

echo "[supervisor] 开始守护（TZ=${TZ:-未设置}，当前时间 $(date '+%F %T %Z')）"
beat
trim_logs
( while true; do sleep 60; trim_logs; done ) &

NP=""
AB=""
NAP_WAIT=0
NAP_STARTED=0
NAP_DOWN=0
NAP_PROBE=0
AB_STARTED=0
AB_DOWN=0
AB_PROBE=0

# 宽限期：xvfb + QQ + NapCat 注入本来就要几十秒，刚起来的 180 秒内不判卡死。
GRACE=180
# 连续探测失败 3 次（每次间隔 45 秒）才判卡死，避免网络抖动式误判。
MAXDOWN=3
PROBE_EVERY=45

while true; do
  beat
  NOW=$(date +%s)

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
    if port_open "$ABPORT"; then
      AB_DOWN=0
    else
      AB_DOWN=$((AB_DOWN + 1))
      echo "[supervisor] AstrBot 控制台端口 $ABPORT 探测不通（连续 $AB_DOWN/$MAXDOWN 次）"
      if [ "$AB_DOWN" -ge "$MAXDOWN" ]; then
        echo "[supervisor] AstrBot 进程还在但控制台端口一直不通，判定卡死，重启"
        stop_svc "$AB" AstrBot astrbot
        AB=""
        AB_DOWN=0
      fi
    fi
  fi

  sleep 3
done
