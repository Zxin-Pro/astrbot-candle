#!/data/data/com.termux/files/usr/bin/bash
# 「设置 → 维护 → 修复沙盒依赖」按钮的入口：就地修好卡住的第 3 步（30-apt）。
# 单独成一个脚本，是为了让用户不必重新部署、不必重装 APK 也能救回被 dpkg 中断挡住的部署。
. "$PREFIX/opt/qqbot/common.sh"

if [ ! -x "$QQBOT_ROOTFS/usr/bin/dpkg" ]; then
  qqerr "沙盒还没建好（找不到 $QQBOT_ROOTFS/usr/bin/dpkg），请先完成第 2 步「下载并解包 Ubuntu」"
  exit 1
fi

qqlog "检查和修复沙盒里的 dpkg 状态（不会动已经装好的包）"
if qq_sandbox fix-dpkg.sh; then
  qqok "修复完成。回主页点「继续部署」就会从第 3 步接着装"
else
  qqerr "修复过程出错，详见上面的输出"
  exit 1
fi
