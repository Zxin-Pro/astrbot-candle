#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 端口占用检测（1.3.4-beta.7 契约 3.2 的脚本侧实现）。
#
# 用法: ports.sh table
#   stdout 一行 JSON，字段与原生桥 AndroidPort.scan() 完全一致：
#   {"ok":true,
#    "items":[{"port":6099,"name":"NapCat WebUI","state":"listen","pid":1234,"proc":"node","self":true},
#             {"port":6185,"name":"AstrBot 面板","state":"free"},
#             {"port":6199,"name":"OneBot WS","state":"other","pid":0,"proc":"others","self":false}],
#    "extra":[{"port":8080,"pid":999,"proc":"others","self":false}]}
#   state 只有 listen / free / other（other = 被非本应用进程占着，不是我们的）；
#   extra 最多 8 条、按端口升序；pid=0 表示「端口在听，但看不到是谁」——权限边界，不是解析失败。
#
# 它存在的意义：桥拿不到的时候（旧 APK、WebView 里没注入 AndroidPort），UI 用
#   Android.exec("bash $PREFIX/opt/qqbot/ports.sh table") 走这条路，JSON 同构，前端不用写第二套解析。
#
# 为什么不用 ss / netstat：Termux 里通常没装，装了的也得 root 才看得到别人的 socket。
# /proc/net/tcp 与 tcp6 世界可读；本应用自己的进程 uid 相同，所以 /proc/<pid>/fd 也读得到，
# 别的应用读不到 —— 于是别人的端口只能报「在听 + proc 未知」，判断冲突这就够了。
#
# 退出码：0 = 出过 JSON（即使 ok:false 也是 0，调用方只看 JSON）；2 = 参数用法不对。

. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || {
    printf '{"ok":false,"err":"common.sh 加载失败"}\n'
    exit 0
}

say() { printf '[ports] %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# 端口清单：三个默认端口 + bots.json 里用户改过的端口
#
# 取值一律走 common.sh 的 qq_*_port（0 / 非法 / 超范围都回默认），保证「界面显示的端口」
# 与「这里探测的端口」是同一个数，不各算各的。
# 顺序固定 NapCat / AstrBot / OneBot，与契约 3.2 的示例、UI 那三行一致。
# 两个角色配成同一个端口时只列一次，先到的角色保留名字（与 Java 桥 portRoles 同口径）。
# ---------------------------------------------------------------------------

ROLE_PORT=()
ROLE_NAME=()

add_role() {
    local p="${1:-}" n="${2:-}" i
    case "$p" in ''|*[!0-9]*) return 0 ;; esac
    if [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then return 0; fi
    for i in "${!ROLE_PORT[@]}"; do
        if [ "${ROLE_PORT[$i]}" = "$p" ]; then return 0; fi
    done
    ROLE_PORT+=("$p")
    ROLE_NAME+=("$n")
}

roles_init() {
    add_role "$(qq_napcat_port 2>/dev/null)" "NapCat WebUI"
    add_role "$(qq_astrbot_port 2>/dev/null)" "AstrBot 面板"
    add_role "$(qq_onebot_port 2>/dev/null)" "OneBot WS"
}

in_role() {
    local p="${1:-}" i
    for i in "${!ROLE_PORT[@]}"; do
        if [ "${ROLE_PORT[$i]}" = "$p" ]; then return 0; fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# /proc/net/tcp(6) 解析
# ---------------------------------------------------------------------------

# 只取 LISTEN 行，输出三列：端口 uid inode。
# 为什么自己写十六进制转换：gawk 的 strtonum 在 busybox awk 上不存在，一个 h2d() 能跑遍所有实现。
proc_net_listen() {
    local f="${1:-}"
    [ -s "$f" ] || return 0
    awk '
        function h2d(s,   i, c, v) {
            v = 0
            s = toupper(s)
            for (i = 1; i <= length(s); i++) {
                c = index("0123456789ABCDEF", substr(s, i, 1))
                if (c == 0) return -1
                v = v * 16 + (c - 1)
            }
            return v
        }
        # 列序：0 sl / 1 local(HEX_IP:HEX_PORT) / 2 rem / 3 st(0A=LISTEN) / ... / 7 uid / ... / 9 inode
        NR > 1 && $4 == "0A" {
            p = $2
            sub(/^.*:/, "", p)
            d = h2d(p)
            if (d > 0 && d < 65536) printf "%d %s %s\n", d, $8, $10
        }' "$f" 2>/dev/null
}

# inode -> pid 反查，输出三列：inode pid 进程名。
# 关键点：[ -r /proc/<pid>/fd ] 这一句就把别的应用过滤掉了（Android 上不同 uid 列不出 fd），
# 不需要 root，也不会撞权限错误。每个进程只起一次 ls 拿全部 fd；
# 不逐 fd readlink —— 上百个进程时那会起几千个子进程。
map_fds() {
    local out="${1:-}" pid line ino name
    : > "$out"
    for pid in /proc/[0-9]*; do
        pid="${pid#/proc/}"
        [ -r "/proc/$pid/fd" ] || continue
        name="$(cat "/proc/$pid/comm" 2>/dev/null)"
        ls -l "/proc/$pid/fd" 2>/dev/null | while IFS= read -r line; do
            case "$line" in
                *"-> socket:["*) ;;
                *) continue ;;
            esac
            ino="${line##*socket:[}"
            ino="${ino%%]*}"
            case "$ino" in ''|*[!0-9]*) continue ;; esac
            printf '%s %s %s\n' "$ino" "$pid" "${name:-?}"
        done >> "$out"
    done
}

# ---------------------------------------------------------------------------
# 主命令
# ---------------------------------------------------------------------------

# 读不到 /proc/net/tcp 时的退路：只探三个角色端口通不通，给不出 pid。
# 为什么还要出 JSON：UI 那三行至少能显示「在听 / 空闲」，比整块报错有用。
cmd_probe() {
    local i p nm state json sep=""
    printf '{"ok":true,"note":"读不到进程表，只做了端口探活","items":['
    for i in "${!ROLE_PORT[@]}"; do
        p="${ROLE_PORT[$i]}"
        nm="${ROLE_NAME[$i]}"
        if qq_port_open "$p"; then
            state=listen
            printf '%s{"port":%s,"name":"%s","state":"%s","pid":0,"proc":"unknown","self":false}' \
                "$sep" "$p" "$(qq_json_esc "$nm")" "$state"
        else
            printf '%s{"port":%s,"name":"%s","state":"free"}' "$sep" "$p" "$(qq_json_esc "$nm")"
        fi
        sep=","
    done
    printf '],"extra":[]}\n'
}

cmd_table() {
    local myuid dir listen map items extra
    local tcp_ok=0 tcp6_ok=0
    local i p nm uid inode self state pid proc hit row
    local line rest euid einode ep seen extra_n sep

    myuid="$(id -u 2>/dev/null)"
    case "$myuid" in ''|*[!0-9]*) myuid=-1 ;; esac

    roles_init
    if [ "${#ROLE_PORT[@]}" -eq 0 ]; then
        # 三个取值函数都有兜底，走到这只能是 bots.json 和 common.sh 同时不对劲
        printf '{"ok":false,"err":"读不出端口配置（bots.json 与 common.sh 都不对劲）"}\n'
        return 0
    fi

    [ -r /proc/net/tcp ] && tcp_ok=1
    [ -r /proc/net/tcp6 ] && tcp6_ok=1
    if [ "$tcp_ok" = 0 ] && [ "$tcp6_ok" = 0 ]; then
        say "读不到 /proc/net/tcp，退到端口探活"
        cmd_probe
        return 0
    fi

    # 临时文件放缓存目录：那是本应用私有目录，一定可写；拿不到就退 /tmp
    dir="$QQBOT_CACHE/ports.$$"
    mkdir -p "$QQBOT_CACHE" 2>/dev/null
    mkdir -p "$dir" 2>/dev/null || dir="/${TMPDIR:-/tmp}/ports.$$"
    mkdir -p "$dir" 2>/dev/null
    listen="$dir/listen"
    map="$dir/inodes"
    : > "$listen"

    proc_net_listen /proc/net/tcp >> "$listen"
    proc_net_listen /proc/net/tcp6 >> "$listen"
    # 同一个端口可能 v4/v6 各一条，甚至 0.0.0.0 与 127.0.0.1 各一条：整行去重后按端口排
    # 不用 sort 的 -o：busybox / toybox 的 sort 不都有，写临时文件再 mv 最稳
    sort -u -n -k1 "$listen" > "$listen.s" 2>/dev/null && mv -f "$listen.s" "$listen"
    map_fds "$map"

    items=""
    sep=""
    for i in "${!ROLE_PORT[@]}"; do
        p="${ROLE_PORT[$i]}"
        nm="${ROLE_NAME[$i]}"
        # 同一端口有两条时优先本应用那条：uid 相同才是我们自己的进程
        row="$(awk -v p="$p" -v u="$myuid" '
            $1 == p {
                if ($2 == u) { print; found = 1; exit }
                if (best == "") best = $0
            }
            END { if (!found && best != "") print best }' "$listen" 2>/dev/null)"
        if [ -z "$row" ]; then
            items="$items$sep{\"port\":$p,\"name\":\"$(qq_json_esc "$nm")\",\"state\":\"free\"}"
            sep=","
            continue
        fi
        uid=""
        inode=""
        read -r _row_port uid inode <<< "$row"
        self=false
        state=listen
        if [ "$uid" != "$myuid" ]; then
            state=other
        else
            self=true
        fi
        pid=0
        proc="others"
        hit="$(awk -v n="$inode" '$1 == n { print $2 " " $3; exit }' "$map" 2>/dev/null)"
        if [ -n "$hit" ]; then
            pid="${hit%% *}"
            proc="${hit#* }"
        elif [ "$self" = true ]; then
            proc="unknown"   # 自己的端口却查不到 inode：进程刚退，或 fd 正在换
        fi
        case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
        proc="${proc:0:32}"
        items="$items$sep{\"port\":$p,\"name\":\"$(qq_json_esc "$nm")\",\"state\":\"$state\",\"pid\":$pid,\"proc\":\"$(qq_json_esc "$proc")\",\"self\":$self}"
        sep=","
    done

    # extra：角色端口之外、1024-9999 里还在听的，按端口升序取前 8 条
    extra=""
    sep=""
    extra_n=0
    seen=" "
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        ep="${line%% *}"
        rest="${line#* }"
        euid="${rest%% *}"
        einode="${rest#* }"
        case "$ep" in ''|*[!0-9]*) continue ;; esac
        in_role "$ep" && continue
        if [ "$ep" -lt 1024 ] || [ "$ep" -gt 9999 ]; then continue; fi
        case "$seen" in *" $ep "*) continue ;; esac
        seen="$seen$ep "
        pid=0
        proc="others"
        self=false
        if [ "$euid" = "$myuid" ]; then self=true; fi
        hit="$(awk -v n="$einode" '$1 == n { print $2 " " $3; exit }' "$map" 2>/dev/null)"
        if [ -n "$hit" ]; then
            pid="${hit%% *}"
            proc="${hit#* }"
        elif [ "$self" = true ]; then
            proc="unknown"
        fi
        case "$pid" in ''|*[!0-9]*) pid=0 ;; esac
        proc="${proc:0:32}"
        extra="$extra$sep{\"port\":$ep,\"pid\":$pid,\"proc\":\"$(qq_json_esc "$proc")\",\"self\":$self}"
        sep=","
        extra_n=$((extra_n + 1))
        if [ "$extra_n" -ge 8 ]; then break; fi
    done < <(sort -n -k1 "$listen" 2>/dev/null)

    printf '{"ok":true,"items":[%s],"extra":[%s]}\n' "$items" "$extra"
    rm -rf "$dir" 2>/dev/null
    return 0
}

case "${1:-}" in
    table|"") cmd_table ;;
    -h|--help|help)
        printf '用法: ports.sh table\n' >&2
        exit 0
        ;;
    *)
        printf '用法: ports.sh table\n' >&2
        exit 2
        ;;
esac
