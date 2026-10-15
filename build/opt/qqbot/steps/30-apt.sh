#!/data/data/com.termux/files/usr/bin/bash
# 步骤 3：在沙盒内安装运行时依赖
. "$PREFIX/opt/qqbot/common.sh"

qqlog "在 Ubuntu 沙盒内安装依赖（最慢的一步，通常 10~25 分钟）"
if ! qq_sandbox 30-apt.sh; then
  qqerr "沙盒内依赖安装失败"
  exit 1
fi
qqok "沙盒依赖安装完成"
