#!/data/data/com.termux/files/usr/bin/bash
# 在 proot 沙盒中启动全部服务（前台，由 qqbot-start 后台拉起）
. "$PREFIX/opt/qqbot/common.sh"

# 手动启动等于解除「停止」状态：watchdog.sh 看到这个标记就会重新开始守护。
# 必须放在下面那个「已在运行就退出」的判断之前，否则运行中再点一次启动不会解除。
rm -f "$QQBOT_CACHE/svc/watchdog.off" 2>/dev/null

# 「查 pid → 清扫 → 写 pid」这段必须串行：连点两次启动会让两个 supervisor 都通过
# 下面的存活检查，随后各自把对方当残留进程清掉，最后两个都去抢 6099/6199 端口。
# 用 mkdir 原子锁，锁目录里记持有者 pid，持有者已经死了就抢过来，避免死锁。
mkdir -p "$QQBOT_HOME"
LOCK="$QQBOT_HOME/start.lock"
i=0
while ! mkdir "$LOCK" 2>/dev/null; do
  LPID=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$LPID" ] && ! kill -0 "$LPID" 2>/dev/null; then
    rm -rf "$LOCK" 2>/dev/null
    continue
  fi
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then
    echo "[start] 另一个启动流程超过 60 秒没结束，本次放弃"
    exit 1
  fi
  sleep 1
done
echo $$ > "$LOCK/pid"
# 正常路径靠 exec 前那句 rm -rf 释放锁（exec 不会触发 EXIT trap），
# 这个 trap 只是保证中途早退（比如出错）时别把锁漏掉。
trap 'rm -rf "$LOCK" 2>/dev/null' EXIT

# 已经有 supervisor 在跑就直接返回。这个脚本会被多处调用（GUI 启动按钮、
# 终端登录时的 profile.d、qqbot-start），不幂等的话会把正在跑的服务清掉重来。
if [ -f "$QQBOT_HOME/supervisor.pid" ]; then
  OLDPID=$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)
  if [ -n "$OLDPID" ] && [ -d "/proc/$OLDPID" ]; then
    echo "[start] 服务已在运行（PID $OLDPID）"
    exit 0
  fi
fi

mkdir -p "$QQBOT_CACHE/logs" "$QQBOT_PTMP" "$QQBOT_DEVDIR/shm"
chmod 1777 "$QQBOT_DEVDIR/shm" 2>/dev/null

# 同步系统时区（写 rootfs 的 /etc/localtime + /etc/timezone，并回传 TZ 值）
_tz="$(qq_tz_sync)"

# 顺手把看门狗踢起来（幂等：它自己在启动时做单实例判断）。
# 放在这里而不是只放 profile.d，是因为「重装 APK 后沙盒自动恢复」走的是
# 控制台的 startServices()，那条路径不经过登录 shell。
if [ -x "$QQBOT_OPT/watchdog.sh" ]; then
  if [ -f "$QQBOT_HOME/watchdog.pid" ] && [ -d "/proc/$(cat "$QQBOT_HOME/watchdog.pid" 2>/dev/null)" ]; then
    :
  else
    nohup setsid bash "$QQBOT_OPT/watchdog.sh" >> "$QQBOT_HOME/logs/watchdog.log" 2>&1 < /dev/null &
  fi
fi

# 启动前清掉可能残留的孤儿进程：上一次 supervisor 如果被 SIGKILL 掉，
# 它 trace 的子进程会失去 tracer 但继续占着端口，新实例就绑不上端口了。
[ -x "$QQBOT_OPT/stop-sandbox.sh" ] && bash "$QQBOT_OPT/stop-sandbox.sh" --sweep-only >/dev/null 2>&1
# 记录自己的 PID：exec 不会改变 PID，所以这里写下的就是随后 proot 的 PID，
# qqbot-stop 杀掉它即可由 --kill-on-exit 连带清理沙盒内所有进程。
# 不能用调用方的 $! 来记录：setsid 在调用者本身是进程组组长时会 fork，
# 那样 $! 指向 setsid 而不是真正的 proot。
mkdir -p "$QQBOT_HOME"
echo $$ > "$QQBOT_HOME/supervisor.pid"
# exec 会替换掉进程映像、不跑 EXIT trap，锁必须在这里手动放掉，
# 否则下一个 qqbot-start 会一直等到超时。
rm -rf "$LOCK" 2>/dev/null
unset LD_PRELOAD
export PROOT_TMP_DIR="$QQBOT_PTMP"
# 注意：绝不能加 --link2symlink。它会把 dpkg 的硬链接换成指向宿主绝对路径
# （/data/data/.../ubuntu/usr/lib/...）的 .l2s.* 符号链接，沙盒内解析不到，
# 使 libc.so.6 / ld-linux-x86-64.so.2 变悬空，之后所有 execve 都返回 ENOENT
# （dpkg 报 "unable to execute rm command for cleanup"、apt 报 "Could not exec dpkg!"）。
# Android 的 /data 是 ext4/f2fs，本来就支持硬链接。
# /usr/bin/env -i 会清空全部环境变量，代理必须在这里显式补上：
# 沙盒内常驻运行的 NapCat / AstrBot 都要用（apt / pip 已经装完了，但运行期
# 若有任何组件要联网，走这里）。no_proxy 固定带上本机地址：NapCat 与 AstrBot
# 之间的 aiocqhttp 是 127.0.0.1 上的 WebSocket，绝不能因为设了代理被转发出本机。
_px="$(qq_proxy)"
_px_env=""
if [ -n "$_px" ]; then
  _px_env="http_proxy=$_px https_proxy=$_px all_proxy=$_px HTTP_PROXY=$_px HTTPS_PROXY=$_px ALL_PROXY=$_px no_proxy=127.0.0.1,localhost,::1 NO_PROXY=127.0.0.1,localhost,::1"
fi
exec "$QQBOT_PROOT" --kill-on-exit -0 -r "$QQBOT_ROOTFS" -b /dev -b "$QQBOT_DEVDIR/shm:/dev/shm" -b /proc -b /sys \
  -b "$QQBOT_CACHE:/qqbot-cache" -b "$QQBOT_OPT:/qqbot-opt" -w /root \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  QQBOT_ARCH="$QQBOT_ARCH" _PYTHON_HOST_PLATFORM="$QQBOT_GUEST_PLATFORM" \
  TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp TZ="$_tz" $_px_env \
  /bin/bash -lc "bash /qqbot-opt/sandbox/run-all.sh"