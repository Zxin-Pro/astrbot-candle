#!/bin/bash
# 网络现场采样（在沙盒里跑，只读，不改配置）。
#
# 为什么要有这个脚本：
#   真机 09:13 那次启动，AstrBot 所有出站连接都失败，报的是
#     Cannot connect to host api.soulter.top:443 ssl:default [Device or resource busy]
#   —— 那是 connect(2) 返回 EBUSY（errno 16），不是 DNS、不是 TLS、不是证书。
#   而沙盒里事后什么都复盘不出来：aiohttp 的连接失败只进内存，proot 又把宿主机目录
#   绑到了 /dev/shm 上（崩溃报告第 25 行显示宿主侧 /dev/shm 根本不存在）。
#   所以固定采样：run-all.sh 启动时一次、之后每 10 分钟一次，追加到 netdiag.log。
#   下次再出 EBUSY，日志里直接就有 socket 状态、fd 用量、代理变量、errno。
#
# 只用 coreutils 与 /proc；没有 python3 时自动跳过 errno 那一段。
set +e

echo "=== $(date '+%F %T %Z') netdiag ==="
echo "pid=$$ ppid=$PPID TZ=${TZ:-未设置} HOME=${HOME:-}"

# 代理变量：AstrBot 的 aiohttp 用 trust_env=True，会直接吃这些变量；
# 一旦 https_proxy 指向一个已经死掉的本地端口，表现就是「连不上 + 奇怪的 errno」。
echo "proxy: http=${http_proxy:-} https=${https_proxy:-} all=${ALL_PROXY:-}${all_proxy:-} no=${no_proxy:-}${NO_PROXY:-}"

echo "nofile=$(ulimit -n 2>/dev/null) fds=$(ls /proc/self/fd 2>/dev/null | wc -l) procs=$(ls -d /proc/[0-9]* 2>/dev/null | wc -l)"

# /dev/shm 是 proot 绑进来的宿主机目录（-b <home>/.qqbot/dev/shm:/dev/shm）。
# 宿主机上这个目录可能是空的、甚至不存在，而 Linux 里一堆东西（Chromium、
# Python multiprocessing、部分 socket 实现）要往里写 —— 写不进去时内核可能回 EBUSY。
for d in /dev/shm /tmp /qqbot-cache; do
  printf '%s: %s\n' "$d" "$(df -h "$d" 2>/dev/null | tail -n 1)"
  _w=$( ( : > "$d/.netdiag.tmp" ) 2>/dev/null && { rm -f "$d/.netdiag.tmp"; echo yes; } || echo NO )
  printf '%s entries=%s writable=%s\n' "$d" "$(ls "$d" 2>/dev/null | wc -l)" "$_w"
done

echo "--- resolv.conf ---"
if [ -f /etc/resolv.conf ]; then sed -n '1,10p' /etc/resolv.conf; else echo "(无 /etc/resolv.conf)"; fi

echo "--- socket 状态（/proc/net/tcp，st 是十六进制） ---"
for f in /proc/net/tcp /proc/net/tcp6; do
  [ -r "$f" ] || continue
  awk -v tag="$(basename "$f")" 'NR>1{c[$4]++} END{for(k in c) printf "%s %s=%d\n", tag, k, c[k]}' "$f" 2>/dev/null
done
echo "sockstat: $(grep -E 'sockets:|TCP:' /proc/net/sockstat 2>/dev/null | tr '\n' ' ')"

_to=""
command -v timeout >/dev/null 2>&1 && _to="timeout 8"
echo "--- 连接探测 ---"
${_to} bash -c 'probe() { local h="$1" p="$2" out; out=$( ( exec 3<>"/dev/tcp/$h/$p" ) 2>&1 ) && echo "connect $h:$p ok" || echo "connect $h:$p 失败: ${out:-无输出}"; }; probe api.soulter.top 443; probe 223.5.5.5 443' 2>&1
echo "--- errno 探测 ---"
# 关键的一段：拿到真正的 errno 名字。EBUSY=16，EAGAIN=11，ENOSYS=38，ETIMEDOUT=110。
if command -v python3 >/dev/null 2>&1; then
  ${_to} python3 - <<'PY' 2>&1 | sed 's/^/py: /'
import errno, socket
for host, port in (("api.soulter.top", 443), ("models.dev", 443), ("223.5.5.5", 443)):
    try:
        s = socket.create_connection((host, port), 5)
        s.close()
        print(f"{host}:{port} ok")
    except OSError as e:
        print(f"{host}:{port} errno={e.errno} {errno.errorcode.get(e.errno, chr(63))} {e}")
PY
else
  echo "py: (没有 python3，跳过 errno 探测)"
fi
echo
