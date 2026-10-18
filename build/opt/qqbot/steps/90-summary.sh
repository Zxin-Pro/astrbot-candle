#!/data/data/com.termux/files/usr/bin/bash
# 部署完成汇总
. "$PREFIX/opt/qqbot/common.sh"

printf '\n'
printf '  \033[32m========================================================\033[0m\n'
printf '  \033[32m  QQBot 沙盒部署完成\033[0m\n'
printf '  \033[32m========================================================\033[0m\n'
printf '\n'
printf '  沙盒系统   Ubuntu 24.04 (proot)  ->  %s\n' "$QQBOT_ROOTFS"
printf '  设备架构   %s (ro.product.cpu.abi=%s)\n' "$QQBOT_ARCH" "$QQBOT_ABI"
printf '  Linux QQ   /opt/QQ\n'
printf '  NapCat     /opt/QQ/resources/app/app_launcher/napcat\n'
printf '  AstrBot    /opt/AstrBot\n'
printf '  缓存/日志  %s\n' "$QQBOT_CACHE"
printf '\n'
printf '  下一步：\n'
printf '    1) 执行 \033[1mqqbot-start\033[0m 启动服务（也可直接重开本应用自动启动）\n'
# 格式串是单引号，里面的 $(...) 不会展开，端口只能靠 %s + 参数传进去
# （第 3 行 qq_astrbot_port 那处早就踩过这个坑，这里照同样的写法）。
printf '    2) 浏览器打开 \033[1mhttp://127.0.0.1:%s\033[0m，Token 填 \033[1mzhuxin\033[0m，用 QQ 扫码登录\n' "$(qq_napcat_port)"
printf '       （二维码需另一台设备扫：局域网内用电脑打开 http://<手机IP>:%s）\n' "$(qq_napcat_port)"
printf '    3) 浏览器打开 \033[1mhttp://127.0.0.1:%s\033[0m，账号 \033[1mastrbot\033[0m 密码 \033[1mAstrbot123\033[0m\n' "$(qq_astrbot_port)"
printf '    NapCat 与 AstrBot 的对接已预置，QQ 登录成功后自动连通，无需手工配置。\n'
printf '    详细说明输入 \033[1mqqbot-help\033[0m\n'
printf '\n'
