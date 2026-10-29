#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# qqbot-sandbox 公共函数库

QQBOT_HOME="$HOME/.qqbot"
QQBOT_CACHE="$QQBOT_HOME/cache"
# 原生层（App）和脚本层共享的状态目录：风控命中（risk.json / risk-<id>.flag）、
# 降载标记（degrade）、紧急下线标记（offline）都在这里，协议见
# docs/接口约定-1.1.30.md 第 2 / 4 节。start-sandbox.sh 用
# -b "$QQBOT_STATE:/qqbot-state" 把它绑进沙盒，所以沙盒里同一批文件叫 /qqbot-state/*。
QQBOT_STATE="$QQBOT_HOME/state"
QQBOT_LOGS="$QQBOT_HOME/logs"
QQBOT_STEPS="$QQBOT_HOME/steps"
QQBOT_OPT="$PREFIX/opt/qqbot"
QQBOT_ROOTFS="$HOME/ubuntu"
QQBOT_PTMP="$QQBOT_HOME/proot-tmp"
# 沙盒内 /dev 是宿主 /dev 的绑定，宿主 /dev 里没有可写的 shm，
# 而 Electron(QQ) 启动时必须能读写 /dev/shm，否则直接 FATAL 崩溃：
#   Unable to access(W_OK|X_OK) /dev/shm: No such file or directory (2)
# 所以自建一个目录，在 proot 参数里覆盖挂到 /dev/shm。
QQBOT_DEVDIR="$QQBOT_HOME/dev"
# 下载代理配置文件：由 App 的设置页写入（纯文本，一行 URL，可能为空或不存在）。
# 所有下载（rootfs / QQ / NapCat / AstrBot / apt / pip）都要走这个代理。
QQBOT_PROXY_FILE="$QQBOT_HOME/proxy.txt"
# proot 自检的输出落盘，自检失败时原样打印。老版本把 stderr 丢进 /dev/null，
# 真机上分不清到底是「文件不存在」还是「被系统拦了 ptrace」，只能瞎猜。
QQBOT_SELFTEST_LOG="$QQBOT_LOGS/proot-selftest.log"

QQBOT_QQ_DEB_BASE="https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef"
QQBOT_QQ_VER="3.2.34-53644"
QQBOT_NAPCAT_VER="v4.18.38"
QQBOT_ASTRBOT_VER="v4.28.2"
# 1.3.5-beta.3：沙盒空间预留。部署整包要解出 2 GB 多的 rootfs，还要装 apt 包，
# 以前只在写目录失败后提示「只剩 xx MB」，用户看到时已经装到一半了。现在部署前先卡一道。
QQBOT_MIN_FREE_MB="${QQBOT_MIN_FREE_MB:-3072}"     # 首次部署至少留 3 GB
QQBOT_RESUME_FREE_MB="${QQBOT_RESUME_FREE_MB:-1024}" # 续跑/升级至少留 1 GB

# ---- v1.1.31：风控改「滑动窗口」+ 自动过期 ----
# 单次命中只告警，窗口内累计达到阈值才暂停自动重启：很多命中是某条消息触发的
# 误报，第一次就停下会让用户以为软件坏了；但真被风控时反复重启又会让风控更狠，
# 所以用窗口 + 阈值折中。三个值都可以在 qq-env.txt 里覆写，方便按机型调。
QQBOT_RISK_WINDOW="${QQBOT_RISK_WINDOW:-600}"     # 窗口长度（秒）
QQBOT_RISK_HITS="${QQBOT_RISK_HITS:-2}"           # 窗口内累计几次才暂停
QQBOT_RISK_TTL="${QQBOT_RISK_TTL:-21600}"         # 暂停标记多久自动过期（秒）

# ---- v1.1.31：组件版本自动获取 ----
# 缓存 24 小时；查不到就用上面那三个内置常量（部署绝不能因为查版本而失败）。
QQBOT_VER_CACHE="$QQBOT_STATE/versions.json"
QQBOT_VER_TTL="${QQBOT_VER_TTL:-86400}"
# 手动覆盖：写在 qq-env.txt 或 ~/.qqbot/env.sh 里都生效，优先级高于查询结果。
QQBOT_NAPCAT_VER_OVERRIDE="${QQBOT_NAPCAT_VER_OVERRIDE:-}"
QQBOT_ASTRBOT_VER_OVERRIDE="${QQBOT_ASTRBOT_VER_OVERRIDE:-}"

QQBOT_TUNA="https://mirrors.tuna.tsinghua.edu.cn"
QQBOT_PYPI="$QQBOT_TUNA/pypi/simple"
QQBOT_GH_MIRRORS="https://ghfast.top/ https://gh-proxy.com/ https://ghproxy.net/ https://github.moeyy.xyz/ https://gh.llkk.cc/ https://ghproxy.cc/ https://gh.xxooo.cf/ https://gh-proxy.net/ https://hub.gitmirror.com/"

# 1.2.1：沙盒镜像（bundle-<arch>.tar.xz）放在官网 /dl/ 下，部署时直连下载。
# 只有整包镜像模式才用得到：部署前 export QQBOT_BUNDLE=1 才去下这份 604 MB 的镜像，
# 默认走官方镜像站在线安装（清华 / 中科大 / 阿里），不再默认下整包。
# 换地址只要改这里，或者部署前 export QQBOT_CDN=…（任何支持 Range 的静态目录都行）。
QQBOT_CDN="${QQBOT_CDN:-https://xn--15t839a.xyz/dl}"

# 1.2.2：包名从 com.termux 改成 zhuxin.astrbot 之后，curl 的编译期 CA 路径还指着
# /data/data/com.termux/files/usr/etc/tls/cert.pem，所有 https 下载都会报
#   curl: (77) error setting certificate file: /data/data/com.termux/...
# 这个路径是 ELF 里的常量，长度不同没法原地改，所以用环境变量把它指回新前缀。
# curl 认 CURL_CA_BUNDLE，OpenSSL 系工具认 SSL_CERT_FILE，两个都设。
if [ -f "$PREFIX/etc/tls/cert.pem" ]; then
  export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-$PREFIX/etc/tls/cert.pem}"
  export SSL_CERT_FILE="${SSL_CERT_FILE:-$PREFIX/etc/tls/cert.pem}"
fi

mkdir -p "$QQBOT_HOME" "$QQBOT_CACHE" "$QQBOT_STATE" "$QQBOT_LOGS" "$QQBOT_STEPS" "$QQBOT_PTMP" "$QQBOT_DEVDIR/shm" 2>/dev/null
chmod 1777 "$QQBOT_DEVDIR/shm" 2>/dev/null

qqlog()  { printf '\033[36m[QQBot]\033[0m %s\n' "$*"; }
qqok()   { printf '\033[32m[QQBot]\033[0m %s\n' "$*"; }
# 注意：警告必须走 stderr。qq_astrbot_port / qq_napcat_port / qq_onebot_port
# 的调用方普遍写成 port=$(qq_xxx_port)，若警告走 stdout 就会被命令替换一并捕获，
# 端口变量里混进警告文本（"http://127.0.0.1:[QQBot] bots.json ...6099"）。
qqwarn() { printf '\033[33m[QQBot]\033[0m %s\n' "$*" >&2; }

# 可用空间闸门：$1=目录，$2=最少 MB。取不到 df 就放行（不能让统计失败挡住部署）。
qq_space_gate() {
  _sg_kb=$(df -k "$1" 2>/dev/null | awk 'NR>1 { print $4; exit }')
  [ -n "$_sg_kb" ] || return 0
  if [ "$_sg_kb" -lt $(($2 * 1024)) ] 2>/dev/null; then
    _sg_gb=$((($2 + 511) / 1024))
    qqerr "手机剩余空间不够：部署沙盒至少要留 ${_sg_gb} GB 可用空间（建议 4 GB），现在只剩 $((_sg_kb / 1024)) MB。先清一点空间再试"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# 最近几条错误单独留一份给界面看（deploy.error）。
#
# 为什么要单独留：部署失败时用户只能看到一个「第 3 步没装完」，得自己去日志页
# 翻几百行 apt 输出才知道原因。这个文件让首页直接显示「上次失败原因」，也让
# 「复制部署日志」有确定的内容可复制。只保留最后 12 行，避免越滚越大。
# 写失败一律忽略：日志功能绝不能反过来把部署弄挂。
qq_err_note() {
  [ -n "$QQBOT_HOME" ] || return 0
  [ -d "$QQBOT_HOME" ] || return 0
  printf '%s %s\n' "$(date '+%H:%M:%S' 2>/dev/null)" "$*" >> "$QQBOT_HOME/deploy.error" 2>/dev/null || return 0
  if [ -f "$QQBOT_HOME/deploy.error" ]; then
    tail -n 12 "$QQBOT_HOME/deploy.error" > "$QQBOT_HOME/deploy.error.tmp" 2>/dev/null \
      && mv -f "$QQBOT_HOME/deploy.error.tmp" "$QQBOT_HOME/deploy.error" 2>/dev/null
  fi
  return 0
}
qqerr()  { printf '\033[31m[QQBot]\033[0m %s\n' "$*" >&2; qq_err_note "$*"; }

# ---------------------------------------------------------------------------
# 沙盒目录体检：能否写 / 能否建硬链接 / 还剩多少空间
#
# 为什么单独做：dpkg 备份 status 用的是 link(2)，dpkg 每次装包第一步就调它。
# 而「写不进去」和「不能建硬链接」是两种完全不同的毛病，旧代码把它们混成一句
# 「不可写，或者不支持硬链接」，用户拿着这句话没法处理，作者也判断不了。
#
# 真机案例（Redmi / Android 16）：写测试通过、link() 却报 Permission denied。
# 所以这里不止测目标目录，还横向测 HOME / cache / PREFIX/tmp——只有把几个位置
# 摆在一起，才能分清是「这台手机的存储整体不支持」还是「这个目录被弄脏了」。
# 完整诊断写进 $QQBOT_HOME/fs-probe.txt，界面的「上传错误日志」会带上它。
#
# 返回值：0 正常 / 1 写不进去 / 3 目标目录建不了硬链接（别处可以，建议重建目录）
#         / 4 这台手机根本建不了硬链接
# ---------------------------------------------------------------------------
# 在某个目录里试一次「写 + 硬链接」：0 成功 / 1 硬链接失败 / 2 写失败
# 失败原因放 qq_probe_why，供调用方原样打印。
qq_ln_probe() {
  _pd=$1
  _perr=$QQBOT_CACHE/.probe.err
  qq_probe_why=''
  mkdir -p "$_pd" 2>/dev/null || { qq_probe_why='目录建不出来'; return 2; }
  if ! : > "$_pd/.qqbot-wtest" 2>"$_perr"; then
    qq_probe_why=$(head -n 1 "$_perr" 2>/dev/null)
    rm -f "$_pd/.qqbot-wtest" 2>/dev/null
    return 2
  fi
  if ln "$_pd/.qqbot-wtest" "$_pd/.qqbot-htest" 2>"$_perr"; then
    rm -f "$_pd/.qqbot-wtest" "$_pd/.qqbot-htest" 2>/dev/null
    return 0
  fi
  qq_probe_why=$(head -n 1 "$_perr" 2>/dev/null)
  rm -f "$_pd/.qqbot-wtest" "$_pd/.qqbot-htest" 2>/dev/null
  return 1
}

qq_fsprobe() {
  _d=${1:-$QQBOT_ROOTFS}
  _rep=$QQBOT_HOME/fs-probe.txt
  mkdir -p "$_d" 2>/dev/null || true
  {
    echo "== 沙盒目录体检 $(date '+%F %T' 2>/dev/null) =="
    echo "目标目录: $_d"
    echo "身份: uid=$(id -u 2>/dev/null) gid=$(id -g 2>/dev/null)"
    echo "架构: $(uname -m 2>/dev/null)   内核: $(uname -r 2>/dev/null)"
    echo "SELinux: $(getenforce 2>/dev/null || echo unknown)   protected_hardlinks: $(cat /proc/sys/fs/protected_hardlinks 2>/dev/null)"
    echo "HOME=$HOME"
    echo "PREFIX=$PREFIX"
  } > "$_rep" 2>/dev/null || true
  if [ ! -d "$_d" ]; then
    qqerr "沙盒目录 $_d 建不出来（连 mkdir 都失败）"
    echo 'mkdir 失败：目录不存在也建不出来' >> "$_rep" 2>/dev/null || true
    return 1
  fi
  # ---- 1) 目标目录：写 + 硬链接 ----
  qq_ln_probe "$_d"; _t=$?
  _werr=''; _herr=''
  if [ "$_t" = 2 ]; then
    _werr=$qq_probe_why
    chmod u+rwx "$_d" 2>/dev/null || true
    chmod -R u+rwX "$_d" 2>/dev/null || true
    qq_ln_probe "$_d"; _t=$?
    [ "$_t" = 2 ] && _werr=$qq_probe_why
  fi
  [ "$_t" = 1 ] && _herr=$qq_probe_why
  # ---- 2) 横向对照：其它几个位置能不能建硬链接 ----
  _alt=0
  for _x in "$HOME" "$QQBOT_CACHE" "$PREFIX/tmp"; do
    [ -d "$_x" ] || continue
    qq_ln_probe "$_x"
    _st=$?
    case $_st in
      0) _sv='可以' ;;
      1) _sv="不行（$qq_probe_why）" ;;
      *) _sv='写不进去' ;;
    esac
    echo "对照 $_x: $_sv" >> "$_rep" 2>/dev/null || true
    [ "$_st" = 0 ] && _alt=1
  done
  # ---- 3) 空间 ----
  _df=$(df -k "$_d" 2>/dev/null | tail -n 1)
  _avail=$(echo "$_df" | awk '{print $4}' 2>/dev/null)
  _use=$(echo "$_df" | awk '{print $5}' 2>/dev/null)
  {
    echo "目录权限: $(ls -ldn "$_d" 2>/dev/null)"
    echo "上级目录: $(ls -ldn "$(dirname "$_d")" 2>/dev/null)"
    echo "目录内抽样: $(ls -ldn "$_d"/* 2>/dev/null | head -n 3 | tr '\n' '|')"
    echo "空间: ${_df:-取不到}   可用KB=${_avail:-?}   使用率=${_use:-?}"
    echo "写测试: $([ "$_t" != 2 ] && echo 通过 || echo 失败)   ${_werr}"
    echo "硬链接: $([ "$_t" = 0 ] && echo 通过 || echo 失败)   ${_herr}"
    echo "挂载: $(mount 2>/dev/null | grep -F ' on /data' | head -n 2 | tr '\n' '|')"
    echo "SELinux 标签: $(ls -Zd "$_d" 2>/dev/null) | $(ls -Zd "$HOME" 2>/dev/null)"
  } >> "$_rep" 2>/dev/null || true
  if [ "$_t" = 2 ]; then
    rm -f "$QQBOT_CACHE/fs-nolink" 2>/dev/null || true
    : > "$QQBOT_CACHE/fs-nowrite" 2>/dev/null || true
    qqerr "沙盒目录 $_d 写不进去：${_werr:-未知错误}"
    if [ -n "$_avail" ] && [ "$_avail" -lt $((QQBOT_MIN_FREE_MB * 1024)) ] 2>/dev/null; then
      qqerr "手机剩余空间不够：部署沙盒至少要留 $((QQBOT_MIN_FREE_MB / 1024)) GB 可用空间（建议 4 GB），现在只剩 $((_avail / 1024)) MB。先清一点空间再试"
    fi
    qqerr "如果这条一直出现：设置页 → 重置部署标记 → 重新部署；还不行就卸载重装（安装时选内部存储）"
    return 1
  fi
  if [ "$_t" = 1 ]; then
    : > "$QQBOT_CACHE/fs-nolink" 2>/dev/null || true
    if [ "$_alt" = 1 ]; then
      qqerr "$_d 里建不了硬链接：${_herr:-未知错误}"
      qqerr "同一个手机上别的目录（HOME / cache）可以建，说明是这个目录被弄脏了，下一步会把它删掉重建"
      return 3
    fi
    # 真机（Redmi / Android 16）实测：目录属主正确、写测试通过，但 link(2) 就是 EPERM。
    # 这种机器上 apt 装任何包都会死在「备份 /var/lib/dpkg/status」这一步。
    # 解法与 AstrBot-Bubble-Android-App 一致：让 proot 用 --link2symlink 把硬链接
    # 模拟成符号链接（proot 内部用 rename + symlink 实现，不依赖文件系统支持硬链接）。
    : > "$QQBOT_HOME/link2symlink" 2>/dev/null || true
    qqwarn "这台手机的存储不支持硬链接（${_herr:-未知错误}）"
    qqwarn "已改用 proot 的 --link2symlink 模拟硬链接，apt/dpkg 可以正常工作了"
    qqlog "沙盒目录体检通过（可写；硬链接用符号链接模拟）"
    return 0
  fi
  rm -f "$QQBOT_CACHE/fs-nowrite" "$QQBOT_CACHE/fs-nolink" 2>/dev/null || true
  # 这次实测硬链接是通的，就把「用符号链接模拟硬链接」的标记撤掉。
  # 标记原本是粘性的：一旦某次体检（或早期版本的误判）写下 link2symlink，
  # 之后每次进沙盒都带着 --link2symlink，proot 便把 link() 改成 rename+symlink。
  # 模拟出来的链接目标写的是**宿主绝对路径**，只要根文件系统的写法一变
  # （/data/data 与 /data/user/0 是同一块存储的两个路径），链接就悬空，
  # dpkg 备份 /var/lib/dpkg/status 时报 EPERM，自愈再判「不支持硬链接」，循环。
  # 真机上实测过：这条标记是 10-19 排查 ENOSYS 时留下的，而 fs-probe 一直是「通过」。
  if [ -f "$QQBOT_HOME/link2symlink" ]; then
    rm -f "$QQBOT_HOME/link2symlink" 2>/dev/null || true
    qqlog "已撤掉 --link2symlink 标记（本机硬链接可用，不再需要模拟）"
  fi
  qqlog "沙盒目录体检通过（可写、硬链接可用${_avail:+，剩余 $((_avail / 1024)) MB}）"
  return 0
}
# ---------------------------------------------------------------------------
# 端口配置（从 bots.json 读）
#
# 本文件在 Termux 侧执行，那里没有 python3（只有沙盒内的 Ubuntu 才有），所以
# 只能靠 sed / grep 解析。Java 侧写 bots.json 用的是无换行缩进的紧凑 JSON，
# 下面的模式都是按这种格式写的。
# ---------------------------------------------------------------------------

# bots.json 的位置：优先用控制台真正在写的 $QQBOT_HOME/bots.json，
# 它不存在或为空（首次部署时）就退回 $QQBOT_CACHE/bots.json 那份副本。
qq_bots_json() {
  if [ -s "$QQBOT_HOME/bots.json" ]; then
    printf '%s' "$QQBOT_HOME/bots.json"
  else
    printf '%s' "$QQBOT_CACHE/bots.json"
  fi
}

# AstrBot 控制台（dashboard）端口：bots.json 顶层键 astrbotPort。
# 0 / 缺失 / 解析失败一律回退 6185（AstrBot 自带的默认端口）。
#
# 用 sed 抓 "astrbotPort" 冒号后的第一个数字，而不是抓整段值：这样值里混进
# 别的字符也只取到端口号。模式里的 [0-9] 同时挡掉了 "astrbotPort": 0（0 表示
# "自动"）和 true / null 这类非数字值。
qq_astrbot_port() {
  local f v
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    v=$(sed -n 's/.*"astrbotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' "$f" 2>/dev/null | head -n 1)
  fi
  case "${v:-}" in
    ''|0) v=6185 ;;
    # >65535 也要挡住：sed 只取前 5 位数字，77777 这种值能原样通过，而
    # 沙盒里的 run-astrbot.sh 是靠自己再查一遍范围的，两边不一致就会出现
    # 「界面显示 77777、实际听 6185」。这里与 run-astrbot.sh 的规则对齐。
    *[!0-9]*) qqwarn "bots.json 的 astrbotPort 不是有效端口（$v），回退 6185"; v=6185 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 的 astrbotPort 超出范围（$v），回退 6185"; v=6185; } ;;
  esac
  printf '%s' "$v"
}

# 当前实例（bots.json 的 active，取不到就用第一个实例）的 NapCat WebUI 端口，
# 缺失 / 解析失败回退 6099。
#
# 先把 bots 数组整段抽出来，再在数组里找实例：不能拿整个文件去搜 "id":"bot1"，
# 顶层别的键里如果也出现同样的片段，就会抓到错误的那一个。
qq_napcat_port() {
  local f v ids act
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    ids=$(sed -n 's/.*"bots"[[:space:]]*:[[:space:]]*\[//p' "$f" 2>/dev/null)
    act=$(sed -n 's/.*"active"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" 2>/dev/null | head -n 1)
    # 单个实例对象里 "id" 与 "napcatPort" 之间没有别的引号字段，所以
    # "id"...napcatPort 这个跨字段模式只会落在同一个对象上（name / qq / token
    # 的值都是不含引号的字符串，不会在中间插进一个假的 "id"）。
    if [ -n "$act" ]; then
      v=$(printf '%s\n' "$ids" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$act"'"[^{}]*"napcatPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
    if [ -z "$v" ]; then
      # 回退到数组里第一个实例。必须先把范围收窄到第一个对象再匹配：
      # ids 是整行（多个实例对象挤在同一行），sed 的 .* 是贪婪的，直接匹配
      # "napcatPort" 会落到本行最后一个实例上（head -n 1 救不回来，因为
      # 整行只会产出一条匹配）。单实例对象内部没有嵌套 {}，所以先用
      # ^[^{]*{...} 把第一个对象的内容切出来，再在里面取值。
      v=$(printf '%s\n' "$ids" | sed -n 's/^[^{]*{\([^}]*\)}.*/\1/p' | sed -n 's/.*"napcatPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
  fi
  case "${v:-}" in
    '') v=6099 ;;
    0|*[!0-9]*) qqwarn "bots.json 里实例 $act 的 napcatPort 不是有效端口（$v），回退 6099"; v=6099 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 里实例 $act 的 napcatPort 超出范围（$v），回退 6099"; v=6099; } ;;
  esac
  printf '%s' "$v"
}

# 当前实例（bots.json 的 active，取不到就用第一个实例）的 OneBot v11 端口，
# 也就是 NapCat 提供给 AstrBot 的 aiocqhttp 适配器监听的那个端口，
# 缺失 / 解析失败回退 6199。
#
# 解析规则与 qq_napcat_port() 完全同构，只把字段名换成 onebotPort：
# 先整段抽出 bots 数组（不能拿整个文件搜 "id":"bot1"，顶层别的键里出现同样的
# 片段就会抓错），再按 "id" -> onebotPort 的跨字段模式在同一个对象里取值。
qq_onebot_port() {
  local f v ids act
  f="$(qq_bots_json)"
  v=""
  if [ -s "$f" ]; then
    ids=$(sed -n 's/.*"bots"[[:space:]]*:[[:space:]]*\[//p' "$f" 2>/dev/null)
    act=$(sed -n 's/.*"active"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" 2>/dev/null | head -n 1)
    # 同 qq_napcat_port()：单个实例对象里 "id" 与 "onebotPort" 之间没有别的引号
    # 字段，所以 [^{}] 这个跨字段模式只会落在同一个实例对象上。
    if [ -n "$act" ]; then
      v=$(printf '%s\n' "$ids" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$act"'"[^{}]*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
    if [ -z "$v" ]; then
      # 同 qq_napcat_port()：先切出第一个实例对象再取值。这里不能直接匹配
      # "onebotPort"，否则贪婪的 .* 会落到同一行最后一个实例上，回退值就不是
      # 「数组里第一个实例的 onebotPort」了。
      v=$(printf '%s\n' "$ids" | sed -n 's/^[^{]*{\([^}]*\)}.*/\1/p' | sed -n 's/.*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
    fi
  fi
  case "${v:-}" in
    '') v=6199 ;;
    0|*[!0-9]*) qqwarn "bots.json 里实例 $act 的 onebotPort 不是有效端口（$v），回退 6199"; v=6199 ;;
    *) [ "$v" -gt 65535 ] && { qqwarn "bots.json 里实例 $act 的 onebotPort 超出范围（$v），回退 6199"; v=6199; } ;;
  esac
  printf '%s' "$v"
}

# ---------------------------------------------------------------------------
# 架构自适应
#
# 不能用 uname 判断：MuMu 等 x86_64 模拟器会把内核 uname 伪装成 aarch64
# （实测 /system/bin/uname -a 返回 "aarch64 Toybox"，但 ro.product.cpu.abi
#  是 x86_64）。这类设备靠 houdini 把 arm64 指令翻译成 x86_64 执行。
#
# 普通程序经 houdini 翻译没问题，但 proot 完全依赖 ptrace 读寄存器与系统调用号，
# 翻译层下 aarch64 的 user_regs_struct 布局与真实 x86_64 寄存器对不上，
# proot 会永久挂死（实测 proot --link2symlink -0 echo 挂死，
# PROOT_NO_SECCOMP=1 也挂死，timeout -s KILL 都杀不掉，还会拖垮整个模拟器）。
#
# 因此 x86_64 设备改用随包内置的静态 x86_64 proot（proot-me 5.5.0），
# 并配 amd64 的 Ubuntu rootfs / amd64 的 Linux QQ。
# 这样 proot 与其全部子进程都是原生 x86_64，ptrace 正常（已在 MuMu 上实测通过）。
# ---------------------------------------------------------------------------
qq_detect_arch() {
  local abi=""
  if [ -x /system/bin/getprop ]; then
    abi="$(/system/bin/getprop ro.product.cpu.abi 2>/dev/null)"
  fi
  if [ -z "$abi" ]; then
    abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
  fi
  case "$abi" in
    x86_64|x86|i686|amd64) QQBOT_ARCH="amd64" ;;
    arm64*|aarch64*)       QQBOT_ARCH="arm64" ;;
    *)
      case "$(uname -m 2>/dev/null)" in
        x86_64|amd64) QQBOT_ARCH="amd64" ;;
        *)            QQBOT_ARCH="arm64" ;;
      esac
      ;;
  esac
  QQBOT_ABI="$abi"
}
qq_detect_arch

# proot 可执行文件：arm64 用 Termux 仓库里的，x86_64 用内置静态版
QQBOT_PROOT="$PREFIX/bin/proot"
QQBOT_PROOT_BUNDLED="$PREFIX/bin/proot-x86_64"
if [ "$QQBOT_ARCH" = "amd64" ]; then
  # amd64 设备（含 MuMu 这类把 uname 伪装成 aarch64 的 x86_64 模拟器）必须用内置的
  # x86_64 proot。缺了它就只能落到 $PREFIX/bin/proot，而那多半是 Termux 仓库里的
  # arm64 版，在 houdini 翻译层下 ptrace 拿不到正确寄存器、会永久挂死。
  # 所以这里宁可明确报错退出，也不要静默降级成那个必然挂死的组合。
  if [ ! -f "$QQBOT_PROOT_BUNDLED" ]; then
    qqerr "缺少内置的 x86_64 proot：$QQBOT_PROOT_BUNDLED"
    qqerr "本机是 amd64 架构（ro.product.cpu.abi=$QQBOT_ABI），用 $QQBOT_PROOT 会挂死。"
    qqerr "请重新安装完整的 qqbot 包（bin/proot-x86_64 是随包内置的）。"
    exit 1
  fi
  chmod 755 "$QQBOT_PROOT_BUNDLED" 2>/dev/null
  QQBOT_PROOT="$QQBOT_PROOT_BUNDLED"
fi
# ---------------------------------------------------------------------------
# beta.5：PROOT_NO_SECCOMP —— 有些内核会拦掉 proot 的 seccomp 加速
#
# 真机报告 20261027-145000-3886（三星 SM-S9280 / One UI Android 16 / 内核 6.1.145）
# 上 proot 自己打印了解法：
#   proot error: execve("/system/bin/sh"): Operation not permitted
#   proot info: It seems your kernel contains this bug: .../bug/1202161
#   To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.
# 而 beta.4 以前只按一种方式试一次，试不通就报「这台设备上的 proot 跑不起来」，
# 用户连部署第一步都进不去。现在由 qq_proot_probe 逐档试（见下面那段的注释），
# 跑通的那一档写进下面这个标记文件。
#
# 标记文件的存在本身就是「这台机器需要 PROOT_NO_SECCOMP=1」的证据：
#   - common.sh 被 30 多个脚本 source，在这里 export 一次就覆盖了所有入口
#     （start-sandbox.sh / 70-launcher.sh / diagnose.sh 里那些直接调
#     $QQBOT_PROOT 的地方都能吃到，不用逐个改）。
#   - 探针下次直接从 noseccomp 档开始，不再从 default 白试一遍（幂等）。
#
# 为什么 amd64 一律不启用：amd64 用的是随包内置的 proot-x86_64，底下是 houdini
# 翻译层，ptrace 寄存器布局跟 proot 对不上，PROOT_NO_SECCOMP=1 在模拟器上照样
# 挂死（见上面 351-354 行的实测记录），开了只会让模拟器卡死，所以直接排除。
QQBOT_PROOT_MODE_FILE="$QQBOT_HOME/proot-noseccomp"
# 当前生效的档位：default | noseccomp | link2symlink。
# 由 qq_proot_probe 在跑通时写入，供日志、诊断和失败文案判断。
QQBOT_PROOT_MODE="default"

qq_proot_export_mode() {
  if [ "$QQBOT_ARCH" = "amd64" ]; then
    unset PROOT_NO_SECCOMP 2>/dev/null
    QQBOT_PROOT_MODE="default"
    return 0
  fi
  if [ -f "$QQBOT_PROOT_MODE_FILE" ]; then
    export PROOT_NO_SECCOMP=1
    case "${QQBOT_PROOT_MODE:-}" in
      ''|default) QQBOT_PROOT_MODE="noseccomp" ;;
    esac
  else
    unset PROOT_NO_SECCOMP 2>/dev/null
    QQBOT_PROOT_MODE="default"
  fi
  return 0
}
# 载入即生效：common.sh 是所有脚本的公共入口，这一行管住全部调用点。
qq_proot_export_mode

# proot-me 的 proot 需要一个可写的临时目录（只设 TMPDIR 不够，
# 不设会报 "can't create temporary file: Permission denied"）
export PROOT_TMP_DIR="$QQBOT_PTMP"

# ---------------------------------------------------------------------------
# proot loader 补丁（v1.1.26）：让 frida-gum 不去读越界的节头表
#
# 现象（真机 arm64，Redmi 24069RA21C / Android 16）：NapCat 一 dlopen
#   /opt/QQ/resources/app/app_launcher/napcat/native/napi2native/napi2native.linux.arm64.node
# 就 SIGSEGV，worker 退出码 11。crashwatch 抓到的现场：
#   signal=11 SIGSEGV si_code=1(SEGV_MAPERR) si_addr=0x2045d8
#   pc  = 模块基址 + 0x39EE44（= gum_elf_module_load+0x880，指令是 ldr w1,[x20]）
#   同一条指令随后按 64 字节步长（Elf64_Shdr 的大小）把节头一项项拷走。
#
# 原因：Termux 仓库那份 proot（5.1.107.98）会把它自己的 loader 映进每个 tracee，
# 但只映射文件里两个 PT_LOAD 覆盖的页：
#   00200000-00201000 r--p off 0x0      （这个段 filesz 只有 0x120）
#   5400000000-5400001000 r-xp off 0x4000
# 而该 loader 的节头表在文件偏移 0x45d8（文件 18136 B，e_shoff=0x45d8），正好落在
# 上面两段映射之外。frida-gum 枚举模块时把 0x200000 当模块基址、按 e_shoff 去读节头，
# 0x200000 + 0x45d8 = 0x2045d8 —— 没有任何映射，于是 SEGV_MAPERR，si_addr 一模一样。
# 这也解释了为什么 x86_64 模拟器一直好好的：那边用的是随包内置的静态 proot，
# 没有这个独立 loader 文件，压根没有这段映射。
#
# 修法：复制一份 loader 到 $QQBOT_HOME，把 e_shoff(0x28, 8B) / e_shnum+e_shstrndx
# (0x3C, 4B) 清零，再用 PROOT_LOADER 指过去。内核执行时只看 program header，节头表
# 只是给工具看的，清零不影响 proot 启动；gum 拿不到节头就不再去读那 256 字节。
# 只改我们自己复制的那份，不动 Termux 的安装文件；清掉这个环境变量即可完全回退。
# ---------------------------------------------------------------------------
QQBOT_PROOT_LOADER_SRC="$PREFIX/libexec/proot/loader"
QQBOT_PROOT_LOADER_SRC32="$PREFIX/libexec/proot/loader32"
QQBOT_PROOT_LOADER_FIX="$QQBOT_HOME/proot-loader"
QQBOT_PROOT_LOADER_FIX32="$QQBOT_HOME/proot-loader32"

# 判断一个 loader 文件是否已经被清零过（偏移 40 的 8 字节、60 的 4 字节全 NUL）。
# 不用 od：Termux 基础环境不保证有，dd + tr 一定有。
qq_loader_patched() {
  [ -s "$1" ] || return 1
  _h=$(dd if="$1" bs=1 skip=40 count=8 2>/dev/null | tr -d '\000')
  [ -z "$_h" ] || return 1
  _h=$(dd if="$1" bs=1 skip=60 count=4 2>/dev/null | tr -d '\000')
  [ -z "$_h" ]
}

# 复制并清零；失败返回非 0（调用方负责继续用原 loader，不能让整个部署挂掉）
qq_make_loader_fix() {
  cp -f "$1" "$2" 2>/dev/null || return 1
  chmod 755 "$2" 2>/dev/null
  dd if=/dev/zero of="$2" bs=1 seek=40 count=8 conv=notrunc 2>/dev/null || return 1
  dd if=/dev/zero of="$2" bs=1 seek=60 count=4 conv=notrunc 2>/dev/null || return 1
  qq_loader_patched "$2"
}

qq_proot_fix_loader() {
  # 内置静态 x86_64 proot 没有独立 loader 文件，直接跳过
  [ "$QQBOT_PROOT" = "$PREFIX/bin/proot" ] || return 0
  [ -f "$QQBOT_PROOT_LOADER_SRC" ] || return 0
  # v1.1.27：运行时发现这份补丁跟当前 proot 不兼容，会留下这个标记文件，
  # 之后一律不再尝试（删掉标记即可重新试）。宁可没有这层保护，也不能让沙盒起不来。
  if [ -f "$QQBOT_HOME/proot-loader.off" ]; then
    return 0
  fi
  # 源文件更新过（Termux 升级了 proot）或者补丁丢了就重做
  if [ ! -f "$QQBOT_PROOT_LOADER_FIX" ] \
     || [ "$QQBOT_PROOT_LOADER_SRC" -nt "$QQBOT_PROOT_LOADER_FIX" ] \
     || ! qq_loader_patched "$QQBOT_PROOT_LOADER_FIX"; then
    if qq_make_loader_fix "$QQBOT_PROOT_LOADER_SRC" "$QQBOT_PROOT_LOADER_FIX"; then
      printf '[QQBot] proot loader 补丁就绪：节头表已清零（%s）\n' "$QQBOT_PROOT_LOADER_FIX" >&2
    else
      rm -f "$QQBOT_PROOT_LOADER_FIX" 2>/dev/null
      printf '[QQBot] proot loader 补丁失败，继续用原 loader（NapCat native 模块可能仍会 SIGSEGV）\n' >&2
      return 0
    fi
  fi
  export PROOT_LOADER="$QQBOT_PROOT_LOADER_FIX"
  # loader32 只在跑 32 位 tracee 时才用得上，有就打、没有就算了
  if [ -f "$QQBOT_PROOT_LOADER_SRC32" ]; then
    if [ ! -f "$QQBOT_PROOT_LOADER_FIX32" ] \
       || [ "$QQBOT_PROOT_LOADER_SRC32" -nt "$QQBOT_PROOT_LOADER_FIX32" ] \
       || ! qq_loader_patched "$QQBOT_PROOT_LOADER_FIX32"; then
      qq_make_loader_fix "$QQBOT_PROOT_LOADER_SRC32" "$QQBOT_PROOT_LOADER_FIX32" >/dev/null 2>&1 || true
    fi
    [ -f "$QQBOT_PROOT_LOADER_FIX32" ] && export PROOT_LOADER_32="$QQBOT_PROOT_LOADER_FIX32"
  fi
  return 0
}
qq_proot_fix_loader

# 按架构决定资源名
QQBOT_ROOTFS_TAR="ubuntu-base-24.04.5-base-$QQBOT_ARCH.tar.gz"
QQBOT_QQ_DEB_NAME="linuxqq_${QQBOT_QQ_VER}_${QQBOT_ARCH}.deb"

# Python 的平台标识必须钉死：MuMu 这类模拟器把 uname 伪装成 aarch64，于是
# Python 的 sysconfig.get_platform() 返回 linux-aarch64，pip 就去下 aarch64 的
# wheel，装进实际是 x86_64 的沙盒里，import 时必然报 invalid ELF header。
# 这里按 ro.product.cpu.abi 的判定结果覆盖，真实手机上也一样正确。
case "$QQBOT_ARCH" in
  amd64) QQBOT_GUEST_PLATFORM="linux-x86_64" ;;
  arm64) QQBOT_GUEST_PLATFORM="linux-aarch64" ;;
  *)     QQBOT_GUEST_PLATFORM="linux-$(uname -m 2>/dev/null)" ;;
esac
export QQBOT_GUEST_PLATFORM

# Ubuntu 源：amd64 在 /ubuntu/ 主分支，arm64 在 /ubuntu-ports/ 分支
# （tuna 的 /ubuntu/ 不承载 arm64 的 Packages 索引，会 404）
# 一律用 http：Ubuntu 基础 rootfs 不含 ca-certificates，首次 apt 走 https 会
# 报 "No system certificates available" 而完全拉不到索引。apt 靠 GPG 签名校验
# 包完整性，不依赖 TLS。等 ca-certificates 装好后，pip 等再用 https。
if [ "$QQBOT_ARCH" = "amd64" ]; then
  QQBOT_UBUNTU_PORTS="http://mirrors.tuna.tsinghua.edu.cn/ubuntu http://mirrors.ustc.edu.cn/ubuntu http://mirrors.aliyun.com/ubuntu http://archive.ubuntu.com/ubuntu"
else
  QQBOT_UBUNTU_PORTS="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports http://mirrors.ustc.edu.cn/ubuntu-ports http://mirrors.aliyun.com/ubuntu-ports http://ports.ubuntu.com/ubuntu-ports"
fi

# ---------------------------------------------------------------------------
# 下载代理
#
# 用户在 App 的设置页填一个代理地址，App 把它写进 $QQBOT_PROXY_FILE
# （$QQBOT_HOME/proxy.txt，纯文本，一行 URL，例如 http://127.0.0.1:7890 或
#  socks5://127.0.0.1:7891）。文件可能不存在或为空，那就表示不使用代理。
#
# qq_proxy 的输出会被 $(...) 捕获后直接当 URL 用，所以：
#   - 只输出 URL 本身，任何提示/调试信息一律走 stderr（>&2），
#   - 空值必须输出零个字符，绝不能是空行。
# ---------------------------------------------------------------------------
qq_proxy() {
  local _f _p
  _f="${QQBOT_PROXY_FILE:-$QQBOT_HOME/proxy.txt}"
  [ -s "$_f" ] || return 0
  # tr -d '\r' 先干掉 CRLF 的 \r（Windows 端写文件很容易带进来）；
  # sed 去掉行首尾空白，再去掉成对的首尾引号（'...' 或 "..."）；
  # head -n 1 只取第一行：文件里若误留了多行，也只有第一行是代理地址。
  _p=$(tr -d '\r' < "$_f" 2>/dev/null | head -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  _p=$(printf '%s' "$_p" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")
  [ -n "$_p" ] || return 0
  printf '%s' "$_p"
}

# 代理非空时输出 curl 用的 "-x <url>"，为空时输出零个字符。
# 调用方必须让结果参与 word-splitting（curl 需要两个独立参数）：
#   # shellcheck disable=SC2046
#   curl ... $(qq_proxy_args) -o out url
# 注意不能用 "$(qq_proxy_args)"：加引号会把它变成单个参数 "-x http://..."，curl 会报错。
qq_proxy_args() {
  local _p
  _p="$(qq_proxy)"
  [ -n "$_p" ] || return 0
  printf '%s' "-x $_p"
}

# 下载完整性下限（字节）。默认 1 KB 只能挡住「0 字节 / 极短错误页」；
# 调用方要按真实体积把 QQBOT_DL_MIN 调大（rootfs 25 MB、QQ deb 150 MB）。
# 为什么需要：curl 在「连接被截断」时不一定会返回非 0（--max-time 到点、CDN 半包都可能退出 0），
# 而坏文件一旦落到正式缓存路径就会被永久复用，之后每次重装都失败在「解包」那一步——
# 报错指向解包、真因在下载，还清不掉。
QQBOT_DL_MIN="${QQBOT_DL_MIN:-1024}"

qq_dl() {
  _u="$1"
  _o="$2"
  _t="$3"
  if [ -z "$_t" ]; then _t=1800; fi
  rm -f "$_o.part"
  # $(qq_proxy_args) 不加引号：代理为空时展开成零个参数，curl 行为与改动前完全一致。
  # shellcheck disable=SC2046
  if ! curl -L --fail --retry 3 --retry-delay 3 --connect-timeout 20 --max-time "$_t" -A "Mozilla/5.0 (Linux; Android 13) qqbot-sandbox/1.0" $(qq_proxy_args) -o "$_o.part" "$_u"; then
    rm -f "$_o.part"
    return 1
  fi
  _sz="$(wc -c < "$_o.part" 2>/dev/null || echo 0)"
  if [ "${_sz:-0}" -lt "${QQBOT_DL_MIN:-1024}" ]; then
    qqwarn "下载体积异常（$_sz 字节 < ${QQBOT_DL_MIN:-1024}），丢弃重来"
    rm -f "$_o.part"
    return 1
  fi
  # 有些镜像对失败请求会返回 200 + 一个 HTML 错误页，小文件靠体积挡不住，这里再看文件头。
  # tr -d '\0' 是给二进制包去的：命令替换里带 NUL 会让 bash 每次打一行
  # "warning: command substitution: ignored null byte in input"，日志里很吵。
  case "$(head -c 15 "$_o.part" 2>/dev/null | tr -d '\0')" in
    '<!DOCTYPE html>'|'<html>'|'<HTML>')
      qqwarn "下载到的是网页而不是文件，丢弃"
      rm -f "$_o.part"
      return 1
      ;;
  esac
  mv -f "$_o.part" "$_o"
}

qq_dl_mirror() {
  _o="$1"
  shift
  for _u in "$@"; do
    qqlog "下载 $(basename "$_u")"
    if qq_dl "$_u" "$_o"; then
      qqok "已保存 $(du -h "$_o" 2>/dev/null | cut -f1)"
      return 0
    fi
    rm -f "$_o" "$_o.part"
    qqwarn "该地址失败，换下一个镜像"
  done
  return 1
}

qq_gh_dl() {
  _o="$1"
  _url="$2"
  # 配了代理时先直连真地址：代理存在的意义就是能访问 GitHub，
  # 再套一层镜像反而多一跳，还可能撞上失效的镜像。
  # 没配代理时这段不执行，行为与改动前完全一致（零回归）。
  if [ -n "$(qq_proxy)" ]; then
    qqlog "下载 $(basename "$_url")"
    # 注意参数顺序：qq_dl 的签名是 qq_dl <url> <输出文件>，
    # 写反了会变成「-o <url>.part <输出路径>」，curl 直接报错、白跑一趟。
    if qq_dl "$_url" "$_o" 2>/dev/null; then
      qqok "已保存 $(du -h "$_o" 2>/dev/null | cut -f1)"
      return 0
    fi
    qqwarn "直连失败，改用镜像"
    rm -f "$_o" "$_o.part"
  fi
  _list=""
  for _m in $QQBOT_GH_MIRRORS; do
    _list="$_list $_m$_url"
  done
  _list="$_list $_url"
  qq_dl_mirror "$_o" $_list
}

# ---------------------------------------------------------------------------
# 1.3.1：部署预取 —— 把后面几步要下的安装包放到后台先下
#
# 部署里四块网络开销彼此独立：apt 装 178 MB 依赖、QQ deb 179 MB、NapCat 35 MB、
# AstrBot 11 MB。原先是「apt 装完才去下 QQ」，而 apt 有大半时间花在 dpkg 解包与配置上
# （那段时间网络完全闲着）；GitHub 那几个包走的镜像又很慢（实测 0.3~0.7 MB/s），
# 串行排队等于把这几分钟白白串起来（MuMu 实测整轮 396 秒）。
# 预取就是在部署一开始用一条后台连接先把这些包拉下来，步骤轮到自己时 mv 一下就到手。
#
# 为什么按「名字@版本」取件、而不是直接写正式缓存路径：正式路径不带版本号
# （NapCat.Shell.zip / AstrBot-src.tar.gz），预取解析到的版本若与步骤解析到的不同，
# 后者就会把旧包当新包用 —— 静默钉死版本，事后极难查。步骤拿自己解析出的版本号
# 来取件，取不到就照旧自己下，最坏只是白下了一份、行为与 1.3.0 完全一致。
# ---------------------------------------------------------------------------
QQBOT_PF_DIR="$QQBOT_CACHE/prefetch"
QQBOT_PF_PIDFILE="$QQBOT_HOME/prefetch.pid"
QQBOT_PF_LOG="$QQBOT_LOGS/prefetch.log"

# 预取开关：默认开。只有对照测速时才会用 QQBOT_PREFETCH=0 关掉。
qq_pf_on() {
  case "${QQBOT_PREFETCH:-1}" in 0|off|no|false) return 1 ;; esac
  return 0
}

# 取件键：标签@版本@文件名。版本未知时用 x 占位。
qq_pf_key() { printf '%s@%s@%s' "${1:-x}" "${2:-x}" "$3"; }

qq_pf_alive() {
  [ -s "$QQBOT_PF_PIDFILE" ] || return 1
  _pfa="$(cat "$QQBOT_PF_PIDFILE" 2>/dev/null)"
  [ -n "$_pfa" ] || return 1
  kill -0 "$_pfa" 2>/dev/null
}

# 等某件被预取好；预取进程已经退出还等不到，就立刻返回 1（交给调用方自己下）。
qq_pf_wait() {
  _pfw="$1"
  _pfmax="${2:-150}"
  _pfi=0
  while [ "$_pfi" -lt "$_pfmax" ]; do
    [ -s "$QQBOT_PF_DIR/$_pfw" ] && return 0
    qq_pf_alive || return 1
    sleep 1
    _pfi=$((_pfi + 1))
  done
  return 1
}

# 把预取好的文件搬到正式路径（同一个文件系统里 mv，瞬时完成）。
qq_pf_take() {
  [ -s "$QQBOT_PF_DIR/$1" ] || return 1
  mkdir -p "$(dirname "$2")" 2>/dev/null || true
  mv -f "$QQBOT_PF_DIR/$1" "$2" 2>/dev/null || return 1
  qqok "使用预取好的 $(basename "$2")（后台已下完，不用再等）"
  return 0
}

# 下载一件：<mirror|gh> <键> <体积下限> <url...>
qq_pf_item() {
  _pi_mode="$1"; _pi_key="$2"; _pi_min="$3"; shift 3
  _pi_out="$QQBOT_PF_DIR/$_pi_key"
  [ -s "$_pi_out" ] && { echo "[prefetch $(date +%H:%M:%S)] 已有 $_pi_key，跳过"; return 0; }
  # 临时文件名按「键」区分：三件 GitHub 包是并行下的，共用一个 .get 会互相覆盖
  _pi_tmp="$QQBOT_PF_DIR/.get.$_pi_key"
  rm -f "$_pi_tmp" "$_pi_tmp.part" 2>/dev/null || true
  echo "[prefetch $(date +%H:%M:%S)] 开始下载 $_pi_key"
  _pi_ok=0
  if [ "$_pi_mode" = "gh" ]; then
    QQBOT_DL_MIN="$_pi_min" qq_gh_dl "$_pi_tmp" "$1" && _pi_ok=1
  else
    QQBOT_DL_MIN="$_pi_min" qq_dl_mirror "$_pi_tmp" "$@" && _pi_ok=1
  fi
  if [ "$_pi_ok" = "1" ] && mv -f "$_pi_tmp" "$_pi_out" 2>/dev/null; then
    echo "[prefetch $(date +%H:%M:%S)] $_pi_key 就绪（$(du -h "$_pi_out" 2>/dev/null | cut -f1)）"
    return 0
  fi
  rm -f "$_pi_tmp" "$_pi_tmp.part" 2>/dev/null || true
  echo "[prefetch $(date +%H:%M:%S)] $_pi_key 下载失败，交给步骤自己重试"
  return 1
}

qq_pf_worker() {
  mkdir -p "$QQBOT_PF_DIR" "$QQBOT_LOGS" 2>/dev/null || true
  rm -f "$QQBOT_PF_DIR"/.get "$QQBOT_PF_DIR"/.get.* "$QQBOT_PF_DIR"/.get.*.part 2>/dev/null || true
  echo "[prefetch $(date +%H:%M:%S)] 预取启动（日志 $QQBOT_PF_LOG）"

  # 1) QQ deb：179 MB、URL 与版本都写死在 common.sh 里，最没风险也最占时间
  _pk="$(qq_pf_key deb "$QQBOT_QQ_VER" "$QQBOT_QQ_DEB_NAME")"
  if [ -s "$QQBOT_CACHE/$QQBOT_QQ_DEB_NAME" ]; then
    echo "[prefetch $(date +%H:%M:%S)] QQ deb 已在缓存里，跳过"
  else
    qq_pf_item mirror "$_pk" $((150 * 1024 * 1024)) "$QQBOT_QQ_DEB_BASE/$QQBOT_QQ_DEB_NAME"
  fi

  # 2) NapCat + AstrBot 源码 + 控制台前端：这三件走 GitHub / 镜像，单连接只有
  #    0.1~0.7 MB/s（是服务器侧限速，不是本机带宽），串着下要两分钟。三件彼此无关，
  #    并行下、谁先好谁先落地。版本与步骤同源解析（qq_latest_ver 带 24 小时缓存），
  #    所以步骤里再查一次拿到的是同一个值，取件键才对得上。
  _pnv="$(qq_latest_ver napcat 2>/dev/null)"
  [ -n "$_pnv" ] || _pnv="$QQBOT_NAPCAT_VER"
  _pav="$(qq_latest_ver astrbot 2>/dev/null)"
  [ -n "$_pav" ] || _pav="$QQBOT_ASTRBOT_VER"

  _pf_bg() {
    qq_pf_item gh "$1" "$2" "$3"
  }

  if [ -s "$QQBOT_CACHE/NapCat.Shell.zip" ] || [ -s "$QQBOT_HOME/payload/NapCat.Shell.zip" ]; then
    echo "[prefetch $(date +%H:%M:%S)] NapCat 已有现成包，跳过"
  else
    _pf_bg "$(qq_pf_key napcat "$_pnv" NapCat.Shell.zip)" $((20 * 1024 * 1024)) \
      "https://github.com/NapNeko/NapCatQQ/releases/download/$_pnv/NapCat.Shell.zip" &
  fi
  if [ -s "$QQBOT_CACHE/AstrBot-src.tar.gz" ]; then
    echo "[prefetch $(date +%H:%M:%S)] AstrBot 源码已有，跳过"
  else
    _pf_bg "$(qq_pf_key astrbot "$_pav" AstrBot-src.tar.gz)" $((1 * 1024 * 1024)) \
      "https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/$_pav.tar.gz" &
  fi
  if [ -s "$QQBOT_CACHE/AstrBot-dashboard.zip" ]; then
    echo "[prefetch $(date +%H:%M:%S)] AstrBot 控制台已有，跳过"
  else
    _pf_bg "$(qq_pf_key astrbot "$_pav" AstrBot-dashboard.zip)" $((1 * 1024 * 1024)) \
      "https://github.com/AstrBotDevs/AstrBot/releases/download/$_pav/AstrBot-$_pav-dashboard.zip" &
  fi
  wait

  echo "[prefetch $(date +%H:%M:%S)] 预取结束"
}

qq_prefetch_start() {
  qq_pf_on || return 0
  if qq_pf_alive; then
    qqlog "预取已在后台运行（pid $(cat "$QQBOT_PF_PIDFILE" 2>/dev/null)）"
    return 0
  fi
  command -v curl >/dev/null 2>&1 || { qqwarn "没有 curl，跳过预取"; return 0; }
  mkdir -p "$QQBOT_PF_DIR" "$QQBOT_LOGS" 2>/dev/null || true
  ( qq_pf_worker ) >> "$QQBOT_PF_LOG" 2>&1 &
  echo $! > "$QQBOT_PF_PIDFILE"
  qqlog "已在后台预取安装包（pid $(cat "$QQBOT_PF_PIDFILE" 2>/dev/null)，日志 $QQBOT_PF_LOG）"
}

qq_prefetch_stop() {
  [ -s "$QQBOT_PF_PIDFILE" ] || return 0
  _pa="$(cat "$QQBOT_PF_PIDFILE" 2>/dev/null)"
  rm -f "$QQBOT_PF_PIDFILE" 2>/dev/null || true
  [ -n "$_pa" ] || return 0
  kill -0 "$_pa" 2>/dev/null || return 0
  # 先把它的 curl 子进程收掉，再收它自己，免得部署结束后还有下载在跑
  command -v pkill >/dev/null 2>&1 && pkill -P "$_pa" 2>/dev/null
  kill -TERM "$_pa" 2>/dev/null || true
  sleep 0.2
  kill -KILL "$_pa" 2>/dev/null || true
  return 0
}

# ---------------------------------------------------------------------------
# 1.2.1：从官网拉一份「已经装好」的沙盒镜像
#
# 1.2.0 是把 600 MB 镜像塞进 APK（懒人包）。实测两个问题：安装包大到小米安全扫描
# 直接报「包含病毒」并只给「取消安装」；手机空间紧张时连装都装不上。1.2.1 改成
# APK 只带程序（约 25 MB），首次部署时从这里把镜像拉下来 —— 内容与懒人包完全一样，
# 只是不进安装包。下载地址是自家服务器，比在线 apt（清华源 + GitHub 代理）快得多。
#
# 下不到不影响部署：调用方回落到在线安装，只是慢一些。
# ---------------------------------------------------------------------------
qq_bundle_pull() {
  _bp_dst="${1:-$QQBOT_HOME/bundle.tar.xz}"
  if [ -s "$_bp_dst" ] && [ -f "$QQBOT_HOME/.bundle-ok" ]; then
    return 0
  fi
  _bp_url="$QQBOT_CDN/bundle-$QQBOT_ARCH.tar.xz"
  _bp_part="$_bp_dst.part"
  mkdir -p "$QQBOT_LOGS" 2>/dev/null
  # 断点续传：上次下到一半的 .part 留着，接上继续，不从 0 重新下 600 MB。
  if [ -s "$_bp_dst" ] && [ ! -s "$_bp_part" ]; then
    mv -f "$_bp_dst" "$_bp_part" 2>/dev/null
  fi
  qqlog "下载沙盒镜像（$QQBOT_ARCH，约 600 MB，只需一次）"
  qqlog "  地址 $QQBOT_CDN"
  _bp_got=0
  _bp_try=0
  while [ "$_bp_try" -lt 3 ]; do
    _bp_try=$((_bp_try + 1))
    curl -L --fail --retry 5 --retry-delay 3 --connect-timeout 20 --max-time 7200 \
      -A "Mozilla/5.0 (Linux; Android 13) qqbot-sandbox/1.0" \
      -C - $(qq_proxy_args) -o "$_bp_part" "$_bp_url" >> "$QQBOT_LOGS/download-bundle.log" 2>&1 &
    _bp_pid=$!
    _bp_last=-5
    while kill -0 "$_bp_pid" 2>/dev/null; do
      sleep 5
      _bp_have=$(stat -c %s "$_bp_part" 2>/dev/null || echo 0)
      case "$_bp_have" in ''|*[!0-9]*) _bp_have=0 ;; esac
      _bp_mb=$(( _bp_have / 1048576 ))
      # 每涨 5 MB 打一行：600 MB 要下几分钟，不报进度会被当成卡死。
      if [ "$_bp_mb" -ge $(( _bp_last + 5 )) ]; then
        _bp_last=$_bp_mb
        qqlog "  已下载 ${_bp_mb} MB"
      fi
    done
    wait "$_bp_pid"
    _bp_rc=$?
    # curl 33 = 服务端不接受我们带来的 Range（.part 已经下完，或者官网那份镜像换了）。
    # 不移掉 .part 的话每一轮都是 416，三次跑完直接报「没下成」，永远好不了。
    if [ "$_bp_rc" -eq 33 ]; then
      rm -f "$_bp_part"
      qqwarn "续传位置无效（.part 可能已下完或镜像已更换），从头重下"
      continue
    fi
    _bp_size=$(stat -c %s "$_bp_part" 2>/dev/null || echo 0)
    case "$_bp_size" in ''|*[!0-9]*) _bp_size=0 ;; esac
    # 装好的镜像不可能只有几十兆：体积明显不对就当这次没成，别把半个包当好的。
    if [ "$_bp_rc" -eq 0 ] && [ "$_bp_size" -gt 104857600 ]; then
      _bp_got=1
      break
    fi
    qqwarn "第 ${_bp_try} 次下载没完成（curl 返回 ${_bp_rc}，已有 ${_bp_size} 字节），5 秒后接着下"
    sleep 5
  done
  if [ "$_bp_got" != "1" ]; then
    qqwarn "沙盒镜像没下成，详见 $QQBOT_LOGS/download-bundle.log"
    return 1
  fi
  # 校验：官网同目录放了一份 .sha256（「<hex>  bundle-<arch>.tar.xz」）。
  # 拿不到校验文件不算失败（老目录没有），能拿到就必须对上。
  _bp_sha=""
  _bp_shaf="$QQBOT_CACHE/bundle.sha256"
  if curl -fsSL --connect-timeout 20 --max-time 60 \
      -A "Mozilla/5.0 (Linux; Android 13) qqbot-sandbox/1.0" \
      $(qq_proxy_args) -o "$_bp_shaf" "$_bp_url.sha256" 2>/dev/null; then
    _bp_sha=$(awk 'NR==1{print $1}' "$_bp_shaf" 2>/dev/null)
  fi
  rm -f "$_bp_shaf"
  if [ -n "$_bp_sha" ] && command -v sha256sum >/dev/null 2>&1; then
    _bp_mine=$(sha256sum "$_bp_part" 2>/dev/null | awk '{print $1}')
    if [ -n "$_bp_mine" ] && [ "$_bp_mine" != "$_bp_sha" ]; then
      qqerr "沙盒镜像校验失败（期望 ${_bp_sha}，实际 ${_bp_mine}），已删除，下次重新下载"
      rm -f "$_bp_part"
      return 1
    fi
    [ -n "$_bp_mine" ] && qqok "镜像 SHA-256 校验通过"
  fi
  mv -f "$_bp_part" "$_bp_dst" || return 1
  : > "$QQBOT_HOME/.bundle-ok"
  rm -f "$QQBOT_HOME/.bundle-pending"
  qqok "沙盒镜像已就位（$(du -h "$_bp_dst" 2>/dev/null | cut -f1)）"
  return 0
}


# proot 自检：能跑通返回 0。带超时保护，避免坏 proot 把进程挂死。
# ---------------------------------------------------------------------------
# 时区
# 沙盒 rootfs 里的 /etc/localtime 原本指向 Etc/UTC，glibc 就按 UTC 走，于是
# UTC+8 的手机上本地 00:00 的日志会写成 16:00。这里从 Android 取系统时区，
# 既写进 rootfs 的 /etc/{timezone,localtime}，又通过 TZ 环境变量传进沙盒。
# 每次启动都重新同步，用户改系统时区后重启服务即可生效。
qq_tz() {
  _t=""
  if command -v getprop >/dev/null 2>&1; then
    _t="$(getprop persist.sys.timezone 2>/dev/null)"
  fi
  # 时区名一定带斜杠（Asia/Shanghai）；取不到或不合法就走下面的兜底。
  case "$_t" in */*) : ;; *) _t="" ;; esac
  if [ -z "$_t" ]; then
    # 兜底：POSIX 形式的偏移符号与 date +%z 相反（+0800 -> UTC-8）。
    # 这个写法不依赖 tzdata，任何 glibc 都能认。
    _o="$(date +%z 2>/dev/null)"
    case "$_o" in
      +*|-*)
        _s="${_o:0:1}"
        _r="${_o#?}"
        _h="${_r%??}"
        _m="${_r#??}"
        _h=$((10#${_h:-0}))
        if [ "$_s" = "+" ]; then _s="-"; else _s="+"; fi
        if [ "$_m" = "00" ]; then _t="UTC$_s$_h"; else _t="UTC$_s$_h:$_m"; fi
        ;;
    esac
  fi
  [ -n "$_t" ] || _t="UTC"
  printf '%s' "$_t"
}

# 把时区落到沙盒看得见的两处：$QQBOT_CACHE/tz（沙盒内是 /qqbot-cache/tz，
# 供沙盒脚本 export TZ 兜底）和 rootfs 的 /etc/timezone + /etc/localtime。
qq_tz_sync() {
  _t="$(qq_tz)"
  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  printf '%s\n' "$_t" > "$QQBOT_CACHE/tz" 2>/dev/null
  if [ -d "$QQBOT_ROOTFS/etc" ]; then
    printf '%s\n' "$_t" > "$QQBOT_ROOTFS/etc/timezone" 2>/dev/null
    if [ -f "$QQBOT_ROOTFS/usr/share/zoneinfo/$_t" ]; then
      ln -sf "/usr/share/zoneinfo/$_t" "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
    else
      # rootfs 里没有这个时区（tzdata 范围外，或上面用的是 POSIX 兜底值）：
      # 保留 UTC 文件，实际生效靠 TZ 环境变量。
      rm -f "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
      cp -f "$QQBOT_ROOTFS/usr/share/zoneinfo/Etc/UTC" "$QQBOT_ROOTFS/etc/localtime" 2>/dev/null
    fi
  fi
  printf '%s' "$_t"
}

# proot 能不能跑：拿一句 echo 当探针，按「档位 × 解释器」逐格试。
#
# 维度一：解释器。探针不能写死 /system/bin/echo —— 1.2.0 就是这么写的，
# Redmi 24069RA21C / Android 16 实测这台机器上根本没有这个路径，proot 把 -0 后面的
# 第一个参数当可执行文件去 execve，直接 ENOENT，于是「proot 自检失败」，部署卡在
# 第 10 步（用户日志 20261021-214703）。所以按顺序试几个「Android 上一定在」的解释器：
#   /system/bin/sh -c echo …（4.x 起一直都在，最稳）
#   /system/bin/toybox echo …（6.0 起）
#   /system/bin/echo …（老路径，留给旧机型）
#
# 维度二（beta.5 新增）：proot 的运行档位。旧版只试 default 一档，遇到三星 One UI /
# Android 16 这种把 seccomp 加速拦掉的内核（报告 20261027-145000-3886，EPERM）就
# 直接判「设备不支持」，而 proot 自己早就把解法打在屏幕上了（PROOT_NO_SECCOMP=1）。
#   档位 1 default      ：现状，什么都不加
#   档位 2 noseccomp    ：PROOT_NO_SECCOMP=1，关掉 seccomp 加速（proot 官方 workaround）
#   档位 3 link2symlink ：再叠 --link2symlink（连硬链接都不可用时的最后兜底）
# 每一格的原始输出都留在日志里，行首带档位标注，出问题时能一眼看出哪档试到哪一步。
#
# 记住哪一档能跑：命中后由 _qp_hit 写/删 $QQBOT_HOME/proot-noseccomp（见文件上方
# qq_proot_export_mode），下次直接从那一档开始，不再从 default 重新试错。
#
# 结果用全局变量 QQBOT_PROOT_MODE 返回，不靠 echo —— 调用方写的是
# `if qq_proot_probe ...` 或者直接看返回值，用命令替换会开子 shell 把状态丢掉。
qq_proot_probe() {
  _qp_to="$1"
  _qp_mark="$2"
  _qp_log="$3"
  : > "$_qp_log" 2>/dev/null
  # $1 档位名 $2 是否开 PROOT_NO_SECCOMP(1/0)；其余是命令行
  _qp_try() {
    _qp_tag="$1"
    _qp_ns="$2"
    shift 2
    _qp_out="$_qp_log.$$.try"
    if [ "$_qp_ns" = "1" ]; then
      export PROOT_NO_SECCOMP=1
    else
      unset PROOT_NO_SECCOMP 2>/dev/null
    fi
    if command -v timeout >/dev/null 2>&1; then
      timeout "$_qp_to" "$@" > "$_qp_out" 2>&1
    else
      "$@" > "$_qp_out" 2>&1
    fi
    _qp_rc=$?
    printf '[probe] (%s) rc=%s %s\n' "$_qp_tag" "$_qp_rc" "$1" >> "$_qp_log" 2>/dev/null
    sed 's/^/    /' "$_qp_out" >> "$_qp_log" 2>/dev/null
    if [ "$_qp_rc" -eq 0 ] && grep -q "$_qp_mark" "$_qp_out" 2>/dev/null; then
      rm -f "$_qp_out"
      return 0
    fi
    rm -f "$_qp_out"
    return 1
  }
  # 一整档：同一个档位下把三个解释器依次试一遍（前一个能跑通就不试后面的）
  _qp_tier() {
    _qp_name="$1"
    _qp_nsflag="$2"
    shift 2
    # 档位自带的开关（--link2symlink）必须排在 proot 本体后面：放前面的话
    # timeout 会把开关当成要执行的命令，直接 rc=127，这一档永远跑不通。
    _qp_try "$_qp_name" "$_qp_nsflag" "$QQBOT_PROOT" "$@" -0 /system/bin/sh -c "echo $_qp_mark" && return 0
    _qp_try "$_qp_name" "$_qp_nsflag" "$QQBOT_PROOT" "$@" -0 /system/bin/toybox echo "$_qp_mark" && return 0
    _qp_try "$_qp_name" "$_qp_nsflag" "$QQBOT_PROOT" "$@" -0 /system/bin/echo "$_qp_mark" && return 0
    return 1
  }
  # 命中：把档位记下来，供后续所有 proot 调用复用
  _qp_hit() {
    QQBOT_PROOT_MODE="$1"
    case "$1" in
      default)
        rm -f "$QQBOT_PROOT_MODE_FILE" 2>/dev/null
        unset PROOT_NO_SECCOMP 2>/dev/null
        ;;
      noseccomp)
        : > "$QQBOT_PROOT_MODE_FILE" 2>/dev/null
        export PROOT_NO_SECCOMP=1
        ;;
      link2symlink)
        : > "$QQBOT_PROOT_MODE_FILE" 2>/dev/null
        : > "$QQBOT_HOME/link2symlink" 2>/dev/null
        export PROOT_NO_SECCOMP=1
        ;;
    esac
    return 0
  }
  _qp_tried_ns=0
  # 上次已经证明这台机器需要 noseccomp：直接从那一档开始，别再从 default 白试一遍。
  if [ "$QQBOT_ARCH" != "amd64" ] && [ -f "$QQBOT_PROOT_MODE_FILE" ]; then
    _qp_tried_ns=1
    if _qp_tier noseccomp 1; then
      _qp_hit noseccomp
      return 0
    fi
    # 标记失效了（换了包 / 系统升级过）：删掉，老老实实走完整矩阵再判断一次。
    rm -f "$QQBOT_PROOT_MODE_FILE" 2>/dev/null
  fi
  # 档位 1：default（绝大多数机器走到这里就结束）
  if _qp_tier default 0; then
    _qp_hit default
    return 0
  fi
  # 档位 2、3 只在非 amd64 上试：amd64 是 houdini 翻译层，开 PROOT_NO_SECCOMP 会挂死
  # （见上面 351-354 行），宁可报失败也不能把模拟器卡住。
  if [ "$QQBOT_ARCH" != "amd64" ]; then
    if [ "$_qp_tried_ns" = "0" ]; then
      if _qp_tier noseccomp 1; then
        _qp_hit noseccomp
        return 0
      fi
    fi
    if _qp_tier link2symlink 1 --link2symlink; then
      _qp_hit link2symlink
      return 0
    fi
  fi
  # 三档全失败：环境恢复成「按标记来」，别把 PROOT_NO_SECCOMP 漏给后续调用
  unset PROOT_NO_SECCOMP 2>/dev/null
  QQBOT_PROOT_MODE="default"
  return 1
}

qq_proot_selftest() {
  unset LD_PRELOAD
  export PROOT_TMP_DIR="$QQBOT_PTMP"
  mkdir -p "$QQBOT_PTMP" 2>/dev/null
  # proot 不存在时先挡下来：否则 timeout 会返回 127，而上层只看退出码，
  # 会把「文件都还没装」误报成「架构不匹配」。
  if [ ! -x "$QQBOT_PROOT" ]; then
    printf 'proot 不存在或不可执行：%s\n' "$QQBOT_PROOT" > "$QQBOT_SELFTEST_LOG" 2>/dev/null
    return 1
  fi
  _qq_proot_selftest_once() {
    qq_proot_probe 25 qqbot-proot-ok "$QQBOT_SELFTEST_LOG"
  }
  _qq_proot_selftest_once && return 0
  # v1.1.26：打了 loader 补丁却起不来 proot，说明这份 proot 不认我们的替代 loader。
  # 立刻回退到原 loader 再试一次 —— 少一层 native 崩溃保护，也好过整个沙盒起不来。
  if [ -n "${PROOT_LOADER:-}" ]; then
    _qq_selftest_patched_log="$QQBOT_SELFTEST_LOG.patched"
    cp -f "$QQBOT_SELFTEST_LOG" "$_qq_selftest_patched_log" 2>/dev/null
    unset PROOT_LOADER
    unset PROOT_LOADER_32
    if _qq_proot_selftest_once; then
      rm -f "$QQBOT_PROOT_LOADER_FIX" "$QQBOT_PROOT_LOADER_FIX32" 2>/dev/null
      : > "$QQBOT_HOME/proot-loader.off" 2>/dev/null
      printf '[QQBot] loader 补丁与当前 proot 不兼容（自检失败），已回退到系统 loader；' >&2
      printf '原补丁现场见 %s\n' "$_qq_selftest_patched_log" >&2
      return 0
    fi
  fi
  return 1
}

# 自检失败时把 proot 自己的报错原样打到日志里（缩进四格，便于和 [QQBot] 行区分）。
qq_proot_selftest_log() {
  if [ -s "$QQBOT_SELFTEST_LOG" ]; then
    sed 's/^/    /' "$QQBOT_SELFTEST_LOG" >&2
  fi
  return 0
}

# 自检三档全失败时，按日志里的关键字告诉用户「下一步该干什么」。
#
# 旧文案是「内置 proot 可能和本机架构不匹配，重新部署一次」—— 对三星这类被内核
# 拦掉 seccomp 的机器完全是错的（重新部署一百次还是同样的 EPERM），用户照做只会更
# 困惑（真机报告 20261027-145000-3886 的留言就是这么来的）。所以改成按错误类型分支：
#   有 EPERM / seccomp 痕迹 → 内核拦的，属于机型适配问题，只能上报日志
#   有 ENOENT / not found   → 内置组件没装全，引导重新部署
#   有 Permission denied    → 系统权限/冻结限制，引导重启并检查省电设置
#   什么都没有              → 兜底：上传日志
# 无论哪一支都带上「请把这段日志发给作者」，因为用户能做的就只有上传报告。
qq_proot_fail_rows() {
  _fr_log="$QQBOT_SELFTEST_LOG"
  _fr_seccomp=0
  _fr_enoent=0
  _fr_perm=0
  if [ -f "$_fr_log" ]; then
    grep -qiE 'PROOT_NO_SECCOMP|Operation not permitted|EPERM|seccomp' "$_fr_log" 2>/dev/null && _fr_seccomp=1
    grep -qiE 'No such file or directory|ENOENT|not found' "$_fr_log" 2>/dev/null && _fr_enoent=1
    grep -qiE 'Permission denied|EACCES|cannot execute' "$_fr_log" 2>/dev/null && _fr_perm=1
  fi
  if [ "$_fr_seccomp" = "1" ]; then
    qqerr "这台手机的内核拦掉了 proot 的 seccomp 加速（Operation not permitted）。"
    qqerr "自动试过三档：默认 / PROOT_NO_SECCOMP=1 / 再加 --link2symlink，都没跑起来。"
    qqerr "这不是你操作的问题，也不用重装或重置部署（重试同样会被拦）。"
    qqerr "请把这段日志发给作者（设置 → 维护 → 上传错误日志），下个版本会针对这个机型适配。"
  elif [ "$_fr_enoent" = "1" ]; then
    qqerr "proot 找不到要执行的程序（No such file or directory），多半是内置组件没装全。"
    qqerr "请回主页点「重置部署」，再点「开始部署」重新走一遍；仍失败请把这段日志发给作者。"
  elif [ "$_fr_perm" = "1" ]; then
    qqerr "系统拒绝执行 proot（Permission denied）。"
    qqerr "请确认没开「应用冻结 / 内存扩展」，把 App 从后台划掉后重启手机再试一次；"
    qqerr "仍失败请把这段日志发给作者（设置 → 维护 → 上传错误日志）。"
  else
    qqerr "proot 自检没有通过，三档都没跑起来（完整日志：$QQBOT_SELFTEST_LOG）。"
    qqerr "请把这段日志发给作者（设置 → 维护 → 上传错误日志）。"
  fi
  return 0
}

# 关于 --link2symlink：默认关闭，只在「这台手机的存储不支持硬链接」时自动开启
# （qq_fsprobe 判定后写 $QQBOT_HOME/link2symlink）。
#
# 早期的教训是：那时 rootfs 是在 proot **内部**用 tar 解压的，tar 为压缩包里的硬链接
# 调 link()，被 link2symlink 换成 .l2s.* 符号链接，导致 libc.so.6 悬空、所有 execve
# 返回 ENOENT。现在解压全部在宿主侧完成（不走 proot），所以只在运行期的 dpkg 事务里
# 生效，不会碰到系统库。真机证据：Redmi / Android 16 上 link(2) 直接 EPERM，
# 不开这个 apt 装不了任何包（AstrBot-Bubble-Android-App 也是无条件开着它跑的）。
qq_proot() {
  unset LD_PRELOAD
  # 探针可能刚在本进程里写下标记（部署流程里 qq_proot_selftest 先跑、qq_proot 后跑），
  # 每次进来都按标记重算一遍，保证 PROOT_NO_SECCOMP 和当前档位一致。
  qq_proot_export_mode
  export PROOT_TMP_DIR="$QQBOT_PTMP"
  # /usr/bin/env -i 会清空全部环境变量，所以代理必须在这里显式补上：
  # 沙盒内的 apt（30-apt.sh）和 pip（60-astrbot.sh）都依赖这几个变量。
  # socks5:// 也要给 http_proxy/https_proxy：curl / requests 都识别 socks5://
  # 前缀并按 SOCKS5 走，只给 all_proxy 反而会被部分工具忽略。
  # 例外是 apt：它只认 socks5h://（源码 methods/http.cc 里唯一判断 SOCKS 的
  # 分支是 Proxy.Access == "socks5h"），所以 30-apt.sh 里会再换一次 scheme。
  # no_proxy 固定加上本机地址：NapCat 与 AstrBot 之间的 aiocqhttp 是
  # 127.0.0.1 上的 WebSocket，绝不能因为设了代理而被转发出本机。
  _px_env=""
  _px="$(qq_proxy)"
  if [ -n "$_px" ]; then
    _px_env="http_proxy=$_px https_proxy=$_px all_proxy=$_px HTTP_PROXY=$_px HTTPS_PROXY=$_px ALL_PROXY=$_px no_proxy=127.0.0.1,localhost,::1 NO_PROXY=127.0.0.1,localhost,::1"
  fi
  # 时区每次进沙盒都重新同步：只写 rootfs 文件不够，TZ 环境变量优先级更高。
  _tz="$(qq_tz_sync)"
  # 存储不支持硬链接时必须开这个：dpkg 每次装包第一步要给 status 建硬链接备份，
  # 建不了就整条链全挂。proot 的 --link2symlink 会用 rename + symlink 模拟，
  # 不依赖底层文件系统。（参考 AstrBot-Bubble-Android-App 的做法。）
  _l2s=""
  if [ -f "$QQBOT_HOME/link2symlink" ]; then _l2s="--link2symlink"; fi
  "$QQBOT_PROOT" --kill-on-exit -0 -r "$QQBOT_ROOTFS" $_l2s -b /dev -b "$QQBOT_DEVDIR/shm:/dev/shm" -b /proc -b /sys -b "$QQBOT_CACHE:/qqbot-cache" -b "$QQBOT_OPT:/qqbot-opt" -w /root /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin QQBOT_ARCH="$QQBOT_ARCH" _PYTHON_HOST_PLATFORM="$QQBOT_GUEST_PLATFORM" TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp TZ="$_tz" $_px_env /bin/bash -lc "$*"
}

qq_sandbox() {
  # 必须转发多余参数：qq_sandbox delete-instance.sh "$ID" 要把 ID 传进沙盒内的脚本，
  # 否则沙盒里跑的是无参版本，脚本只会打印用法然后什么都不做。
  local s="$1"; shift
  qq_proot "bash /qqbot-opt/sandbox/$s $*"
}

# ---------------------------------------------------------------------------
# 端口探测 / 网络自愈（协议：docs/接口约定-1.1.30.md 第 3 节）
# ---------------------------------------------------------------------------

# 本机端口是否可连。Termux 的 bash 一般带 /dev/tcp；万一这台设备的 bash 没编译
# 网络重定向，就退到 nc；两个都没有时返回 0（当作「通」）—— 探测能力缺失不能
# 变成「永远等不到端口」，那会把 AstrBot 挡在门外。
_QQ_PORT_PROBE=""
qq_port_open() {
  local p="${1:-}"
  case "$p" in ''|*[!0-9]*) return 1 ;; esac
  if [ -z "$_QQ_PORT_PROBE" ]; then
    _e=$( ( exec 3<>"/dev/tcp/127.0.0.1/1" ) 2>&1 )
    case "$_e" in
      *"No such file"*|*"not supported"*) _QQ_PORT_PROBE=0 ;;
      *) _QQ_PORT_PROBE=1 ;;
    esac
  fi
  if [ "$_QQ_PORT_PROBE" = 1 ]; then
    ( exec 3<>"/dev/tcp/127.0.0.1/$p" ) >/dev/null 2>&1 && return 0
    return 1
  fi
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 2 127.0.0.1 "$p" >/dev/null 2>&1 && return 0
    return 1
  fi
  return 0
}

# bots.json 里所有实例的 NapCat WebUI 端口（可能多个，一行一个）。
# 一个都读不到就退回 qq_napcat_port（当前实例的端口，最后兜底 6099）。
qq_napcat_ports() {
  local ps
  ps=$(grep -o '"napcatPort"[[:space:]]*:[[:space:]]*[0-9]\+' "$(qq_bots_json)" 2>/dev/null | grep -o '[0-9]\+$' | sort -u)
  [ -n "$ps" ] || ps=$(qq_napcat_port 2>/dev/null)
  printf '%s\n' "$ps"
}

# 网络恢复后先等 NapCat 的 WebUI 端口可连，再交给调用方去启 AstrBot。
#   用法: qq_net_wait_napcat [最多等几秒，默认 60]
#   返回: 0 = 至少一个 NapCat 端口可连；1 = 超时
# 原生 NetworkWatch 只在「无网→有网」或默认网络切换时触发（30 秒 debounce），
# 顺序固定是「先等 NapCat，再启 AstrBot」，本函数就是那个「等」。
# 幂等：只做只读探测，不启动、不杀死任何进程，重复调用只是再等一轮。
qq_net_wait_napcat() {
  local wait="${1:-60}" spent=0 p
  case "$wait" in ''|*[!0-9]*) wait=60 ;; esac
  while [ "$spent" -lt "$wait" ]; do
    for p in $(qq_napcat_ports 2>/dev/null); do
      if qq_port_open "$p"; then return 0; fi
    done
    sleep 2 2>/dev/null || sleep 1
    spent=$((spent + 2))
  done
  return 1
}

# ---------------------------------------------------------------------------
# 电量 / 温度降载（协议：docs/接口约定-1.1.30.md 第 6 节）
# 读的方向：原生层写 $QQBOT_STATE/degrade（内容一般是 battery / thermal），
# 存在即降载。脚本层只做「少干活」的反应，绝不改写这个文件。
# ---------------------------------------------------------------------------

# 是否正在降载。返回 0 = 降载中。
qq_degrade_state() {
  [ -f "$QQBOT_STATE/degrade" ]
}

# 降载原因（battery / thermal / 空），只取第一行，供日志用。
qq_degrade_reason() {
  head -n 1 "$QQBOT_STATE/degrade" 2>/dev/null | tr -d '\r\n'
}

# ---------------------------------------------------------------------------
# 风控 / 验证识别（协议：docs/接口约定-1.1.30.md 第 2 节）
#
# 分工：这里是「宿主侧」实现，由 watchdog.sh 每轮调用一遍。沙盒内的 run-napcat.sh
# 没法 source 本文件（这是 Termux 路径下的脚本，沙盒里看不到），另有一份精简实现；
# 两边读写的是同一批文件（$QQBOT_HOME/state 与沙盒内的 /qqbot-state 是同一个目录），
# 偏移文件只允许往前推进，所以双写既不会漏报，也不会把旧事件当成新事件再报一遍。
# ---------------------------------------------------------------------------

# 实例 id 转成安全的文件名片段（id 来自 bots.json，可能有奇怪字符）
qq_risk_id() { printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_'; }

# 确保状态目录与可变词表存在。出厂词表在 $QQBOT_OPT/sandbox/risk-words.txt，
# 第一次复制到 $QQBOT_HOME/state/risk-words.txt；之后改 state 里那份即可热生效。
# 返回 0 = 词表可用。
qq_risk_init() {
  local src="$QQBOT_OPT/sandbox/risk-words.txt" dst="$QQBOT_STATE/risk-words.txt"
  local cfg="$QQBOT_STATE/risk-cfg" tmp
  mkdir -p "$QQBOT_STATE" 2>/dev/null
  if [ ! -s "$dst" ] && [ -s "$src" ]; then
    cp -f "$src" "$dst" 2>/dev/null
  fi
  # 三个阈值同步给沙盒侧：沙盒里 source 不到这份 common.sh（那是 Termux 路径，
  # proot 里看不见），它只能读状态目录里的文件。两份实现的规则必须一致，
  # 否则宿主判「暂停」、沙盒判「继续重启」，风控根本没被拦住。
  tmp="$cfg.$$"
  printf 'WINDOW=%s\nHITS=%s\nTTL=%s\n' \
    "$QQBOT_RISK_WINDOW" "$QQBOT_RISK_HITS" "$QQBOT_RISK_TTL" > "$tmp" 2>/dev/null \
    && mv -f "$tmp" "$cfg" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  [ -s "$dst" ]
}

# JSON 字符串转义（去掉控制字符、转义反斜杠与双引号）。
# 风控 `line` 字段可能带 QQ 输出里的任意字节，不转义会让原生层解析失败。
qq_risk_esc() {
  printf '%s' "${1:-}" | tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# 通知通道：原生层读 risk.json 后自己会发通知，这里只是「能补就补」。
# 优先用 $QQBOT_STATE/notify（可执行脚本，自定义通道），其次 termux-api 的
# termux-notification；两个都没有就只写状态文件，什么都不做。
qq_risk_notify() {
  local msg="${1:-}"
  if [ -x "$QQBOT_STATE/notify" ]; then
    "$QQBOT_STATE/notify" "$msg" >/dev/null 2>&1 || true
    return 0
  fi
  command -v termux-notification >/dev/null 2>&1 || return 0
  if command -v timeout >/dev/null 2>&1; then
    timeout 5 termux-notification -t "QQBot 风控" -c "$msg" >/dev/null 2>&1 || true
  else
    termux-notification -t "QQBot 风控" -c "$msg" >/dev/null 2>&1 || true
  fi
  return 0
}

# 写风控状态（字段严格照协议第 2 节：ok/risk/instance/word/line/since/file）。
# file 用沙盒内路径（/qqbot-cache/logs/...），和原生层看到的一致。
qq_risk_write() {
  local id="$1" word="$2" line="$3" file="$4" now tmp
  case "$file" in
    "$QQBOT_CACHE"/*) file="/qqbot-cache/${file#"$QQBOT_CACHE"/}" ;;
  esac
  now=$(date +%s)
  tmp="$QQBOT_STATE/risk.json.$$"
  printf '{"ok":true,"risk":true,"instance":"%s","word":"%s","line":"%s","since":%s,"file":"%s"}\n' \
    "$(qq_risk_esc "$id")" "$(qq_risk_esc "$word")" "$(qq_risk_esc "${line:0:200}")" \
    "$now" "$(qq_risk_esc "$file")" > "$tmp" 2>/dev/null && mv -f "$tmp" "$QQBOT_STATE/risk.json" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  return 0
}

# 扫一个实例日志的「新增部分」，命中风控词就写 risk.json + 打暂停标记。
#   用法: qq_risk_scan <logfile> <instance>
#   返回: 0 = 该实例当前处于风控暂停态（本轮命中，或之前已经命中过）；1 = 正常
# 只扫新增字节（偏移记在 risk-<id>.off），所以每 15 秒调一次也不费电；
# 同一实例 60 秒内只写一次 risk.json（debounce 记在 risk-<id>.last）。
qq_risk_scan() {
  local logf="${1:-}" raw="${2:-}" id words flag off_f last_f word_f hits_f
  local sz off cur word line2 _l2 now n
  [ -n "$logf" ] && [ -n "$raw" ] && [ -f "$logf" ] || return 1
  id="$(qq_risk_id "$raw")"
  qq_risk_init || return 1
  words="$QQBOT_STATE/risk-words.txt"
  flag="$QQBOT_STATE/risk-$id.flag"
  off_f="$QQBOT_STATE/risk-$id.off"
  last_f="$QQBOT_STATE/risk-$id.last"
  word_f="$QQBOT_STATE/risk-$id.word"
  hits_f="$QQBOT_STATE/risk-$id.hits"

  sz=$(wc -c < "$logf" 2>/dev/null)
  case "$sz" in ''|*[!0-9]*) return 1 ;; esac
  off=$(cat "$off_f" 2>/dev/null)
  case "$off" in ''|*[!0-9]*) off=0 ;; esac
  if [ "$sz" -lt "$off" ]; then
    # 日志被原地截断（run-all.sh 的 trim_logs）或换了个文件：从当前位置接着扫，
    # 不回头重扫 —— 截断后留在文件里的那 2MB 是已经看过的旧内容，重扫只会把
    # 很久以前的风控词当成刚发生的事再报一遍。
    echo "$sz" > "$off_f" 2>/dev/null
  fi
  if [ "$sz" -le "$off" ]; then
    [ -f "$flag" ] && return 0
    return 1
  fi

  # 只取新增部分：tail -c +N 是「从第 N 字节开始」，所以偏移要 +1。
  # 词表里的 # 注释行不是关键词，先滤掉（grep -f 会把注释也当模式）。
  word=$(tail -c "+$((off + 1))" "$logf" 2>/dev/null \
    | grep -a -o -i -F -m 1 -f <(grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$words" 2>/dev/null | tr -d '\r') 2>/dev/null \
    | head -n 1)

  # 偏移先推进：这一段无论有没有命中，都已经看过了。只前进不后退（沙盒里那份
  # 实现也写同一个文件，两边并发时靠这条「取大」规则避免把偏移改回去）。
  cur=$(cat "$off_f" 2>/dev/null)
  case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
  [ "$sz" -gt "$cur" ] && echo "$sz" > "$off_f" 2>/dev/null

  if [ -z "$word" ]; then
    [ -f "$flag" ] && return 0
    return 1
  fi

  # 命中了：先往 .hits 里记一笔时间戳（保留最近 20 条），再按「窗口内累计次数」
  # 决定要不要暂停。窗口内的计数就是「最近 N 秒里记了几笔」——不用额外清理旧记录。
  now=$(date +%s 2>/dev/null || echo 0)
  printf '%s\n' "$now" >> "$hits_f" 2>/dev/null
  if [ -s "$hits_f" ]; then
    tail -n 20 "$hits_f" > "$hits_f.tmp" 2>/dev/null && mv -f "$hits_f.tmp" "$hits_f" 2>/dev/null
  fi
  rm -f "$hits_f.tmp" 2>/dev/null
  # 计数按「不同秒」去重：宿主 watchdog 与沙盒 run-napcat 会同时扫同一份日志，
  # 同一次命中可能被两边各记一笔（同一秒两行）。按行数算会把一次命中当两次，
  # 于是「首次只告警」的阈值形同虚设 —— 实测 2 行日志就能触发暂停。
  n=$(awk -v now="$now" -v w="$QQBOT_RISK_WINDOW" \
        'NF && $1 ~ /^[0-9]+$/ && (now - $1) <= w { if ($1 != prev) { c++; prev = $1 } } END { print c + 0 }' \
        "$hits_f" 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=1 ;; esac
  printf '%s\n' "$word" > "$word_f" 2>/dev/null

  if [ "$n" -lt "$QQBOT_RISK_HITS" ]; then
    # 还没到阈值：只告警、不暂停，也不写 risk.json —— 红横幅上写着「已暂停自动重启」，
    # 状态与实际不符比不提示更糟。继续重启比停下来安全：多数首命中是误报。
    qq_risk_notify "实例 $raw 命中风控词「$word」（$QQBOT_RISK_WINDOW 秒内第 $n 次，累计 $QQBOT_RISK_HITS 次才暂停）"
    qqlog "[risk] 实例 $raw 命中风控词「$word」，窗口内第 $n/$QQBOT_RISK_HITS 次，暂不暂停"
    [ -f "$flag" ] && return 0
    return 1
  fi

  # 到阈值了：标记一定补上（幂等），risk.json 按 debounce 写。
  : > "$flag" 2>/dev/null
  cur=$(cat "$last_f" 2>/dev/null)
  case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
  if [ "$cur" -gt 0 ] && [ $(( $(date +%s) - cur )) -lt 60 ]; then
    return 0
  fi
  line2=$(tail -c "+$((off + 1))" "$logf" 2>/dev/null \
    | grep -a -i -F -m 1 -e "$word" 2>/dev/null | head -n 1)
  [ -n "$line2" ] || line2="$word"
  # 长行截断后再清一次非法 UTF-8：截断可能正好切在一个汉字的三个字节中间，
  # 那会让原生层的 JSON 解析直接抛异常。
  line2=$(printf '%s' "$line2" | tr -d '\000-\037' | cut -c1-600 2>/dev/null)
  if command -v iconv >/dev/null 2>&1; then
    _l2="$(printf '%s' "$line2" | iconv -f UTF-8 -t UTF-8 -c 2>/dev/null)"
    [ -n "$_l2" ] && line2="$_l2"
  fi
  qq_risk_write "$raw" "$word" "$line2" "$logf"
  date +%s > "$last_f" 2>/dev/null
  qq_risk_notify "实例 $raw 可能需要验证，已暂停自动重启（命中「$word」）"
  qqlog "[risk] 实例 $raw 命中风控词「$word」，已暂停自动重启"
  return 0
}

# 扫 bots.json 里所有实例的日志。返回 0 = 至少有一个实例处于风控暂停态。
# 实例 id 同时从沙盒用的 $QQBOT_CACHE/bots.json 和界面写的 $QQBOT_HOME/bots.json
# 取并集：这两份在过渡期可能不一致，漏掉任何一个实例就等于漏掉一次风控告警。
qq_risk_scan_all() {
  local ids id n=0
  ids=$(
    for _bf in "$QQBOT_CACHE/bots.json" "$QQBOT_HOME/bots.json"; do
      [ -s "$_bf" ] || continue
      grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "$_bf" 2>/dev/null \
        | sed -n 's/.*"\([^"]*\)"$/\1/p'
    done | sort -u
  )
  [ -n "$ids" ] || return 1
  for id in $ids; do
    [ -n "$id" ] || continue
    qq_risk_scan "$QQBOT_CACHE/logs/qq-$id.log" "$id" && n=$((n + 1))
  done
  [ "$n" -gt 0 ]
}

# 把 TTL 秒数说成人话（600 → 「10 分钟」，21600 → 「6 小时」）。
# 日志里出现「已超过 0 小时」会被当成 bug 报上来，宁可多这几行。
qq_risk_ttl_text() {
  local s="${1:-0}"
  case "$s" in ''|*[!0-9]*) s=0 ;; esac
  if [ "$s" -ge 3600 ] && [ $((s % 3600)) -eq 0 ]; then
    printf '%s 小时' "$((s / 3600))"
  elif [ "$s" -ge 60 ]; then
    printf '%s 分钟' "$((s / 60))"
  else
    printf '%s 秒' "$s"
  fi
}

# 该实例是否被风控暂停（原生层 riskClear() 删掉 flag 就恢复）。
# v1.1.31 起带自动过期：flag 的 mtime 超过 QQBOT_RISK_TTL（默认 6 小时）就删掉
# flag 与 risk.json，让看门狗重新开始尝试拉起 —— 否则一次误报会让实例永久停摆，
# 用户只能自己点「我知道了」。用 mtime 而不是文件里的时间戳，是为了让老版本
# 留下的空 flag（没有时间戳字段）也能自然过期。
qq_risk_blocked() {
  local id f mt now ttl
  [ -n "${1:-}" ] || return 1
  id="$(qq_risk_id "$1")"
  f="$QQBOT_STATE/risk-$id.flag"
  [ -f "$f" ] || return 1
  now=$(date +%s 2>/dev/null || echo 0)
  mt=$(date -r "$f" +%s 2>/dev/null || stat -c %Y "$f" 2>/dev/null || echo 0)
  case "$mt" in ''|*[!0-9]*) mt=0 ;; esac
  ttl="${QQBOT_RISK_TTL:-21600}"
  case "$ttl" in ''|*[!0-9]*) ttl=21600 ;; esac
  if [ "$mt" -gt 0 ] && [ "$now" -gt 0 ] && [ $((now - mt)) -ge "$ttl" ]; then
    rm -f "$f" "$QQBOT_STATE/risk.json" 2>/dev/null
    qqlog "[risk] 实例 $id 的风控暂停已超过 $(qq_risk_ttl_text "$ttl")，自动解除并恢复自动重启"
    return 1
  fi
  return 0
}

# 当前所有被风控暂停的实例 id（一行一个），给 watchdog.sh 打日志用。
qq_risk_blocked_list() {
  local f id
  for f in "$QQBOT_STATE"/risk-*.flag; do
    [ -f "$f" ] || continue
    id=${f##*/}; id=${id#risk-}; id=${id%.flag}
    printf '%s\n' "$id"
  done
}

# 解除风控暂停：删 risk.json 与所有 risk-*.flag（原生层 riskClear() 也走同一批文件）。
# 偏移文件不删而是重置到「当前日志末尾」：删成 0 会让下一次扫描把整份旧日志重扫一遍，
# 几十秒前刚被解除的那条命中会立刻又报一次，等于永远清不掉。
qq_risk_clear() {
  local f id logf
  rm -f "$QQBOT_STATE/risk.json" 2>/dev/null
  for f in "$QQBOT_STATE"/risk-*.off; do
    [ -f "$f" ] || continue
    id=${f##*/}; id=${id#risk-}; id=${id%.off}
    logf="$QQBOT_CACHE/logs/qq-$id.log"
    if [ -f "$logf" ]; then
      wc -c < "$logf" 2>/dev/null | tr -d ' ' > "$f" 2>/dev/null
    else
      rm -f "$f" 2>/dev/null
    fi
  done
  rm -f "$QQBOT_STATE"/risk-*.flag "$QQBOT_STATE"/risk-*.last "$QQBOT_STATE"/risk-*.word "$QQBOT_STATE"/risk-*.hits 2>/dev/null
  return 0
}

# ---------------------------------------------------------------------------
# 存储统计 / 备份 / 崩溃聚合的公用小工具（功能 54 / 50 / 58，协议第 7、8、9 节）
# ---------------------------------------------------------------------------

# JSON 字符串转义（和 qq_risk_esc 同一套规则，只是名字更通用，给新脚本复用）
qq_json_esc() { qq_risk_esc "${1:-}"; }

# ---------------------------------------------------------------------------
# 后台拉起长驻进程 / supervisor 启动标记（1.3.4-beta.2）
# ---------------------------------------------------------------------------

# 语义等价于「nohup setsid <cmd> >>日志 2>&1 &」，但不会再往调用方的 stderr
# 打「<pid> Killed <cmd>」这行作业通知。
#
# 为什么原来那句话会漏出来：bash 收到子进程被 SIGKILL 的通知后，会在下一个命令
# 边界把「Killed」打到**调用方自己的 stderr**（deploy.sh 的 stderr 就是部署日志），
# 而 disown %+ 往往在子进程已经死掉之后才执行，摘不干净。用户的错误报告里于是
# 出现「deploy.sh: line 268: 23963 Killed nohup setsid bash ...」，看起来像启动失败。
# 把作业放进一个「拉起即退出」的子 shell，作业表随子 shell 消亡，通知再也打不出来；
# 子 shell 自己的 stderr 也一并丢掉，兜住最后那一瞬的竞争。
# 用法：qq_spawn_detached 「日志路径」 「命令」 [参数...]
qq_spawn_detached() {
  local _log="${1:-}"
  shift 2>/dev/null || true
  [ "$#" -ge 1 ] || return 1
  [ -n "$_log" ] || _log=/dev/null
  if [ "$_log" != /dev/null ]; then
    mkdir -p "$(dirname "$_log")" 2>/dev/null
  fi
  ( nohup setsid "$@" >>"$_log" 2>&1 </dev/null & ) 2>/dev/null
  return 0
}

# supervisor 的「启动标记」：记下这次由 start-sandbox.sh 拉起的 pid 与时间。
# 正常停止时由 stop-sandbox.sh 删掉；下次启动若发现标记还在、pid 却已经不在 /proc，
# 就说明上一次不是被 stop 停的（典型是系统低内存清理 / 省电策略直接 SIGKILL），
# 在 supervisor-killed.log 里留一行 —— 上传的错误报告里就能写「上次被系统终止」，
# 而不是只留一堆没人看得懂的 Killed。
QQ_START_MARK="${QQ_START_MARK:-$QQBOT_STATE/supervisor.start}"
QQ_KILLED_LOG="${QQ_KILLED_LOG:-$QQBOT_LOGS/supervisor-killed.log}"

qq_start_mark_check() {
  [ -s "$QQ_START_MARK" ] || return 0
  local _opid _otime
  _opid=$(sed -n 's/^pid=//p' "$QQ_START_MARK" 2>/dev/null | head -n 1)
  _otime=$(sed -n 's/^started=//p' "$QQ_START_MARK" 2>/dev/null | head -n 1)
  case "$_opid" in ''|*[!0-9]*) rm -f "$QQ_START_MARK" 2>/dev/null; return 0 ;; esac
  # 还活着 —— 是同一轮启动（或另一条路径同时拉起的），不算被杀
  [ -d "/proc/$_opid" ] && return 0
  mkdir -p "$(dirname "$QQ_KILLED_LOG")" 2>/dev/null
  printf '%s 上次 supervisor（pid %s，启动于 %s）不是被 stop-sandbox 停掉的，可能被系统清理（低内存 / 省电策略）\n' \
    "$(date '+%F %T')" "$_opid" "${_otime:-?}" >> "$QQ_KILLED_LOG" 2>/dev/null
  # 只留最近 50 行，别让这份记录自己长大
  if [ -f "$QQ_KILLED_LOG" ]; then
    _tailtmp="$QQ_KILLED_LOG.tmp.$$"
    tail -n 50 "$QQ_KILLED_LOG" > "$_tailtmp" 2>/dev/null && mv -f "$_tailtmp" "$QQ_KILLED_LOG" 2>/dev/null
    rm -f "$_tailtmp" 2>/dev/null
  fi
  rm -f "$QQ_START_MARK" 2>/dev/null
  return 0
}

qq_start_mark_set() {
  local _pid="${1:-$$}"
  mkdir -p "$(dirname "$QQ_START_MARK")" 2>/dev/null
  {
    printf 'pid=%s\n' "$_pid"
    printf 'started=%s\n' "$(date '+%F %T')"
  } > "$QQ_START_MARK" 2>/dev/null
  return 0
}

qq_start_mark_clear() { rm -f "$QQ_START_MARK" 2>/dev/null; }


# ---------------------------------------------------------------------------
# 上传日志脱敏（v1.1.31）
#
# 背景：错误报告是用户主动提交的，但报告里难免夹着真实账号。以前只把 bots.json
# 的 token 打码，QQ 号、群号、手机号、二维码登录链接、本地 API Key 都会原样上传。
#
# 设计要点：
#   * 只打「像凭据 / 账号」的片段。端口（6099/6185/6199）、文件大小、时间戳、
#     行号、版本号必须原样保留 —— 它们是排查问题的关键，抹掉报告就白传了。
#   * 一条规则一遍 sed，命中数单独统计：一次性大 sed 说不清改了几处，而用户需要
#     那句「已脱敏 N 处」来判断这份报告还能不能帮我们定位问题。
#   * 调用顺序有讲究：必须在 1.5 MB 截断**之后**再脱敏（截断会把最后一段切在半路，
#     先脱敏再截断等于漏掉被切掉那部分里的命中数）。
# ---------------------------------------------------------------------------
qq_redact_file() {
  local f="${1:-}" total=0 n pat rep rule
  [ -n "$f" ] && [ -f "$f" ] || { printf '0'; return 1; }
  while IFS= read -r rule; do
    case "$rule" in ''|'#'*) continue ;; esac
    pat=${rule%%@@*}
    rep=${rule#*@@}
    n=$(grep -a -o -E -e "$pat" "$f" 2>/dev/null | wc -l 2>/dev/null | tr -d ' \n')
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    if [ "$n" -gt 0 ]; then
      # 不用 sed -i：个别机型上 sed -i 会重写 inode 与权限位，报告文件随后可能被
      # 其它进程读不到。写临时文件再 mv，权限沿用原文件的。
      if sed -E "s#$pat#$rep#g" "$f" > "$f.redact" 2>/dev/null; then
        cat "$f.redact" > "$f" 2>/dev/null
      fi
      total=$((total + n))
    fi
  done <<'QQBOT_REDACT_RULES'
"([A-Za-z_]*(token|secret|passw[a-z]*|api[_-]?key|apikey)[A-Za-z_]*)"[[:space:]]*:[[:space:]]*"[^"]*"@@"\1": "***"
# WebUI token 还会以「散文」形式出现，JSON 规则抓不到：
#   [napcat] 启动实例 bot1（WebUI 端口 6099，WebUI token zhuxin，日志 …）
#   10-20 07:33:19 [info] [NapCat] [WebUi] WebUi Token: zhuxin
# 这两行都是我们自己/上游打出来的，实测会整段进报告 —— token 是控制台密码，
# 属于必须抹掉的一类。值里排除 * 是为了幂等：已经抹成 *** 的不会再被数一遍。
([Ww]eb[Uu][Ii][[:space:]]*[Tt]oken[:：]?[[:space:]]*)[A-Za-z0-9._~+/=-]+@@\1***
([Tt]oken[:：][[:space:]]*)[A-Za-z0-9._~+/=-]{6,}@@\1***
(NAPCAT_WEBUI_SECRET_KEY|NAPCAT_WEBUI_JWT_SECRET_KEY|WEBUI_TOKEN|QQBOT_TOKEN)=[^[:space:]]+@@\1=***
[Bb]earer[[:space:]]+[A-Za-z0-9._~+/=-]{8,}@@Bearer ***
sk-[A-Za-z0-9_-]{8,}@@sk-***
AIza[0-9A-Za-z_-]{10,}@@AIza***
https?://txz\.qq\.com/p\?[^[:space:]"]+@@https://txz.qq.com/p?k=***
([?&](token|access_token|key|secret|password)=)[^&[:space:]"]+@@\1***
("(uin|qq|user_id|group_id|account|uin_list)"[[:space:]]*:[[:space:]]*"?)[0-9]{5,12}@@\1***
(账号|群号|QQ号|用户号|uin|user_id|group_id)([[:space:]]*(:|：|=)[[:space:]]*)[0-9]{5,12}@@\1\2***
(^|[^0-9])1[3-9][0-9]{9}([^0-9]|$)@@\1***\2
[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}@@***@***
QQBOT_REDACT_RULES
  rm -f "$f.redact" 2>/dev/null
  printf '%s' "$total"
  return 0
}

# ---------------------------------------------------------------------------
# 组件版本自动获取（v1.1.31）
#
# 目的：内置常量（QQBOT_NAPCAT_VER / QQBOT_ASTRBOT_VER）只在发版时更新，用户装完
# 就再也拿不到上游的新版本。这里问一次 GitHub releases/latest，缓存 24 小时。
#
# 三条硬规则：
#   1. 任何一步失败都退回内置常量 —— 查版本是锦上添花，绝不能让它把部署搞挂。
#   2. 只采信与内置常量**同大版本**的 tag。AstrBot 4.x 与 5.x 的资源布局、配置项都
#      变过，自动跨大版本升级会把部署搞坏；要跨大版本就改内置常量再发版。
#   3. 手动覆盖（QQBOT_NAPCAT_VER_OVERRIDE / QQBOT_ASTRBOT_VER_OVERRIDE）优先，
#      灰度或回滚时不用等我们发新版。
# ---------------------------------------------------------------------------

# 读缓存字段：_qq_ver_cache_field <kind> <tag|ts>
_qq_ver_cache_field() {
  local k="${1:-}" fld="${2:-}"
  [ -s "$QQBOT_VER_CACHE" ] || return 0
  sed -n 's/.*"'"$k"'_'"$fld"'"[[:space:]]*:[[:space:]]*\("\([^"]*\)"\|\([0-9][0-9]*\)\).*/\2\3/p' \
    "$QQBOT_VER_CACHE" 2>/dev/null | head -n 1
}

# 写缓存：保留另一类组件已有的值，只更新自己这一项。
_qq_ver_cache_put() {
  local k="${1:-}" t="${2:-}" s="${3:-0}" nt at nts ats tmp
  nt=$(_qq_ver_cache_field napcat tag); [ -n "$nt" ] || nt="$QQBOT_NAPCAT_VER"
  nts=$(_qq_ver_cache_field napcat ts); case "$nts" in ''|*[!0-9]*) nts=0 ;; esac
  at=$(_qq_ver_cache_field astrbot tag); [ -n "$at" ] || at="$QQBOT_ASTRBOT_VER"
  ats=$(_qq_ver_cache_field astrbot ts); case "$ats" in ''|*[!0-9]*) ats=0 ;; esac
  case "$k" in
    napcat)  nt="$t"; nts="$s" ;;
    astrbot) at="$t"; ats="$s" ;;
  esac
  mkdir -p "$QQBOT_STATE" 2>/dev/null
  tmp="$QQBOT_VER_CACHE.$$"
  printf '{"napcat_tag":"%s","napcat_ts":%s,"astrbot_tag":"%s","astrbot_ts":%s}\n' \
    "$(qq_json_esc "$nt")" "$nts" "$(qq_json_esc "$at")" "$ats" > "$tmp" 2>/dev/null \
    && mv -f "$tmp" "$QQBOT_VER_CACHE" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  return 0
}

# 问一次 GitHub：_qq_ver_fetch <owner/repo>，输出 releases/latest 的原始 JSON（可能为空）。
# 单独返回整段 JSON 而不是只取 tag：调用方还要用同一份响应核对「这个版本有没有我们要的
# 资源文件」——AstrBot 的 tag 与 dashboard 资源必须成对存在，只信 tag_name 可能拼出一个
# 404 的下载地址，用户会在部署到一半时莫名其妙失败。
_qq_ver_fetch() {
  local repo="${1:-}" api body
  api="https://api.github.com/repos/$repo/releases/latest"
  if command -v curl >/dev/null 2>&1; then
    body=$(curl -fsSL --max-time 12 \
      -H 'Accept: application/vnd.github+json' \
      -H 'User-Agent: qqbot-sandbox' "$api" 2>/dev/null)
  elif command -v wget >/dev/null 2>&1; then
    body=$(wget -q -O - --timeout=12 \
      --header='Accept: application/vnd.github+json' \
      --header='User-Agent: qqbot-sandbox' "$api" 2>/dev/null)
  fi
  [ -n "$body" ] || return 0
  printf '%s' "$body"
}

# 从 releases JSON 里取 tag_name（JSON 里的键顺序稳定，直接用 sed 抓第一个）
_qq_ver_pick_tag() {
  printf '%s' "${1:-}" \
    | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

# 该 JSON 的资源列表里是否有某个文件名（用 "name": "<file>" 精确匹配）
_qq_ver_has_asset() {
  local body="${1:-}" name="${2:-}"
  [ -n "$body" ] && [ -n "$name" ] || return 1
  printf '%s' "$body" | grep -F -q "\"$name\"" 2>/dev/null
}

# 组件最新版本：qq_latest_ver <napcat|astrbot>
# 输出（stdout）：可直接拼进下载 URL 的 tag，例如 v4.18.38 / v4.29.1
qq_latest_ver() {
  local kind="${1:-}" builtin="" repo="" override="" cand="" ts=0 now bmaj cmaj body need
  now=$(date +%s 2>/dev/null || echo 0)
  case "$kind" in
    napcat)
      builtin="$QQBOT_NAPCAT_VER"; repo="NapNeko/NapCatQQ"; need="NapCat.Shell.zip"
      override="${QQBOT_NAPCAT_VER_OVERRIDE:-}" ;;
    astrbot)
      builtin="$QQBOT_ASTRBOT_VER"; repo="AstrBotDevs/AstrBot"; need="__DASH__"
      override="${QQBOT_ASTRBOT_VER_OVERRIDE:-}" ;;
    *)
      printf '%s' "$QQBOT_NAPCAT_VER"; return 1 ;;
  esac
  if [ -n "$override" ]; then
    printf '%s' "$override"; return 0
  fi
  # 缓存命中（24 小时内）直接用，避免每次部署都打一次 GitHub（未登录 API 限流 60 次/小时）
  ts=$(_qq_ver_cache_field "$kind" ts)
  case "$ts" in ''|*[!0-9]*) ts=0 ;; esac
  cand=$(_qq_ver_cache_field "$kind" tag)
  if [ -n "$cand" ] && [ "$ts" -gt 0 ] && [ $((now - ts)) -lt "$QQBOT_VER_TTL" ]; then
    printf '%s' "$cand"; return 0
  fi
  body=$(_qq_ver_fetch "$repo")
  cand=$(_qq_ver_pick_tag "$body")
  # 只接受 vX.Y.Z 且与内置常量同大版本（跨大版本升级会改动配置项与资源布局，必须发版）
  case "$cand" in v[0-9]*) ;; *) cand="" ;; esac
  if [ -n "$cand" ]; then
    bmaj=$(printf '%s' "$builtin" | sed -n 's/^v\([0-9][0-9]*\).*/\1/p')
    cmaj=$(printf '%s' "$cand" | sed -n 's/^v\([0-9][0-9]*\).*/\1/p')
    [ -n "$bmaj" ] && [ "$bmaj" = "$cmaj" ] || cand=""
  fi
  # 资源核对：没有对应的安装包就宁可用内置版本，也不要拼一个 404 的地址
  if [ -n "$cand" ]; then
    case "$need" in
      __DASH__) need="AstrBot-$cand-dashboard.zip" ;;
    esac
    _qq_ver_has_asset "$body" "$need" || {
      [ -n "$cand" ] && qqlog "[ver] $kind $cand 没有 $need，继续用内置 $builtin"
      cand=""
    }
  fi
  if [ -n "$cand" ]; then
    _qq_ver_cache_put "$kind" "$cand" "$now"
    printf '%s' "$cand"; return 0
  fi
  # 查询失败：不清缓存里已有的值（可能是上一次成功的结果），但要按内置常量返回，
  # 免得把一个来路不明的空值写进 URL。
  printf '%s' "$builtin"
  return 0
}

# AstrBot 的数据目录（宿主侧路径）。真机上 AstrBot 装在 rootfs 的 /opt/AstrBot，
# 数据就在 /opt/AstrBot/data（run-astrbot.sh:32 cd /opt/AstrBot，60-astrbot.sh:211
# mkdir -p /opt/AstrBot/data）；文档和老版本写作 /root/astrbot/data。这里「哪个
# 存在用哪个」，两个都不存在时返回首选路径，调用方按 0 字节处理，不会因此报错。
qq_astrbot_data_dir() {
  local a="$QQBOT_ROOTFS/opt/AstrBot/data" b="$QQBOT_ROOTFS/root/astrbot/data"
  if [ -d "$b" ] && [ ! -d "$a" ]; then
    printf '%s' "$b"
  else
    printf '%s' "$a"
  fi
}

# 路径字节数合计（文件或目录，可传多个）。GNU du 有 -b 就用 -sb，没有就 -sk×1024。
# 单个路径失败按 0 计 —— 统计和清理都不能因为某个目录读不了就整体失败。
qq_du_bytes() {
  local t n s=0
  if [ -z "${QQ_DU_B:-}" ]; then
    if du -sb "$QQBOT_HOME" >/dev/null 2>&1; then QQ_DU_B=1; else QQ_DU_B=0; fi
  fi
  for t in "$@"; do
    [ -e "$t" ] || continue
    if [ "$QQ_DU_B" = "1" ]; then
      n=$(du -sb "$t" 2>/dev/null | sed 's/[[:space:]].*//')
    else
      n=$(du -sk "$t" 2>/dev/null | sed 's/[[:space:]].*//')
      case "$n" in ''|*[!0-9]*) n=0 ;; *) n=$((n * 1024)) ;; esac
    fi
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    s=$((s + n))
  done
  printf '%s\n' "$s"
}

# 从 stdin 读文本算 sha1 前 12 位（功能 58 的崩溃签名）。
# sha1sum 不在就退到 openssl，再不行用 md5sum —— 签名只要求「同崩溃同签名」，
# 不要求密码学强度，别为了它引入新依赖。
qq_sha1_12() {
  local h=""
  if command -v sha1sum >/dev/null 2>&1; then
    h=$(sha1sum 2>/dev/null | sed 's/[[:space:]].*//')
  elif command -v openssl >/dev/null 2>&1; then
    h=$(openssl dgst -sha1 2>/dev/null | sed 's/.*=[[:space:]]*//')
  elif command -v md5sum >/dev/null 2>&1; then
    h=$(md5sum 2>/dev/null | sed 's/[[:space:]].*//')
  fi
  printf '%s' "${h:0:12}"
}

# 崩溃聚合（功能 58，协议第 9 节）。
#
# 输入：crashwatch 写的现场文件，默认 $QQBOT_CACHE/crashes/crash-<tid>-sig<sig>.log
#       （格式见 sandbox/crashwatch.c:278-365：tag / signal / pc / "--- pc 所在段 ---"
#       后面跟一行 /proc/self/maps 原始内容）。也兼容老的 $QQBOT_HOME/crash/*.txt。
# 归并：签名 = sha1(模块名 + 归一化 pc 偏移 + 信号) 前 12 位。地址、时间、pid/tid、
#       完整路径都不进签名，换设备换安装路径也只是同一类。组件名取 crashwatch 的
#       tag（run-napcat.sh 传的 QQBOT_CRASH_TAG 是实例 id），没有就写 napcat。
# 输出：$QQBOT_CACHE/crash-counts.json，按 count 降序取前 50 类；样本每类留 3 条。
# 顺带打印一行 `[crash-agg] N 类 / M 次`（watchdog.sh 每 30 分钟调一次）。
qq_crash_agg() {
  local dir="${QQBOT_CRASH_DIR:-$QQBOT_CACHE/crashes}"
  local legacy="$QQBOT_HOME/crash"
  local out="$QQBOT_CACHE/crash-counts.json" tmp="$out.$$"
  local now ver dev f tag signame pc line lo mod off nstr key mt ns
  local cnt total=0 ncls=0 sep="" ordered samples sp
  declare -A K_CNT=() K_FIRST=() K_LAST=() K_SIG=() K_COMP=() K_SIGL=() K_SAMP=() K_NS=()

  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  now=$(date +%s)
  ver="?"
  [ -s "$QQBOT_OPT/VERSION" ] && ver="$(head -n 1 "$QQBOT_OPT/VERSION" 2>/dev/null | tr -d '\r')"
  [ -n "$ver" ] || ver="?"
  dev="$(getprop ro.product.model 2>/dev/null | tr -d '\r')"
  [ -n "$dev" ] || dev="?"

  for f in "$dir"/crash-*.log "$dir"/crash-*.txt "$legacy"/*.log "$legacy"/*.txt; do
    [ -f "$f" ] || continue
    tag="$(sed -n 's/^tag[[:space:]]*=[[:space:]]*//p' "$f" 2>/dev/null | head -n 1 | tr -d '\r')"
    [ -n "$tag" ] || tag="napcat"
    signame="$(sed -n 's/^signal[[:space:]]*=[[:space:]]*[0-9]*[[:space:]]*(\([A-Za-z0-9_]*\)).*/\1/p' "$f" 2>/dev/null | head -n 1)"
    [ -n "$signame" ] || signame="SIG?"
    pc="$(sed -n 's/^pc[[:space:]]*=[[:space:]]*\(0x[0-9a-fA-F][0-9a-fA-F]*\).*/\1/p; s/^rip[[:space:]]*=[[:space:]]*\(0x[0-9a-fA-F][0-9a-fA-F]*\).*/\1/p' "$f" 2>/dev/null | head -n 1)"
    line="$(grep -a -A1 -F -- '--- pc 所在段 ---' "$f" 2>/dev/null | sed -n '2p')"
    mod="[anon]"; off=0
    if [ -n "$line" ]; then
      # maps 行格式：start-end perms offset dev inode pathname
      lo="${line%%-*}"; lo="${lo#0x}"
      mod="$(printf '%s\n' "$line" | sed 's/^[^ ]* *[^ ]* *[^ ]* *[^ ]* *[^ ]* *//')"
      case "$mod" in *" (deleted)") mod="${mod% (deleted)}" ;; esac
      mod="$(basename "$mod" 2>/dev/null)"
      [ -n "$mod" ] || mod="[anon]"
      if [ -n "$pc" ]; then
        case "$lo" in
          ''|*[!0-9a-fA-F]*) off=0 ;;
          *) off=$(( pc - 0x$lo )) 2>/dev/null || off=0 ;;
        esac
        [ "$off" -ge 0 ] 2>/dev/null || off=0
      fi
    fi
    key="$tag|$mod+0x$(printf '%x' "$off")|$signame"
    K_CNT[$key]=$(( ${K_CNT[$key]:-0} + 1 ))
    if [ -z "${K_SIG[$key]:-}" ]; then
      nstr="$mod+0x$(printf '%x' "$off")|$signame"
      K_SIG[$key]="$(printf '%s' "$nstr" | qq_sha1_12)"
      K_COMP[$key]="$tag"
      K_SIGL[$key]="$signame"
    fi
    # crashwatch 不写时间戳，用文件 mtime 当 first/last
    mt="$(stat -c %Y "$f" 2>/dev/null)"
    case "$mt" in ''|*[!0-9]*) mt="$now" ;; esac
    if [ -z "${K_FIRST[$key]:-}" ] || [ "$mt" -lt "${K_FIRST[$key]}" ]; then K_FIRST[$key]="$mt"; fi
    if [ "$mt" -gt "${K_LAST[$key]:-0}" ]; then K_LAST[$key]="$mt"; fi
    K_NS[$key]=$(( ${K_NS[$key]:-0} + 1 ))
    ns="${K_NS[$key]}"
    if [ "$ns" -le 3 ]; then
      K_SAMP[$key]="${K_SAMP[$key]:-}$f"$'\n'
    fi
  done

  for key in "${!K_CNT[@]}"; do
    total=$((total + ${K_CNT[$key]}))
  done
  ordered="$(for key in "${!K_CNT[@]}"; do
    printf '%s\t%s\n' "${K_CNT[$key]}" "$key"
  done | sort -rn | head -n 50)"

  {
    printf '{"ts":%s,"classes":[' "$now"
    while IFS=$'\t' read -r cnt key; do
      [ -n "${key:-}" ] || continue
      [ -n "${K_SIG[$key]:-}" ] || continue
      ncls=$((ncls + 1))
      samples=""
      sp=""
      while IFS= read -r sp; do
        [ -n "$sp" ] || continue
        samples="${samples:+$samples,}\"$(qq_json_esc "$sp")\""
      done <<EOF
${K_SAMP[$key]:-}
EOF
      printf '%s{"sig":"%s","component":"%s","signal":"%s","count":%s,"first":%s,"last":%s,"ver":"%s","device":"%s","samples":[%s]}' \
        "$sep" "${K_SIG[$key]}" "$(qq_json_esc "${K_COMP[$key]}")" "$(qq_json_esc "${K_SIGL[$key]}")" \
        "$cnt" "${K_FIRST[$key]}" "${K_LAST[$key]}" "$(qq_json_esc "$ver")" "$(qq_json_esc "$dev")" "$samples"
      sep=","
    done <<EOF2
$ordered
EOF2
    printf ']}\n'
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$out" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  printf '[crash-agg] %s 类 / %s 次\n' "$ncls" "$total"
  return 0
}
