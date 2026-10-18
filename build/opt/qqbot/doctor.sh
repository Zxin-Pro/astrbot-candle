#!/data/data/com.termux/files/usr/bin/bash
# 运行环境自检：只读诊断，不改任何配置。
#
# 为什么要有：NapCat 起不来时，用户在界面上只看到「NapCat 未安装 / 离线」，
# 真正的原因（Xvfb 缺库、/tmp 不可写、GLX 不可用、qq 二进制不在）躺在沙盒里，
# 截图看不到、口述也说不清。这个脚本把三档 Xvfb 参数逐个真试一遍，
# 并把 Xvfb 自己打印的报错原样列出，结果既显示在界面上，也存成 doctor.txt
# 跟着「上传错误日志」一起发给作者。
# 用法：doctor.sh
. "$PREFIX/opt/qqbot/common.sh"

OUT="$QQBOT_HOME/doctor.txt"
IN="$QQBOT_CACHE/doctor-in.sh"

cat > "$IN" <<'QQBOT_INNER'
echo "arch     : $(uname -m 2>/dev/null)"
xv="$(command -v Xvfb 2>/dev/null)"
echo "Xvfb     : ${xv:-缺失！}"
if [ -n "$xv" ]; then
  # Xvfb 没有 -version 选项（会打印 Unrecognized option），直接看文件本身。
  ls -l /usr/bin/Xvfb 2>&1 | tr -s ' ' | sed 's/^/  /'
  n=$(ldd /usr/bin/Xvfb 2>/dev/null | grep -c 'not found')
  echo "Xvfb 缺库: ${n} 个"
  ldd /usr/bin/Xvfb 2>/dev/null | grep 'not found' | head -n 8 | sed 's/^/  /'
fi
echo "/tmp     : $(ls -ld /tmp 2>&1 | tr -s ' ')"
echo "/tmp/.X11-unix: $(ls -ld /tmp/.X11-unix 2>&1 | tr -s ' ')"
if { : > /tmp/.qqbot-dtest; } 2>/dev/null; then echo "写 /tmp  : 可以"; rm -f /tmp/.qqbot-dtest; else echo "写 /tmp  : 不行"; fi
echo "剩余空间 : $(df -h / 2>/dev/null | tail -n 1 | tr -s ' ')"
echo "qq 二进制: $(ls -l /opt/QQ/qq 2>&1 | tr -s ' ')"
echo
echo "--- 逐档试起 Xvfb（每档最多 10 秒）---"
# 一律 24 位色深：8 位没有可用 GL、16 位 EGL 配不出 surface，GPU 进程会初始化失败
# 并把 QQ 一起带走（详见 run-napcat.sh 里 XVFB_VARIANTS 上面那段实测记录）。
for spec in "640x480x24|" "1280x800x24|" "640x480x24|-nolock"; do
  screen="${spec%%|*}"
  extra="${spec#*|}"
  rm -f /tmp/.X77-lock /tmp/.X11-unix/X77 2>/dev/null
  : > /tmp/xvfb-try.log 2>/dev/null
  if [ -z "$xv" ]; then echo "[$screen] 没法试：Xvfb 不存在"; continue; fi
  Xvfb :77 -screen 0 "$screen" -ac -nolisten tcp $extra > /tmp/xvfb-try.log 2>&1 &
  p=$!
  ok="否"
  i=0
  while [ "$i" -lt 100 ]; do
    if [ -S /tmp/.X11-unix/X77 ]; then ok="是"; break; fi
    kill -0 "$p" 2>/dev/null || break
    i=$((i+1))
    sleep 0.1
  done
  echo "[$screen ${extra:-（无扩展）}] 起得来: $ok"
  sed -n '1,10p' /tmp/xvfb-try.log 2>/dev/null | sed 's/^/    /'
  kill -TERM "$p" 2>/dev/null
  wait "$p" 2>/dev/null
  rm -f /tmp/.X77-lock /tmp/.X11-unix/X77 2>/dev/null
done
QQBOT_INNER

port_open() {
  (echo > "/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1 && echo '在听' || echo '没听'
}

sup_pid="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
sup_state='没有 pid 文件'
if [ -n "$sup_pid" ]; then
  if [ -d "/proc/$sup_pid" ]; then sup_state="$sup_pid 活着"; else sup_state="$sup_pid 已经没了"; fi
fi

{
  echo "===== 运行环境自检 $(date '+%F %T' 2>/dev/null) ====="
  echo "--- Termux 侧 ---"
  # proot 在 Termux 里是按架构命名的（proot-aarch64 / proot-x86_64），没有叫 proot 的，
  # 所以不能查 command -v proot —— 那会永远显示「缺失」，把人往错的方向带。
  echo "proot    : $(ls -1 "$PREFIX"/bin/proot* 2>/dev/null | tr '\n' ' ')"
  echo "沙盒目录 : $(ls -ldn "$QQBOT_ROOTFS" 2>&1 | tr -s ' ')"
  echo "已部署   : $([ -f "$QQBOT_HOME/deploy.done" ] && echo 是 || echo 否)"
  echo "supervisor: $sup_state"
  echo "watchdog : $(cat "$QQBOT_HOME/watchdog.pid" 2>/dev/null || echo 无)"
  echo "端口 6099: $(port_open 6099)"
  echo "端口 6199: $(port_open 6199)"
  echo "端口 6185: $(port_open 6185)"
  echo "NapCat 日志: $(ls -l "$QQBOT_CACHE"/logs/qq*.log 2>/dev/null | tr -s ' ' | tr '\n' ' ')"
  echo "X 相关进程:"
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    c=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)
    case "$c" in
      *Xvfb*|*/opt/QQ/qq*|*run-napcat*)
        echo "  ${p#/proc/}: $(printf '%s' "$c" | cut -c1-150)" ;;
    esac
  done | head -n 20
  echo "NapCat 守护日志尾部（supervisor.log，最后 30 行）:"
  tail -n 30 "$QQBOT_HOME/logs/supervisor.log" 2>/dev/null | sed 's/^/  /' || echo "  (无)"
  echo "QQ 日志尾部（最后 30 行）:"
  for f in "$QQBOT_CACHE"/logs/qq-*.log "$QQBOT_CACHE"/logs/qq.log; do
    [ -s "$f" ] || continue
    echo "  [$f]"
    tail -n 30 "$f" 2>/dev/null | sed 's/^/  /'
    break
  done
  echo
  echo "--- 沙盒内 ---"
  if [ -d "$QQBOT_ROOTFS/usr/bin" ]; then
    qq_proot 'bash /qqbot-cache/doctor-in.sh' 2>&1
  else
    echo "(沙盒还没解包，跳过)"
  fi
} 2>&1 | tee "$OUT"

echo
echo "自检结果已存到 $OUT（「上传错误日志」时会一起发出去）"
# 结束标记要写进文件本身：界面是靠轮询 doctor.txt 判断跑完没有的，
# 只把这句话打到终端，前端永远等不到「结束」。
echo "（自检结束）" >> "$OUT" 2>/dev/null
