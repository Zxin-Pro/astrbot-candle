#!/data/data/com.termux/files/usr/bin/bash
# 步骤 3：在沙盒内安装运行时依赖
. "$PREFIX/opt/qqbot/common.sh"

# 沙盒里的输出同时落一份到 30-apt.out：失败时要能从里面捞出真正的原因，
# 而不是只给用户一句「依赖安装失败」让他自己去翻几百行日志。
set -o pipefail
OUT="$QQBOT_LOGS/30-apt.out"
mkdir -p "$QQBOT_LOGS" 2>/dev/null
qqlog "在 Ubuntu 沙盒内安装依赖（最慢的一步，通常 10~25 分钟）"
if qq_sandbox 30-apt.sh 2>&1 | tee "$OUT"; then
  qqok "沙盒依赖安装完成"
  exit 0
fi
# 优先捞带 E: / dpkg: error 前缀的行 —— 那是 apt 认定的错误；没有就退回最后几行。
REASON="$(grep -a -E '^(E:|dpkg: error|[sandbox] E:)' "$OUT" 2>/dev/null | tail -n 3 | tr '\n' ' ')"
[ -n "$REASON" ] || REASON="$(grep -a -v '^[[:space:]]*$' "$OUT" 2>/dev/null | tail -n 3 | tr '\n' ' ')"
REASON="$(printf '%s' "$REASON" | sed -e 's/  */ /g' | cut -c1-400)"
# 这一步最常见的失败是上次被打断留下半配置的 dpkg。30-apt.sh 已经会自愈并重试，
# 走到这里说明自愈也没救回来，所以把逃生口直接写进提示里。
qqerr "沙盒内依赖安装失败：${REASON:-没有留下输出}"
qqerr "完整输出见「日志」页 → 其它日志文件 → 30-apt.out；到「设置 → 维护 → 修复沙盒依赖」跑一次，再回主页点「继续部署」"
exit 1
