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

# 没部署 / 部署没走完时，这个按钮本身没有东西可修（没有 dpkg 可修），但旧文案只有
# 一句「请先完成第 2 步」，用户看完不知道手该点哪里 —— 真机报告 20261027-145000-3886
# 的留言原话就是「我尝试过修复沙盒依赖，但提示沙盒还没有部署」。分两种情形，
# 各自指名下一个该点的按钮：
#   (a) rootfs 不存在或是空的 → 还没开始部署：回主页点「开始部署」（镜像随包，不用下载）
#   (b) rootfs 在但没 dpkg     → 上次解包中途断了：点「重置部署」再来一次
if [ ! -x "$QQBOT_ROOTFS/usr/bin/dpkg" ]; then
  if [ ! -d "$QQBOT_ROOTFS" ] || [ -z "$(ls -A "$QQBOT_ROOTFS" 2>/dev/null)" ]; then
    qqerr "这个按钮是用来修「部署卡在第 3 步（apt 装包）」的，你现在还没开始部署，所以没有东西可修。"
    qqerr "下一步：回主页点「开始部署」。内置镜像（约 583 MB）已经在手机里，这一步不需要联网下载。"
  else
    qqerr "Ubuntu 容器只解压了一半（找不到 $QQBOT_ROOTFS/usr/bin/dpkg），上次部署在解包途中被打断了。"
    qqerr "下一步：回主页点「重置部署」，再点「开始部署」，让它从解包重新走一遍。"
  fi
  qqerr "如果照上面做完还是回到这里，请到 设置 → 维护 → 上传错误日志，把报告发给作者。"
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
