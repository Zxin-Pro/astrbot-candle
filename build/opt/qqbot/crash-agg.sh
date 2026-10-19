#!/data/data/com.termux/files/usr/bin/bash
# 崩溃聚合看板的数据生产脚本（功能 58，协议见 docs/接口约定-1.1.30.md 第 9 节）。
#
# 用法: crash-agg.sh
#   扫 crashwatch 的现场文件（$QQBOT_CRASH_DIR/*.log，兼容老的 $QQBOT_HOME/crash/*.txt），
#   按「模块名 + 归一化 pc 偏移 + 信号」归并，写 $QQBOT_HOME/cache/crash-counts.json，
#   stdout 打一行 `[crash-agg] N 类 / M 次`。
#
# 真正的逻辑在 common.sh 的 qq_crash_agg()：watchdog.sh 每 30 分钟也直接调它，
# 这个薄封装只是给「手动跑一次 / 原生层 exec」用的 —— 两边写的是同一个文件。
. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || { echo "ERR=common.sh 加载失败"; exit 1; }
qq_crash_agg
