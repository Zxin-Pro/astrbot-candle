#!/data/data/com.termux/files/usr/bin/bash
# 步骤 6.5：按 bots.json 自动把 NapCat 与 AstrBot 对接起来
# （写各实例的 onebot11.json websocketClients，并同步 AstrBot 的 aiocqhttp 平台列表）
. "$PREFIX/opt/qqbot/common.sh"

bash "$QQBOT_OPT/link.sh"
