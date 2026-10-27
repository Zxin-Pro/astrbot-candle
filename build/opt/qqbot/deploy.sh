#!/data/data/zhuxin.astrbot/files/usr/bin/bash
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
  # 抢锁之后必须复核：同一瞬间可能有另一个部署进程也在走这条「修陈旧锁」的路，
  # 各自 rm 完都能把锁建起来，结果两份部署同时往同一个 bundle.tar.xz.part 里写
  # （1.2.2 真机实测到过：两个 deploy.sh、两个 curl 抢同一个文件）。
  _dl_try=0
  while [ "$_dl_try" -lt 6 ]; do
    _dl_try=$((_dl_try + 1))
    ( set -o noclobber; echo $$ > "$LOCK" ) 2>/dev/null || true
    sleep 0.3
    [ "$(cat "$LOCK" 2>/dev/null)" = "$$" ] && return 0
    HOLDER="$(cat "$LOCK" 2>/dev/null)"
    if [ -n "$HOLDER" ] && [ "$HOLDER" != "$$" ] && kill -0 "$HOLDER" 2>/dev/null \
       && tr '\0' ' ' < "/proc/$HOLDER/cmdline" 2>/dev/null | grep -q 'deploy\.sh'; then
      return 1
    fi
    rm -f "$LOCK"
  done
  return 1
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

# 1.3.1：部署要下的四个大件互不依赖，先在后台并行预取（详见 common.sh 里的说明）。
# 预取只影响「下载」：某一件没下到，步骤会照旧自己下，部署不会因此失败。
# 带内置镜像的包（$QQBOT_OPT/BUNDLE_ARCH 存在）本来就不需要下载任何东西，别去预取：
# 那会白耗几百 MB 流量，还跟解压抢 IO。
if qq_pf_on && [ ! -f "$QQBOT_OPT/BUNDLE_ARCH" ] && [ ! -f "$QQBOT_HOME/.bundle-pending" ]; then
  qq_prefetch_start
fi

# 正常退出（含失败与 Ctrl-C）时抹掉自己的 pid 文件与锁，否则界面上「部署进行中」会一直挂着，
# 而它靠的就是这个文件。被 SIGKILL 时 trap 不会触发，所以上面的「陈旧锁」兜底也得留着。
cleanup_pid() {
  # 部署结束（成功或失败）不能把预取与并行的步骤留在后台：它们还在下载/安装，白耗流量与电。
  qq_prefetch_stop
  [ -n "${PAR60:-}" ] && kill "$PAR60" 2>/dev/null
  [ "$(cat "$QQBOT_HOME/deploy.pid" 2>/dev/null)" = "$" ] && rm -f "$QQBOT_HOME/deploy.pid"
  # 只删自己持有的锁：万一锁已被别人（陈旧锁修复路径）接管，别把新持有者的锁删掉。
  [ "$(cat "$LOCK" 2>/dev/null)" = "$$" ] && rm -f "$LOCK"
  return 0
}
# 注意：INT/TERM 不能直接挂 cleanup_pid —— 它只清理文件就 return，bash 处理完信号会
# 接着往下跑，结果锁被删掉、部署其实还在继续（实测：Ctrl-C 之后日志还在滚）。
qq_deploy_abort() {
  cleanup_pid
  exit 130
}
trap cleanup_pid EXIT
trap qq_deploy_abort INT TERM HUP

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
    # beta.5：按日志关键字给具体下一步（内核拦 seccomp / 组件缺失 / 权限限制），
    # 不再一句「重新部署一次」把用户打回去重来。
    qq_proot_fail_rows
    exit 1
  fi
  qqok "proot 自检通过（档位 ${QQBOT_PROOT_MODE:-default}）"
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
# ---------------------------------------------------------------------------
# 1.3.1：60-astrbot 与 40-qq / 50-napcat 并行
#
# 60-astrbot 只依赖 30-apt 装好的 python3（解压源码 → 建 venv → pip 装依赖），
# 与 QQ 的 deb 解包、NapCat 注入完全不共用文件，所以可以提前到 40-qq 开始时并行跑，
# 把它那一段 pip 安装（MuMu 实测约 40 秒，真机更久）藏进 QQ/NapCat 的安装时间里。
# 内存不够就不并行：两条 proot 同时解包/装包，4 GB 手机上容易把系统逼到 OOM，
# 反而更慢（还会留下半配置的 dpkg 状态）。QQBOT_PARALLEL_60=0 可强制串行。
qq_par60_on() {
  case "${QQBOT_PARALLEL_60:-1}" in 0|off|no|false) return 1 ;; esac
  _p60ma="$(awk '/^MemAvailable:/{print int($2/1024); exit}' /proc/meminfo 2>/dev/null)"
  [ -n "$_p60ma" ] || return 1
  [ "$_p60ma" -ge "${QQBOT_PAR60_MIN_MB:-1200}" ]
}

run_steps() {
  FAILED=""
  PAR60=""
  for s in $STEPS; do
    # 走到 40-qq 时顺手把 60-astrbot 放到后台（见 qq_par60_on 上面那段说明）
    if [ "$s" = "40-qq" ] && [ -z "$PAR60" ] && [ ! -f "$QQBOT_STEPS/60-astrbot" ] && qq_par60_on; then
      qqlog "并行：60-astrbot 提前到后台跑（与 40-qq / 50-napcat 同时进行）"
      bash "$QQBOT_OPT/steps/60-astrbot.sh" > "$QQBOT_LOGS/60-astrbot.par.log" 2>&1 &
      PAR60=$!
    fi
    if [ -f "$QQBOT_STEPS/$s" ]; then
      qqok "跳过已完成步骤 $s"
      continue
    fi
    # 轮到 60-astrbot 时，先收并行那一份的结果，成功就直接算完成、不再跑第二遍
    if [ "$s" = "60-astrbot" ] && [ -n "$PAR60" ]; then
      qqlog "等待并行运行的 60-astrbot 收尾"
      if wait "$PAR60"; then
        : > "$QQBOT_STEPS/$s"
        qqok "步骤 $s 完成（并行）"
        PAR60=""
        continue
      fi
      qqerr "并行运行的 60-astrbot 失败，改为串行重试（日志 $QQBOT_LOGS/60-astrbot.par.log）"
      PAR60=""
    fi
    _n=0
    while : ; do
      _n=$((_n + 1))
      if [ "$_n" -eq 1 ]; then
        qqlog "========== 步骤 $s =========="
      else
        qqlog "========== 步骤 $s（第 $_n 次尝试）=========="
      fi
      _src=0
      bash "$QQBOT_OPT/steps/$s.sh" || _src=$?
      if [ "$_src" -eq 0 ]; then
        : > "$QQBOT_STEPS/$s"
        qqok "步骤 $s 完成"
        break
      fi
      # 退出码 3 = 这一步自己判定「重试也没用」（内置镜像解压出真错误、树不完整）。
      # 以前不看退出码一律重试三次：用户那边每次都要把 2 GB 的镜像重新解一遍，
      # 界面就卡在「第 2 步」十几分钟，最后还是失败。
      if [ "$_src" -eq 3 ]; then
        qqerr "步骤 $s 判定为不可重试的错误，先停下（看上面的具体原因）"
        FAILED="$s"
        return 1
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
    # 这里必须走 qq_spawn_detached，不能再裸 nohup + disown：子进程被系统杀掉时
    # bash 会在下一行之前把「deploy.sh: line N: <pid> Killed nohup setsid bash ...」
    # 打进部署日志（disown 来不及摘），上传后就变成「服务刚启动就被杀」的假警报 ——
    # 35 份真机报告里 14 份都有这一行。qq_spawn_detached 把作业塞进立刻退出的子 shell，
    # 作业表随子 shell 消亡，通知不会再打出来。真死假死仍由下面的 kill -0 判定。
    qq_spawn_detached "$QQBOT_LOGS/supervisor.log" bash "$QQBOT_OPT/start-sandbox.sh"
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
    if [ -n "$SUP" ] && kill -0 "$SUP" 2>/dev/null; then
      qqok "服务已启动（supervisor pid $SUP）"
    elif [ -n "$SUP" ]; then
      # pid 写出来了、进程却已经没了：不要假报成功（09:13 那次就是打出
      # 「服务已启动（supervisor pid 23963）」之后紧跟一行 Killed）。
      qqerr "服务启动后立刻退出（supervisor pid $SUP 已不存在）"
      qqlog "详情见 $QQBOT_LOGS/supervisor.log"
    else
      qqok "服务已启动（沙盒还在拉起，稍等一会儿刷新页面）"
    fi
    qqlog "首次启动需要几分钟，可在应用「日志」页查看进度"
  fi
else
  qqerr "部署中断于 $FAILED，重新打开本应用会自动续跑"
  exit 1
fi
