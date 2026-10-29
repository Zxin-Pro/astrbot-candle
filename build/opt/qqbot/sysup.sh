#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 系统级升级（sysup.sh）：给「更多 → 系统升级」卡片用的脚本层。
#
# 为什么单独一个脚本，而不是塞进 deploy.sh：部署是「从零装一遍」，升级是「在已经跑起来的
# 机器上就地更新」，两者失败后果完全不同（升级失败不能把已经能用的环境弄坏）。
# 分开之后，升级中途被打断最多让某个组件半新半旧，用户还能重跑一次 upgrade。
#
# 三条纪律：
#   1) 所有真正的安装/升级命令都在 proot 的 Ubuntu 里跑（Termux 侧没有 python3）。
#      唯一的例外是「读版本号」——纯文件读取，宿主侧读更快也不会碰坏 rootfs。
#   2) 只输出纯文本行，一行一件事，格式固定为 id|字段|字段|…，方便 Java 直接 split。
#      进度条不要，Java 靠轮询 tail 拿最新几行。
#   3) 任何一步失败都要用中文说清楚「哪一步、下一步干什么」，不要只回一个错误码。
#
# 子命令：version / check / upgrade <ubuntu|astrbot|napcat> / status

. "$PREFIX/opt/qqbot/common.sh"

# 升级进程的 pid：Java 侧「取消升级」要按这个 pid 把整个进程组（含 proot 里的 apt）带走。
SYSUp_PIDFILE="$QQBOT_HOME/sysup.pid"

say() { printf '%s\n' "$*"; }

# 读 JSON 文件里某个键的值：read_key <文件> <键>，取第一个匹配。
read_key() {
  [ -f "${1:-}" ] || return 0
  sed -n 's/.*"'"${2:-}"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${1:-}" 2>/dev/null | head -n 1
}

# 去掉版本号前面的 v（AstrBot 的 tag 是 v4.28.2，界面上显示 4.28.2 更好看）
bare_ver() { printf '%s' "${1:-}" | sed -e 's/^[vV]//'; }

# 当前版本（宿主侧读数，不进 proot）
cur_ubuntu() {
  local f="$QQBOT_ROOTFS/etc/os-release"
  [ -f "$f" ] || return 0
  sed -n 's/^VERSION_ID=//p' "$f" 2>/dev/null | head -n 1 | tr -d '"'"'"
}

cur_astrbot() {
  local v=""
  # 部署脚本装完会写一份 $QQBOT_CACHE/astrbot-ver，那是唯一权威记录。
  [ -s "$QQBOT_CACHE/astrbot-ver" ] && v="$(cat "$QQBOT_CACHE/astrbot-ver" 2>/dev/null)"
  if [ -z "$v" ]; then
    # 老版本没写这个文件：退到源码目录里的 __version__
    v="$(sed -n 's/^__version__[[:space:]]*=[[:space:]]*["'"'"']\([^"'"'"']*\)["'"'"'].*/\1/p' \
      "$QQBOT_ROOTFS/opt/AstrBot/astrbot/__init__.py" 2>/dev/null | head -n 1)"
  fi
  bare_ver "$v"
}

# 记录本次实际生效的 NapCat 版本：一行纯版本号（去掉 v 前缀）。
# 为什么单独落一个文件：一旦升级过，宿主机上可能同时存在旧解包目录和新文件，
# 从文件里现读容易读到写了一半的状态；这份标记是「已经成功装完」时才写的，可信。
napcat_ver_save() {
  local v
  v="$(bare_ver "${1:-}" | tr -d ' \t\r\n')"
  [ -n "$v" ] || return 0
  # 9.x 是 QQ 主程序的版本号，绝不能写进 NapCat 的标记（写了就再也自愈不了）
  case "$v" in
    9.*) return 0 ;;
  esac
  mkdir -p "$QQBOT_CACHE" 2>/dev/null || true
  printf '%s\n' "$v" > "$QQBOT_CACHE/napcat-ver" 2>/dev/null || true
}

# 最后一个来源：napcat.mjs 里抓版本字符串。
# 打包产物里实测出现过这些写法： "version":"4.18.38" / version: '4.18.38' / NapCat v4.18.38。
# 三种都试，抓不到就交白卷（上层显示「版本未知」），绝不猜、绝不拿别的版本凑数。
napcat_mjs_ver() {
  local f="$QQBOT_ROOTFS/opt/QQ/resources/app/app_launcher/napcat/napcat.mjs" v=""
  [ -f "$f" ] || return 0
  v="$(grep -oE '"?version"?[[:space:]]*[:=][[:space:]]*"?[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?"?' "$f" 2>/dev/null \
    | head -n 1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?')"
  [ -n "$v" ] || v="$(grep -oE 'NapCat[[:space:]]*[vV]?[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?' "$f" 2>/dev/null \
    | head -n 1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?')"
  printf '%s' "$v"
}

# NapCat 当前版本。只认下面三个来源，**绝不读 /opt/QQ/resources/app/package.json**：
# 那是 QQ 主程序自己的 package.json，里面是 QQ 的版本号（9.x）。之前退到它当兜底，
# 结果界面把 QQ 的 9.x 当成 NapCat 版本显示（1.3.5-beta.3 修的就是这个 bug）。
cur_napcat() {
  local v=""
  # 1) 部署 / 升级成功时写下的标记，最权威
  if [ -s "$QQBOT_CACHE/napcat-ver" ]; then
    # tr -d '\r'：标记文件是一行纯版本号，容忍别处写入时带的回车
    v="$(head -n 1 "$QQBOT_CACHE/napcat-ver" 2>/dev/null | tr -d '\r')"
  fi
  # 2) NapCat 自己的 package.json（Shell 版放在 QQ 的 app_launcher 下面）
  [ -n "$v" ] || v="$(read_key "$QQBOT_ROOTFS/opt/QQ/resources/app/app_launcher/napcat/package.json" version)"
  # 3) napcat.mjs 里的版本字符串
  [ -n "$v" ] || v="$(napcat_mjs_ver)"
  v="$(bare_ver "$v" | tr -d ' \t\r\n')"
  # 保险丝：任何来源读出来是 9.x 的一律当读错了丢掉（NapCat 现在是 4.x）。
  # 宁可显示「版本未知」，也不显示一个看起来很确定的错数字。
  case "$v" in
    9.*) v="" ;;
  esac
  printf '%s' "$v"
}

# 最新版本（GitHub releases/latest，common.sh 里带 24 小时缓存，不会每次都打网络）
latest_of() {
  local v=""
  case "${1:-}" in
    astrbot) v="$(qq_latest_ver astrbot 2>/dev/null)" ;;
    napcat)  v="$(qq_latest_ver napcat 2>/dev/null)" ;;
  esac
  bare_ver "$v"
}

# 版本比较只做「相同 / 不同」：升级卡片只需要告诉用户有没有新版，
# 谁大谁小交给上游（apt / pip 自己会判断）。输出 state|note 两段。
cmp_state() {
  local cur="${1:-}" latest="${2:-}"
  if [ -z "$cur" ]; then
    printf 'unknown|还没有装（先跑一遍部署）'
  elif [ -z "$latest" ]; then
    printf 'unknown|读不到最新版本（网络不通），当前 %s' "$cur"
  elif [ "$cur" = "$latest" ]; then
    printf 'ok|已是最新（%s）' "$cur"
  else
    printf 'old|可升级：%s → %s' "$cur" "$latest"
  fi
}

# ---------------------------------------------------------------------------
# version：三行 id|cur|note|state（前三段就是契约里的 id|cur|note）
# ---------------------------------------------------------------------------
cmd_version() {
  local u a n s note
  u="$(cur_ubuntu)"
  a="$(cur_astrbot)"
  n="$(cur_napcat)"

  # Ubuntu 没有「上游最新版号」这个概念：apt 里的版本取决于软件源，所以这里只报装没装，
  # 不跟 GitHub 比大小（拿 24.04 去比 24.04.1 只会让用户困惑）。
  if [ -z "$u" ]; then
    say "ubuntu||还没有装（先跑一遍部署）|unknown"
  else
    say "ubuntu|${u}|系统包由 apt 管理，点升级会更新到软件源里的最新版本|ok"
  fi

  s="$(cmp_state "$a" "$(latest_of astrbot)")"
  note="${s#*|}"
  say "astrbot|${a:-}|${note}|${s%%|*}"

  s="$(cmp_state "$n" "$(latest_of napcat)")"
  note="${s#*|}"
  say "napcat|${n:-}|${note}|${s%%|*}"
  return 0
}

# ---------------------------------------------------------------------------
# check：兼容性（AstrBot 要 Python ≥3.10，NapCat 要 Node）
# 一次 proot 进去把两个版本都问出来，别为了一句 python3 -V 起两次 proot。
# ---------------------------------------------------------------------------
cmd_check() {
  local out pyv nodev maj min ok msg

  if [ ! -d "$QQBOT_ROOTFS" ]; then
    say "astrbot|0|Ubuntu 沙盒还没解包，先跑一遍部署"
    say "napcat|0|Ubuntu 沙盒还没解包，先跑一遍部署"
    return 0
  fi

  out="$(qq_proot 'printf "PYV=%s\n" "$(python3 -V 2>&1 | sed -e "s/^Python[[:space:]]*//")"; printf "NODEV=%s\n" "$(node -v 2>&1)"' 2>/dev/null)"

  pyv="$(printf '%s\n' "$out" | sed -n 's/^PYV=//p' | head -n 1)"
  nodev="$(printf '%s\n' "$out" | sed -n 's/^NODEV=//p' | head -n 1)"

  # Python ≥ 3.10 是 AstrBot 的硬要求（它用了 3.10 的 match 语法）。
  ok=0
  maj="${pyv%%.*}"
  min="$(printf '%s' "$pyv" | cut -d. -f2)"
  case "$maj$min" in
    ''|*[!0-9]*) : ;;
    *) if [ "$maj" -gt 3 ] || { [ "$maj" -eq 3 ] && [ "$min" -ge 10 ]; }; then ok=1; fi ;;
  esac
  if [ -z "$pyv" ]; then
    msg="沙盒里读不到 Python 版本"
  elif [ "$ok" = "1" ]; then
    msg="Python ${pyv}，满足 ≥3.10"
  else
    msg="Python ${pyv} 太老，AstrBot 需要 ≥3.10"
  fi
  say "astrbot|${ok}|${msg}"

  case "$nodev" in
    v[0-9]*)
      say "napcat|1|Node ${nodev#v}，满足" ;;
    '')
      say "napcat|0|沙盒里没有 Node，NapCat 起不来（重新部署一次会装上）" ;;
    *)
      say "napcat|0|Node 版本读不出来：${nodev}" ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
# status：各组件现在跑没跑（只看端口，不改任何东西）
# ---------------------------------------------------------------------------
cmd_status() {
  local p
  if [ -d "$QQBOT_ROOTFS" ]; then
    say "ubuntu|ok|根文件系统就绪"
  else
    say "ubuntu|down|还没有解包 Ubuntu"
  fi

  p="$(qq_napcat_port 2>/dev/null)"
  case "$p" in ''|*[!0-9]*) p=6099 ;; esac
  if qq_port_open "$p"; then
    say "napcat|ok|正在运行（端口 ${p}）"
  else
    say "napcat|down|没在运行（端口 ${p} 不通）"
  fi

  p="$(qq_astrbot_port 2>/dev/null)"
  case "$p" in ''|*[!0-9]*) p=6185 ;; esac
  if qq_port_open "$p"; then
    say "astrbot|ok|正在运行（端口 ${p}）"
  else
    say "astrbot|down|没在运行（端口 ${p} 不通）"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# upgrade：真正干活的地方
# ---------------------------------------------------------------------------

up_ubuntu() {
  say "更新软件源清单（apt-get update）"
  if ! qq_proot 'apt-get update'; then
    say "更新软件源失败：多半是网络不通或源地址失效，换个网络再试一次"
    return 1
  fi

  say "升级已安装的系统包（这一步最慢，通常 5~20 分钟，别退出）"
  # --force-confold：配置文件有改动时保留本地那份，绝不弹交互对话框
  # （proot 里的 apt 一旦停在「是否保留配置文件」上，界面看起来就是永远卡住）。
  if ! qq_proot 'DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confold upgrade'; then
    say "系统包升级失败：先跑「设置 → 维护 → 修复沙盒依赖」，再回来重试"
    return 1
  fi

  say "清理 apt 下载缓存"
  qq_proot 'apt-get clean' >/dev/null 2>&1 || true
  say "Ubuntu 升级完成"
  return 0
}

up_astrbot() {
  local av src dash have
  av="$(qq_latest_ver astrbot 2>/dev/null)"
  [ -n "$av" ] || av="$QQBOT_ASTRBOT_VER"
  if [ -z "$av" ]; then
    say "查不到 AstrBot 的版本号，先换个网络再试"
    return 1
  fi
  say "目标版本：${av}（当前 $(cur_astrbot)）"

  # 分支一：venv 里真有 astrbot 这个 pip 包（有些安装方式会 pip install 一份），
  # 那 pip -U 就是最短路径。
  if qq_proot '[ -x /opt/AstrBot/.venv/bin/python ] && /opt/AstrBot/.venv/bin/python -m pip show astrbot >/dev/null 2>&1' >/dev/null 2>&1; then
    say "这个 AstrBot 是 pip 安装的，用 pip 升级"
    if ! qq_proot 'cd /opt/AstrBot && ./.venv/bin/python -m pip install --disable-pip-version-check -U astrbot'; then
      say "pip 升级失败：检查一下网络，或者到「日志」页看看 pip 的原话"
      return 1
    fi
    say "AstrBot 升级完成（pip）"
    return 0
  fi

  # 分支二（绝大多数情况）：本项目的 AstrBot 是「源码 + venv」装法，
  # pip 里根本没有 astrbot 这个包，只能整包换源码。做法与部署步骤 6 完全一致，
  # 保证升级后目录结构、控制台前端、依赖三者仍然对得上。
  src="$QQBOT_CACHE/AstrBot-src.tar.gz"
  dash="$QQBOT_CACHE/AstrBot-dashboard.zip"
  have="$(cat "$QQBOT_CACHE/astrbot-ver" 2>/dev/null)"
  if [ "$have" != "$av" ] || [ ! -s "$src" ] || [ ! -s "$dash" ]; then
    rm -f "$src" "$dash" 2>/dev/null
    say "下载 AstrBot ${av} 源码（约 4 MB）"
    if ! qq_gh_dl "$src" "https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/${av}.tar.gz"; then
      say "源码下载失败：网络不通或者这个版本已经被删了"
      return 1
    fi
    say "下载 AstrBot ${av} 控制台（约 5 MB）"
    if ! qq_gh_dl "$dash" "https://github.com/AstrBotDevs/AstrBot/releases/download/${av}/AstrBot-${av}-dashboard.zip"; then
      say "控制台下载失败：网络不通或者这个版本没有对应的控制台包"
      return 1
    fi
  else
    say "本地已经有 ${av} 的安装包，跳过下载"
  fi

  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  printf '%s\n' "$av" > "$QQBOT_CACHE/astrbot-ver" 2>/dev/null

  say "在沙盒里替换源码并更新依赖（5~15 分钟，期间别退出）"
  if ! qq_sandbox 60-astrbot.sh; then
    say "AstrBot 安装失败：到「日志」页看 60-astrbot 的输出，修好后可以再点一次升级"
    return 1
  fi
  say "AstrBot 升级完成（${av}），重启后生效"
  return 0
}

up_napcat() {
  local nv ncurl nc official rel

  # 先找官方升级脚本：NapCat 官方只发安装包（NapCat.Shell.zip），
  # 但有些版本会在包里带一个 napcat-update.sh，找到就用官方的。
  for official in \
    "$QQBOT_ROOTFS/opt/QQ/resources/app/app_launcher/napcat/napcat-update.sh" \
    "$QQBOT_ROOTFS/root/napcat/napcat-update.sh" \
    "$QQBOT_ROOTFS/opt/napcat/napcat-update.sh"; do
    if [ -f "$official" ]; then
      rel="${official#"$QQBOT_ROOTFS"}"
      say "找到官方升级脚本：$rel"
      if qq_proot "bash $rel"; then
        say "NapCat 升级完成（官方脚本）"
        # 官方脚本不告诉我们版本号，升级完从文件里读一次现状落标记。
        # 注意先删旧标记：cur_napcat 第一优先级就是标记，不删会把升级前的旧版本原样写回去。
        rm -f "$QQBOT_CACHE/napcat-ver" 2>/dev/null || true
        napcat_ver_save "$(cur_napcat)"
        return 0
      fi
      say "官方脚本执行失败，改用安装包覆盖的方式"
      break
    fi
  done

  nv="$(qq_latest_ver napcat 2>/dev/null)"
  [ -n "$nv" ] || nv="$QQBOT_NAPCAT_VER"
  if [ -z "$nv" ]; then
    say "这个组件需要手动升级：查不到 NapCat 的最新版本号（网络不通）"
    return 1
  fi
  ncurl="https://github.com/NapNeko/NapCatQQ/releases/download/${nv}/NapCat.Shell.zip"
  say "目标版本：${nv}（当前 $(cur_napcat)）"

  nc="$QQBOT_CACHE/NapCat.Shell.zip"
  rm -f "$nc" 2>/dev/null
  say "下载 NapCat.Shell.zip（约 34 MB）"
  if ! qq_gh_dl "$nc" "$ncurl"; then
    say "这个组件需要手动升级：安装包下载失败"
    say "手动命令（在沙盒终端里执行）：curl -fL -o /qqbot-cache/NapCat.Shell.zip $ncurl && bash /qqbot-opt/sandbox/50-napcat.sh"
    return 1
  fi

  say "把新版本文件覆盖进 QQ（配置目录不会被覆盖）"
  if ! qq_sandbox 50-napcat.sh; then
    say "NapCat 覆盖失败：到「日志」页看输出；配置没被改动，修好后可以再点一次升级"
    return 1
  fi
  say "NapCat 升级完成（${nv}），重启后生效"
  # 覆盖装完就把版本写进标记：下次读版本直接读它，不再去文件里猜
  napcat_ver_save "$nv"
  return 0
}

cmd_upgrade() {
  local id="${1:-}"
  case "$id" in
    ubuntu|astrbot|napcat) : ;;
    '') say "用法：sysup.sh upgrade <ubuntu|astrbot|napcat>"; return 2 ;;
    *) say "不认识的组件：${id}（只能是 ubuntu / astrbot / napcat）"; return 2 ;;
  esac

  # 记下自己的 pid：Java 的「取消升级」会按它把整个进程组杀掉（proot 里的 apt 也要带走）。
  mkdir -p "$QQBOT_HOME" 2>/dev/null
  printf '%s\n' "$$" > "$SYSUp_PIDFILE" 2>/dev/null
  # 退出时把退出码打成一行 __QQBOT_RC=N：Java 侧的 poll() 靠这一行判断「跑完了 / 失败了」，
  # 比去猜进程还在不在更准（proot 里的 apt 会另起子进程，父进程活着不代表还在干活）。
  # 信号要先变成 exit，EXIT 钩子才会执行；不 trap INT TERM 的话 bash 会被直接打死，
  # pid 文件就会残留下来，界面永远显示「升级中」。
  trap 'rc=$?; rm -f "$SYSUp_PIDFILE" 2>/dev/null; printf "__QQBOT_RC=%s\n" "$rc"' EXIT
  trap 'exit 143' INT TERM HUP

  say "开始升级：${id}"
  case "$id" in
    ubuntu)  up_ubuntu || return 1 ;;
    astrbot) up_astrbot || return 1 ;;
    napcat)  up_napcat || return 1 ;;
  esac
  return 0
}

main() {
  local cmd="${1:-}"
  if [ $# -gt 0 ]; then shift; fi
  case "$cmd" in
    version) cmd_version ;;
    check)   cmd_check ;;
    status)  cmd_status ;;
    upgrade) cmd_upgrade "${1:-}" ;;
    ''|-h|--help|help)
      say "用法：sysup.sh version|check|status|upgrade <ubuntu|astrbot|napcat>"
      return 0 ;;
    *)
      say "不认识的子命令：${cmd}"
      say "用法：sysup.sh version|check|status|upgrade <ubuntu|astrbot|napcat>"
      return 2 ;;
  esac
}

main "$@"
