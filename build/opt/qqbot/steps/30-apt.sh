#!/data/data/com.termux/files/usr/bin/bash
# 步骤 3：在沙盒内安装运行时依赖
. "$PREFIX/opt/qqbot/common.sh"

qqlog "在 Ubuntu 沙盒内安装依赖（最慢的一步，通常 10~25 分钟）"
if ! qq_sandbox 30-apt.sh; then
  # 这一步最常见的失败是上次被打断留下半配置的 dpkg。30-apt.sh 已经会自愈并重试，
  # 走到这里说明自愈也没救回来，所以把逃生口直接写进提示里。
  qqerr "沙盒内依赖安装失败。到「设置 → 维护 → 修复沙盒依赖」跑一次，再回主页点「继续部署」"
  exit 1
fi
qqok "沙盒依赖安装完成"
