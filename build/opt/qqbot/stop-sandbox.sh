#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 停止沙盒（Termux 侧）。GUI 的「停止」按钮、qqbot-stop 命令都走这里。
#
# 为什么不能直接 kill -9 proot：
#   proot 靠 --kill-on-exit 在退出时清理它 trace 的子进程。SIGKILL 会让它
#   来不及清理，子进程全部变成孤儿；孤儿身上还挂着 proot 装的 seccomp
#   过滤器，但 tracer 已经死了，于是所有被过滤的系统调用都返回 ENOSYS
#   （日志里会刷 "sleep: Function not implemented"），而且它们会一直占着
#   6099 / 6199 等端口，让新启动的实例绑不上端口。
# 所以这里的顺序是：先 TERM 让 proot 自己退，等它；赖着不走才 KILL；
# 最后无论如何都扫一遍残留进程。
# source 失败必须立刻退出：QQBOT_HOME / QQBOT_CACHE / QQBOT_OPT 全在 common.sh 里，
# 拿不到它们这些变量就是空的，下面 rm -f "$QQBOT_CACHE/run"/*.pid 会漂移成
# rm -f "/run"/*.pid 之类，误删本机文件。
if ! . "$PREFIX/opt/qqbot/common.sh" 2>/dev/null; then
  echo "[stop] 无法加载 common.sh（PREFIX=$PREFIX），已退出"
  exit 1
fi

PIDF="$QQBOT_HOME/supervisor.pid"

# --sweep-only：只清残留进程，不碰 supervisor.pid 指向的进程。
# start-sandbox.sh 启动前用它保证没有旧进程占着端口。
# --no-offline：不要写紧急下线标记。看门狗自己重启沙盒（stop → start，间隔几秒）
# 时用它 —— 那是脚本内部的重启，不是用户要下线，写标记会让原生层一闪而过地
# 显示「已停止」，还可能被当成「用户主动停的」而不再自动拉起。
SWEEP_ONLY=0
NO_OFFLINE=0
for _a in "$@"; do
  case "$_a" in
    --sweep-only) SWEEP_ONLY=1 ;;
    --no-offline) NO_OFFLINE=1 ;;
  esac
done

# 停止标记必须最先落盘，不能等杀完进程再写。
# 为什么：下面光「等 supervisor 退出 + 扫残留 + 等端口落」就要 4~14 秒，而看门狗
# 每 15 秒轮一次 —— 它看到 supervisor 没了就会走 restart_sandbox() 把沙盒重新拉
# 起来（实测 16:23 那次：用户点停止 3 秒后看门狗把沙盒拉起来，停止脚本反而把新
# 起来的进程当成残留扫掉，最后状态是「界面显示已停止、后台还在跑」）。
# 把标记写在最前面，看门狗下一轮就先看见开关、不再重启。
mkdir -p "$QQBOT_CACHE/svc" 2>/dev/null
if [ "$SWEEP_ONLY" = "0" ] && [ "$NO_OFFLINE" = "0" ]; then
  : > "$QQBOT_CACHE/svc/watchdog.off" 2>/dev/null
  mkdir -p "$QQBOT_STATE" 2>/dev/null
  date +%s > "$QQBOT_STATE/offline" 2>/dev/null
fi

is_sandbox_proc() {
  case "$1" in
    # proot 不能只按 "proot-x86_64" 判：部署步骤（deploy.sh 里的 qq_sandbox
    # 30/40/50/60-x.sh）和用户手动开的 qqbot-shell 会话也都是 proot，按老写法
    # 会被「启动沙盒」顺手杀掉，正在跑的部署会被拦腰砍掉。只有 supervisor 自己
    # 启动的那个沙盒（命令行里带 sandbox/run-all.sh）才是清扫目标。
    *proot-x86_64*|*/usr/bin/proot*)
      case "$1" in *sandbox/run-all.sh*) return 0 ;; *) return 1 ;; esac ;;
    *sandbox/run-all.sh*|*sandbox/run-napcat.sh*|*sandbox/run-astrbot.sh*|*xvfb-run*|*Xvfb*|*/opt/QQ/qq*|*/opt/qqbot-inst/*|*AstrBot*|*napcat.mjs*) return 0 ;;
    *) return 1 ;;
  esac
}

# 部署脚本路径下的进程（steps/ 与部署步骤用的 sandbox/ 脚本）一律放过：
# 它们正在为「把服务跑起来」做准备工作，不该被清扫掉。
is_deploy_proc() {
  case "$1" in
    */qqbot-opt/steps/*) return 0 ;;
    # 启动流程本身也放过：start-sandbox.sh 正在做 loader 探测 / 清扫 / 写 resolv.conf，
    # 这时候把它砍掉，沙盒就停在「半个」状态。
    */start-sandbox.sh*) return 0 ;;
    *) return 1 ;;
  esac
}

# supervisor.pid 指向的进程还活着吗？活着就记下来，下面的 sweep 要避开它那棵树。
# supervisor.pid 是在 exec proot 之前就写下的（start-sandbox.sh:81），所以这个 pid
# 可能是 proot，也可能还是 start-sandbox.sh 自己 —— 两种都算「正在运行的沙盒」。
SUP_PID=""
if [ -f "$PIDF" ]; then
  _supp=$(cat "$PIDF" 2>/dev/null)
  case "$_supp" in
    ''|*[!0-9]*) : ;;
    *) [ -d "/proc/$_supp" ] && SUP_PID="$_supp" ;;
  esac
fi

# 取父进程 pid。不能用 awk/sed：清扫是热路径，每进程 fork 一次在手机上要几十秒。
# /proc/<pid>/stat 的第 2 字段 comm 允许带空格和括号，所以先砍到最后一个 ") "，
# 剩下的第 1 个字段是 state、第 2 个就是 ppid。
proc_ppid() {
  local s
  { s=$(<"/proc/$1/stat"); } 2>/dev/null || { echo ""; return; }
  s=${s##*) }
  set -- $s
  echo "${2:-}"
}

# 进程是否属于 $SUP_PID 这棵树（含自己）。只在 SWEEP_ONLY 时用。
in_sup_tree() {
  local p="$1" i=0
  [ -z "$SUP_PID" ] && return 1
  while [ -n "$p" ] && [ "$p" != "0" ] && [ "$p" != "1" ] && [ "$i" -lt 12 ]; do
    [ "$p" = "$SUP_PID" ] && return 0
    p=$(proc_ppid "$p")
    i=$((i + 1))
  done
  return 1
}

# 返回杀掉的进程数
sweep() {
  local sig="$1" n=0 num c
  # 正在重装（reinstall.sh 写的标记）时一律不清扫：重装会在沙盒里跑 apt / pip /
  # 解压这类长任务，它们的命令行里带 AstrBot、napcat 这些关键字，正好命中下面的
  # 匹配表。被「启动沙盒」的 --sweep-only 顺手砍掉，重装就前功尽弃了。
  if [ -f "$QQBOT_CACHE/svc/reinstall.on" ]; then
    echo "[stop] 检测到正在重装组件，跳过残留清扫"
    return 0
  fi
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    num=${p#/proc/}
    [ "$num" = "$$" ] && continue
    [ "$num" = "$PPID" ] && continue
    # 用 bash 内建 $(<file) 读 cmdline，不要每个进程 fork 一次 tr：
    # 设备上有几千个 /proc 项，fork 版实测 8 秒才扫完 400 个，
    # 整个扫描要几分钟（启动被卡住、看着像挂死）。内建版一两秒扫完。
    # NUL 字节留在字符串里不影响 case 的子串匹配。
    { c=$(<"$p/cmdline"); } 2>/dev/null
    [ -n "$c" ] || continue
    is_sandbox_proc "$c" || continue
    is_deploy_proc "$c" && continue
    # --sweep-only 的语义是「启动前清残留」，不是「停掉正在跑的沙盒」。
    # 另一路 start-sandbox.sh 已经把 supervisor.pid 写出来、proot 正在跑的时候，
    # 它的 proot / run-all.sh / Xvfb / QQ 全都命中上面的匹配表；照扫不误就等于
    # 把刚拉起来的沙盒当场砍死，紧接着再来一路启动 —— 用户看到的就是沙盒反复
    # 重启、二维码一直换。所以只要 supervisor.pid 还指向活进程，就跳过它整棵树。
    if [ "$SWEEP_ONLY" = "1" ] && [ -n "$SUP_PID" ]; then
      [ "$num" = "$SUP_PID" ] && continue
      in_sup_tree "$num" && continue
    fi
    kill -"$sig" "$num" 2>/dev/null && n=$((n + 1))
  done
  echo "$n"
}

if [ "$SWEEP_ONLY" = "0" ] && [ -f "$PIDF" ]; then
  PID=$(cat "$PIDF" 2>/dev/null)
  if [ -n "$PID" ] && [ -d "/proc/$PID" ]; then
    # 看门狗内部重启（--no-offline）特有的竞态：它读 supervisor.pid 看到「有 pid 但
    # cmdline 不是 proot+run-all.sh」就判死，于是 TERM + KILL。可 supervisor.pid 是
    # start-sandbox.sh 在 exec proot 之前写的，此时进程还在做 loader 探测 —— 一刀下去
    # 半启动状态留在原地（真机日志 supervisor.log 里连着两遍「[start] 自动启动」、
    # deploy.sh: line 290 的「23963 Killed」都是这条路径）。启动中就不杀，让它跑完。
    if [ "$NO_OFFLINE" = "1" ]; then
      { _pc=$(<"/proc/$PID/cmdline"); } 2>/dev/null
      case "$_pc" in
        *start-sandbox.sh*)
          echo "[stop] supervisor（PID $PID）仍在启动中，跳过本轮停止"
          PID="" ;;
      esac
    fi
  fi
  if [ -n "$PID" ] && [ -d "/proc/$PID" ]; then
    echo "[stop] 通知 supervisor 退出（PID $PID）"
    kill -TERM "$PID" 2>/dev/null
    i=0
    while [ -d "/proc/$PID" ] && [ "$i" -lt 4 ]; do
      sleep 1 2>/dev/null || true
      i=$((i + 1))
      # 紧急下线要求「10 秒内 6099/6185 不可连」（协议第 4 节）：优雅窗口只给 4 秒。
      # 之前给 10 秒时实测整条停止要 19 秒才落端口 —— supervisor 的 TERM trap 会等
      # 子进程收尾才退，而子进程本来就要靠下面的 sweep 收拾，白等没有意义。
      # 端口一旦全落就立刻往下走（proot 退得比子进程收尾快是常态）。
      [ "$i" -ge 4 ] && break
      _any=1
      for _p in 6099 6185 6199; do
        qq_port_open "$_p" && { _any=0; break; }
      done
      [ "$_any" = "1" ] && break
    done
    if [ -d "/proc/$PID" ]; then
      echo "[stop] supervisor 没有响应 TERM（已等 ${i} 秒），强制结束"
      kill -KILL "$PID" 2>/dev/null
    fi
  fi
fi

sleep 1 2>/dev/null || true
N1=$(sweep TERM)
[ "$N1" -gt 0 ] 2>/dev/null && echo "[stop] 清理残留进程 $N1 个"
sleep 2 2>/dev/null || true
N2=$(sweep KILL)
[ "$N2" -gt 0 ] 2>/dev/null && echo "[stop] 强制清理残留进程 $N2 个"

# 最后再确认一次端口真的落了（协议第 4 节要求 10 秒内 6099/6185 不可连）。
# 为什么还要这一步：proot 退了、子进程却还挂在 LISTEN 上是见过的故障，
# 紧接着的 start-sandbox.sh 会绑定失败，现场表现就是「停止以后再也起不来」。
# 最多再等 10 秒，边等边补扫；仍然落不下去就如实报出来（多半是别的 App 占着）。
wait_ports_closed() {
  local deadline open p
  qq_port_open 1 >/dev/null 2>&1
  # /dev/tcp 和 nc 都没有时探测恒为「通」，那就别在这儿空转 10 秒、也别误报。
  [ "${_QQ_PORT_PROBE:-}" = "0" ] && return 0
  deadline=$(( $(date +%s) + 10 ))
  while :; do
    open=""
    for p in 6099 6185 6199; do
      qq_port_open "$p" && open="$open $p"
    done
    [ -z "$open" ] && return 0
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 1 2>/dev/null || true
    sweep KILL >/dev/null 2>&1
  done
  echo "[stop] 端口仍未释放：$open"
  return 1
}
wait_ports_closed

if [ "$SWEEP_ONLY" = "0" ]; then
  # 标记已经在脚本开头写过了（见上面的说明），这里只做收尾。
  # 但还要再补一刀：等端口的那十来秒里，看门狗有可能已经按上一轮的判断把沙盒
  # 重新拉起来了（它读开关的时机比我们写标记晚一点点）。收尾前再扫一次、再确认
  # 一次端口，把这种「刚起来又被漏掉」的进程清掉，保证界面说停了就是真停了。
  N3=$(sweep KILL)
  [ "$N3" -gt 0 ] 2>/dev/null && echo "[stop] 收尾清理残留进程 $N3 个"
  rm -f "$PIDF" 2>/dev/null
  # 正常停止 = 不是被系统杀的：把启动标记一起删掉，否则下次启动会把这次
  # 主动停止误记成「上次被系统终止」（qq_start_mark_check 只认 pid 在不在）。
  qq_start_mark_clear
  rm -f "$QQBOT_CACHE/run"/*.pid 2>/dev/null
  if ! wait_ports_closed; then
    echo "[stop] 警告：端口仍被占用，可能有别的程序的进程占着 6099/6185/6199"
  fi
  if [ "$NO_OFFLINE" = "0" ]; then
    echo "[stop] 已停止（自动守护已暂停，下次启动会恢复）"
  else
    echo "[stop] 已停止（看门狗内部重启，不写停止标记）"
  fi
fi