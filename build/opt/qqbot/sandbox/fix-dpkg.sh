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
