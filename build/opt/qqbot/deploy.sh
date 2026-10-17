#!/data/data/com.termux/files/usr/bin/bash
# qqbot-sandbox 主部署脚本（幂等，可中断续跑）

. "$PREFIX/opt/qqbot/common.sh"

# 并发护栏：profile.d 里的 50-qqbot-sandbox.sh 每次新开登录 shell 都会直接跑本脚本，
# 部署期间再打开一次应用（或多开一个终端）就会并发跑第二份部署，两条 rm -rf "$ROOTFS"
# 和两批 apt 会互相踩，轻则部署失败、重则 rootfs 半残。
#
# 抢锁用 `set -o noclobber` 下的 > 重定向：它走 O_EXCL，是原子的，同一瞬间启动的
# 两个进程只有一个能建成文件，而且文件里立刻就有自己的 pid。
# 早先只靠 deploy.pid「先读后写」是拦不住的 —— 两个进程在同一瞬间都读到「没有 pid 文件」，
# 于是双双放行；真机上实测到过：日志里两行「发现陈旧的 deploy.pid…」交错出现，
# 两份部署同时对 dpkg 下手（一份 dpkg --configure -a 拿到了 frontend lock，
# 另一份报 rc=2 只能重试）。锁文件必须一次写成功，不能分「建文件」和「写 pid」两步。
LOCK="$QQBOT_HOME/deploy.lock"
qq_deploy_lock() {
  if ( set -o noclobber; echo $$ > "$LOCK" ) 2>/dev/null; then
    return 0
  fi
  HOLDER="$(cat "$LOCK" 2>/dev/null)"
  # 只判 kill -0 不够：Android 会复用 PID，而部署一跑就是几十分钟，
  # 记下来的 PID 很容易飘到一个毫不相干的进程上。那样新部署会直接 exit 0，
  # 而 exit 0 在校验方看来就是「成功」—— 界面显示一切正常、实际一步没跑，
  # 用户永远等不到部署完成。所以必须再确认那个 PID 真的是 deploy.sh。
  if [ -n "$HOLDER" ] && [ "$HOLDER" != "$$" ] && kill -0 "$HOLDER" 2>/dev/null \
     && tr '\0' ' ' < "/proc/$HOLDER/cmdline" 2>/dev/null | grep -q 'deploy\.sh'; then
    return 1
  fi
  qqlog "发现陈旧的部署锁（${HOLDER:-空} 已经不是部署进程了），继续本次部署"
  rm -f "$LOCK"
  ( set -o noclobber; echo $$ > "$LOCK" ) 2>/dev/null || return 1
  return 0
}
if ! qq_deploy_lock; then
  qqlog "已有部署在进行中 (pid $(cat "$LOCK" 2>/dev/null))，本次跳过"
  exit 0
fi

# 记录自己的 PID，供 GUI / qqbot-status 判断部署是否还在跑（调用方的 $! 在
# 经过 setsid 之后不可信，所以由脚本自己写）
mkdir -p "$QQBOT_HOME/logs"
echo $$ > "$QQBOT_HOME/deploy.pid"
# 上一轮留下的失败原因不能带到这一轮：否则部署正在顺利跑，界面上还挂着一条红色的旧报错。
rm -f "$QQBOT_HOME/deploy.error" 2>/dev/null || true

# 正常退出（含失败与 Ctrl-C）时抹掉自己的 pid 文件与锁，否则界面上「部署进行中」会一直挂着，
# 而它靠的就是这个文件。被 SIGKILL 时 trap 不会触发，所以上面的「陈旧锁」兜底也得留着。
cleanup_pid() {
  [ "$(cat "$QQBOT_HOME/deploy.pid" 2>/dev/null)" = "$$" ] && rm -f "$QQBOT_HOME/deploy.pid"
  # 只删自己持有的锁：万一锁已被别人（陈旧锁修复路径）接管，别把新持有者的锁删掉。
  [ "$(cat "$LOCK" 2>/dev/null)" = "$$" ] && rm -f "$LOCK"
  return 0
}
trap cleanup_pid EXIT INT TERM HUP

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock 2>/dev/null || true
fi

# 注意：绝不能用 uname 判架构（MuMu 等模拟器会把 uname 伪装成 aarch64），
# common.sh 里已按 ro.product.cpu.abi 判定并选好了对应的 proot。
case "$QQBOT_ARCH" in
  amd64|arm64) ;;
  *)
    qqerr "无法识别的设备架构（ro.product.cpu.abi=$QQBOT_ABI）"
    exit 1
    ;;
esac

qqlog "设备架构 $QQBOT_ARCH（ro.product.cpu.abi=$QQBOT_ABI）"
qqlog "proot     $QQBOT_PROOT"
qqlog "工作目录  $QQBOT_HOME"

# 全新安装时 $PREFIX/bin/proot 还不存在：Termux 的 bootstrap 里根本没有 proot，
# 它是步骤 10 用 apt 装进来的（只有 amd64 用的是随包内置的 proot-x86_64，从第一秒就在）。
# 老版本在这里无条件自检，arm64 真机上等于「拿一个还不存在的文件去自检」：
# timeout 返回 127，被误判成「架构与 proot 不匹配」直接 exit 1，部署一步都跑不起来。
# 所以只有 proot 真的存在时才做前置自检，不存在就等步骤 10 装完再检。
if [ ! -x "$QQBOT_PROOT" ]; then
  qqlog "proot 尚未安装（$QQBOT_PROOT），跳过前置自检，步骤 10 装好后会再检一次"
elif command -v timeout >/dev/null 2>&1; then
  if ! qq_proot_selftest; then
    qqerr "proot 自检失败：$QQBOT_PROOT"
    qq_proot_selftest_log
    qqerr "这台设备上的 proot 跑不起来，部署无法继续。"
    exit 1
  fi
  qqok "proot 自检通过"
else
  qqwarn "未找到 timeout，跳过 proot 自检（步骤 10 后会补检）"
fi

STEPS="10-termux 20-rootfs 30-apt 40-qq 50-napcat 60-astrbot 65-link 70-launcher"
TRIES=3

# 每个步骤最多试 TRIES 次，每次失败后先跑一遍 dpkg 自愈再重试。
# 为什么必须重试：30-apt 要连着装上百个包，手机上随时可能因为内存紧张、用户切走应用、
# 系统回收进程而中断一次。以前一失败就整体停下，用户只能自己反复点「继续部署」，
# 而每点一次都会撞同一道闸门（dpkg 半配置），看起来就像「第 3 步永远装不上」。
# 现在失败一次会自动自愈 + 重试，重试成功的话用户根本不会看到那条报错。
run_steps() {
  FAILED=""
  for s in $STEPS; do
    if [ -f "$QQBOT_STEPS/$s" ]; then
      qqok "跳过已完成步骤 $s"
      continue
    fi
    _n=0
    while : ; do
      _n=$((_n + 1))
      if [ "$_n" -eq 1 ]; then
        qqlog "========== 步骤 $s =========="
      else
        qqlog "========== 步骤 $s（第 $_n 次尝试）=========="
      fi
      if bash "$QQBOT_OPT/steps/$s.sh"; then
        : > "$QQBOT_STEPS/$s"
        qqok "步骤 $s 完成"
        break
      fi
      if [ "$_n" -ge "$TRIES" ]; then
        qqerr "步骤 $s 连续 $_n 次失败"
        FAILED="$s"
        return 1
      fi
      qqerr "步骤 $s 第 $_n 次失败，先自动自愈再重试"
      bash "$QQBOT_OPT/fix-sandbox.sh" --force || true
      sleep 3
    done
  done
  return 0
}

run_steps

# 最后一道保险：第 3 步连着三次都失败，说明沙盒根文件系统里的 dpkg 状态已经烂到修不回来
# （可能是更早的版本反复中断留下的烂摊子）。这时重建根文件系统比继续硬修快得多。
# 只做一次，避免在系统性故障（比如一直装不上）时无限重建。
if [ "$FAILED" = "30-apt" ] && [ ! -f "$QQBOT_HOME/.rootfs-rebuilt" ]; then
  # 如果是「文件系统不支持硬链接」或「根文件系统整体不可写」，重建出来的新树还是一样，
  # 白等一次 30 MB 下载 + 十几分钟。这种硬件/存储层面的问题必须直说。
  if [ -f "$QQBOT_CACHE/fs-nolink" ]; then
    qqerr "这台手机给应用分配的存储不支持硬链接，apt/dpkg 在这种存储上无法工作"
    qqerr "请确认应用装在「内部存储」（不要装到 SD 卡 / 外置存储 / 内存卡），必要时卸载重装后再部署"
    rm -f "$QQBOT_CACHE/fs-nolink" 2>/dev/null || true
    exit 1
  fi
  if [ -f "$QQBOT_CACHE/fs-nowrite" ]; then
    qqerr "沙盒目录里残留的文件不属于当前应用，dpkg 无法写入，需要重建根文件系统"
    rm -f "$QQBOT_CACHE/fs-nowrite" 2>/dev/null || true
  fi
  qqerr "第 3 步反复失败，dpkg 状态可能已经无法就地修复"
  qqlog "重建沙盒根文件系统后再来一次（需重新下载约 30 MB，之后会自动接着装依赖）"
  : > "$QQBOT_HOME/.rootfs-rebuilt"
  rm -rf "$QQBOT_ROOTFS"
  # 里面有别的用户的文件时 rm 会静默失败，先放开权限再删一次；还删不掉就只能让用户重装。
  if [ -e "$QQBOT_ROOTFS" ]; then
    qqlog "旧的沙盒目录没删干净，放开权限后再删"
    chmod -R 777 "$QQBOT_ROOTFS" 2>/dev/null || true
    rm -rf "$QQBOT_ROOTFS" 2>/dev/null || true
  fi
  if [ -e "$QQBOT_ROOTFS" ]; then
    qqerr "旧的沙盒目录删不掉（里面的文件不属于当前应用），只能卸载本应用后重新安装"
    exit 1
  fi
  rm -f "$QQBOT_STEPS/20-rootfs" "$QQBOT_STEPS/30-apt" \
        "$QQBOT_STEPS/40-qq" "$QQBOT_STEPS/50-napcat" \
        "$QQBOT_STEPS/60-astrbot" "$QQBOT_STEPS/65-link" "$QQBOT_STEPS/70-launcher"
  run_steps
fi

if [ -z "$FAILED" ]; then
  : > "$QQBOT_HOME/deploy.done"
  rm -f "$QQBOT_HOME/.rootfs-rebuilt" 2>/dev/null || true
  # 成功了就把失败记录清掉：不然界面上会一直顶着一句早就过期的报错。
  rm -f "$QQBOT_HOME/deploy.error" 2>/dev/null || true
  qqok "全部部署完成"
  bash "$QQBOT_OPT/steps/90-summary.sh" || true

  # 部署完成后自动拉起 NapCat 与 AstrBot，用户无需再手动点任何东西
  SUP=""
  [ -f "$QQBOT_HOME/supervisor.pid" ] && SUP="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
  if [ -n "$SUP" ] && kill -0 "$SUP" 2>/dev/null; then
    qqok "服务已在运行（supervisor pid $SUP）"
  else
    qqlog "自动启动 NapCat 与 AstrBot"
    mkdir -p "$QQBOT_LOGS" "$QQBOT_PTMP"
    # setsid 让 proot 脱离本脚本所在进程组：本脚本若被调用方的 destroy()
    # 按进程组 kill 掉，同组的 proot 会被连带杀死。pid 由 start-sandbox.sh 自己写。
    nohup setsid bash "$QQBOT_OPT/start-sandbox.sh" >> "$QQBOT_LOGS/supervisor.log" 2>&1 < /dev/null &
    # start-sandbox.sh 要先进 proot 再写 pid，慢的机器上 3 秒不够 —— 原来这里直接 cat，
    # 结果日志里打出「服务已启动（supervisor pid ）」，pid 是空的。等文件出现（最多 12 秒）。
    SUP=""
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
      sleep 1
      if [ -f "$QQBOT_HOME/supervisor.pid" ]; then
        SUP="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
        [ -n "$SUP" ] && break
      fi
    done
    if [ -n "$SUP" ]; then
      qqok "服务已启动（supervisor pid $SUP）"
    else
      qqok "服务已启动（沙盒还在拉起，稍等一会儿刷新页面）"
    fi
    qqlog "首次启动需要几分钟，可在应用「日志」页查看进度"
  fi
else
  qqerr "部署中断于 $FAILED，重新打开本应用会自动续跑"
  exit 1
fi
