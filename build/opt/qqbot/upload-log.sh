#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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
  echo "----- 运行环境 -----"
  echo "page_size : $(getconf PAGE_SIZE 2>/dev/null || echo '?')"
  echo "ulimit -u : $(ulimit -u 2>/dev/null)"
  echo "ulimit -s : $(ulimit -s 2>/dev/null)"
  echo "ulimit -v : $(ulimit -v 2>/dev/null)"
  echo "nproc     : $(nproc 2>/dev/null || echo '?')"
  echo "max_map   : $(cat /proc/sys/vm/max_map_count 2>/dev/null || echo '?')"
  echo "inotify   : $(cat /proc/sys/fs/inotify/max_user_watches 2>/dev/null || echo 'unreadable')"
  echo "/dev/shm  : $(ls -ld /dev/shm 2>/dev/null || echo '(不存在)')"
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
  # 内置镜像解压失败时，真正的原因只在 tar 的 stderr 里（就是那行 "Cannot hard link to …"），
  # 以前报告只带一句「tar 返回 2」，等于没有线索，白让用户再传一次。
  echo "----- 内置镜像解压现场 -----"
  echo "BUNDLE_ARCH : $(cat "$QQBOT_OPT/BUNDLE_ARCH" 2>/dev/null || echo '(无)')"
  echo "bundle 文件 : $(ls -l "$QQBOT_HOME/bundle.tar.xz" 2>/dev/null || echo '(已删除/未落盘)')"
  echo "bundle 标记 : .bundle-ok=$([ -f "$QQBOT_HOME/.bundle-ok" ] && echo yes || echo no) .bundle-pending=$([ -f "$QQBOT_HOME/.bundle-pending" ] && echo yes || echo no) deploy.done=$([ -f "$QQBOT_HOME/deploy.done" ] && echo yes || echo no)"
  echo "rootfs 大小 : $(du -sm "$QQBOT_ROOTFS" 2>/dev/null | awk '{print $1" MB"}' || echo '?')"
  echo "rootfs 标志 : arch=$(cat "$QQBOT_ROOTFS/rootfs.arch" 2>/dev/null || echo '?') main.py=$([ -f "$QQBOT_ROOTFS/opt/AstrBot/main.py" ] && echo yes || echo no) bin/bash=$([ -x "$QQBOT_ROOTFS/bin/bash" ] && echo yes || echo no)"
  _berr="$QQBOT_CACHE/bundle-tar.err"
  if [ -s "$_berr" ]; then
    echo "--- tar stderr（$(wc -c < "$_berr" 2>/dev/null) 字节，尾部 80 行）---"
    tail -n 80 "$_berr" 2>/dev/null
    echo "--- 归类统计 ---"
    echo "Cannot hard link to : $(grep -c 'Cannot hard link to' "$_berr" 2>/dev/null || echo 0)"
    echo "Cannot create symlink : $(grep -c 'Cannot create symlink' "$_berr" 2>/dev/null || echo 0)"
    echo "Cannot open : $(grep -c 'Cannot open' "$_berr" 2>/dev/null || echo 0)"
    echo "Unexpected EOF : $(grep -c 'Unexpected EOF' "$_berr" 2>/dev/null || echo 0)"
  else
    echo "(没有 bundle-tar.err：这版还没跑过解压，或解压成功)"
  fi
  echo "--- 硬链接映射（解压时用复制补齐的别名）---"
  cat "$QQBOT_CACHE/bundle-hardlinks.txt" 2>/dev/null | head -n 20 || echo "(无)"
  echo
  echo "----- 部署日志尾部 300 行 (deploy.log) -----"
  tail -n 300 "$QQBOT_HOME/logs/deploy.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 服务日志尾部 60 行 (supervisor.log) -----"
  tail -n 60 "$QQBOT_HOME/logs/supervisor.log" 2>/dev/null || echo "(无)"
  echo
  # AstrBot 自己的 stdout/stderr 全在这份日志里（sandbox/run-astrbot.sh 最后一行
  # 用 >> 重定向）。它的「一起就退」几乎全是 Python 异常，只有这里能看到 traceback；
  # 以前报告只收 supervisor.log，那边只有一行 "AstrBot 退出：rc=1"，等于没有线索。
  echo "----- AstrBot 日志尾部 200 行 (astrbot.log) -----"
  tail -n 200 "$QQBOT_CACHE/logs/astrbot.log" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' || echo "(无)"
  echo
  # 事件循环卡死时，AstrBot 自己的 watchdog 会把**阻塞线程的完整 Python 堆栈**
  # 写进这个文件；以前报告只收 astrbot.log 尾部，而那里只有一行「lag detected」，
  # 等于知道卡了却永远不知道卡在哪（真机 2026-10-27 01:32 那次 136 秒就是这么丢的）。
  echo "----- 事件循环看门狗堆栈 (event_loop_watchdog.log) -----"
  _wdlog="$QQBOT_ROOTFS/opt/AstrBot/data/logs/event_loop_watchdog.log"
  if [ -s "$_wdlog" ]; then
    echo "### $_wdlog（$(wc -c < "$_wdlog" 2>/dev/null) 字节，修改于 $(date -r "$_wdlog" "+%F %T" 2>/dev/null)）"
    # 每次阻塞写一份完整堆栈，文件按 1MB 轮转；只取末尾 64KB/200 行，够定位最新的几次。
    tail -c 65536 "$_wdlog" 2>/dev/null | tail -n 200
  else
    echo "(无：AstrBot 只在事件循环阻塞超过 30 秒时才往里写)"
  fi
  echo
  echo "----- 沙盒看门狗日志尾部 80 行 (watchdog.log) -----"
  tail -n 80 "$QQBOT_HOME/logs/watchdog.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 网络现场采样尾部 120 行 (netdiag.log) -----"
  tail -n 120 "$QQBOT_CACHE/logs/netdiag.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 组件开关 / 心跳 / pid -----"
  echo "svc flags : $(ls -1 "$QQBOT_CACHE/svc" 2>/dev/null | tr '\n' ' ')"
  _hb=$(cat "$QQBOT_CACHE/heartbeat" 2>/dev/null)
  case "$_hb" in
    ''|*[!0-9]*) echo "heartbeat : (无)" ;;
    *) echo "heartbeat : $_hb，距今 $(( $(date +%s) - _hb )) 秒" ;;
  esac
  echo "supervisor.pid : $(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null || echo '(无)')（进程 $([ -d "/proc/$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)" ] && echo 在 || echo 不在)）"
  echo "watchdog.pid   : $(cat "$QQBOT_HOME/watchdog.pid" 2>/dev/null || echo '(无)')（进程 $([ -d "/proc/$(cat "$QQBOT_HOME/watchdog.pid" 2>/dev/null)" ] && echo 在 || echo 不在)）"
  for _pf in "$QQBOT_CACHE"/run/*.pid; do
    [ -f "$_pf" ] || continue
    _pv=$(cat "$_pf" 2>/dev/null)
    echo "$(basename "$_pf") : $_pv（进程 $([ -n "$_pv" ] && [ -d "/proc/$_pv" ] && echo 在 || echo 不在)）"
  done
  echo
  echo "----- AstrBot 自检（在沙盒里跑）-----"
  # 直接拿 venv 里的 python 探一遍：能 import 就说明依赖是好的，问题在别处；
  # 报 ImportError 就把缺的模块名打出来，一眼定位。
  # 判据必须是「沙盒里的文件」而不是宿主机上的可执行位：venv 里的 bin/python 是指向
  # /usr/bin/python3.x 的绝对符号链接，宿主上根本不存在，[ -x ] 永远为假 —— 1.3.2 的
  # 报告里因此出现「venv 不存在或不可执行」，而实际上 AstrBot 正跑在这个 venv 上。
  # pyvenv.cfg 是 venv 的真实标记，类型是普通文件，宿主侧 stat 就能判。
  if [ -f "$QQBOT_ROOTFS/opt/AstrBot/.venv/pyvenv.cfg" ]; then
    qq_proot "cd /opt/AstrBot 2>/dev/null || exit 0; ./.venv/bin/python -V 2>&1; echo '--- import astrbot ---'; ./.venv/bin/python -c 'import astrbot' 2>&1 | tail -n 12; echo '--- main.py 语法/导包 ---'; ./.venv/bin/python -c 'import ast,sys; ast.parse(open(\"main.py\",encoding=\"utf-8\").read()); print(\"main.py 语法 ok\")' 2>&1 | tail -n 6" 2>&1 | sed 's/\x1b\[[0-9;]*m//g'
  else
    echo "venv 不存在（缺 $QQBOT_ROOTFS/opt/AstrBot/.venv/pyvenv.cfg）：AstrBot 依赖可能没装好"
  fi
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
  echo "----- 崩溃现场（crashwatch 抓的）-----"
  _found_crash=0
  for f in "$QQBOT_CACHE"/crashes/crash-*.log; do
    [ -f "$f" ] || continue
    _found_crash=1
    _csz=$(wc -c < "$f" 2>/dev/null || echo 0)
    echo "### $f（${_csz} 字节，修改于 $(date -r "$f" "+%F %T" 2>/dev/null)）"
    # maps 很长但正是定位的关键：崩溃地址落在哪个段、那段是哪个 .so，全靠它。
    if [ "${_csz:-0}" -eq 0 ]; then
      echo "(空文件)"
    elif [ "${_csz:-0}" -gt 204800 ]; then
      sed -n '1,160p' "$f" 2>/dev/null
      echo "……（余下省略，全文共 $(wc -l < "$f" 2>/dev/null) 行 / ${_csz} 字节）"
    else
      cat "$f" 2>/dev/null
    fi
    echo
  done
  [ "$_found_crash" = 1 ] || echo "(没有崩溃记录：可能是正常退出，或这版还没带 crashwatch)"
  echo "----- 崩溃记录器编译日志 (cw-build.log) -----"
  tail -n 20 "$QQBOT_CACHE/crashes/cw-build.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 重装日志尾部 150 行 (reinstall.log) -----"
  # 「重装失败（退出码 1）」以前只留一个退出码，看不出死在哪一步；
  # 50-napcat.sh 是 set -e，具体哪一行失败只有这份日志记着。
  tail -n 150 "$QQBOT_CACHE/logs/reinstall.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 组件关键文件是否在位 -----"
  _APP="$QQBOT_ROOTFS/opt/QQ/resources/app"
  for p in "$_APP/app_launcher/napcat/napcat.mjs" \
           "$_APP/app_launcher/napcat/package.json" \
           "$_APP/loadNapCat.js" \
           "$_APP/package.json"; do
    if [ -f "$p" ]; then
      echo "有  $p（$(wc -c < "$p" 2>/dev/null) 字节）"
    else
      echo "缺  $p"
    fi
  done
  echo "--- app_launcher/napcat 目录内容 ---"
  ls -1 "$_APP/app_launcher/napcat" 2>/dev/null | head -n 40 || echo "(目录不存在)"
  echo "--- app/package.json 的 main 字段 ---"
  grep -o '"main"[[:space:]]*:[[:space:]]*"[^"]*"' "$_APP/package.json" 2>/dev/null || echo "(读不到)"
  echo
  echo "----- NapCat 安装包缓存 -----"
  ls -l "$QQBOT_CACHE/NapCat.Shell.zip" 2>/dev/null || echo "(缺 NapCat.Shell.zip：断网或第 2 步没下完)"
  echo "----- proot loader 补丁（v1.1.26：防 native 模块读越界节头表 SIGSEGV）-----"
  _ld="$PREFIX/libexec/proot/loader"
  if [ -f "$_ld" ]; then
    echo "系统 loader : $_ld（$(wc -c < "$_ld" 2>/dev/null) 字节，e_shoff=$(dd if="$_ld" bs=1 skip=40 count=8 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')）"
  else
    echo "系统 loader : 不存在（内置静态 proot 没有独立 loader，正常）"
  fi
  echo "PROOT_LOADER: ${PROOT_LOADER:-（未设置）}"
  if [ -n "${PROOT_LOADER:-}" ] && [ -f "${PROOT_LOADER:-}" ]; then
    echo "补丁 loader : $(wc -c < "$PROOT_LOADER" 2>/dev/null) 字节，e_shoff=$(dd if="$PROOT_LOADER" bs=1 skip=40 count=8 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')"
  fi
  echo "--- QQ 启动参数覆写 (qq-args.txt) ---"
  cat "$QQBOT_CACHE/qq-args.txt" 2>/dev/null || echo "(没有覆写，走内置阶梯)"
  echo
  echo "----- 崩溃记录目录 -----"
  ls -l "$QQBOT_CACHE/crashes" 2>/dev/null || echo "(无)"
  echo
  echo "----- 沙盒自检结果 (doctor.txt) -----"
  cat "$QQBOT_HOME/doctor.txt" 2>/dev/null || echo "(还没跑过「检查运行环境」)"
} > "$TMP" 2>&1

SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
# 上限 1.5 MB：正常报告 50~200 KB，超了说明某个日志爆了，截断尾部保留。
if [ "${SZ:-0}" -gt 1572864 ]; then
  tail -c 1572864 "$TMP" > "$TMP.2" 2>/dev/null && mv -f "$TMP.2" "$TMP"
  SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
fi

# ---- 1.4) 想给开发者的话（beta.10）----
# 界面上那句选填的附言由 Java 落盘到 $QQBOT_HOME/devnote.txt，这里把它贴到整份报告的
# 最前面（在 AstrBot 标题之前），再 cat 原报告 —— 临时文件 + mv，任何一步失败都只丢掉
# 附言，不影响后面的脱敏和上传。顺序必须在「截断之后、脱敏之前」：截断只保尾部，
# 先贴再截会被切掉；脱敏跑在合并后的整份文件上，留言里的账号 / 手机号同样要打码。
NOTE="$QQBOT_HOME/devnote.txt"
if [ -s "$NOTE" ]; then
  # 字数按字符数（不是字节数）：去掉 UTF-8 续字节再数，跟界面上的计数器口径一致
  NCH=$(printf '%s' "$(cat "$NOTE" 2>/dev/null)" | LC_ALL=C tr -d '\200-\277' | wc -c | tr -d ' ')
  case "${NCH:-0}" in ''|*[!0-9]*) NCH=0 ;; esac
  if {
        echo "===== 想给开发者的话 ====="
        cat "$NOTE" 2>/dev/null
        echo
        echo "（留言 ${NCH} 字）"
        echo
      } > "$TMP.note" 2>/dev/null && cat "$TMP" >> "$TMP.note" 2>/dev/null; then
    mv -f "$TMP.note" "$TMP" 2>/dev/null || rm -f "$TMP.note"
  else
    rm -f "$TMP.note"
  fi
fi

# ---- 1.5) 整份脱敏（v1.1.31）----
# 必须放在截断之后：先脱敏再截断的话，被切掉那一段里的命中数统计不到，
# 而且截断点可能正好落在一串被打码的字符中间。
# 端口、文件大小、时间戳、版本号都不在规则里，原样保留 —— 排查全靠它们。
N_RED=0
if type qq_redact_file >/dev/null 2>&1; then
  N_RED=$(qq_redact_file "$TMP" 2>/dev/null)
fi
case "${N_RED:-0}" in ''|*[!0-9]*) N_RED=0 ;; esac
SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)

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
  if [ "$N_RED" -gt 0 ]; then
    echo "报告已脱敏 $N_RED 处（账号 / 群号 / 手机号 / 邮箱 / 令牌 / 二维码链接）。"
  else
    echo "报告里没有发现需要脱敏的内容。"
  fi
  echo "作者会很感谢你 —— 报告里带了机型、系统版本和部署日志尾部。"
  # 传成功了才删附言；失败保留着，用户再点一次重试还能带上同一段话
  if [ -s "$NOTE" ]; then echo "附言已经放在报告最前面。"; fi
  rm -f "$NOTE" 2>/dev/null
  exit 0
fi
CID=$(cat "$QQBOT_CACHE/errreport.err" 2>/dev/null | head -n 1)
echo "上传失败：（HTTP $CODE，curl 退出码 $CURLRC）${CID}"
echo "本机已经把报告存在 $TMP，网络恢复后可以再点一次「上传错误日志」。"
exit 1