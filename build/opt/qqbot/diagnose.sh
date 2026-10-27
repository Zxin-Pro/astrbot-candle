#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 诊断脚本（1.3.4-beta.2）：把「网络 / 内核 / 存储 / 现场 / 崩溃记录器」五个方向
# 各做成一个子命令，供 App 界面点按钮、也供命令行手工排查。
#
# 用法：
#   diagnose.sh netcheck    [--text]        出网体检（DNS / 443 端口 / 代理）
#   diagnose.sh kernelcheck [--text]        内核能不能跑 proot（exec + fork 两级）
#   diagnose.sh disktop [N] [--text]        QQBot 相关目录里最大的 N 个文件（默认 10）
#   diagnose.sh bundle [版本号] [--text]    打包一份诊断包（tar.gz）并脱敏
#   diagnose.sh health      [--text]        崩溃记录器 / 上次部署 / 被杀记录状态
#
# 输出约定：默认 stdout 一行 JSON（给 Java 桥解析）；带 --text 时输出给人看的多行
# 文本（upload-log.sh 往报告里贴的就是这份）。两种模式都会把结果缓存到
# $QQBOT_CACHE/diag-<net|kernel|disk|bundle|health>.{json,txt}，上传日志时不必重跑。
#
# 失败一律降级成 JSON 里的 state=fail + 一句人话 hint，不带非零退出码去炸调用方；
# 只有「脚本本身找不到 common.sh」才 exit 1。

. "${PREFIX:-/data/data/zhuxin.astrbot/files/usr}/opt/qqbot/common.sh" 2>/dev/null || {
  printf '%s\n' '{"ok":false,"what":"script","state":"fail","title":"诊断脚本加载失败","hint":"重新部署一次；还不行请把这句话发给开发者。","text":"找不到 common.sh，部署可能没完成。","items":[]}'
  exit 1
}

DG_CACHE="$QQBOT_CACHE"
DG_WHAT=""
DG_ARG=""
DG_TEXT=0
DG_MS=0
DG_TCP_PROBE=""
NET_HOST="${DIAG_NET_HOST:-api.soulter.top}"

dg_have() { command -v "$1" >/dev/null 2>&1; }

# 统一超时：shell 函数没法直接喂给 timeout，所以这个只包外部命令。
dg_to() {
  local _s="$1"; shift
  [ "$#" -gt 0 ] || return 1
  if dg_have timeout; then timeout "$_s" "$@"; else "$@"; fi
}

# JSON 字符串转义。和 common.sh 的 qq_json_esc 的区别：那个把控制字符直接删掉
# （风控行只有一行），但诊断的 text 字段是多行报告，必须保留换行 —— 这里把换行
# 转成 \n 转义序列，再把真实换行去掉。
dg_esc() {
  printf '%s' "${1:-}" \
    | tr -d '\000-\010\013-\037\177' \
    | tr '\t' ' ' \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/$/\\n/' \
    | tr -d '\n' \
    | sed -e 's/\\n$//'
}

# 字节数转人话（口径与 storage.sh 的 human 一致）
dg_human() {
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1073741824 ]; then
    printf '%s.%s GB' $((b / 1073741824)) $((b % 1073741824 * 10 / 1073741824))
  elif [ "$b" -ge 1048576 ]; then
    printf '%s.%s MB' $((b / 1048576)) $((b % 1048576 * 10 / 1048576))
  elif [ "$b" -ge 1024 ]; then
    printf '%s KB' $((b / 1024))
  else
    printf '%s B' "$b"
  fi
}

dg_num() {
  case "${1:-}" in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac
}

# 输出 + 落缓存。参数：what state title hint text items_json [额外 "k":v ...]
dg_emit() {
  local what="$1" state="$2" title="$3" hint="$4" text="$5" items="$6"
  shift 6
  local ok=false ts json name
  [ "$state" = "ok" ] && ok=true
  ts=$(date +%s 2>/dev/null || echo 0)
  [ -n "$items" ] || items='[]'
  json="{\"ok\":$ok,\"what\":\"$(qq_json_esc "$what")\",\"state\":\"$state\""
  json="$json,\"title\":\"$(dg_esc "$title")\",\"hint\":\"$(dg_esc "$hint")\""
  json="$json,\"text\":\"$(dg_esc "$text")\",\"items\":$items,\"ts\":$ts"
  while [ "$#" -gt 0 ]; do json="$json,$1"; shift; done
  json="$json}"

  name="$what"
  case "$what" in
    netcheck) name="net" ;;
    kernelcheck) name="kernel" ;;
    disktop) name="disk" ;;
  esac
  mkdir -p "$DG_CACHE" 2>/dev/null
  printf '%s\n' "$json" > "$DG_CACHE/diag-$name.json.tmp" 2>/dev/null \
    && mv -f "$DG_CACHE/diag-$name.json.tmp" "$DG_CACHE/diag-$name.json" 2>/dev/null
  {
    [ -n "$title" ] && printf '%s\n' "$title"
    [ -n "$text" ] && printf '%s\n' "$text"
    [ -n "$hint" ] && printf '建议：%s\n' "$hint"
    true   # 上面每行都是 [ -n ] && printf：hint 为空时组返回值是 1，
           # 会让后面的 && mv 整条不执行，缓存里只剩 .tmp（干跑踩过）
  } > "$DG_CACHE/diag-$name.txt.tmp" 2>/dev/null \
    && mv -f "$DG_CACHE/diag-$name.txt.tmp" "$DG_CACHE/diag-$name.txt" 2>/dev/null

  if [ "$DG_TEXT" = "1" ]; then
    [ -n "$title" ] && printf '%s\n' "$title"
    [ -n "$text" ] && printf '%s\n' "$text"
    [ -n "$hint" ] && printf '建议：%s\n' "$hint"
  else
    printf '%s\n' "$json"
  fi
  return 0   # 同理由：--text 且无建议时最后一条 printf 没执行，函数会返回 1
}

# 端口连通性：优先 bash 的 /dev/tcp（Termux 自带），没编译出来就退 curl。
# 结果毫秒数放在 DG_MS。
dg_tcp() {
  local h="$1" p="$2" s="${3:-6}" t0 rc
  case "$h" in ''|*[!A-Za-z0-9.:_-]*) return 1 ;; esac
  case "$p" in ''|*[!0-9]*) return 1 ;; esac
  t0=$(date +%s 2>/dev/null || echo 0)
  if [ -z "$DG_TCP_PROBE" ]; then
    DG_TCP_PROBE=1
    case "$( ( exec 3<>"/dev/tcp/127.0.0.1/1" ) 2>&1 )" in
      *"No such file"*|*"not supported"*) DG_TCP_PROBE=0 ;;
    esac
  fi
  if [ "$DG_TCP_PROBE" = "1" ]; then
    if dg_have timeout; then
      timeout "$s" bash -c "exec 3<>/dev/tcp/$h/$p" >/dev/null 2>&1 </dev/null
    else
      bash -c "exec 3<>/dev/tcp/$h/$p" >/dev/null 2>&1 </dev/null
    fi
    rc=$?
  elif dg_have curl; then
    curl -sS -k -o /dev/null --connect-timeout "$s" --max-time "$s" "https://$h:$p/" >/dev/null 2>&1
    rc=$?
  else
    rc=1
  fi
  DG_MS=$(( ($(date +%s 2>/dev/null || echo 0) - t0) * 1000 ))
  [ "$DG_MS" -ge 0 ] || DG_MS=0
  return $rc
}

# ---------------------------------------------------------------- netcheck ----
dg_netcheck() {
  local ip="" dns="fail" dns_txt="没解析出来"
  local tcp="fail" tcp_txt="连不上" alt_txt="" alt_ok=0
  local px="" px_state="none" px_txt="未设置" ph="" pp=""
  local verdict hint text items now

  # 1) DNS：getent → nslookup → 靠直连推断
  if dg_have getent; then
    ip=$(dg_to 6 getent hosts "$NET_HOST" 2>/dev/null | awk 'NR==1{print $1}')
  fi
  if [ -z "$ip" ] && dg_have nslookup; then
    # 「名称: / Name:」之后的第一条 Address 才是解析结果；前面的 Address 是本机 DNS 服务器。
    # 没有名字行时（部分 busybox 只打一条 Address）才退「最后一条」，且至少两条才敢用，
    # 否则会把服务器地址 fe80::1 当成解析成功（Git Bash 干跑踩过）。
    ip=$(dg_to 6 nslookup "$NET_HOST" 2>/dev/null | awk '
      /^[Nn]ame:/ || /^名称[：:]/ { w=1; next }
      /^[Aa]ddress/ {
        a=$NF; sub(/#.*/,"",a)
        if (w==1 && done==0) { print a; done=1; exit }
        n++; last=a
      }
      END { if (done==0 && n>=2) print last }')
  fi
  case "$ip" in
    ''|*[!0-9a-fA-F:.]*) ip="" ;;
  esac
  if [ -n "$ip" ]; then
    dns="ok"; dns_txt="$NET_HOST → $ip"
  fi

  # 2) 443 端口：先按域名连（顺带验证 DNS），失败再用 IP 直连区分「DNS 坏」和「整条网坏」
  if dg_tcp "$NET_HOST" 443 6; then
    tcp="ok"
    tcp_txt="$NET_HOST:443 可连（$DG_MS 毫秒）"
    if [ -z "$ip" ]; then
      dns="ok"
      dns_txt="$NET_HOST（本机没有解析工具，按直连成功推断 DNS 正常）"
    fi
  else
    if dg_tcp 223.5.5.5 443 6; then
      alt_ok=1; alt_txt="223.5.5.5:443 可连（阿里 DNS，说明手机本身能出网）"
    elif dg_tcp 1.1.1.1 443 6; then
      alt_ok=1; alt_txt="1.1.1.1:443 可连（说明手机本身能出网）"
    else
      alt_txt="223.5.5.5 / 1.1.1.1 的 443 都连不上"
    fi
  fi

  # 3) 代理：proxy.txt 里那一行
  px=$(qq_proxy 2>/dev/null | head -n 1)
  if [ -n "$px" ]; then
    px_state="set"
    px_txt="已设置 $px"
    ph=$(printf '%s' "$px" | sed -e 's#^[A-Za-z0-9+.-]*://##' -e 's#/.*$##' -e 's#^.*@##' -e 's#:.*$##')
    pp=$(printf '%s' "$px" | sed -e 's#^[A-Za-z0-9+.-]*://##' -e 's#/.*$##' -e 's#^.*@##' -e 's#^[^:]*:##')
    case "$ph" in
      127.0.0.1|localhost|::1|0.0.0.0)
        case "$pp" in
          ''|*[!0-9]*) : ;;
          *)
            if qq_port_open "$pp"; then
              px_state="listening"
              px_txt="已设置 $px，本机 $pp 端口可连"
            else
              px_state="dead"
              px_txt="已设置 $px，但本机 $pp 端口连不上（代理软件没在运行？）"
            fi ;;
        esac ;;
    esac
  fi

  # 4) 结论
  if [ "$tcp" = "ok" ]; then
    verdict="网络正常"
    hint=""
  elif [ "$alt_ok" = "1" ]; then
    verdict="能出网，但连不上 $NET_HOST"
    hint="手机的网络是通的，问题在解析或线路：Wi-Fi 设置里把 DNS 改成 223.5.5.5 再试；换流量试一次；都还不行就是服务端暂时不通，过一会儿再上传。"
  elif [ "$px_state" = "dead" ]; then
    verdict="代理没起来"
    hint="设置里填的那个代理软件没在运行：重新打开它，或者把代理清空再上传。"
  else
    verdict="连不上外网"
    hint="先确认手机能上网（浏览器随便打开一个网页），再关掉代理试一次；恢复后重新上传。"
  fi

  now=$(date "+%F %T" 2>/dev/null)
  text="出网体检（$now）
DNS      : $dns_txt
443 端口 : $tcp_txt
代理     : $px_txt
结论     : $verdict"
  [ -n "$alt_txt" ] && text="$text
补充     : $alt_txt"

  items="{\"k\":\"DNS\",\"v\":\"$(dg_esc "$dns_txt")\"},{\"k\":\"443 端口\",\"v\":\"$(dg_esc "$tcp_txt")\"},{\"k\":\"代理\",\"v\":\"$(dg_esc "$px_txt")\"},{\"k\":\"结论\",\"v\":\"$(dg_esc "$verdict")\"}"

  if [ "$tcp" = "ok" ]; then
    dg_emit netcheck "ok" "出网体检：$verdict" "$hint" "$text" "[$items]" \
      "\"dns\":\"$dns\"" "\"tcp443\":\"$tcp\"" "\"proxy\":\"$px_state\"" "\"dnsIp\":\"$ip\"" "\"tcpMs\":$DG_MS"
  else
    dg_emit netcheck "fail" "出网体检：$verdict" "$hint" "$text" "[$items]" \
      "\"dns\":\"$dns\"" "\"tcp443\":\"$tcp\"" "\"proxy\":\"$px_state\"" "\"dnsIp\":\"$ip\"" "\"tcpMs\":$DG_MS"
  fi
}

# ------------------------------------------------------------ kernelcheck ----
# proot 在容器里跑每个程序都要 fork；有些 ROM 的 seccomp 会拒掉 fork，这时 proot
# 能 exec 却起不了沙盒，报错只有一句看不出所以然的「proot: fork: Operation not
# permitted」。这里两级分开测，给出明确结论。
dg_kernelcheck() {
  local proot_bin="${QQBOT_PROOT:-$PREFIX/bin/proot}"
  local log="$DG_CACHE/kernelcheck.log"
  local probe="fail" fork="fail" fork_out="" detail="" verdict hint text items
  local mode="${QQBOT_PROOT_MODE:-default}"
  local miss=""

  mkdir -p "$DG_CACHE" 2>/dev/null
  : > "$log" 2>/dev/null

  if [ ! -x "$proot_bin" ]; then
    miss="proot"
    detail="proot 不存在或不可执行：$proot_bin"
  elif [ ! -d "$QQBOT_ROOTFS" ]; then
    miss="rootfs"
    detail="rootfs 不存在：$QQBOT_ROOTFS"
  else
    # 第一级：proot 能不能 exec（直接复用项目里已有的三档探针）
    if qq_proot_probe 8 qqbot-diag-probe "$log" >/dev/null 2>&1; then
      probe="ok"
    fi
    # 第一级可能刚把档位探出来（写了标记），fork 这段直接调 proot，先按标记刷新环境，
    # 否则 noseccomp 的机器上这一级会拿默认档去跑，白失败一次。
    qq_proot_export_mode >/dev/null 2>&1 || true
    mode="${QQBOT_PROOT_MODE:-default}"
    # 第二级：容器里 fork（proot 启动任何程序都走它）
    fork_out=$(
      unset LD_PRELOAD 2>/dev/null
      export PROOT_TMP_DIR="$QQBOT_PTMP" 2>/dev/null
      dg_to 15 "$proot_bin" -0 -r "$QQBOT_ROOTFS" /bin/bash -c 'true & wait; echo qqbot-fork-ok' 2>>"$log" </dev/null
    )
    case "$fork_out" in
      *qqbot-fork-ok*) fork="ok" ;;
    esac
    # fork 通了而 exec 探针没过：多半是 /system/bin/sh 被 ROM 改名（探针才依赖它），
    # 真正影响沙盒的是 fork，按「能跑」算，但把探针结果标成 warn 留痕。
    if [ "$fork" = "ok" ] && [ "$probe" != "ok" ]; then
      probe="warn"
    fi
    detail=$(tail -n 8 "$log" 2>/dev/null)
  fi

  if [ "$fork" = "ok" ]; then
    verdict="内核能跑沙盒"
    hint=""
  elif [ -n "$miss" ]; then
    if [ "$miss" = "proot" ]; then
      verdict="内置 proot 没装上"
      hint="首页 → 一键部署，把内置工具装完再看这一项；装完还不行请把这份诊断发出来。"
    else
      verdict="容器还没装"
      hint="先完成部署（会下载并解压 rootfs），再来看这一项。"
    fi
  elif [ "$probe" = "ok" ]; then
    verdict="本机内核不支持 fork"
    hint="proot 能执行，但在容器里创建子进程被系统拒绝，沙盒起不来。先重启手机再试一次；仍不行的话，这台 ROM 的权限 / 省电策略限制比较严（部分定制系统、开了「内存扩展」「应用冻结」的手机常见），请把这份诊断一起发给开发者。"
  else
    verdict="proot 跑不起来"
    # 旧文案是「内置 proot 可能和本机架构不匹配：重新部署一次」，对内核拦 seccomp 的
    # 机型是纯粹的误导（重装一百次也一样）。改成按日志关键字给下一步。
    _kh="$log"
    if grep -qiE 'PROOT_NO_SECCOMP|Operation not permitted|EPERM|seccomp' "_kh" 2>/dev/null; then
      hint="这台手机的内核拦掉了 proot 的 seccomp 加速（Operation not permitted），三档 workaround 都没过。不是你的操作问题，重装/重置部署也没用；请把这份诊断发给开发者，下个版本会针对该机型适配。"
    elif grep -qiE 'No such file or directory|ENOENT|not found' "_kh" 2>/dev/null; then
      hint="内置组件似乎没装全（No such file or directory）。回主页点「重置部署」后重新部署一次；仍失败请把这份诊断发出来。"
    elif grep -qiE 'Permission denied|EACCES|cannot execute' "_kh" 2>/dev/null; then
      hint="系统拒绝执行 proot（Permission denied）。确认没开应用冻结 / 内存扩展，重启手机后再试；仍失败请把这份诊断发出来。"
    else
      hint="三档都没跑起来（日志已附在下面）。请把这份诊断发给开发者。"
    fi
  fi

  text="内核探测（fork / proot）
proot 执行 : $([ "$probe" = "fail" ] && echo 失败 || echo 正常)
proot 档位 : $mode
容器 fork  : $([ "$fork" = "ok" ] && echo 正常 || echo 失败)
结论       : $verdict"
  [ -n "$detail" ] && text="$text
细节       :
$detail"

  items="{\"k\":\"proot 执行\",\"v\":\"$probe\"},{\"k\":\"容器 fork\",\"v\":\"$fork\"},{\"k\":\"结论\",\"v\":\"$(dg_esc "$verdict")\"}"
  if [ "$fork" = "ok" ]; then
    dg_emit kernelcheck "ok" "内核探测：$verdict" "$hint" "$text" "[$items]" \
      "\"proot\":\"$probe\"" "\"fork\":\"$fork\"" "\"mode\":\"$mode\"" "\"detail\":\"$(dg_esc "$detail")\""
  else
    dg_emit kernelcheck "fail" "内核探测：$verdict" "$hint" "$text" "[$items]" \
      "\"proot\":\"$probe\"" "\"fork\":\"$fork\"" "\"mode\":\"$mode\"" "\"detail\":\"$(dg_esc "$detail")\""
  fi
}

# ---------------------------------------------------------------- disktop ----
dg_disktop() {
  local n; n=$(dg_num "${DG_ARG:-10}")
  [ "$n" -ge 1 ] || n=1
  [ "$n" -le 30 ] || n=30
  local adir="" p sz total=0 free=0 rows="" text="" cnt=0 k=0 sep="" fsz fp
  local out_dirs="" out_files="" items="" top_txt=""

  adir="$(qq_astrbot_data_dir 2>/dev/null)"
  free=$(df -k "$HOME" 2>/dev/null | awk 'NR==2{print $4}')
  free=$(dg_num "$free"); free=$((free * 1024))

  # 目录体检：只算 QQBot 自己的目录，rootfs 整体不动（手机上 du 一遍要几十秒）。
  # AstrBot 的 logs 在 data 里面，只展示不重复计入合计。
  for p in "$QQBOT_OPT" "$QQBOT_CACHE" "$QQBOT_STATE" "$QQBOT_LOGS" "$adir"; do
    [ -n "$p" ] && [ -d "$p" ] || continue
    sz=$(qq_du_bytes "$p" 2>/dev/null || echo 0)
    sz=$(dg_num "$sz")
    total=$((total + sz))
    rows="$rows$(printf '%s\t%s\n' "$sz" "$p")"
  done
  [ -n "$adir" ] && [ -d "$adir/logs" ] && {
    sz=$(qq_du_bytes "$adir/logs" 2>/dev/null || echo 0)
    sz=$(dg_num "$sz")
    rows="$rows$(printf '%s\t%s\n' "$sz" "$adir/logs（含在上面那行里）")"
  }

  # 最大的 N 个文件：只扫缓存 / 日志 / 状态 / AstrBot 数据（用户真正关心的那几处）
  local tsv="$DG_CACHE/.topfiles.$$"
  : > "$tsv" 2>/dev/null
  for p in "$QQBOT_CACHE" "$QQBOT_LOGS" "$QQBOT_STATE" "$adir"; do
    [ -n "$p" ] && [ -d "$p" ] || continue
    if [ "${QQ_DU_B:-1}" = "1" ]; then
      du -ab "$p" 2>/dev/null >> "$tsv"
    else
      du -ak "$p" 2>/dev/null | while IFS=$'\t' read -r _s _p; do
        printf '%s\t%s\n' $((_s * 1024)) "$_p"
      done >> "$tsv"
    fi
  done
  while IFS=$'\t' read -r fsz fp; do
    case "$fsz" in ''|*[!0-9]*) continue ;; esac
    [ -f "$fp" ] || continue
    cnt=$((cnt + 1))
    k=$((k + 1))
    out_files="$out_files$sep{\"path\":\"$(qq_json_esc "$fp")\",\"size\":$fsz,\"kind\":\"file\"}"
    sep=","
    top_txt="$top_txt
$k. $(dg_human "$fsz")  $fp"
    [ "$cnt" -lt "$n" ] || break
  done <<EOF
$(sort -rn "$tsv" 2>/dev/null | head -n $((n * 4)))
EOF
  rm -f "$tsv" 2>/dev/null

  sep=""
  while IFS=$'\t' read -r sz p; do
    [ -n "$p" ] || continue
    out_dirs="$out_dirs$sep{\"path\":\"$(qq_json_esc "$p")\",\"size\":$sz,\"kind\":\"dir\"}"
    sep=","
    text="$text
目录     : $(dg_human "$sz")  $p"
  done <<EOF
$rows
EOF
  [ -n "$top_txt" ] && text="存储占用（最大的 $n 个文件）
剩余空间 : $(dg_human "$free")
合计占用 : $(dg_human "$total")（只算上面这些目录，不重复计嵌套）
$top_txt" || text="存储占用（最大的 $n 个文件）
剩余空间 : $(dg_human "$free")
合计占用 : $(dg_human "$total")
目录     :（没有可扫的目录）"

  items="$out_dirs"
  if [ -n "$out_files" ]; then
    [ -n "$items" ] && items="$items,"
    items="$items$out_files"
  fi

  dg_emit disktop "ok" "存储占用" "" "$text" "[$items]" \
    "\"n\":$n" "\"total\":$total" "\"free\":$free"
}

# ------------------------------------------------------------------ bundle ----
dg_bundle() {
  local appver="${DG_ARG:-unknown}"
  local ts out stage rc=0 copied=0 red=0 f lc=0 cc=0 cwdir
  ts=$(date "+%Y%m%d-%H%M%S" 2>/dev/null)
  out="$DG_CACHE/diag-$ts.tar.gz"
  stage="$QQBOT_PTMP/diag-$ts-$$"
  mkdir -p "$stage/logs" "$stage/crashes" 2>/dev/null || {
    stage="/tmp/diag-$ts-$$"
    mkdir -p "$stage/logs" "$stage/crashes" 2>/dev/null
  }

  if [ ! -d "$stage" ]; then
    dg_emit bundle "fail" "诊断包没打成" "先清一下缓存（设置 → 清理缓存）再试；还不行请把这条发给开发者。" "临时目录建不出来，可能是存储满了。" "[]"
    return 0
  fi

  # 设备 + 部署状态（纯文本，打包后统一脱敏）
  {
    echo "===== QQBot 诊断包 ====="
    echo "time      : $(date "+%F %T %Z" 2>/dev/null)"
    echo "app_ver   : $appver"
    echo "model     : $(getprop ro.product.model 2>/dev/null) / $(getprop ro.product.brand 2>/dev/null)"
    echo "android   : $(getprop ro.build.version.release 2>/dev/null) (sdk $(getprop ro.build.version.sdk 2>/dev/null))"
    echo "abi       : $(getprop ro.product.cpu.abi 2>/dev/null)"
    echo "kernel    : $(uname -a 2>/dev/null)"
    echo "home      : $HOME"
    echo "prefix    : $PREFIX"
    echo "bundle    : $(cat "$QQBOT_OPT/BUNDLE_ARCH" 2>/dev/null || echo '(无)')"
    echo
    echo "----- 空间 -----"
    df -k "$HOME" 2>/dev/null
    echo
    echo "----- 部署步骤标记 -----"
    ls -1 "$QQBOT_STEPS" 2>/dev/null
    echo "deploy.done : $([ -f "$QQBOT_HOME/deploy.done" ] && echo yes || echo no)"
    echo "deploy.error:"
    cat "$QQBOT_HOME/deploy.error" 2>/dev/null || echo "(无)"
    echo
    echo "----- supervisor 状态 -----"
    echo "supervisor.pid : $(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null || echo '(无)')"
    echo "start mark     : $(cat "$QQ_START_MARK" 2>/dev/null | tr '\n' ' ')"
    echo "被杀记录（尾 10 行）:"
    tail -n 10 "$QQ_KILLED_LOG" 2>/dev/null || echo "(无)"
  } > "$stage/state.txt" 2>/dev/null
  copied=$((copied + 1))

  [ -f "$QQBOT_HOME/doctor.txt" ] && { cp -f "$QQBOT_HOME/doctor.txt" "$stage/doctor.txt" 2>/dev/null && copied=$((copied + 1)); }
  [ -f "$QQBOT_HOME/fs-probe.txt" ] && { cp -f "$QQBOT_HOME/fs-probe.txt" "$stage/fs-probe.txt" 2>/dev/null && copied=$((copied + 1)); }
  [ -f "$QQBOT_CACHE/storage.json" ] && { cp -f "$QQBOT_CACHE/storage.json" "$stage/storage.json" 2>/dev/null && copied=$((copied + 1)); }
  [ -f "$QQBOT_CACHE/crash-counts.json" ] && { cp -f "$QQBOT_CACHE/crash-counts.json" "$stage/crash-counts.json" 2>/dev/null && copied=$((copied + 1)); }

  # 网络 / 内核自检缓存（跑过就直接带上，不让打包再等一遍）
  for f in "$DG_CACHE/diag-net.txt" "$DG_CACHE/diag-kernel.txt"; do
    [ -f "$f" ] || continue
    cp -f "$f" "$stage/$(basename "$f")" 2>/dev/null && copied=$((copied + 1))
  done

  tail -c 200000 "$QQBOT_LOGS/supervisor.log" > "$stage/supervisor.log" 2>/dev/null && copied=$((copied + 1))
  for f in "$QQBOT_LOGS"/*.log "$QQBOT_CACHE"/logs/*.log; do
    [ -f "$f" ] || continue
    [ "$lc" -ge 15 ] && break
    lc=$((lc + 1))
    tail -c 120000 "$f" > "$stage/logs/$(basename "$f")" 2>/dev/null && copied=$((copied + 1))
  done

  cwdir="${QQBOT_CRASH_DIR:-$QQBOT_CACHE/crashes}"
  for f in "$cwdir"/crash-*.log; do
    [ -f "$f" ] || continue
    [ "$cc" -ge 20 ] && break
    cc=$((cc + 1))
    tail -c 120000 "$f" > "$stage/crashes/$(basename "$f")" 2>/dev/null && copied=$((copied + 1))
  done
  [ -f "$cwdir/cw-build.log" ] && { tail -c 60000 "$cwdir/cw-build.log" > "$stage/crashes/cw-build.log" 2>/dev/null && copied=$((copied + 1)); }

  # 脱敏：包里是用户手机的日志，账号 / 令牌 / 手机号必须在本地先打码
  if type qq_redact_file >/dev/null 2>&1; then
    for f in $(find "$stage" -type f 2>/dev/null); do
      lc=$(qq_redact_file "$f" 2>/dev/null)
      lc=$(dg_num "$lc")
      red=$((red + lc))
    done
  fi
  lc=0

  if ! dg_have tar; then
    rc=1
  else
    dg_to 60 tar -czf "$out.tmp" -C "$stage" . >/dev/null 2>&1
    rc=$?
  fi
  rm -rf "$stage" 2>/dev/null

  if [ "$rc" -ne 0 ] || [ ! -f "$out.tmp" ]; then
    rm -f "$out.tmp" 2>/dev/null
    dg_emit bundle "fail" "诊断包没打成" "先清一下缓存（设置 → 清理缓存）再试；还不行请把这条发给开发者。" "打包失败（tar 返回 $rc），可能是存储满了或者没装 tar。" "[]"
    return 0
  fi
  mv -f "$out.tmp" "$out" 2>/dev/null
  local bytes; bytes=$(dg_num "$(wc -c < "$out" 2>/dev/null)")

  text="诊断包已生成
文件 : $out
大小 : $(dg_human "$bytes")（$bytes 字节）
内容 : 设备与部署状态、doctor、supervisor 与各服务日志尾、崩溃现场、自检缓存
脱敏 : $red 处"
  dg_emit bundle "ok" "诊断包已生成" "" "$text" "[]" \
    "\"bytes\":$bytes" "\"path\":\"$(qq_json_esc "$out")\"" "\"redacted\":$red" "\"files\":$copied"
}

# ------------------------------------------------------------------ health ----
dg_health() {
  local so="$QQBOT_CACHE/crashwatch.so" cwdir="${QQBOT_CRASH_DIR:-$QQBOT_CACHE/crashes}"
  local so_b=0 build_b=0 records=0 doctor_at=0 docf="$QQBOT_HOME/doctor.txt"
  local dep_state="unknown" dep_detail="" dep_at=0 dep_txt="没跑过" doc_txt="还没跑过"
  local killed_n=0 killed_last="" mark="" verdict hint text items

  [ -f "$so" ] && so_b=$(dg_num "$(wc -c < "$so" 2>/dev/null)")
  [ -f "$cwdir/cw-build.log" ] && build_b=$(dg_num "$(wc -c < "$cwdir/cw-build.log" 2>/dev/null)")
  records=$(dg_num "$(ls -1 "$cwdir"/crash-*.log 2>/dev/null | wc -l | tr -d ' ')")
  if [ -f "$docf" ]; then
    doctor_at=$(dg_num "$(date -r "$docf" +%s 2>/dev/null || stat -c %Y "$docf" 2>/dev/null || echo 0)")
    [ "$doctor_at" -gt 0 ] && doc_txt=$(date -r "$docf" "+%F %T" 2>/dev/null || echo "有，但读不出时间")
  fi
  if [ -f "$QQBOT_HOME/deploy.done" ]; then
    dep_state="ok"
    dep_at=$(dg_num "$(date -r "$QQBOT_HOME/deploy.done" +%s 2>/dev/null || echo 0)")
    dep_txt="成功（$(date -r "$QQBOT_HOME/deploy.done" "+%F %T" 2>/dev/null)）"
  elif [ -f "$QQBOT_HOME/deploy.error" ]; then
    dep_state="fail"
    dep_detail=$(head -c 300 "$QQBOT_HOME/deploy.error" 2>/dev/null)
    dep_at=$(dg_num "$(date -r "$QQBOT_HOME/deploy.error" +%s 2>/dev/null || echo 0)")
    dep_txt="失败：$dep_detail"
  fi
  if [ -f "$QQ_KILLED_LOG" ]; then
    killed_n=$(dg_num "$(wc -l < "$QQ_KILLED_LOG" 2>/dev/null | tr -d ' ')")
    killed_last=$(tail -n 1 "$QQ_KILLED_LOG" 2>/dev/null)
  fi
  mark=$(cat "$QQ_START_MARK" 2>/dev/null | tr '\n' ' ')

  if [ "$so_b" -gt 0 ] && [ "$build_b" -gt 0 ] && [ "$records" -eq 0 ]; then
    verdict="崩溃记录器编译失败"
    hint="崩溃记录器没编出来（编译器报错见 cw-build.log），本机崩了不会留现场。把这份诊断发给开发者即可，不用重装。"
  elif [ "$so_b" -gt 0 ]; then
    verdict="崩溃记录器就位"
    hint=""
  elif [ "$records" -gt 0 ]; then
    verdict="有崩溃记录，但记录器文件不见了"
    hint="记录器可能被清理掉了：不影响现在的运行，但下次崩溃就抓不到了。重新部署一次可以恢复。"
  else
    verdict="这版还没初始化崩溃记录器"
    hint="若是刚装好还没部署完，属正常；部署完成后仍是这样，重新部署一次。"
  fi

  text="崩溃记录器 / 部署状态
记录器 : $([ "$so_b" -gt 0 ] && echo "已就位（$(dg_human "$so_b")）" || echo "还没有")
编译日志: $([ "$build_b" -gt 0 ] && echo "$build_b 字节（有编译报错，见 cw-build.log）" || echo "无")
崩溃记录: $records 条（$cwdir）
上次部署: $dep_txt
自检时间: $doc_txt
被杀记录: $killed_n 次$([ -n "$killed_last" ] && printf '（最近：%s）' "$killed_last")"
  [ -n "$mark" ] && text="$text
启动标记: $mark"

  items="{\"k\":\"崩溃记录器\",\"v\":\"$([ "$so_b" -gt 0 ] && echo 已就位 || echo 未初始化)\"},{\"k\":\"崩溃记录\",\"v\":\"$records 条\"},{\"k\":\"上次部署\",\"v\":\"$dep_state\"},{\"k\":\"被杀记录\",\"v\":\"$killed_n 次\"},{\"k\":\"结论\",\"v\":\"$(dg_esc "$verdict")\"}"

  if [ "$so_b" -gt 0 ]; then local so_js="true"; else local so_js="false"; fi
  dg_emit health "ok" "$verdict" "$hint" "$text" "[$items]" \
    "\"crash\":{\"so\":$so_js,\"soBytes\":$so_b,\"buildLogBytes\":$build_b,\"records\":$records}" \
    "\"crashDir\":\"$(qq_json_esc "$cwdir")\"" \
    "\"doctorAt\":$doctor_at" \
    "\"deploy\":{\"state\":\"$dep_state\",\"at\":$dep_at,\"detail\":\"$(dg_esc "$dep_detail")\"}" \
    "\"killed\":{\"count\":$killed_n,\"last\":\"$(dg_esc "$killed_last")\"}"
}

# -------------------------------------------------------------------- main ----
DG_WHAT="$1"
[ "$#" -gt 0 ] && shift
for a in "$@"; do
  case "$a" in
    --text) DG_TEXT=1 ;;
    *) [ -z "$DG_ARG" ] && DG_ARG="$a" ;;
  esac
done

case "$DG_WHAT" in
  netcheck|net) dg_netcheck ;;
  kernelcheck|kernel) dg_kernelcheck ;;
  disktop|disk) dg_disktop ;;
  bundle|pack) dg_bundle ;;
  health) dg_health ;;
  "")
    printf '%s\n' "用法: diagnose.sh <netcheck|kernelcheck|disktop|bundle|health> [参数] [--text]" >&2
    printf '%s\n' '{"ok":false,"what":"usage","state":"fail","title":"没给子命令","text":"用法: diagnose.sh <netcheck|kernelcheck|disktop|bundle|health> [参数] [--text]","items":[]}'
    exit 2 ;;
  *)
    printf '%s\n' "未知子命令：$DG_WHAT" >&2
    printf '%s\n' "{\"ok\":false,\"what\":\"$(qq_json_esc "$DG_WHAT")\",\"state\":\"fail\",\"title\":\"未知子命令\",\"text\":\"支持 netcheck / kernelcheck / disktop / bundle / health\",\"items\":[]}"
    exit 2 ;;
esac
