#!/data/data/com.termux/files/usr/bin/bash
# qqbot-sandbox 主部署脚本（幂等，可中断续跑）

. "$PREFIX/opt/qqbot/common.sh"

# 并发护栏：profile.d 里的 50-qqbot-sandbox.sh 每次新开登录 shell 都会直接跑本脚本，
# 部署期间再打开一次应用（或多开一个终端）就会并发跑第二份部署，两条 rm -rf "$ROOTFS"
# 和两批 apt 会互相踩，轻则部署失败、重则 rootfs 半残。这里用 deploy.pid 拦掉后来者。
if [ -f "$QQBOT_HOME/deploy.pid" ]; then
  OLDPID=$(cat "$QQBOT_HOME/deploy.pid" 2>/dev/null)
  if [ -n "$OLDPID" ] && [ "$OLDPID" != "$$" ] && kill -0 "$OLDPID" 2>/dev/null; then
    qqlog "已有部署在进行中 (pid $OLDPID)，本次跳过"
    exit 0
  fi
fi

# 记录自己的 PID，供 GUI / qqbot-status 判断部署是否还在跑（调用方的 $! 在
# 经过 setsid 之后不可信，所以由脚本自己写）
mkdir -p "$QQBOT_HOME/logs"
echo $$ > "$QQBOT_HOME/deploy.pid"

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock 2>/dev/null || true
fi

# 注意：绝不能用 uname 判架构（MuMu 等模拟器会把 uname 伪装成 aarch64），
# common.sh 里已按 ro.product.cpu.abi 判定并选好了对应的 proot。
case "$QQBOT_ARCH" in
  amd64|arm64) ;;
  *)
    qqerr "无法识别的设备架构（ro.product.cpu.abi=$QQBOT_ABI）"
    exit 1
    ;;
esac

qqlog "设备架构 $QQBOT_ARCH（ro.product.cpu.abi=$QQBOT_ABI）"
qqlog "proot     $QQBOT_PROOT"
qqlog "工作目录  $QQBOT_HOME"

if command -v timeout >/dev/null 2>&1; then
  if ! qq_proot_selftest; then
    qqerr "proot 自检失败：$QQBOT_PROOT"
    qqerr "该设备的架构与 proot 不匹配，部署无法继续。"
    exit 1
  fi
  qqok "proot 自检通过"
else
  qqwarn "未找到 timeout，跳过 proot 自检（步骤 10 后会补检）"
fi

STEPS="10-termux 20-rootfs 30-apt 40-qq 50-napcat 60-astrbot 65-link 70-launcher"
FAILED=""
for s in $STEPS; do
  if [ -f "$QQBOT_STEPS/$s" ]; then
    qqok "跳过已完成步骤 $s"
    continue
  fi
  qqlog "========== 步骤 $s =========="
  if bash "$QQBOT_OPT/steps/$s.sh"; then
    : > "$QQBOT_STEPS/$s"
    qqok "步骤 $s 完成"
  else
    qqerr "步骤 $s 失败"
    FAILED="$s"
    break
  fi
done

if [ -z "$FAILED" ]; then
  : > "$QQBOT_HOME/deploy.done"
  qqok "全部部署完成"
  bash "$QQBOT_OPT/steps/90-summary.sh" || true

  # 部署完成后自动拉起 NapCat 与 AstrBot，用户无需再手动点任何东西
  SUP=""
  [ -f "$QQBOT_HOME/supervisor.pid" ] && SUP="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
  if [ -n "$SUP" ] && kill -0 "$SUP" 2>/dev/null; then
    qqok "服务已在运行（supervisor pid $SUP）"
  else
    qqlog "自动启动 NapCat 与 AstrBot"
    mkdir -p "$QQBOT_LOGS" "$QQBOT_PTMP"
    # setsid 让 proot 脱离本脚本所在进程组：本脚本若被调用方的 destroy()
    # 按进程组 kill 掉，同组的 proot 会被连带杀死。pid 由 start-sandbox.sh 自己写。
    nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" >> "$QQBOT_LOGS/supervisor.log" 2>&1 < /dev/null &
    sleep 3
    qqok "服务已启动（supervisor pid $(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)）"
    qqlog "首次启动需要几分钟，可在应用「日志」页查看进度"
  fi
else
  qqerr "部署中断于 $FAILED，重新打开本应用会自动续跑"
  exit 1
fi
