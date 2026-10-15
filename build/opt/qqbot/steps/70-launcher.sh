#!/data/data/com.termux/files/usr/bin/bash
# 步骤 7：安装 Termux 侧命令
. "$PREFIX/opt/qqbot/common.sh"

mkdir -p "$PREFIX/bin"

cat > "$PREFIX/bin/qqbot-start" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
mkdir -p "$QQBOT_LOGS" "$QQBOT_CACHE/logs"
if [ -f "$QQBOT_HOME/supervisor.pid" ]; then
  OLDPID=$(cat "$QQBOT_HOME/supervisor.pid")
  if kill -0 "$OLDPID" 2>/dev/null; then
    echo "服务已在运行中（PID $OLDPID）。如需重启请先执行 qqbot-stop"
    exit 0
  fi
fi
# setsid 让 proot 脱离当前 shell 的进程组（进程组被 kill 时不会被连带杀死）；
# pid 由 start-sandbox.sh 自己写，避免 setsid fork 后 $! 指向错进程
nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" > "$QQBOT_LOGS/supervisor.log" 2>&1 < /dev/null &
sleep 5
NEWPID=$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)
if [ -n "$NEWPID" ] && kill -0 "$NEWPID" 2>/dev/null; then
  echo "已在后台启动（PID $NEWPID）"
else
  echo "启动似乎失败了，请看日志：$QQBOT_LOGS/supervisor.log"
fi
echo ""
echo "  QQ / NapCat 日志 : qqbot-log qq"
echo "  AstrBot 日志     : qqbot-log astrbot"
# heredoc 是引号形式（<<'EOS'），$(...) 生成阶段不展开、只写进脚本，
# 由生成的 qqbot-start 在运行期展开 —— 它第 8 行 . 了 common.sh，函数取得到。
echo "  NapCat WebUI     : http://127.0.0.1:$(qq_napcat_port)  （Token: qqbot）"
echo "  AstrBot 控制台   : http://127.0.0.1:$(qq_astrbot_port)  （账号 astrbot / 密码 Astrbot123，登录后请改密）"
EOS

cat > "$PREFIX/bin/qqbot-stop" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
exec bash "$QQBOT_OPT/stop-sandbox.sh"
EOS

cat > "$PREFIX/bin/qqbot-status" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
if [ -f "$QQBOT_HOME/supervisor.pid" ] && kill -0 "$(cat "$QQBOT_HOME/supervisor.pid")" 2>/dev/null; then
  echo "状态：运行中（PID $(cat "$QQBOT_HOME/supervisor.pid")）"
else
  echo "状态：未运行"
fi
echo ""
echo "--- QQ / NapCat 日志尾部 ---"
# run-napcat.sh 写的日志叫 qq-<实例id>.log（多实例时会有好几个），qq.log 只是回退
QLOG=$(ls -1t "$QQBOT_CACHE/logs"/qq-*.log 2>/dev/null | head -n 1)
[ -n "$QLOG" ] || QLOG="$QQBOT_CACHE/logs/qq.log"
tail -n 20 "$QLOG" 2>/dev/null || echo "(暂无)"
echo ""
echo "--- AstrBot 日志尾部 ---"
tail -n 20 "$QQBOT_CACHE/logs/astrbot.log" 2>/dev/null || echo "(暂无)"
EOS

cat > "$PREFIX/bin/qqbot-log" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
case "$1" in
  qq|napcat)
    # 同 qqbot-status：真实文件名是 qq-<实例id>.log，取最新那个；qq.log 作回退
    F=$(ls -1t "$QQBOT_CACHE/logs"/qq-*.log 2>/dev/null | head -n 1)
    [ -n "$F" ] || F="$QQBOT_CACHE/logs/qq.log"
    ;;
  astrbot|ab) F="$QQBOT_CACHE/logs/astrbot.log" ;;
  deploy) F="$QQBOT_LOGS/deploy.log" ;;
  *) F="$QQBOT_LOGS/supervisor.log" ;;
esac
if [ ! -f "$F" ]; then echo "日志文件不存在：$F"; exit 1; fi
echo "跟踪 $F （Ctrl+C 退出）"
tail -n 60 -f "$F"
EOS

cat > "$PREFIX/bin/qqbot-shell" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
unset LD_PRELOAD
export PROOT_TMP_DIR="$QQBOT_PTMP"
# /usr/bin/env -i 清空了环境，代理要在这里补回来（heredoc 是引号形式，
# 这段文字原样写进生成的 qqbot-shell，运行期由它自己展开）。
_px="$(qq_proxy)"
_px_env=""
if [ -n "$_px" ]; then
  _px_env="http_proxy=$_px https_proxy=$_px all_proxy=$_px HTTP_PROXY=$_px HTTPS_PROXY=$_px ALL_PROXY=$_px no_proxy=127.0.0.1,localhost,::1 NO_PROXY=127.0.0.1,localhost,::1"
fi
exec "$QQBOT_PROOT" --kill-on-exit -0 -r "$QQBOT_ROOTFS" -b /dev -b "$QQBOT_DEVDIR/shm:/dev/shm" -b /proc -b /sys -b "$QQBOT_CACHE:/qqbot-cache" -b "$QQBOT_OPT:/qqbot-opt" -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin QQBOT_ARCH="$QQBOT_ARCH" _PYTHON_HOST_PLATFORM="$QQBOT_GUEST_PLATFORM" TERM="$TERM" LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp $_px_env /bin/bash -l
EOS

cat > "$PREFIX/bin/qqbot-help" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
cat <<TXT

  QQBot 沙盒（AstrBot + NapCat）使用说明
  =====================================

  qqbot-start     启动 NapCat(QQ) 与 AstrBot（后台）
  qqbot-stop      停止全部服务
  qqbot-status    查看运行状态与日志尾部
  qqbot-log qq    实时查看 QQ / NapCat 日志
  qqbot-log astrbot   实时查看 AstrBot 日志
  qqbot-log deploy    查看部署日志
  qqbot-shell     进入 Ubuntu 沙盒命令行（exit 返回）
  qqbot-help      显示本帮助

  首次使用顺序：
    1. qqbot-start（或直接打开本应用，会自动后台启动）
    2. 浏览器打开 http://127.0.0.1:$(qq_napcat_port)，Token 填 qqbot，页面会显示 QQ 登录二维码
       （二维码要在另一台设备上扫码：同一局域网内用电脑/平板打开
         http://<手机IP>:$(qq_napcat_port)，再用本机 QQ 扫码；NapCat 也会把二维码存成图片）
    3. 手机浏览器打开 http://127.0.0.1:$(qq_astrbot_port) 进入 AstrBot 控制台
       （账号 astrbot / 密码 Astrbot123，首次登录会要求改密）
    4. 无需再手工配置：NapCat 已预置一条指向 AstrBot 的 WebSocket 客户端，
       AstrBot 也已预置 aiocqhttp(OneBot v11) 适配器（监听 $(qq_onebot_port)）。
       QQ 扫码登录成功后会自动连通。

  说明：
    * 沙盒是 proot 模拟的 Ubuntu 24.04，不是真虚拟机，不需要 root。
    * 数据都在 ~/ubuntu 和 ~/.qqbot 下，卸载应用会一并清除。
    * 本应用基于 Termux 改造，包名为 com.termux。

TXT
EOS

chmod 0755 "$PREFIX/bin/qqbot-start" "$PREFIX/bin/qqbot-stop" "$PREFIX/bin/qqbot-status" "$PREFIX/bin/qqbot-log" "$PREFIX/bin/qqbot-shell" "$PREFIX/bin/qqbot-help"

cat > "$QQBOT_HOME/env.sh" <<'EOS'
# 由 qqbot-sandbox 生成，记录关键路径
export QQBOT_HOME="$HOME/.qqbot"
export QQBOT_ROOTFS="$HOME/ubuntu"
export QQBOT_CACHE="$HOME/.qqbot/cache"
EOS

qqok "命令已安装：qqbot-start / qqbot-stop / qqbot-status / qqbot-log / qqbot-shell / qqbot-help"
