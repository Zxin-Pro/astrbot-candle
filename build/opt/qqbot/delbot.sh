#!/data/data/com.termux/files/usr/bin/bash
# 删除一个 NapCat 实例（QQ 号）：从 bots.json 移除，并清掉沙盒里的登录数据
# 用法: delbot.sh <id>
set -e
. "$PREFIX/opt/qqbot/common.sh"

ID="${1:-}"
if [ -z "$ID" ]; then
  echo "用法: delbot.sh <id>"
  exit 1
fi
if [ ! -s "$QQBOT_HOME/bots.json" ]; then
  echo "还没有实例配置"
  exit 1
fi

# 注意：这里绝不能用 python3 改 bots.json —— Termux 侧根本没有 python3
# （只有 proot / tar / xz / curl 等几个包），脚本会静默失败。
# 沙盒内的 Ubuntu 有 python3，所以借它的手改 /qqbot-cache/bots.json 再拷回来。
mkdir -p "$QQBOT_CACHE"
cp -f "$QQBOT_HOME/bots.json" "$QQBOT_CACHE/bots.json"

# 先摘配置：NapCat 守护进程按 bots.json 决定启停，实例从列表里消失后
# 它自己就会停进程并调用 purge 清数据。
qq_sandbox remove-instance.sh "$ID"
# 拷回改成「先写同目录临时文件再 mv 原子替换」：cp 是截断式写入，而 NapCat 守护
# 进程每 5 秒轮询一次、UI 侧 writeBots 也可能同时写，读到半截 JSON 就会误判
# 实例被删（守护进程会 purge 掉登录数据）。同目录 rename 是原子的。
TMP="$QQBOT_HOME/bots.json.tmp.$$"
cp -f "$QQBOT_CACHE/bots.json" "$TMP"
mv -f "$TMP" "$QQBOT_HOME/bots.json"

# 再立刻清一次数据（守护进程要等下一轮 5 秒轮询才 purge，这里让删除即时生效）
qq_sandbox delete-instance.sh "$ID" || true
# AstrBot 侧的平台配置也要同步（去掉这个实例对应的 aiocqhttp 端口）
qq_sandbox link.sh || true

qqok "已删除实例 $ID。重启服务后 AstrBot 的平台配置也会同步。"
