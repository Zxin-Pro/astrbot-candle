#!/data/data/com.termux/files/usr/bin/bash
# 打包一次失败现场并发给作者（由用户在界面上点「上传错误日志」才会执行）。
#
# 为什么要有这个：真机上的失败原因往往只在用户手机里能看到，截图只能看到一部分，
# 而「第 2 步 / 第 3 步失败」在模拟器上又复现不出来。把诊断、日志尾部、机型信息
# 打包上传，作者就能拿到完整现场去改代码。
#
# 隐私：只发本应用的部署日志与系统信息；bots.json 里的 token 会打码；
#       不收集任何聊天内容（本应用也拿不到 QQ 的消息）。
# 用法：upload-log.sh [版本号]
. "$PREFIX/opt/qqbot/common.sh"

URL="https://xn--15t839a.xyz/api/errdump"
VER="${1:-unknown}"
TMP="$QQBOT_CACHE/errreport.txt"
OUT="$QQBOT_CACHE/errreport.out"
RID="$(date +%Y%m%d-%H%M%S 2>/dev/null)-$RANDOM"

have() { command -v "$1" >/dev/null 2>&1; }

# ---- 1) 现场快照 ----
{
  echo "===== AstrBot 烛版 错误报告 ====="
  echo "report_id : $RID"
  echo "app_ver   : $VER"
  echo "time      : $(date "+%F %T %Z" 2>/dev/null)"
  echo "model     : $(getprop ro.product.model 2>/dev/null) / $(getprop ro.product.brand 2>/dev/null)"
  echo "android   : $(getprop ro.build.version.release 2>/dev/null) (sdk $(getprop ro.build.version.sdk 2>/dev/null))"
  echo "abi       : $(getprop ro.product.cpu.abi 2>/dev/null)"
  echo "kernel    : $(uname -a 2>/dev/null)"
  echo "home      : $HOME"
  echo "prefix    : $PREFIX"
  echo
  echo "----- 空间 -----"
  df -k "$HOME" 2>/dev/null
  echo
  echo "----- 部署步骤标记 -----"
  ls -1 "$QQBOT_STEPS" 2>/dev/null
  echo "deploy.done: $([ -f "$QQBOT_HOME/deploy.done" ] && echo yes || echo no)"
  echo
  echo "----- 目录体检 (fs-probe.txt) -----"
  cat "$QQBOT_HOME/fs-probe.txt" 2>/dev/null || echo "(无)"
  echo
  echo "----- 上次失败原因 (deploy.error) -----"
  cat "$QQBOT_HOME/deploy.error" 2>/dev/null || echo "(无)"
  echo
  echo "----- 部署日志尾部 300 行 (deploy.log) -----"
  tail -n 300 "$QQBOT_HOME/logs/deploy.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 服务日志尾部 60 行 (supervisor.log) -----"
  tail -n 60 "$QQBOT_HOME/logs/supervisor.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- bots.json（token 已打码）-----"
  sed -E "s/(\"token\"[[:space:]]*:[[:space:]]*\")[^\"]*/\1***/g" "$QQBOT_HOME/bots.json" 2>/dev/null || echo "(无)"
  echo
  echo "----- 根文件系统抽样 -----"
  ls -ldn "$QQBOT_ROOTFS" "$QQBOT_ROOTFS/var/lib/dpkg" "$QQBOT_ROOTFS/var/lib/dpkg/status" 2>/dev/null
  echo
  echo "----- 进程 -----"
  ps -eo pid,args 2>/dev/null | grep -E "proot|run-all|watchdog|Xvfb|/opt/QQ/qq" | head -n 20
  echo
  # NapCat（QQ/Electron）的日志：这里才有 "Missing X server"、GLX 失败、二维码之类
  # 真正说明问题的行。以前只有部署日志，NapCat 起不来的报告里什么线索都没有。
  echo "----- NapCat 实例日志尾部 120 行 -----"
  for f in "$QQBOT_CACHE"/logs/qq-*.log "$QQBOT_CACHE"/logs/qq.log; do
    [ -f "$f" ] || continue
    echo "### $f（$(wc -c < "$f" 2>/dev/null) 字节）"
    tail -n 120 "$f" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g'
    echo
  done
  echo "----- Xvfb 日志（每个实例一份）----- "
  for f in "$QQBOT_CACHE"/run/xvfb-*.log; do
    [ -f "$f" ] || continue
    echo "### $f"
    sed -n '1,60p' "$f" 2>/dev/null
    echo
  done
  echo "----- 沙盒自检结果 (doctor.txt) -----"
  cat "$QQBOT_HOME/doctor.txt" 2>/dev/null || echo "(还没跑过「检查运行环境」)"
} > "$TMP" 2>&1

SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
# 上限 1.5 MB：正常报告 50~200 KB，超了说明某个日志爆了，截断尾部保留。
if [ "${SZ:-0}" -gt 1572864 ]; then
  tail -c 1572864 "$TMP" > "$TMP.2" 2>/dev/null && mv -f "$TMP.2" "$TMP"
  SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
fi

if ! have curl; then
  echo "上传失败：这台手机的 Termux 里没有 curl（第 1 步没装完？）"
  exit 2
fi

# ---- 2) 上传 ----
CODE=$(curl -sS --max-time 45 -o "$OUT" -w "%{http_code}" \
  -H "Content-Type: text/plain; charset=utf-8" \
  -H "X-Qqbot-Report: 1" \
  --data-binary @"$TMP" \
  "$URL?rid=$RID&ver=$VER" 2>"$QQBOT_CACHE/errreport.err")
CURLRC=$?

if [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; then
  echo "已上传，编号 $RID（$(grep -o "\"id\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$OUT" 2>/dev/null | head -n 1)）"
  echo "作者会很感谢你 —— 报告里带了机型、系统版本和部署日志尾部。"
  exit 0
fi
CID=$(cat "$QQBOT_CACHE/errreport.err" 2>/dev/null | head -n 1)
echo "上传失败：（HTTP $CODE，curl 退出码 $CURLRC）${CID}"
echo "本机已经把报告存在 $TMP，网络恢复后可以再点一次「上传错误日志」。"
exit 1