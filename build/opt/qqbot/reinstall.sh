#!/data/data/com.termux/files/usr/bin/bash
# 重装 AstrBot / NapCat —— 控制台「设置 → 组件重装」的两个按钮走这里。
# 用法: reinstall.sh napcat | reinstall.sh astrbot
#
# 为什么先让 supervisor 停服务，而不是自己 kill：
#   服务的进程组只有 run-all.sh 自己知道（xvfb / QQ / AstrBot 都是它的子进程组）。
#   外面直接 kill 会把它们拆成孤儿——孤儿身上还挂着 proot 装的 seccomp 过滤器，
#   tracer 已经退出，于是所有被过滤的系统调用都返回 ENOSYS，而且端口一直被占着，
#   重装后新起的实例绑不上端口。
#   这里借「组件开关」的 .off 标记把「停 + 暂时不要拉起」交给 supervisor，装完再按
#   原状态把标记还原，supervisor 会在 3 秒内把服务重新拉起来（原本就关着的就保持关着）。
#
# 全程输出重定向到 $QQBOT_CACHE/logs/reinstall.log（Java 侧启动时就做了），
# 控制台日志页的「其它日志文件」下拉里能看到它。
set -u

# PREFIX 正常由 Termux 环境传入（启动器 / App 的 QqbotShell 都会给）。从 adb shell
# 或旧脚本手动调用时它是空的，写死 com.termux 会在 1.2.2 改名后指向不存在的旧路径。
# 脚本自己就在 $PREFIX/opt/qqbot/ 下，按自身位置回推最稳。
if [ -z "${PREFIX:-}" ]; then
  _self_dir="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
  PREFIX="$(cd "$_self_dir/../.." 2>/dev/null && pwd)"
fi
PREFIX="${PREFIX:-/data/data/zhuxin.astrbot/files/usr}"
COMP="${1:-}"
case "$COMP" in napcat|astrbot) ;; *) echo "用法: reinstall.sh napcat|astrbot"; exit 2 ;; esac

if ! . "$PREFIX/opt/qqbot/common.sh" 2>/dev/null; then
  echo "[reinstall] 无法加载 common.sh（PREFIX=$PREFIX）"
  exit 1
fi

# 沙盒没装好就没得重装：qq_proot 会去挂一个不存在的 rootfs。
if [ ! -d "$QQBOT_ROOTFS/usr" ]; then
  echo "[reinstall] 沙盒还没装好（$QQBOT_ROOTFS 不存在），请先在主页完成一次自动部署"
  exit 1
fi

SVC="$QQBOT_CACHE/svc"
MARK="$SVC/reinstall.on"   # 存在期间 stop-sandbox.sh 的残留清扫会让路，见该脚本的 sweep()
OFF="$SVC/$COMP.off"
mkdir -p "$SVC" "$QQBOT_CACHE/logs" 2>/dev/null

# 列出该组件当前活着的进程号。
# 沙盒是用 proot 跑的（没有 PID namespace），沙盒里的进程在宿主机 /proc 里就是同一批
# PID，Termux 侧直接扫 /proc 就能看到它们。
# 用 bash 内建 $(<file) 读 cmdline：设备上 /proc 有几千项，每个进程 fork 一次 tr 要几分钟。
svc_pids() {
  local p c
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    { c=$(<"$p/cmdline"); } 2>/dev/null
    [ -n "$c" ] || continue
    case "$COMP" in
      napcat)
        case "$c" in
          # active 实例直接用 /opt/QQ（run-napcat.sh 的 QQDIR 分支），其余实例走 /opt/qqbot-inst/<id>/QQ
          *xvfb-run*|*Xvfb*|*/opt/QQ/qq*|*/opt/qqbot-inst/*) echo "${p#/proc/}" ;;
        esac ;;
      astrbot)
        case "$c" in *AstrBot/.venv/bin/python*|*AstrBot/main.py*) echo "${p#/proc/}" ;; esac ;;
    esac
  done
}

# supervisor 是否活着。优先信 pid 文件，但文件可能被同步脚本删掉而进程还在——
# 只按文件判断就会重复拉起第二个守护循环，两个 run-all.sh 会互相抢服务。
sup_alive() {
  local p c n
  p="$QQBOT_HOME/supervisor.pid"
  if [ -s "$p" ]; then
    n=$(cat "$p" 2>/dev/null)
    if [ -n "$n" ] && [ -d "/proc/$n" ]; then return 0; fi
  fi
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    { c=$(<"$p/cmdline"); } 2>/dev/null
    case "$c" in *sandbox/run-all.sh*) return 0 ;; esac
  done
  return 1
}

kill_svc() {
  local sig="$1" p
  for p in $(svc_pids); do kill -"$sig" "$p" 2>/dev/null; done
  return 0
}

wait_gone() {
  local i=0
  while [ "$i" -lt "$1" ]; do
    [ -z "$(svc_pids)" ] && return 0
    sleep 1 2>/dev/null || sleep 1
    i=$((i + 1))
  done
  [ -z "$(svc_pids)" ]
}

echo ""
echo "[reinstall] ===== 开始重装 $COMP（$(date '+%F %T')）====="
echo "[reinstall] 组件会先停掉，装完自动恢复原来的开关状态"

PRE_OFF=0
[ -f "$OFF" ] && PRE_OFF=1
: > "$MARK"
: > "$OFF"

# 等 supervisor 停干净。run-all.sh 的循环是 3 秒一轮，找到标记后最多再等 ~12 秒
# （stop_svc 先 TERM 等 10 秒，赖着不走才 KILL 并清扫）。这里先给足时间，只在
# 还剩下东西时才自己去杀，避免每次重装白扫几十遍 /proc。
echo "[reinstall] 等待 $COMP 退出…"
if ! wait_gone 20; then
  echo "[reinstall] 还有残留进程，再等 10 秒"
  wait_gone 10 || true
fi
if [ -n "$(svc_pids)" ]; then
  echo "[reinstall] supervisor 没能停干净（容器可能没在跑），自己清理残留进程"
  kill_svc TERM
  sleep 3 2>/dev/null || true
  kill_svc KILL
  sleep 1 2>/dev/null || true
fi
if [ -n "$(svc_pids)" ]; then
  echo "[reinstall] 警告：仍有进程占用，重装可能不完整"
fi

RC=0

case "$COMP" in
  astrbot)
    # data/ 放的是用户自己的东西：cmd_config.json（模型/平台配置）、data_v4.db、
    # 已装插件。60-astrbot.sh 开头就是 rm -rf /opt/AstrBot，不先挪走就会被连锅端。
    # 挪到 rootfs 里的 /root（同一个文件系统，mv 是瞬间的重命名），装完再放回去。
    echo "[reinstall] 备份 AstrBot 的 data/ 目录"
    qq_proot "if [ -d /opt/AstrBot/data ]; then rm -rf /root/astrbot-data.bak; mv /opt/AstrBot/data /root/astrbot-data.bak && echo '[sandbox] data/ 已备份到 /root/astrbot-data.bak'; fi" || true

    echo "[reinstall] 重新安装 AstrBot（要重新建 venv 并装依赖，几分钟起步）"
    qq_sandbox "60-astrbot.sh" || RC=$?

    # 不管装成没装成都要把 data/ 放回去：装失败时用户至少不会连配置一起丢。
    qq_proot "if [ -d /root/astrbot-data.bak ]; then rm -rf /opt/AstrBot/data; mv /root/astrbot-data.bak /opt/AstrBot/data && echo '[sandbox] 用户的 data/ 已恢复'; fi" || true
    ;;
  napcat)
    # /opt/qqbot-inst/<id>/QQ 是从 /opt/QQ 用 cp -al 硬链接复制出来的实例副本，
    # 不删掉它们，重装只会作用到当前实例：50-napcat.sh 用 cp -rf 覆盖 /opt/QQ 时
    # 会断开硬链接另写新文件，其它实例还链着旧 NapCat。
    # /root/qqinst/<id> 是 QQ 的登录数据（--user-data-dir），必须保留，否则要重新扫码。
    # NapCat 自己的 config/ 不用留：run-napcat.sh 每次启动都会删掉重写
    # webui.json / onebot11.json。
    echo "[reinstall] 删除旧的 NapCat 与各实例的 QQ 副本（保留 QQ 登录数据）"
    # 不用 rm -rf：50-napcat.sh 是 set -e，中途任一步失败就会留下「napcat 目录已经没了、
    # 新的又没装进来」的状态，于是 loadNapCat.js 报 ERR_MODULE_NOT_FOUND，QQ 连 GUI
    # 都起不来（真机 1.1.22 就这样把 NapCat 弄没的）。改成先改名备份，装完再删。
    qq_proot "rm -rf /root/napcat.bak /opt/qqbot-inst; _n=/opt/QQ/resources/app/app_launcher/napcat; if [ -d \"\$_n\" ]; then mv \"\$_n\" /root/napcat.bak && echo '[sandbox] 旧的 NapCat 已备份到 /root/napcat.bak'; fi" || RC=$?

    echo "[reinstall] 重新安装 NapCat 并注入 Linux QQ"
    [ "$RC" = 0 ] && { qq_sandbox "50-napcat.sh" || RC=$?; }

    # 装完必须实际校验：set -e 只保证「脚本没中途退出」，不保证文件真的落到位置。
    _NDIR="$QQBOT_ROOTFS/opt/QQ/resources/app/app_launcher/napcat"
    if [ -f "$_NDIR/napcat.mjs" ] && [ -f "$_NDIR/package.json" ]; then
      echo "[reinstall] 校验通过：napcat.mjs 在位（$(wc -c < "$_NDIR/napcat.mjs" 2>/dev/null) 字节），package.json 在位"
    else
      echo "[reinstall] 校验失败：$_NDIR 里的 napcat.mjs / package.json 不齐"
      echo "[reinstall]   安装包：$(ls -l "$QQBOT_CACHE/NapCat.Shell.zip" 2>/dev/null || echo '缺 NapCat.Shell.zip')"
      qq_proot "echo '--- 目标目录 ---'; ls -la /opt/QQ/resources/app/app_launcher/napcat 2>&1 | head -n 20; echo '--- 解压临时目录 /tmp/napcat ---'; ls -la /tmp/napcat 2>&1 | head -n 20; echo '--- 找到的 napcat.mjs ---'; find /tmp/napcat -maxdepth 3 -name napcat.mjs 2>/dev/null | head" 2>/dev/null || true
      # 装不回去就把备份挪回原位，至少别让 NapCat 变成「目录都没有」
      if [ -d "$QQBOT_ROOTFS/root/napcat.bak" ]; then
        echo "[reinstall] 新装的不完整，回滚到重装前的 NapCat（至少还能跑）"
        qq_proot "rm -rf /opt/QQ/resources/app/app_launcher/napcat; mv /root/napcat.bak /opt/QQ/resources/app/app_launcher/napcat && echo '[sandbox] 已回滚到重装前的 NapCat'" || true
      fi
      [ "$RC" = 0 ] && RC=1
    fi
    ;;
esac

if [ "$RC" != 0 ]; then
  echo "[reinstall] 安装脚本返回 $RC，$COMP 可能没装完整（详见上面的输出）"
fi

# 重装后立刻把对接配置重建一遍：NapCat 的 onebot11.json 由 run-napcat.sh 启动时按
# bots.json 写，AstrBot 的平台列表要 link.sh 来同步。link.sh 会先停 AstrBot，
# 反正它现在也是停的，不会额外代价。
if [ "$RC" = 0 ]; then
  echo "[reinstall] 重建对接配置"
  qq_sandbox "link.sh" || echo "[reinstall] link.sh 返回非 0，可在日志里确认"
fi

# 还原开关状态：拉起来之前先删标记，否则 supervisor 会一直当成「用户关掉了」。
rm -f "$MARK" 2>/dev/null
if [ "$PRE_OFF" = 1 ]; then
  : > "$OFF"
  echo "[reinstall] $COMP 原本就是关闭状态，已保持关闭（去主页「组件开关」打开）"
else
  rm -f "$OFF" 2>/dev/null
  echo "[reinstall] 已打开开关，supervisor 会在几秒内把 $COMP 拉起来"
fi

# 容器本来没在跑时顺手拉起来：supervisor 不在的话，光删 .off 标记没人会把服务启动。
if [ "$PRE_OFF" = 0 ] && [ -f "$QQBOT_HOME/deploy.done" ]; then
  if ! sup_alive; then
    echo "[reinstall] 容器没在跑，顺手启动沙盒"
    nohup bash "$PREFIX/opt/qqbot/start-sandbox.sh" > "$QQBOT_CACHE/logs/supervisor.log" 2>&1 &
    echo $! > "$QQBOT_HOME/supervisor.pid"
    sleep 2 2>/dev/null || true
  fi
fi

STAMP=$(date '+%F %T')
echo "[reinstall] ===== 重装 $COMP 结束（$STAMP，退出码 $RC）====="
exit "$RC"

