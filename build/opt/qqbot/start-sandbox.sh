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

# $QQBOT_STATE 必须真实存在：下面要把它绑进沙盒（-b .../state:/qqbot-state），
# proot 绑定一个不存在的宿主目录会直接失败，整个沙盒就起不来了。
mkdir -p "$QQBOT_CACHE/logs" "$QQBOT_PTMP" "$QQBOT_DEVDIR/shm" "$QQBOT_STATE"
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
# v1.1.27：起沙盒之前，先用「打了补丁的 loader」跑一次最短自检（就一句 echo）。
# 补丁只是给 loader 清了节头表，正常情况下必然能过；万一某台设备的 proot 不认这份
# 替代 loader，这里立刻退回系统 loader 并留下 off 标记，绝不把「补丁不兼容」变成
# 「沙盒起不来」—— 那是比 NapCat 崩溃严重得多的故障。
if [ -n "${PROOT_LOADER:-}" ]; then
  mkdir -p "$QQBOT_LOGS" 2>/dev/null
  _probe_ok=0
  # 探针同样不能写死 /system/bin/echo（Android 16 上没有这个路径，见 common.sh 的
  # qq_proot_probe 注释）：交给共用的候选表，跑不通才判定「loader 补丁不兼容」。
  qq_proot_probe 20 qqbot-loader-probe "$QQBOT_LOGS/loader-probe.log" && _probe_ok=1
  if [ "$_probe_ok" = 1 ]; then
    echo "[start] proot loader 补丁可用（PROOT_LOADER=$PROOT_LOADER）"
  else
    echo "[start] proot loader 补丁跑不通，已退回系统 loader（以后不再尝试，除非删掉 $QQBOT_HOME/proot-loader.off）"
    echo "[start] 补丁现场里的 proot 输出见 $QQBOT_LOGS/loader-probe.log"
    unset PROOT_LOADER
    unset PROOT_LOADER_32
    : > "$QQBOT_HOME/proot-loader.off" 2>/dev/null
    rm -f "$QQBOT_PROOT_LOADER_FIX" "$QQBOT_PROOT_LOADER_FIX32" 2>/dev/null
  fi
fi
# 关于 --link2symlink：运行期必须和部署期（qq_proot）一致。
# 体检判定「这台手机的存储建不了硬链接」时（真机 Redmi / Android 16 实测 link(2)
# 直接 EPERM），qq_fsprobe 会写 $QQBOT_HOME/link2symlink，部署期的 apt/dpkg 靠它
# 才装得上包。运行期这里以前硬编码不加，于是沙盒里的 link(2) 依旧 EPERM ——
# Xvfb 起显示号第一步就是 link() 一个 /tmp/.X<n>-lock，失败立刻 Fatal 退出，
# NapCat 因此永远起不来；AstrBot 是纯 Python 用不到 link(2)，看起来就成了「好的」。
#
# 早期那条「加了会让 libc.so.6 悬空」的警告只适用于「rootfs 在 proot 内部 tar
# 解压」的旧流程：tar 会为压缩包里的硬链接调 link()，被换成指向宿主绝对路径的
# .l2s.* 符号链接，沙盒内解析不到。现在解压全在宿主侧完成，运行期只有 dpkg / Xvfb
# 这类零散 link() 会走到它。（AstrBot-Bubble-Android-App 是无条件开着它跑的。）
_l2s=""
if [ -f "$QQBOT_HOME/link2symlink" ]; then
  _l2s="--link2symlink"
elif : > "$QQBOT_HOME/.l2s-probe-$$" 2>/dev/null; then
  # 标记丢了也自愈：在宿主侧直接试一次 link(2)。写都写不了就当「支持」，
  # 交给后面 qq_fsprobe 去报真正的写入错误，不在这里抢戏。
  #
  # 探测文件名必须带 $$（v1.1.22 修）：以前固定叫 .l2s-probe / .l2s-probe2，
  # 两个进程同时启动时后到的那个 ln 会报 EEXIST，被误判成「建不了硬链接」，
  # 于是明明能建链接的机器被永久打上 --link2symlink，之后 /tmp 里一堆
  # .l2s. 残骸还删不掉。另外先 rm -f 一次，防上次被强杀留下的同名文件。
  rm -f "$QQBOT_HOME/.l2s-probe-$$" "$QQBOT_HOME/.l2s-probe2-$$" 2>/dev/null
  if ln "$QQBOT_HOME/.l2s-probe-$$" "$QQBOT_HOME/.l2s-probe2-$$" 2>/dev/null; then
    :
  else
    _l2s="--link2symlink"
    : > "$QQBOT_HOME/link2symlink" 2>/dev/null || true
    echo "[start] 这台手机的存储建不了硬链接，改用 --link2symlink（否则 Xvfb 起不来）"
  fi
  rm -f "$QQBOT_HOME/.l2s-probe-$$" "$QQBOT_HOME/.l2s-probe2-$$" 2>/dev/null
fi
# proot 的 --link2symlink 会把沙盒内的 link() 换成「符号链接 + .0001 实体」这一对
# 文件。Xvfb 建 /tmp/.X<n>-lock 走的正是 link()，于是沙盒里留下的是
# <rootfs>/tmp/.l2s..l2s..tX<n>-lock0001 这种名字 —— 沙盒内 `rm -f /tmp/.X<n>-lock`
# 根本删不到它们。攒多了以后 Xvfb 一读这些十年前的「悬空锁」就
# `Can't read lock file /tmp/.X<n>-lock` 然后 Fatal，白烧一整档参数。
# 这里在宿主侧（路径是普通的）把它们清掉，成本几乎为零。
rm -f "$QQBOT_ROOTFS"/tmp/.l2s.*-lock* "$QQBOT_ROOTFS"/tmp/.tX*-lock* 2>/dev/null
# /usr/bin/env -i 会清空全部环境变量，代理必须在这里显式补上：
# 沙盒内常驻运行的 NapCat / AstrBot 都要用（apt / pip 已经装完了，但运行期
# 若有任何组件要联网，走这里）。no_proxy 固定带上本机地址：NapCat 与 AstrBot
# 之间的 aiocqhttp 是 127.0.0.1 上的 WebSocket，绝不能因为设了代理被转发出本机。
_px="$(qq_proxy)"
_px_env=""
if [ -n "$_px" ]; then
  _px_env="http_proxy=$_px https_proxy=$_px all_proxy=$_px HTTP_PROXY=$_px HTTPS_PROXY=$_px ALL_PROXY=$_px no_proxy=127.0.0.1,localhost,::1 NO_PROXY=127.0.0.1,localhost,::1"
fi
# 紧急下线标记：走到这里说明前面所有检查都过了、沙盒确实要起来了，所以现在才算
# 「启动成功」并解除标记（协议第 4 节，原生 emergencyResume 也看这个文件）。
# 必须放在 exec 之前 —— exec 之后进程映像就被 proot 替换，再也回不到这一行。
rm -f "$QQBOT_STATE/offline" 2>/dev/null
# -b "$QQBOT_STATE:/qqbot-state"：把原生层写的状态目录（风控 risk.json / risk-<id>.flag、
# 降载 degrade、下线 offline）暴露给沙盒，run-napcat.sh 才能读到风控暂停标记。
# 没绑上也不会崩：沙盒里的 [ -f /qqbot-state/... ] 判断自然为假，行为退回到「不启用」。
exec "$QQBOT_PROOT" --kill-on-exit -0 -r "$QQBOT_ROOTFS" $_l2s -b /dev -b "$QQBOT_DEVDIR/shm:/dev/shm" -b /proc -b /sys \
  -b "$QQBOT_CACHE:/qqbot-cache" -b "$QQBOT_OPT:/qqbot-opt" -b "$QQBOT_STATE:/qqbot-state" -w /root \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  QQBOT_ARCH="$QQBOT_ARCH" _PYTHON_HOST_PLATFORM="$QQBOT_GUEST_PLATFORM" \
  TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp TZ="$_tz" $_px_env \
  /bin/bash -lc "bash /qqbot-opt/sandbox/run-all.sh"