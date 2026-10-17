#!/bin/bash
# 修复「上一次部署被打断」留下的 dpkg 半配置状态。
#
# 为什么需要它：30-apt.sh 里的 apt-get install 如果正好在「包已解包、还没配置完」时被强杀
# （用户切走应用、手机休眠、系统回收进程、OOM），dpkg 数据库里就会留下半配置的包。从此任何
# apt-get install 都会在开头直接拒绝：
#   E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.
# 而部署步骤是无脑重跑的，于是用户点一百次「继续部署」都卡在第 3 步，永远出不来。
#
# 幂等、不联网也能安全执行。可以被 30-apt.sh 调用，也可以单独跑（App 的「设置 → 维护 → 修复沙盒依赖」）。
# 永远 exit 0：调用方要的是「尽力修好」，不该因为修不好就把整个部署中断掉。

export DEBIAN_FRONTEND=noninteractive

STALE=/var/lib/dpkg/updates.stale
say() { echo "[sandbox] $*"; }

# 不用 pgrep / fuser：本脚本可能跑在「procps / psmisc 还没装上」的时候，直接扫 /proc 最可靠。
qq_dpkg_busy() {
  for _c in /proc/[0-9]*/comm; do
    [ -r "$_c" ] || continue
    case "$(cat "$_c" 2>/dev/null)" in
      apt-get|apt|dpkg|dpkg-deb|dpkg-trigger|unattended-upgr*) return 0 ;;
    esac
  done
  return 1
}

qq_dirty() { [ -n "$(dpkg --audit 2>/dev/null || true)" ]; }

# apt 判断「dpkg 被中断」的唯一依据是 /var/lib/dpkg/updates/ 里有没有残留条目
# （apt-pkg/deb/dpkgpm.cc 里就是遍历这个目录，除 tmp.i 与 lock 外有一个就报 interrupted）。
qq_journal() {
  _n=0
  for _f in /var/lib/dpkg/updates/*; do
    [ -e "$_f" ] || continue
    _b=$(basename "$_f")
    case "$_b" in tmp.i|lock) continue ;; esac
    _n=$((_n + 1))
  done
  echo "$_n"
}

# dpkg --audit 里包名行形如 " libc-bin   GNU C Library: Binaries"（一个空格缩进）
qq_audit_pkgs() { dpkg --audit 2>/dev/null | awk '/^ [^ ]/ {print $1}'; }

say "检查 dpkg 状态（journal=$(qq_journal) 条，半配置包：$(qq_audit_pkgs | tr '\n' ' ')）"

# 0) 先确认 dpkg 数据库本身能写 —— 真机上出现过的形态是：
#      dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
#    这种时候后面每一条 dpkg 命令都会失败，先修权限，修不好就直说，别让用户对着同一面墙重试。
DB=/var/lib/dpkg
if [ -d "$DB" ]; then
  # status 必须普通文件：旧版 proot --link2symlink 曾把它换成指向宿主绝对路径的符号链接
  if [ -L "$DB/status" ]; then
    say "status 是符号链接（旧版 link2symlink 留下的），换回普通文件"
    rm -f "$DB/status.qqbottmp" 2>/dev/null || true
    if cp -fL "$DB/status" "$DB/status.qqbottmp" 2>/dev/null && [ -s "$DB/status.qqbottmp" ]; then
      mv -f "$DB/status.qqbottmp" "$DB/status" 2>/dev/null || true
    else
      rm -f "$DB/status.qqbottmp" 2>/dev/null || true
      say "E: status 读不出来，dpkg 数据库已损坏，只能重建根文件系统"
    fi
  fi
  rm -f "$DB/status-old" "$DB/status-new" 2>/dev/null || true
  if ( : > "$DB/.qqbot-wtest" ) 2>/dev/null; then
    rm -f "$DB/.qqbot-wtest" 2>/dev/null || true
    say "dpkg 数据库目录可写"
  else
    _we=""
    if ! { : > "$DB/.qqbot-wtest"; } 2>/tmp/.qqbot-wtest.err; then
      _we=$(cat /tmp/.qqbot-wtest.err 2>/dev/null | tail -n 1)
    fi
    rm -f /tmp/.qqbot-wtest.err 2>/dev/null || true
    say "E: $DB 不可写（${_we:-Permission denied}），加固权限"
    chmod -R 777 "$DB" 2>/dev/null || true
    if ( : > "$DB/.qqbot-wtest" ) 2>/dev/null; then
      rm -f "$DB/.qqbot-wtest" 2>/dev/null || true
      say "加固后已可写"
    else
      say "E: 连 chmod 都改不动，这个根文件系统的文件不属于当前应用，需要重建根文件系统"
    fi
  fi
  if ln "$DB/status" "$DB/.qqbot-htest" 2>/dev/null; then
    rm -f "$DB/.qqbot-htest" 2>/dev/null || true
  else
    rm -f "$DB/.qqbot-htest" 2>/dev/null || true
    say "E: 沙盒所在文件系统不支持硬链接，dpkg 无法备份 status，只能重建根文件系统"
  fi
fi

# 1) 有别的 apt/dpkg 在跑就先等它，别互相破坏
if qq_dpkg_busy; then
  say "检测到仍在运行的 apt/dpkg，等它结束（最多 180 秒）"
  _w=0
  while qq_dpkg_busy && [ "$_w" -lt 180 ]; do sleep 3; _w=$((_w + 3)); done
fi

# 2) status 被截断/丢失时，用 dpkg 自带的 status-old 兜底恢复（纯本地）
if [ ! -s /var/lib/dpkg/status ] && [ -s /var/lib/dpkg/status-old ]; then
  say "/var/lib/dpkg/status 异常，从 status-old 恢复"
  cp -f /var/lib/dpkg/status-old /var/lib/dpkg/status 2>/dev/null || true
fi

# 3) 把半配置的包配置完 —— 这是 'dpkg was interrupted' 的正解，纯本地操作
_before=$(qq_journal)
_i=1
while [ "$_i" -le 3 ]; do
  _out=$(dpkg --configure -a </dev/null 2>&1); _rc=$?
  if [ "$_rc" = "0" ]; then
    [ "$_i" -gt 1 ] && say "dpkg --configure -a 第 $_i 次成功"
    break
  fi
  say "dpkg --configure -a 第 $_i 次失败（rc=$_rc），输出末尾是："
  printf '%s\n' "$_out" | tail -n 25
  _i=$((_i + 1)); sleep 2
done

# 4) 还是脏就用 --force-all 再压一次（某些维护者脚本在容器里会因 ENOSYS 之类的怪错退出）
if qq_dirty; then
  say "常规配置没修好，改用 --force-all 再试一次"
  dpkg --configure -a --force-all </dev/null 2>&1 | tail -n 25 || true
fi

# 5) 还不行就把这个包强制拿掉，后面让 apt 重新装回来。
#    「每次都卡在同一个包」是真实存在的情形（维护者脚本与容器环境冲突），
#    一直重试 dpkg --configure 只会无限撞墙，必须换一条路。
GONE=""
if qq_dirty; then
  for _p in $(qq_audit_pkgs); do
    say "包 $_p 仍处于半配置状态，先强制移除，稍后用 apt 重装"
    if dpkg --remove --force-remove-reinstreq --force-depends "$_p" </dev/null 2>&1 | tail -n 12; then
      GONE="$GONE $_p"
    fi
  done
fi

# 6) 修复断裂依赖 / 把上一步拿掉的包装回来。没网时这两步会失败——放过它。
if [ -n "$GONE" ]; then
  say "重新安装被移除的包：$GONE"
  apt-get install -y --reinstall --no-install-recommends -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef $GONE </dev/null 2>&1 | tail -n 25 || true
fi
if qq_dirty; then
  say "还有没配置完的包，用 apt-get -f install 收尾"
  apt-get -f install -y -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef --no-install-recommends </dev/null 2>&1 | tail -n 25 || true
fi

# 7) 清残留锁：只有在确认没有 apt/dpkg 存活时才动，否则会把活进程搞坏
if ! qq_dpkg_busy; then
  rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true
fi

# 8) 清掉下到一半的 .deb（半截包会让 dpkg 报错）
rm -rf /var/cache/apt/archives/partial/* 2>/dev/null || true

# 9) 重建可能缺失的 dpkg/apt 工作目录
mkdir -p /var/lib/dpkg/updates /var/lib/dpkg/info /var/lib/dpkg/parts /var/lib/apt/lists/partial /var/cache/apt/archives/partial 2>/dev/null || true

# 10) 最后一道保险：还剩 journal 记录就把它挪走。
#     apt 只看这个目录空不空；不移走的话，不管包修没修好，apt 都会一直说 interrupted。
#     挪到 updates.stale 而不是删掉，出问题还能翻回去看。
_j=$(qq_journal)
if [ "$_j" != "0" ]; then
  _ts=$(date +%s)
  mkdir -p "$STALE" 2>/dev/null || true
  say "dpkg 还留着 $_j 条未完成的 journal 记录，挪到 $STALE 里（apt 才会认为中断已结束）"
  for _f in /var/lib/dpkg/updates/*; do
    [ -e "$_f" ] || continue
    _b=$(basename "$_f")
    case "$_b" in tmp.i|lock) continue ;; esac
    mv -f "$_f" "$STALE/$_b.$_ts" 2>/dev/null || true
  done
  say "挪完 journal=$(qq_journal) 条"
fi

# 11) 健康判定
if dpkg-query -W -f='${Package}\n' >/dev/null 2>&1; then
  if qq_dirty; then
    say "注意：仍有个别包没配置完，下面这次安装可能会失败。有问题的包是："
    dpkg --audit 2>&1 | head -n 20 || true
  else
    say "dpkg 状态正常"
  fi
else
  say "警告：dpkg 数据库仍不可用，继续尝试"
fi
say "自愈完成（开始前 journal=$_before 条，现在 $(qq_journal) 条）"
exit 0
