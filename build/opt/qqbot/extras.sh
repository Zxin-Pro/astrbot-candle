#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# extras.sh - 控制台「扩展工具」的数据源（1.3.3-beta.4）
#
# 设计原则：
#   1) 只读优先：除了 clean 子命令，任何子命令都不改沙盒里的东西；
#   2) 每个子命令输出**一行 JSON**（ok 字段必存在），前端直接 JSON.parse；
#   3) 不带参数跑 = usage，方便在终端里手测；
#   4) 单条子命令必须在 3 秒内返回 —— 它是从 Android.exec() 同步调用的，
#      桥接线程被占住时页面上的其它读取全部排队。
#
# 用法：extras.sh <sysinfo|ports|procs|netdiag|env|clean|usage>
. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null
# 没有 common.sh 就什么都不能删：QQBOT_HOME/QQBOT_CACHE 为空时 rm -rf 会落到根目录。
[ -n "$QQBOT_HOME" ] || QQBOT_HOME="$HOME/.qqbot"

# ---- JSON 小工具 ----------------------------------------------------------
# 只做字符串转义：本脚本输出的值全部是文件名 / 端口 / 数字 / 短文本，
# 不嵌套对象，手拼比引 jq 可靠（沙盒里未必装了 jq，装了也慢）。
jstr() {
  printf '%s' "${1:-}" | tr -d '\r' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' ' | cut -c1-260
}
jnum() {
  case "${1:-}" in
    ''|*[!0-9.-]*) printf '0' ;;
    *) printf '%s' "$1" ;;
  esac
}
# 1 = 真
jtf() { if [ "${1:-0}" = "1" ]; then printf 'true'; else printf 'false'; fi; }

# ---- 宿主侧小工具 ---------------------------------------------------------
# 这些在 Termux 宿主里跑（不用 proot），都是毫秒级：
# /proc/loadavg、/proc/meminfo、df 在 Android 上普通应用也能读。
host_mem() {
  # 单位 KB
  local total free avail
  total=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)
  free=$(awk '/^MemFree:/{print $2}' /proc/meminfo 2>/dev/null)
  avail=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
  [ -z "$avail" ] && avail="$free"
  printf '%s %s %s' "$(jnum "${total:-0}")" "$(jnum "${free:-0}")" "$(jnum "${avail:-0}")"
}

host_load() {
  local l
  l=$(cat /proc/loadavg 2>/dev/null)
  printf '%s' "${l:-0 0 0 0/0 0}"
}

host_cores() {
  local n
  n=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null)
  [ -z "$n" ] && n=1
  printf '%s' "$n"
}

# 磁盘：优先看应用自己的数据分区（沙盒就长在这里）。单位 KB。
host_disk() {
  df -k "$HOME" 2>/dev/null | tail -n 1 | awk '{printf "%s %s %s", $2, $3, $4}'
}

# ---- sysinfo -------------------------------------------------------------
do_sysinfo() {
  local mem total free avail load cores disk dtotal dused dfree
  mem=$(host_mem); set -- $mem; total=$1; free=$2; avail=$3
  load=$(host_load)
  cores=$(host_cores)
  disk=$(host_disk); set -- $disk; dtotal=${1:-0}; dused=${2:-0}; dfree=${3:-0}
  local l1 l5 l15
  l1=$(printf '%s' "$load" | awk '{print $1+0}')
  l5=$(printf '%s' "$load" | awk '{print $2+0}')
  l15=$(printf '%s' "$load" | awk '{print $3+0}')
  # 宿主进程数：沙盒里的 python / napcat / qq 都算在内
  local np
  np=$(for d in /proc/[0-9]*; do echo x; done 2>/dev/null | wc -l)
  printf '{"ok":true,"cores":%s,"load1":%s,"load5":%s,"load15":%s,"procs":%s,' \
    "$(jnum "$cores")" "$(jnum "$l1")" "$(jnum "$l5")" "$(jnum "$l15")" "$(jnum "$np")"
  printf '"memTotal":%s,"memFree":%s,"memAvail":%s,' \
    "$(jnum "$total")" "$(jnum "$free")" "$(jnum "$avail")"
  printf '"diskTotal":%s,"diskUsed":%s,"diskFree":%s,"ts":%s}' \
    "$(jnum "$dtotal")" "$(jnum "$dused")" "$(jnum "$dfree")" "$(date +%s)"
}

# ---- ports ---------------------------------------------------------------
# 不读 /proc/net/tcp：Android 10+ 的 SELinux 把 /proc/net 关给了普通应用
# （实测 Permission denied），读不到不等于没监听。改成对「已知端口清单」逐个
# /dev/tcp 探测 —— 端口清单本来就来自 bots.json + astrbotPort，覆盖了全部实例，
# 比扫全网段更准，也不需要任何权限。
do_ports() {
  local bj="$QQBOT_HOME/bots.json"
  local cand="" p
  if [ -f "$bj" ]; then
    cand=$(grep -o '"napcatPort"[[:space:]]*:[[:space:]]*[0-9]*' "$bj" 2>/dev/null | grep -o '[0-9]*$')
    cand="$cand $(grep -o '"onebotPort"[[:space:]]*:[[:space:]]*[0-9]*' "$bj" 2>/dev/null | grep -o '[0-9]*$')"
  fi
  cand="$cand $(qq_astrbot_port 2>/dev/null) 6099 6199 6185 6080 6200"
  local sorted
  sorted=$(printf '%s\n' $cand | grep -E '^[0-9]+$' | grep -v '^0$' | sort -n -u | head -30)
  local ap np op
  ap=$(qq_astrbot_port 2>/dev/null); np=$(qq_napcat_port 2>/dev/null); op=$(qq_onebot_port 2>/dev/null)
  local out="" first=1 listening=0
  for p in $sorted; do
    local who="—" ok=0
    [ "$p" = "${ap:-}" ] && who="AstrBot 控制台"
    [ "$p" = "${np:-}" ] && who="NapCat WebUI"
    [ "$p" = "${op:-}" ] && who="OneBot"
    [ "$p" = "6099" ] && [ "$who" = "—" ] && who="NapCat WebUI"
    [ "$p" = "6199" ] && [ "$who" = "—" ] && who="OneBot"
    [ "$p" = "6185" ] && [ "$who" = "—" ] && who="AstrBot 控制台"
    qq_port_open "$p" && { ok=1; listening=$((listening+1)); }
    if [ "$first" = "1" ]; then first=0; else out="$out,"; fi
    out="$out{\"port\":$(jnum "$p"),\"who\":\"$(jstr "$who")\",\"open\":$(jtf "$ok")}"
  done
  printf '{"ok":true,"astrbotPort":%s,"napcatPort":%s,"onebotPort":%s,"listening":%s,"list":[%s]}' \
    "$(jnum "${ap:-0}")" "$(jnum "${np:-0}")" "$(jnum "${op:-0}")" "$listening" "$out"
}

# ---- procs ---------------------------------------------------------------
# 沙盒相关进程的内存 TOP6：只读 /proc/<pid>/status，不 fork ps（Android 上 ps -A 可能被限制）。
do_procs() {
  local out="" first=1
  local rows="$QQBOT_CACHE/extras-procs.txt"
  : > "$rows" 2>/dev/null
  for d in /proc/[0-9]*; do
    [ -r "$d/status" ] || continue
    local name rss
    name=$(awk '/^Name:/{print $2; exit}' "$d/status" 2>/dev/null)
    [ -z "$name" ] && continue
    case "$name" in
      *python*|*napcat*|*qq*|*Xvfb*|*proot*|*node*) ;;
      *) continue ;;
    esac
    rss=$(awk '/^VmRSS:/{print $2; exit}' "$d/status" 2>/dev/null)
    [ -z "$rss" ] && continue
    printf '%s %s\n' "$rss" "$name" >> "$rows" 2>/dev/null
  done
  while read -r rss name; do
    [ -z "$name" ] && continue
    if [ "$first" = "1" ]; then first=0; else out="$out,"; fi
    out="$out{\"name\":\"$(jstr "$name")\",\"rssKB\":$(jnum "$rss")}"
  done <<EOF
$(sort -rn "$rows" 2>/dev/null | head -6)
EOF
  printf '{"ok":true,"list":[%s]}' "$out"
}

# ---- netdiag -------------------------------------------------------------
# 逐项探测，每项都带 ok / 一句话结论。
# 顺序刻意从「最近的一段」到「最远的一段」：先看本机端口，再看 DNS，最后才出网。
# 这样用户一眼就知道断在本地还是断在运营商。
do_netdiag() {
  local items="" first=1 ap np op
  ap=$(qq_astrbot_port 2>/dev/null); np=$(qq_napcat_port 2>/dev/null); op=$(qq_onebot_port 2>/dev/null)
  add() { # add <name> <ok 0/1> <detail>
    if [ "$first" = "1" ]; then first=0; else items="$items,"; fi
    items="$items{\"name\":\"$(jstr "$1")\",\"ok\":$(jtf "$2"),\"detail\":\"$(jstr "$3")\"}"
  }
  if qq_port_open "${ap:-6185}"; then add "AstrBot 控制台" 1 "127.0.0.1:${ap:-6185} 可连接"
  else add "AstrBot 控制台" 0 "127.0.0.1:${ap:-6185} 没有监听（组件可能没启动）"; fi
  if qq_port_open "${np:-6099}"; then add "NapCat WebUI" 1 "127.0.0.1:${np:-6099} 可连接"
  else add "NapCat WebUI" 0 "127.0.0.1:${np:-6099} 没有监听（容器可能没起来）"; fi
  if qq_port_open "${op:-6199}"; then add "OneBot 端口" 1 "127.0.0.1:${op:-6199} 可连接"
  else add "OneBot 端口" 0 "127.0.0.1:${op:-6199} 没有监听"; fi
  local dns
  dns=$(getent hosts github.com 2>/dev/null | head -n 1 | awk '{print $1}')
  [ -z "$dns" ] && dns=$(nslookup github.com 2>/dev/null | awk '/^Address: /{print $2}' | head -n 1)
  if [ -n "$dns" ]; then add "DNS 解析" 1 "github.com → $dns"
  else add "DNS 解析" 0 "解析失败，检查网络或代理"; fi
  local c
  c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 6 https://api.github.com 2>/dev/null)
  if [ "${c:-000}" != "000" ] && [ -n "$c" ]; then add "外网连通" 1 "api.github.com HTTP $c"
  else add "外网连通" 0 "连不上 GitHub（可能被墙 / 需要代理）"; fi
  local px
  px=$(qq_proxy 2>/dev/null)
  if [ -n "$px" ]; then add "下载代理" 1 "已配置代理"
  else add "下载代理" 1 "未配置（直连）"; fi
  printf '{"ok":true,"items":[%s],"ts":%s}' "$items" "$(date +%s)"
}

# ---- env -----------------------------------------------------------------
do_env() {
  local ver arch
  ver=$(cat "$QQBOT_OPT/VERSION" 2>/dev/null)
  arch=$(uname -m 2>/dev/null)
  local napver astrver
  napver=$(cat "$QQBOT_STATE/napcat-ver.txt" 2>/dev/null)
  astrver=$(cat "$QQBOT_STATE/astrbot-ver.txt" 2>/dev/null)
  local rootfs=0
  [ -d "$QQBOT_ROOTFS/usr" ] && rootfs=1
  printf '{"ok":true,"appVersion":"%s","arch":"%s","rootfs":%s,"napcat":"%s","astrbot":"%s","uptime":%s}' \
    "$(jstr "${ver:-?}")" "$(jstr "${arch:-?}")" "$rootfs" \
    "$(jstr "${napver:-未记录}")" "$(jstr "${astrver:-未记录}")" \
    "$(jnum "$(cut -d. -f1 /proc/uptime 2>/dev/null)")"
}

# ---- clean ---------------------------------------------------------------
# clean <logs|cache|pycache|apt|all>
# 只删公认可以重建的东西：日志、pip/apt 缓存、__pycache__、临时文件。
do_clean() {
  local what="${1:-all}"
  local before after freed
  before=$(du -sk "$HOME" 2>/dev/null | awk '{print $1}')
  case "$what" in
    logs)
      find "$QQBOT_LOGS" -type f -name '*.log' -mtime +1 -delete 2>/dev/null
      find "$QQBOT_LOGS" -maxdepth 2 -type f -name '*.log.*' -mtime +1 -delete 2>/dev/null
      ;;
    cache)
      rm -rf "${QQBOT_CACHE:?}"/* 2>/dev/null
      rm -rf "${QQBOT_PTMP:?}"/* 2>/dev/null
      ;;
    pycache)
      qq_proot "find /root /opt -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null; pip cache purge >/dev/null 2>&1; true" >/dev/null 2>&1
      ;;
    apt)
      qq_proot "apt-get clean >/dev/null 2>&1; rm -rf /var/lib/apt/lists/* 2>/dev/null; true" >/dev/null 2>&1
      ;;
    all)
      find "$QQBOT_LOGS" -type f -name '*.log' -mtime +1 -delete 2>/dev/null
      rm -rf "${QQBOT_CACHE:?}"/* 2>/dev/null
      qq_proot "find /root /opt -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null; apt-get clean >/dev/null 2>&1; rm -rf /var/lib/apt/lists/* 2>/dev/null; pip cache purge >/dev/null 2>&1; true" >/dev/null 2>&1
      ;;
    *) printf '{"ok":false,"err":"unknown target"}'; return 0 ;;
  esac
  after=$(du -sk "$HOME" 2>/dev/null | awk '{print $1}')
  freed=$(( ${before:-0} - ${after:-0} ))
  [ "$freed" -lt 0 ] && freed=0
  printf '{"ok":true,"target":"%s","freedKB":%s,"beforeKB":%s,"afterKB":%s}' \
    "$(jstr "$what")" "$(jnum "$freed")" "$(jnum "${before:-0}")" "$(jnum "${after:-0}")"
}

case "${1:-usage}" in
  sysinfo) do_sysinfo ;;
  ports)   do_ports ;;
  procs)   do_procs ;;
  netdiag) do_netdiag ;;
  env)     do_env ;;
  clean)   shift; do_clean "${1:-all}" ;;
  usage|*)
    cat <<'U'
extras.sh <command>
  sysinfo   内存/负载/磁盘
  ports     宿主监听端口 + 归属
  procs     沙盒相关进程内存 TOP
  netdiag   DNS / 端口 / 外网 逐项探测
  env       版本与架构
  clean     logs|cache|pycache|apt|all  清理并返回释放空间
U
    ;;
esac
