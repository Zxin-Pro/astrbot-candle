#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 步骤 7：安装 Termux 侧命令
. "$PREFIX/opt/qqbot/common.sh"

# PREFIX 为空时必须立刻停下来：mkdir -p "$PREFIX/bin" 会变成 mkdir -p "/bin"，
# 普通应用往 /bin 写一定 Permission denied，可旧代码不看结果，最后照样打印
# 「命令已安装」，用户拿到的是一台没有任何 qqbot-* 命令的手机。
if [ -z "${PREFIX:-}" ]; then
  _lf_msg="PREFIX 为空：找不到 Termux 前缀目录，qqbot-start / qqbot-stop 等命令无法安装"
  # PREFIX 坏掉时上面那行 . common.sh 多半也没 source 成功（qqerr 根本不存在），
  # 所以这里先探一下再打印，保证这条中文报错一定能出现在日志里。
  if command -v qqerr >/dev/null 2>&1; then
    qqerr "$_lf_msg"
    qqerr "这是应用环境异常（不是用户操作问题），请退出 App 重新部署；仍失败就卸载重装"
  else
    printf '\033[31m[QQBot]\033[0m %s\n' "$_lf_msg" >&2
  fi
  exit 1
fi

# 写文件统一走这里：失败就把 _LF 置 1 并打印中文原因，不再当成成功往下走。
_LF=0
mkdir -p "$PREFIX/bin" || { qqerr "建不出命令目录：$PREFIX/bin"; exit 1; }

cat > "$PREFIX/bin/qqbot-start" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-start"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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
# --user-start：这是用户亲手敲的命令，要解除「设置 → 关闭进程」留下的下线标记
nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" --user-start > "$QQBOT_LOGS/supervisor.log" 2>&1 < /dev/null &
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
# 不再把默认 Token / 面板口令打进日志：部署日志会被「上传错误日志」带进报告（v1.2.11）
echo "  NapCat WebUI     : http://127.0.0.1:$(qq_napcat_port)  （Token 见 App 的实例卡片）"
echo "  AstrBot 控制台   : http://127.0.0.1:$(qq_astrbot_port)  （账号/密码见沙盒内 /opt/AstrBot/data/cmd_config.json 的 dashboard 段）"
EOS

cat > "$PREFIX/bin/qqbot-stop" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-stop"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
. "$PREFIX/opt/qqbot/common.sh"
exec bash "$QQBOT_OPT/stop-sandbox.sh"
EOS

cat > "$PREFIX/bin/qqbot-status" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-status"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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

cat > "$PREFIX/bin/qqbot-log" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-log"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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

cat > "$PREFIX/bin/qqbot-shell" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-shell"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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

cat > "$PREFIX/bin/qqbot-help" <<'EOS' || { qqerr "写入失败：$PREFIX/bin/qqbot-help"; _LF=1; }
#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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
    2. 浏览器打开 http://127.0.0.1:$(qq_napcat_port)，Token 见 App 的实例卡片，页面会显示 QQ 登录二维码
       （二维码要在另一台设备上扫码：同一局域网内用电脑/平板打开
         http://<手机IP>:$(qq_napcat_port)，再用本机 QQ 扫码；NapCat 也会把二维码存成图片）
    3. 手机浏览器打开 http://127.0.0.1:$(qq_astrbot_port) 进入 AstrBot 控制台
       （账号/密码见沙盒内 /opt/AstrBot/data/cmd_config.json 的 dashboard 段，首次登录会要求改密）
    4. 无需再手工配置：NapCat 已预置一条指向 AstrBot 的 WebSocket 客户端，
       AstrBot 也已预置 aiocqhttp(OneBot v11) 适配器（监听 $(qq_onebot_port)）。
       QQ 扫码登录成功后会自动连通。

  说明：
    * 沙盒是 proot 模拟的 Ubuntu 24.04，不是真虚拟机，不需要 root。
    * 数据都在 ~/ubuntu 和 ~/.qqbot 下，卸载应用会一并清除。
    * 本应用基于 Termux 改造，包名为 zhuxin.astrbot（1.2.2 起；更早版本是 com.termux）。

TXT
EOS

# chmod 也可能是「文件根本没写出来」的第一现场：逐个改，失败单独报，
# 不再用一条 chmod 一长串路径（那条失败时不告诉你到底哪个文件有问题）。
for _lf in qqbot-start qqbot-stop qqbot-status qqbot-log qqbot-shell qqbot-help; do
  if [ ! -s "$PREFIX/bin/$_lf" ]; then
    qqerr "命令没写出来（文件不存在或为空）：$PREFIX/bin/$_lf"
    _LF=1
  elif ! chmod 0755 "$PREFIX/bin/$_lf" 2>/dev/null; then
    qqerr "改不动权限：$PREFIX/bin/$_lf"
    _LF=1
  fi
done

cat > "$QQBOT_HOME/env.sh" <<'EOS' || { qqerr "写入失败：$QQBOT_HOME/env.sh"; _LF=1; }
# 由 qqbot-sandbox 生成，记录关键路径
export QQBOT_HOME="$HOME/.qqbot"
export QQBOT_ROOTFS="$HOME/ubuntu"
export QQBOT_CACHE="$HOME/.qqbot/cache"
EOS

# 只有全部文件都真的写出来、权限也改成功，才算「命令已安装」。
# 旧代码无论上面成没成都打印这句，失败反而被这行绿灯盖住。
if [ "$_LF" != "0" ]; then
  qqerr "命令安装失败：上面的文件没能全部写入 $PREFIX/bin，qqbot-start 等命令不可用"
  exit 1
fi
qqok "命令已安装：qqbot-start / qqbot-stop / qqbot-status / qqbot-log / qqbot-shell / qqbot-help"
