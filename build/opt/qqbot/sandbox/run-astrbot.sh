#!/bin/bash
# 在沙盒内启动 AstrBot
#
# 控制台（dashboard）端口由 bots.json 的顶层键 astrbotPort 决定，这里用环境变量
# DASHBOARD_PORT 传进去。为什么用环境变量而不是写 cmd_config.json 的 dashboard.port：
#   * AstrBot 的解析顺序是 环境变量 DASHBOARD_PORT > ASTRBOT_DASHBOARD_PORT >
#     cmd_config.json 的 dashboard.port（astrbot/dashboard/server.py 的
#     DashboardServer.run()），环境变量永远赢；
#   * cmd_config.json 是 AstrBot 自己会回写的文件，从外面去改它要跟运行中的
#     AstrBot 抢锁，容易写出损坏的配置或被旧值覆盖。
# 端口每次启动都重新读一遍 bots.json，所以改完端口重启服务就能生效。
#
# 下面这段解析和 Termux 侧 common.sh 的 qq_astrbot_port() 是同一套规则，故意各写
# 一份：本脚本跑在沙盒（Ubuntu）里，common.sh 是 Termux 侧的、这里根本取不到。
BOTS_JSON=/qqbot-cache/bots.json
ASTRBOT_PORT=
if [ -s "$BOTS_JSON" ]; then
  ASTRBOT_PORT=$(sed -n 's/.*"astrbotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' "$BOTS_JSON" 2>/dev/null | head -n 1)
fi
# 空 / 0（0 = "自动"）/ 非数字 / 超范围一律回退 6185，绝不能把 0 当端口用
case "${ASTRBOT_PORT:-}" in
  ''|0|*[!0-9]*) ASTRBOT_PORT=6185 ;;
esac
if [ "$ASTRBOT_PORT" -lt 1 ] || [ "$ASTRBOT_PORT" -gt 65535 ]; then
  ASTRBOT_PORT=6185
fi
export DASHBOARD_PORT="$ASTRBOT_PORT"
mkdir -p /qqbot-cache/logs
export HOME=/root
# 固定控制台初始密码（须满足 >=8 位且含大写/小写/数字，见 astrbot/core/utils/auth_password.py）
export ASTRBOT_DASHBOARD_INITIAL_PASSWORD=Astrbot123
cd /opt/AstrBot || exit 1
echo "[astrbot] 启动 AstrBot"
echo "[astrbot] 控制台端口 $DASHBOARD_PORT（来自 bots.json 的 astrbotPort，改后需重启服务生效）"
exec /opt/AstrBot/.venv/bin/python main.py >> /qqbot-cache/logs/astrbot.log 2>&1
