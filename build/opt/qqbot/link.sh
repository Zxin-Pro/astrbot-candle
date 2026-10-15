#!/data/data/com.termux/files/usr/bin/bash
# 一键把 NapCat 与 AstrBot 对接（支持多实例）
set -e
. "$PREFIX/opt/qqbot/common.sh"

mkdir -p "$QQBOT_CACHE/logs" "$QQBOT_HOME"

if [ ! -s "$QQBOT_HOME/bots.json" ]; then
  printf '%s' '{"active":"bot1","bots":[{"id":"bot1","name":"\u4e3b\u53f7","qq":"","token":"qqbot","napcatPort":6099,"onebotPort":6199,"enable":true}]}' > "$QQBOT_HOME/bots.json"
  qqlog "已生成默认实例配置 $QQBOT_HOME/bots.json"
fi

cp -f "$QQBOT_HOME/bots.json" "$QQBOT_CACHE/bots.json"

qqlog "同步 NapCat 与 AstrBot 的对接配置"
qq_sandbox link.sh
if [ -f "$QQBOT_CACHE/svc/astrbot.off" ]; then
  qqok "对接配置已更新。AstrBot 现在被组件开关关着，打开它时会自动生效"
else
  qqok "对接配置已更新。重启服务后生效：qqbot-stop 然后 qqbot-start"
fi
