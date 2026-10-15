#!/data/data/com.termux/files/usr/bin/bash
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
SWEEP_ONLY=0
[ "$1" = "--sweep-only" ] && SWEEP_ONLY=1

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
    *) return 1 ;;
  esac
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
    kill -"$sig" "$num" 2>/dev/null && n=$((n + 1))
  done
  echo "$n"
}

if [ "$SWEEP_ONLY" = "0" ] && [ -f "$PIDF" ]; then
  PID=$(cat "$PIDF" 2>/dev/null)
  if [ -n "$PID" ] && [ -d "/proc/$PID" ]; then
    echo "[stop] 通知 supervisor 退出（PID $PID）"
    kill -TERM "$PID" 2>/dev/null
    i=0
    while [ -d "/proc/$PID" ] && [ "$i" -lt 30 ]; do
      sleep 1 2>/dev/null || true
      i=$((i + 1))
    done
    if [ -d "/proc/$PID" ]; then
      echo "[stop] supervisor 没有响应 TERM，强制结束"
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

if [ "$SWEEP_ONLY" = "0" ]; then
  # 给看门狗留停止开关：否则刚停掉就会被 watchdog.sh 自动拉起来。
  # start-sandbox.sh 启动时会删掉这个文件，控制台的「启动」按钮同样走那条路径。
  mkdir -p "$QQBOT_CACHE/svc" 2>/dev/null
  : > "$QQBOT_CACHE/svc/watchdog.off" 2>/dev/null
  rm -f "$PIDF" 2>/dev/null
  rm -f "$QQBOT_CACHE/run"/*.pid 2>/dev/null
  echo "[stop] 已停止（自动守护已暂停，下次启动会恢复）"
fi