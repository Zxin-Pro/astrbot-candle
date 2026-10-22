# qqbot-sandbox: 首次登录自动部署入口（由 libtermux-bootstrap.so 注入）
if [ -z "$QQBOT_SANDBOX_ENTERED" ]; then
  QQBOT_SANDBOX_ENTERED=1
  export QQBOT_SANDBOX_ENTERED
  QQBOT_HOME_DIR="$HOME/.qqbot"
  if [ -f "$QQBOT_HOME_DIR/deploy.done" ]; then
    printf '\n'
    printf '  \033[36m[QQBot]\033[0m 沙盒已就绪。输入 \033[1mqqbot-help\033[0m 查看用法，\033[1mqqbot-start\033[0m 启动服务。\n'
    printf '\n'
    # 看门狗（幂等，单实例）：它负责在沙盒本体被系统回收 / 守护循环卡死后
    # 自动把整个沙盒重新拉起来。先起它，再决定要不要手动拉沙盒。
    if [ -x "$PREFIX/opt/qqbot/watchdog.sh" ]; then
      _wp="$QQBOT_HOME_DIR/watchdog.pid"
      if [ -f "$_wp" ] && kill -0 "$(cat "$_wp" 2>/dev/null)" 2>/dev/null; then :; else
        mkdir -p "$QQBOT_HOME_DIR/logs"
        nohup setsid bash "$PREFIX/opt/qqbot/watchdog.sh" >> "$QQBOT_HOME_DIR/logs/watchdog.log" 2>&1 < /dev/null &
      fi
    fi
    # 每次打开应用自动恢复机器人服务（已在运行则跳过）
    if [ -f "$QQBOT_HOME_DIR/supervisor.pid" ] && kill -0 "$(cat "$QQBOT_HOME_DIR/supervisor.pid")" 2>/dev/null; then
      printf '  \033[36m[QQBot]\033[0m 机器人服务已在运行（PID %s）。\n' "$(cat "$QQBOT_HOME_DIR/supervisor.pid")"
    elif [ -f "$QQBOT_HOME_DIR/state/offline" ]; then
      # state/offline 是「关闭进程 / 紧急下线」留下的标记（v1.2.11）。以前这里不看它，
      # 用户关掉之后一开终端（profile.d 重跑）沙盒就被悄悄拉起来。
      printf '  \033[36m[QQBot]\033[0m 已按「关闭进程」关闭，不再自动启动。要用请执行 \033[1mqqbot-start\033[0m。\n'
    else
      mkdir -p "$QQBOT_HOME_DIR/logs"
      # setsid 让 proot 脱离当前进程组，避免被上层 shell 的进程组 kill 连带杀死；
      # pid 由 start-sandbox.sh 自己写（setsid 可能 fork，$! 不可信）
      nohup setsid bash "$PREFIX/opt/qqbot/start-sandbox.sh" > "$QQBOT_HOME_DIR/logs/supervisor.log" 2>&1 < /dev/null &
      sleep 3
      if kill -0 "$(cat "$QQBOT_HOME_DIR/supervisor.pid")" 2>/dev/null; then
        printf '  \033[36m[QQBot]\033[0m 已在后台自动启动机器人服务（PID %s）。\n' "$(cat "$QQBOT_HOME_DIR/supervisor.pid")"
      else
        printf '  \033[36m[QQBot]\033[0m 自动启动失败，请手动执行 \033[1mqqbot-start\033[0m，日志：%s\n' "$QQBOT_HOME_DIR/logs/supervisor.log"
      fi
    fi
    printf '\n'
  else
    printf '\n'
    printf '  \033[36m[QQBot]\033[0m 首次启动：正在自动部署 Linux 沙盒 + AstrBot + NapCat\n'
    printf '  \033[36m[QQBot]\033[0m 日志文件：%s\n' "$QQBOT_HOME_DIR/logs/deploy.log"
    printf '  \033[36m[QQBot]\033[0m 这一步需要联网，视网速约 20~60 分钟，请保持应用在前台。\n'
    printf '\n'
    mkdir -p "$QQBOT_HOME_DIR/logs"
    bash "$PREFIX/opt/qqbot/deploy.sh" 2>&1 | tee -a "$QQBOT_HOME_DIR/logs/deploy.log"
    printf '\n'
    printf '  \033[36m[QQBot]\033[0m 部署流程结束。输入 \033[1mqqbot-help\033[0m 查看用法。\n'
    printf '\n'
  fi
fi
