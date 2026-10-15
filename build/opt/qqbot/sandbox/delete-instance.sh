#!/bin/bash
# 删除一个 NapCat 实例：停掉它的进程并清理 QQ 副本 / 登录数据 / 日志
# 用法: delete-instance.sh <id>
set -u
ID="${1:-}"
[ -n "$ID" ] || { echo "[napcat] 缺少实例 id"; exit 1; }
RUNDIR=/qqbot-cache/run
LOGDIR=/qqbot-cache/logs
QQDIR="/opt/qqbot-inst/$ID/QQ"
PF="$RUNDIR/$ID.pid"
if [ -f "$PF" ]; then
  PG="$(cat "$PF" 2>/dev/null)"
  if [ -n "$PG" ]; then
    echo "[napcat] 停止实例 $ID（pid $PG）"
    kill -TERM -"$PG" 2>/dev/null
    kill -TERM "$PG" 2>/dev/null
    sleep 2
    kill -KILL -"$PG" 2>/dev/null
    kill -KILL "$PG" 2>/dev/null
  fi
  rm -f "$PF"
fi
# 兜底：按 cmdline 扫掉残留的 xvfb-run / Xvfb / QQ 进程
for p in /proc/[0-9]*; do
  { c=$(<"$p/cmdline"); } 2>/dev/null
  case "$c" in
    # /opt/qqbot-inst/<id>/QQ 是非当前实例的硬链接副本；当前实例（active）用的是
    # /opt/QQ 那一份，只能靠 --user-data-dir=/root/qqinst/<id>/.config/QQ 认出来。
    *"$QQDIR"*|*"/root/qqinst/$ID/"*) kill -KILL "${p#/proc/}" 2>/dev/null ;;
  esac
done
rm -rf "/opt/qqbot-inst/$ID" "/root/qqinst/$ID" "$LOGDIR/qq-$ID.log"
echo "[napcat] 已删除实例 $ID"
