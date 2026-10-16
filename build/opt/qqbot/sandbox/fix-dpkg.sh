#!/bin/bash
# 修复「上一次部署被打断」留下的 dpkg 半配置状态。
#
# 为什么需要它：30-apt.sh 里的 apt-get install 如果正好在「包已解包、还没配置完」时被强杀
# （用户切走应用、手机休眠、系统回收进程），dpkg 数据库里就会留下半配置的包。从此任何
# apt-get install 都会在开头直接拒绝：
#   E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.
# 而部署步骤是无脑重跑的，于是用户点一百次「继续部署」都卡在第 3 步，永远出不来。
#
# 幂等、不联网也能安全执行（下面第 1~8 步全是纯本地操作）。
# 可以被 30-apt.sh 调用，也可以单独跑（App 的「设置 → 维护 → 修复沙盒依赖」）。
# 永远 exit 0：调用方要的是「尽力修好」，不该因为修不好就把整个部署中断掉。

export DEBIAN_FRONTEND=noninteractive

# 不用 pgrep / fuser：本脚本可能跑在「procps / psmisc 还没装上」的时候
# （30-apt 第一批 install 之前就是），直接扫 /proc 最可靠。
qq_dpkg_busy() {
  for _c in /proc/[0-9]*/comm; do
    [ -r "$_c" ] || continue
    case "$(cat "$_c" 2>/dev/null)" in
      apt-get|apt|dpkg|dpkg-deb|dpkg-trigger|unattended-upgr*) return 0 ;;
    esac
  done
  return 1
}

# dpkg --audit 有输出 = 有包没配置完，这正是 apt 报的「dpkg was interrupted」。
qq_dirty() { [ -n "$(dpkg --audit 2>/dev/null || true)" ]; }

echo "[sandbox] 检查 dpkg 状态"

# 1) 有别的 apt/dpkg 在跑就先等它，别互相破坏（部署被 watchdog 之类的旧进程拖住是可能的）
if qq_dpkg_busy; then
  echo "[sandbox] 检测到仍在运行的 apt/dpkg，等它结束（最多 120 秒）"
  _w=0
  while qq_dpkg_busy && [ "$_w" -lt 120 ]; do sleep 3; _w=$((_w + 3)); done
fi

# 2) status 被截断/丢失时，用 dpkg 自带的 status-old 兜底恢复（纯本地）
if [ ! -s /var/lib/dpkg/status ] && [ -s /var/lib/dpkg/status-old ]; then
  echo "[sandbox] /var/lib/dpkg/status 异常，从 status-old 恢复"
  cp -f /var/lib/dpkg/status-old /var/lib/dpkg/status
fi

# 3) 把半配置的包配置完 —— 这是 'dpkg was interrupted' 的正解，纯本地操作
if qq_dirty; then echo "[sandbox] 上次安装被打断，dpkg 里有没配置完的包，开始自愈"; fi
_i=1
while [ "$_i" -le 3 ]; do
  _out="$(dpkg --configure -a </dev/null 2>&1)"; _rc=$?
  # 维护者脚本很啰嗦，只留尾部，别把部署日志淹掉
  [ -n "$_out" ] && printf '%s\n' "$_out" | tail -n 30
  [ "$_rc" = "0" ] && break
  echo "[sandbox] dpkg --configure -a 第 $_i 次未成功（rc=$_rc），重试"
  _i=$((_i + 1)); sleep 2
done

# 4) 修复断裂依赖。没网时这一步会失败——放过它，不能因为修不好就中断部署。
if qq_dirty; then
  echo "[sandbox] 还有没配置完的包，用 apt-get -f install 收尾"
  apt-get -f install -y -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef \
          --no-install-recommends </dev/null 2>&1 | tail -n 30 || true
fi

# 5) 清残留锁：只有在确认没有 apt/dpkg 存活时才动，否则会把活进程搞坏
if ! qq_dpkg_busy; then
  rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend \
        /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null || true
fi

# 6) 清掉下到一半的 .deb（半截包会让 dpkg 报错）
rm -rf /var/cache/apt/archives/partial/* 2>/dev/null || true

# 7) 重建可能缺失的 dpkg/apt 工作目录
mkdir -p /var/lib/dpkg/updates /var/lib/dpkg/info /var/lib/dpkg/parts \
         /var/lib/apt/lists/partial /var/cache/apt/archives/partial 2>/dev/null || true

# 8) 健康判定：能枚举已装包才算修好
if dpkg-query -W -f='${Package}\n' >/dev/null 2>&1; then
  if qq_dirty; then
    echo "[sandbox] 注意：仍有个别包没配置完，下面这次安装可能会失败。有问题的包是："
    dpkg --audit 2>&1 | head -n 20 || true
  else
    echo "[sandbox] dpkg 状态正常"
  fi
else
  echo "[sandbox] 警告：dpkg 数据库仍不可用，继续尝试"
fi
exit 0
