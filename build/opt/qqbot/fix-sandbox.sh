#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 「设置 → 维护 → 修复沙盒依赖」按钮的入口：就地修好卡住的第 3 步（30-apt）。
# 单独成一个脚本，是为了让用户不必重新部署、不必重装 APK 也能救回被 dpkg 中断挡住的部署。
#
# 用法：
#   fix-sandbox.sh            手动修复（部署正在跑时拒绝执行）
#   fix-sandbox.sh --force    部署脚本内部自动重试时用，跳过那把锁
. "$PREFIX/opt/qqbot/common.sh"

FORCE=""
case "$1" in --force|-f) FORCE=1 ;; esac

if [ ! -x "$QQBOT_ROOTFS/usr/bin/dpkg" ]; then
  qqerr "沙盒还没建好（找不到 $QQBOT_ROOTFS/usr/bin/dpkg），请先完成第 2 步「下载并解包 Ubuntu」"
  exit 1
fi

# 部署正在跑的时候绝不能动 dpkg：两边同时操作 dpkg 数据库只会把状态弄得更乱，
# apt 会报 "dpkg frontend lock was locked by another process"，严重的还会让包彻底半残。
# 判活核对 cmdline，避免 PID 复用到无关进程上。
if [ -z "$FORCE" ] && [ -f "$QQBOT_HOME/deploy.pid" ]; then
  _d=$(cat "$QQBOT_HOME/deploy.pid" 2>/dev/null)
  case "$_d" in ''|*[!0-9]*) _d="" ;; esac
  if [ -n "$_d" ] && [ -d "/proc/$_d" ]; then
    _c=$(tr '\0' ' ' < "/proc/$_d/cmdline" 2>/dev/null)
    case "$_c" in
      *deploy.sh*)
        qqerr "部署正在运行中（pid $_d），现在不要去动 dpkg。等它跑完，或在主页点「停止部署」"
        exit 1 ;;
    esac
  fi
fi

qqlog "检查和修复沙盒里的 dpkg 状态（不会动已经装好的包）"
if qq_sandbox fix-dpkg.sh; then
  qqok "修复完成。回主页点「继续部署」就会从第 3 步接着装"
else
  qqerr "修复过程出错，详见上面的输出"
  exit 1
fi
