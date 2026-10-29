#!/bin/bash
# NapCat 多实例守护进程
#
# 每 5 秒重读 /qqbot-cache/bots.json，按实例的 enable 动态启停：
#   enable=true  且没在跑   -> 启动
#   enable=false 但还在跑   -> 停止（保留数据，重新启用后登录态还在）
#   已从 bots.json 里删掉   -> 停止并删掉该实例的 QQ 副本 / 登录数据 / 日志
#
# 每个实例的 QQ 登录数据都放在自己的 /root/qqinst/<id>（由 --user-data-dir 指定），
# 所以切换「当前实例」、删除实例都不会影响别的实例的登录态。
# QQ 程序目录只有一个例外：bots.json 里的 active 实例优先用 /opt/QQ，其余实例用硬链接
# 复制出来的 /opt/qqbot-inst/<id>/QQ（硬链接共享数据块，几乎不占额外空间）。某个实例一旦
# 建好自己的副本就一直用它，避免目录来回搬导致登录态丢失。
#
# set -m：每个实例自成进程组，停实例时 kill -TERM -<pid> 能一次收掉 xvfb-run / Xvfb / QQ。
# 必须先 TERM 再 KILL：直接 -9 会跳过进程自己的清理逻辑，留下占用端口的孤儿进程。
set -m
export HOME=/root

# 这三个是 NapCat 的反向开关，必须保证不被继承进来（从 supervisor / Termux 环境里漏进来
# 的话反检测会当场失效，而且是不声不响地失效）：
#   NAPCAT_DISABLE_BYPASS=1      关掉全部 bypass
#   NAPCAT_DISABLE_TIME_SYNC=1   关掉每小时自动对时
#   NAPCAT_ENABLE_VERBOSE_LOG=1  把反检测明文日志写到盘上（napi2native_bypass.log）
unset NAPCAT_DISABLE_BYPASS NAPCAT_DISABLE_TIME_SYNC NAPCAT_ENABLE_VERBOSE_LOG

LOGDIR=/qqbot-cache/logs
RUNDIR=/qqbot-cache/run
# 原生层（App）与脚本层共享的状态目录，宿主侧是 $HOME/.qqbot/state，
# start-sandbox.sh 用 -b 绑进来。风控的 risk.json / risk-<id>.flag 就在这里。
STATE_DIR=/qqbot-state
INSTBASE=/opt/qqbot-inst
QQBASE=/opt/QQ
BOTS_JSON=/qqbot-cache/bots.json
mkdir -p "$LOGDIR" "$RUNDIR" "$INSTBASE" /root/qqinst

if [ ! -s "$BOTS_JSON" ]; then
  # 先写临时文件再 mv 原子替换：`> $BOTS_JSON` 是截断式写入，守护进程和 UI 侧
  # 只要在这几十字节写完之前读一次，就会读到空 / 半截的 JSON（进而误判实例被删）。
  printf '%s' '{"active":"bot1","bots":[{"id":"bot1","name":"main","qq":"","token":"zhuxin","napcatPort":6099,"onebotPort":6199,"enable":true}]}' > "$BOTS_JSON.$$.tmp"
  mv -f "$BOTS_JSON.$$.tmp" "$BOTS_JSON"
fi

declare -A PIDS QDIR STOP PURGE XPIDS RISK_SAID
CLEANED=0

log() { echo "[napcat] $*"; }

# ---------- 风控识别（沙盒侧，协议：docs/接口约定-1.1.30.md 第 2 节） ----------
# 沙盒里 source 不到宿主的 common.sh（那是 Termux 路径下的脚本，沙盒看不见），
# 所以这里有一份精简实现，和宿主侧 watchdog.sh 读写的是同一批文件
# （/qqbot-state 就是宿主 $QQBOT_HOME/state）。两边共用一个偏移文件、且偏移只
# 前进不后退（取大），所以双写既不会漏报，也不会把旧事件重报一遍。
risk_id() { printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_'; }

# 三个阈值：宿主 qq_risk_init() 每次启动会写 /qqbot-state/risk-cfg（KEY=VALUE），
# 沙盒 source 不到宿主 common.sh，只能从这里读。读不到就用和宿主一样的默认值。
risk_cfg() {
  local k="${1:-}" v=""
  [ -s /qqbot-state/risk-cfg ] || return 0
  v=$(sed -n "s/^$k=//p" /qqbot-state/risk-cfg 2>/dev/null | head -n 1 | tr -d ' \r')
  case "$v" in ''|*[!0-9]*) return 0 ;; esac
  printf '%s' "$v"
}
risk_window() { local v; v=$(risk_cfg WINDOW); printf '%s' "${v:-600}"; }
risk_hits()   { local v; v=$(risk_cfg HITS);   printf '%s' "${v:-2}"; }
risk_ttl()    { local v; v=$(risk_cfg TTL);    printf '%s' "${v:-21600}"; }

# 该实例是否处于风控暂停态。带自动过期：flag 的 mtime 超过 TTL 就删掉 flag 与
# risk.json，让看门狗重新尝试拉起 —— 否则一次误报会让实例永久停摆。
risk_blocked() {
  local i f mt now ttl
  i=$(risk_id "${1:-}")
  [ -n "${1:-}" ] || return 1
  f="$STATE_DIR/risk-$i.flag"
  [ -f "$f" ] || return 1
  now=$(date +%s 2>/dev/null || echo 0)
  mt=$(date -r "$f" +%s 2>/dev/null || stat -c %Y "$f" 2>/dev/null || echo 0)
  case "$mt" in ''|*[!0-9]*) mt=0 ;; esac
  ttl=$(risk_ttl)
  if [ "$mt" -gt 0 ] && [ "$now" -gt 0 ] && [ $((now - mt)) -ge "$ttl" ]; then
    rm -f "$f" "$STATE_DIR/risk.json" 2>/dev/null
    log "[risk] 实例 $i 的风控暂停已过期（>${ttl}s），自动解除"
    return 1
  fi
  return 0
}

# 命中的词（给日志文案用；可能为空）
risk_word() { head -n 1 "$STATE_DIR/risk-$(risk_id "${1:-}").word" 2>/dev/null; }

# 扫日志新增部分，命中就写 risk.json 并打暂停标记。
#   用法: risk_scan <logfile> <instance>
#   返回: 0 = 处于风控暂停态；1 = 正常
risk_scan() {
  local f="${1:-}" raw="${2:-}" id sz off cur w line2 tmp _l2
  [ -n "$raw" ] && [ -f "$f" ] || return 1
  id=$(risk_id "$raw")
  mkdir -p "$STATE_DIR" 2>/dev/null
  # 词表：宿主侧 qq_risk_init 会放一份；沙盒先起来时这里自己补一次。
  # 之后直接改 /qqbot-state/risk-words.txt 即可热生效。
  if [ ! -s "$STATE_DIR/risk-words.txt" ] && [ -s /qqbot-opt/sandbox/risk-words.txt ]; then
    cp -f /qqbot-opt/sandbox/risk-words.txt "$STATE_DIR/risk-words.txt" 2>/dev/null
  fi
  [ -s "$STATE_DIR/risk-words.txt" ] || return 1
  sz=$(wc -c < "$f" 2>/dev/null)
  case "$sz" in ''|*[!0-9]*) return 1 ;; esac
  off=$(cat "$STATE_DIR/risk-$id.off" 2>/dev/null)
  case "$off" in ''|*[!0-9]*) off=0 ;; esac
  if [ "$sz" -lt "$off" ]; then
    # 日志被 trim_logs 原地截断：从当前位置接着看，不回头重扫 ——
    # 截断后留下的那 2MB 是已经看过的旧内容，重扫会把很久以前的事当成刚发生。
    echo "$sz" > "$STATE_DIR/risk-$id.off" 2>/dev/null
  fi
  if [ "$sz" -le "$off" ]; then
    risk_blocked "$raw" && return 0
    return 1
  fi
  # grep 的 -f 会把 # 注释行也当成模式，所以先滤掉注释和空行。
  w=$(tail -c "+$((off + 1))" "$f" 2>/dev/null \
      | grep -a -o -i -F -m 1 -f <(grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$STATE_DIR/risk-words.txt" 2>/dev/null | tr -d '\r') 2>/dev/null \
      | head -n 1)
  # 偏移先推进（只前进不后退）：这一段无论有没有命中都已经看过了。
  cur=$(cat "$STATE_DIR/risk-$id.off" 2>/dev/null)
  case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
  [ "$sz" -gt "$cur" ] && echo "$sz" > "$STATE_DIR/risk-$id.off" 2>/dev/null
  [ -n "$w" ] || { risk_blocked "$raw" && return 0; return 1; }

  # 命中一次先记账：/qqbot-state/risk-<id>.hits 里每行一个时间戳，只留最近 20 行。
  # 窗口内的次数就是「最近 N 秒记了几笔」。规则必须和宿主 qq_risk_scan() 一致 ——
  # 两边同时扫同一份日志，判据不同就会出现宿主说「已暂停」、沙盒继续重启的情况。
  local hits_f now n
  hits_f="$STATE_DIR/risk-$id.hits"
  now=$(date +%s 2>/dev/null || echo 0)
  printf '%s\n' "$now" >> "$hits_f" 2>/dev/null
  if [ -s "$hits_f" ]; then
    tail -n 20 "$hits_f" > "$hits_f.tmp" 2>/dev/null && mv -f "$hits_f.tmp" "$hits_f" 2>/dev/null
  fi
  rm -f "$hits_f.tmp" 2>/dev/null
  # 窗口计数按「不同秒」去重 —— 宿主 watchdog 与我们这份实现会同时扫同一份日志，
  # 同一次命中可能各记一笔（同一秒两行），按行数算等于把阈值砍半。
  n=$(awk -v now="$now" -v w="$(risk_window)" \
        'NF && $1 ~ /^[0-9]+$/ && (now - $1) <= w { if ($1 != prev) { c++; prev = $1 } } END { print c + 0 }' \
        "$hits_f" 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=1 ;; esac
  printf '%s\n' "$w" > "$STATE_DIR/risk-$id.word" 2>/dev/null
  if [ "$n" -lt "$(risk_hits)" ]; then
    log "[risk] 实例 $raw 命中风控词「$w」，窗口内第 $n/$(risk_hits) 次，暂不暂停"
    risk_blocked "$raw" && return 0
    return 1
  fi

  : > "$STATE_DIR/risk-$id.flag" 2>/dev/null
  # 60 秒内只写一次 risk.json（原生层靠它弹通知）；标记本身每轮都补，幂等。
  cur=$(cat "$STATE_DIR/risk-$id.last" 2>/dev/null)
  case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
  if [ "$cur" -gt 0 ] && [ $(( $(date +%s) - cur )) -lt 60 ]; then
    return 0
  fi
  line2=$(tail -c "+$((off + 1))" "$f" 2>/dev/null | grep -a -i -F -m 1 -e "$w" 2>/dev/null | head -n 1)
  [ -n "$line2" ] || line2="$w"
  line2=$(printf '%s' "$line2" | tr -d '\000-\037' | cut -c1-600 2>/dev/null)
  # 截断可能正好切在一个汉字的三个字节中间，那会让原生层 JSON 解析抛异常。
  if command -v iconv >/dev/null 2>&1; then
    _l2=$(printf '%s' "$line2" | iconv -f UTF-8 -t UTF-8 -c 2>/dev/null)
    [ -n "$_l2" ] && line2="$_l2"
  fi
  tmp="$STATE_DIR/risk.json.$$"
  printf '{"ok":true,"risk":true,"instance":"%s","word":"%s","line":"%s","since":%s,"file":"/qqbot-cache/logs/qq-%s.log"}\n' \
    "$raw" "$(printf '%s' "$w" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')" \
    "$(printf '%s' "$line2" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')" \
    "$(date +%s)" "$raw" > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATE_DIR/risk.json" 2>/dev/null
  rm -f "$tmp" 2>/dev/null
  date +%s > "$STATE_DIR/risk-$id.last" 2>/dev/null
  return 0
}

# 把当前所有 Xvfb / QQ 进程的显示号打出来。NapCat 起不来时，
# 「X 到底有没有在跑、QQ 有没有真的活着」比任何参数都重要。
dump_procs() {
  local p c found=0
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    { c=$(<"$p/cmdline"); } 2>/dev/null || continue
    case "$c" in
      *Xvfb*|*/opt/QQ/qq*|*/opt/qqbot-inst/*)
        log "  [ps] ${p#/proc/}: $(printf '%s' "$c" | tr '\0' ' ' | cut -c1-160)"
        found=1 ;;
    esac
  done
  [ "$found" = 1 ] || log "  [ps] 没有任何 Xvfb / QQ 进程在跑"
  log "  [x] /tmp/.X11-unix: $(ls -1 /tmp/.X11-unix 2>/dev/null | tr '\n' ' ')"
}

in_list() {
  local x="$1"; shift
  local y
  for y in "$@"; do [ "$y" = "$x" ] && return 0; done
  return 1
}

# ---------- 写某个实例的 OneBot11 反向 WebSocket 配置（指向 AstrBot 的 aiocqhttp 端口） ----------
write_onebot() {
  python3 - "$1" "$2" <<'PY'
import json, os, sys
cdir, oport = sys.argv[1], int(sys.argv[2])
os.makedirs(cdir, exist_ok=True)
cfg = os.path.join(cdir, 'onebot11.json')
d = {}
if os.path.exists(cfg):
    try:
        d = json.load(open(cfg, encoding='utf-8-sig'))
    except Exception:
        d = {}
net = d.setdefault('network', {})
for k in ['websocketServers', 'httpServers', 'httpSseServers', 'httpClients', 'plugins']:
    net.setdefault(k, [])
net['websocketClients'] = [{
    'name': 'astrbot', 'enable': True,
    'url': 'ws://127.0.0.1:%d/ws' % oport,
    'messagePostFormat': 'array', 'reportSelfMessage': False,
    'reconnectInterval': 5000, 'token': '', 'debug': False,
    'heartInterval': 30000, 'verifyCertificate': True,
}]
d.setdefault('musicSignUrl', '')
d.setdefault('enableLocalFile2Url', False)
d.setdefault('parseMultMsg', False)
d.setdefault('imageDownloadProxy', '')
# 先删再写：QQ 目录是用硬链接复制的，直接覆盖会把第一个实例的配置一起改掉
if os.path.exists(cfg):
    os.remove(cfg)
json.dump(d, open(cfg, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('[napcat] OneBot 配置 %s -> ws://127.0.0.1:%d/ws' % (cfg, oport))
PY
}

# ---------- 写 WebUI 配置（局域网开关，v1.1.31） ----------
# 以前是每次启动删掉 webui.json 让 NapCat 自己按环境变量重建。但「只监听 127.0.0.1」
# 这件事没法用环境变量表达 —— NapCat 的 host 只认配置文件。所以改成自己写：
#   * state/lan.on 存在（宿主 /qqbot-state/lan.on）→ host "::"（IPv6 通配，同时吃 IPv4，
#     手机与电脑在同一局域网时可以直接用手机 IP 访问控制台 / WebUI）；
#   * 不存在 → "127.0.0.1"，只有本机能连，这是默认值。
# token / 端口仍以 bots.json 为准（由调用方传进来），token 为空时写空串（NapCat 会用
# 环境变量里的 NAPCAT_WEBUI_SECRET_KEY 覆盖，两者保持一致就行）。
write_webui() {
  local cdir="$1" port="$2" token="$3" lan="$4"
  python3 - "$cdir" "$port" "$token" "$lan" <<'PY'
import json, os, sys
cdir, port, token, lan = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
host = '::' if lan == '1' else '127.0.0.1'
os.makedirs(cdir, exist_ok=True)
cfg = os.path.join(cdir, 'webui.json')
d = {}
# 保留用户可能在 WebUI 里改过的其它键（loginRate、autoLoginAccount 等）
if os.path.exists(cfg):
    try:
        d = json.load(open(cfg, encoding='utf-8-sig'))
    except Exception:
        d = {}
d['host'] = host
d['port'] = port
d['token'] = token
if not isinstance(d.get('loginRate'), int) or d.get('loginRate') <= 0:
    d['loginRate'] = 3
# 先删再写：QQ 目录是硬链接复制的，直接覆盖会串到别的实例
if os.path.exists(cfg):
    os.remove(cfg)
json.dump(d, open(cfg, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('[napcat] WebUI 监听 %s:%d（局域网%s）' % (host, port, '已开启' if lan == '1' else '关闭'))
PY
}

# ---------- 反检测：把 napcat.json 补成「bypass 全开 + autoTimeSync」 ----------
# NapCat 的「反检测」= config/napcat.json 里 bypass 的 6 个布尔键（hook / window /
# module / process / container / js）全 true，外加 autoTimeSync=true（源码注释里唯一
# 明说防风控的开关）。schema 默认虽是 true，但发行包和 WebUI 写的是 false，必须显式写。
#   * 文件不存在      -> 按权威 9 键结构创建；
#   * 文件已存在      -> 只强制 bypass 六键 + autoTimeSync，其余键原样保留、绝不整体覆盖；
#   * 已是目标状态    -> 不落盘（避免无意义的 inode 变化，那会干扰上面按 inode 判断的硬链接分离）；
#   * 坏 JSON / 任何异常 -> 只告警不中断，绝不阻塞启动（NapCat 自己会重建它）。
# ⚠️ napcat.mjs 里有 `bypass.${a} must be boolean` 校验：只能写 JSON 布尔 true，
#    不能写字符串 "true"，也不能往里多塞键。
# ⚠️ 这个 python 体和 50-napcat.sh 里那份必须保持一致，改一处要同步改另一处。
write_napcat() {
  local cdir="$1" id="$2" uin="${3:-}"
  python3 - "$cdir" "$id" "$uin" <<'PY'
import json, os, sys

# NapCat 4.18.38 的 NapcatConfigSchema 一共就这 9 个键，没有别的隐藏开关。
# bypass 六键在 schema 里的默认值虽然是 true，但发行包和 WebUI 实际写的是 false，
# 所以必须显式写 true，不能靠 schema 默认值兜底。
CANON = {
    'fileLog': False,
    'consoleLog': True,
    'fileLogLevel': 'debug',
    'consoleLogLevel': 'info',
    'packetBackend': 'auto',
    'packetServer': '',
    'o3HookMode': 1,
    'autoTimeSync': True,
}
# WebUI「反检测配置」页上的 6 个开关：hook 特征隐藏 / 窗口伪造 / 加载模块隐藏 /
# 进程反检测 / 容器反检测 / JS 反检测
BYPASS_KEYS = ('hook', 'window', 'module', 'process', 'container', 'js')


def merge(d):
    """强制打开反检测并补齐 9 个键；已有的其它键（含用户自定义键）一律原样保留。"""
    bp = d.get('bypass')
    if not isinstance(bp, dict):
        bp = {}
    for k in BYPASS_KEYS:
        bp[k] = True
    d['bypass'] = bp
    # 源码注释里唯一明说防风控的开关：每小时重新对时，防长时间运行后本地时钟漂移
    d['autoTimeSync'] = True
    for k, v in CANON.items():
        cur = d.get(k)
        if isinstance(v, bool):
            ok = isinstance(cur, bool)
        elif isinstance(v, int):
            # bool 是 int 的子类，必须排除，否则 true 会被当成合法的 o3HookMode
            ok = isinstance(cur, int) and not isinstance(cur, bool)
        else:
            ok = isinstance(cur, str)
        if not ok:
            d[k] = v
    return d


def patch(path, create_ok):
    d, orig = None, None
    if not os.path.exists(path):
        if not create_ok:
            return
        print('[napcat] %s 不存在，按权威结构创建' % path)
    else:
        try:
            with open(path, encoding='utf-8-sig') as fh:
                raw = json.load(fh)
            if isinstance(raw, dict):
                d = raw
            else:
                print('[napcat] %s 顶层不是 JSON 对象，重建为权威配置' % path)
        except Exception as e:
            # 坏 JSON 绝不能拦住启动：NapCat 自己会按默认值重建它，这里只报个警
            print('[napcat] %s 解析失败（%s），重建为权威配置' % (path, e))
        if d is not None:
            # 深拷一份用于比较：改完若与原文等价就不落盘，避免无意义的 inode 变化
            orig = json.loads(json.dumps(d))

    if d is None:
        d = {}
    d = merge(d)

    if d.get('packetBackend') == 'disable':
        print('[napcat] 警告：%s 里 packetBackend=disable（正常应为 auto），已原样保留' % path)

    if orig is not None and d == orig:
        print('[napcat] %s 已是目标状态（9 键齐全、bypass 全开），未改动' % path)
        return

    # 先写临时文件再 os.replace：json.dump 直接写目标路径是截断式写入，被紧接着启动的
    # NapCat 读到半截 JSON 会当成配置损坏；而且 config/ 是硬链接复制来的，必须换成新
    # inode，否则会改到别的实例共用同一个 inode 的那份文件。
    tmp = '%s.qqbot-%d.tmp' % (path, os.getpid())
    try:
        with open(tmp, 'w', encoding='utf-8') as fh:
            json.dump(d, fh, indent=2, ensure_ascii=False)
        os.replace(tmp, path)
    except Exception:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    print('[napcat] 已写入 %s（bypass 六键全 true + autoTimeSync）' % path)


cdir, iid = sys.argv[1], sys.argv[2]
uin = (sys.argv[3] if len(sys.argv) > 3 else '').strip()
try:
    os.makedirs(cdir, exist_ok=True)
except OSError:
    pass

# 1) 主配置：不存在就按权威结构创建；存在就只合并，绝不整体覆盖
patch(os.path.join(cdir, 'napcat.json'), True)

# 2) config/napcat_<uin>.json 存在时 NapCat 会优先用它，而 WebUI 读缺失键时默认 false
#    —— 用户在「反检测」页点一次保存就会写出这个文件，把我们打开的开关悄悄关掉，
#    所以发现它就必须一起合并。安装时还拿不到 uin，这一步靠 run-napcat.sh 每次启动兜住。
extra = os.path.join(cdir, 'napcat_%s.json' % uin) if uin else ''
if not uin:
    print('[napcat] 实例 %s 没有 qq 号信息，跳过 napcat_<uin>.json 检查' % iid)
elif os.path.exists(extra):
    print('[napcat] 实例 %s 发现 %s，一并合并' % (iid, os.path.basename(extra)))
    patch(extra, False)
else:
    print('[napcat] 实例 %s 无 %s，跳过' % (iid, os.path.basename(extra)))
PY
}

# ---------- 读 bots.json，每行: id|token|napcatPort|onebotPort|enable|active ----------
# 首行固定是状态行：__OK__ = 解析成功且 bots 数组非空，后面的实例行可信；
#                   __SKIP__ = 本轮数据不可信，调用方必须整轮跳过（不动任何实例）。
#   * 文件不存在    -> __SKIP__：可能只是还没创建 / 正被外部改名，本轮不改动任何实例
#   * JSON 解析失败 -> __SKIP__：可能读到写了一半的文件，本轮直接返回，绝不触发 PURGE
#   * bots 数组为空 -> __SKIP__：空数组合法，但绝不能据此把所有实例清掉
# 只有 __OK__ 这一种情况，才允许把「列表里没有的实例」判为已删除（PURGE）。
# 原来的写法把「解析失败」等同于「配置为空」并回退成只含 bot1 的默认列表，
# 于是其余实例全被判成「已从 bots.json 删除」而被 purge_inst 的 rm -rf 删光登录数据，
# 任何一次读到空 / 半截文件都会触发，属于数据丢失级风险。
read_bots() {
python3 - "$BOTS_JSON" <<'PY'
import json, os, sys
p = sys.argv[1]
if not os.path.exists(p):
    print('__SKIP__|文件不存在')
    sys.exit(0)
try:
    with open(p, encoding='utf-8-sig') as fh:
        d = json.load(fh)
    # 顶层必须是对象：json 合法但不是对象（比如写成 [1,2,3]）时 d.get 会抛异常，
    # 异常逃出去就一行输出都没有，调用方会当成「实例全没了」——那是数据丢失。
    if not isinstance(d, dict):
        print('__SKIP__|顶层不是 JSON 对象')
        sys.exit(0)
    act = str(d.get('active') or '')
    bs = d.get('bots') or []
    if not bs:
        print('__SKIP__|bots 数组为空')
        sys.exit(0)
    # 先把所有实例行拼好，确认没出错再打印，避免打出半截列表
    lines = ['__OK__|']
    for b in bs:
        i = str(b.get('id') or '').strip()
        if not i:
            continue
        tok = str(b.get('token') or '').strip()
        # 历史默认 token 是 qqbot —— v1.1.16 及更早的宿主机 link.sh 就是这么种进 bots.json 的
        # （现在种的是 zhuxin）。这里在启动前就地把它当成 zhuxin：老设备不用改 bots.json
        # 也能把 WebUI token 换过来。这条归一化只服务存量设备，别删。
        if tok in ('', 'qqbot'):
            tok = 'zhuxin'
        lines.append('%s|%s|%d|%d|%d|%s' % (i, tok,
                     int(b.get('napcatPort') or 6099), int(b.get('onebotPort') or 6199),
                     0 if b.get('enable', True) is False else 1, act))
except Exception as e:
    # 任何意外（解析失败、字段类型不对、读到写了一半的文件）都按「本轮不可信」处理
    print('__SKIP__|%s' % e)
    sys.exit(0)
print('\n'.join(lines))
PY
}

# ---------- 取某个实例在 bots.json 里的字段值（多实例各用各的 token） ----------
# 用法：napcat_inst_value <实例id> <键名> <取不到时的默认值>
#   * 按实例 id 在 bots.json 的 bots 数组里找同一个对象，取它的 key —— 不是写死的常量，
#     所以 bot1/bot2 可以各用各的 WebUI token（NAP CAT 的 WebUI 端口同理各用各的 napcatPort）。
#   * stdout 只打「值本身」，保证 $() 拿到的是干净的一行；诊断信息一律走 stderr。
#   * 文件不存在 / JSON 坏 / 实例不存在 / 字段缺失或为空 -> 回退到 <默认值>。
#   * token 的历史默认值 qqbot 在这里归一成 zhuxin：v1.1.16 及更早的宿主机 link.sh 种下的是
#     qqbot，不改那个文件也能把 WebUI token 换过来（存量设备兼容，别删）。
#     ⚠️ 这条规则和 read_bots 里那条必须保持一致。
# 注意：沙盒里 /qqbot-cache 是宿主机 $QQBOT_CACHE 的映射，bots.json 直接可读。
napcat_inst_value() {
  local id="$1" key="$2" def="$3" v
  v=$(python3 - "$BOTS_JSON" "$id" "$key" <<'PY'
import json, sys

path, iid, key = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(path, encoding='utf-8-sig') as fh:
        root = json.load(fh)
    bots = root.get('bots') or []
    if not isinstance(bots, list):
        bots = []
except Exception as e:
    # 诊断走 stderr（调用方只把 stdout 当值），stdout 绝不能出现半行垃圾
    print('[napcat] 读 %s 失败：%s' % (path, e), file=sys.stderr)
    sys.exit(0)
for b in bots:
    if not isinstance(b, dict) or str(b.get('id') or '').strip() != iid:
        continue
    val = b.get(key)
    if val is None or (isinstance(val, str) and not val.strip()):
        break
    if key == 'napcatPort':
        # 端口必须是正整数；0 / 非数字都按「取不到」处理，交给默认值
        try:
            n = int(val)
        except (TypeError, ValueError):
            break
        if n <= 0:
            break
        s = str(n)
    else:
        s = str(val).strip()
        if key == 'token' and s == 'qqbot':
            s = 'zhuxin'
    print(s)
    break
PY
)
  [ -n "$v" ] || v="$def"
  printf '%s' "$v"
}

# ---------- X 显示（每个实例一个） ----------
# 为什么不继续用 xvfb-run：它是脚本，会先跑 mcookie/xauth、再用 -a 从 99 往下试号，
# 每次启动都多几秒，而且在 proot 里 xauth 偶发卡住会让 QQ 一直等 X。
# 参考项目（AstrBot-Bubble-Android-App）是自己起 Xvfb、等 /tmp/.X11-unix/XN 出现后再拉 QQ。
# 它用的是 1x1x8，我们照抄过，结果在真机上把 QQ 的 GPU 进程害死了 —— 色深必须 24 位，
# 详见 XVFB_VARIANTS 上面那段实测记录。屏幕尺寸倒是真的无所谓（640x480 够用）。
#
# 注意 -e 与 -L 的区别（v1.1.22 修）：proot 开了 --link2symlink 之后，Xvfb 建的
# /tmp/.X<n>-lock 是一个符号链接；Xvfb 一退出，目标没了，这个链接就变成**悬空链接**。
# `[ -e ]` 会顺着链接看过去、判定「不存在」，于是 pick_display 把 :40 当成空闲，
# Xvfb 一读锁就 `Can't read lock file /tmp/.X40-lock` 然后 Fatal —— 模拟器上就是这样
# 白白烧掉两档、每轮多花 24 秒才落到 -nolock 那档。所以这里：有实体的算占用，
# 悬空的当成上次崩溃留下的垃圾直接清掉复用。
pick_display() {
  local d lock sock
  for d in $(seq 40 99); do
    lock="/tmp/.X$d-lock"; sock="/tmp/.X11-unix/X$d"
    [ -e "$lock" ] && continue
    [ -e "$sock" ] && continue
    if [ -L "$lock" ] || [ -L "$sock" ]; then
      rm -f "$lock" "$sock" 2>/dev/null
    fi
    printf '%s' "$d"
    return 0
  done
  printf '40'
}

# 挑一个比 $1 大的空闲显示号；找不到就返回空（调用方据此收手）。
next_display_after() {
  local from="$1" d
  for d in $(seq 40 99); do
    [ "$d" -le "$from" ] && continue
    [ -e "/tmp/.X11-unix/X$d" ] && continue
    [ -e "/tmp/.X$d-lock" ] && continue
    printf '%s' "$d"
    return 0
  done
  printf ''
}

# 真机上 Xvfb 秒退的原因不止一种，用同一套参数重试只会得到同一个结果。
# 下面四档一律 24 位色深，见下面那段「色深」说明；后两档换成绕开锁文件：
#   1) 640x480x24 —— 最小可用的 24 位屏，先试它；
#   2) 1280x800x24 —— 屏幕参数不是问题时的备用档，把失败留个完整记录；
#   3) / 4) 同上两档 + -nolock —— 真机上 Xvfb 起显示号第一步会给 /tmp/.X<n>-lock
#      建一个硬链接，proot 里 link(2) 被拒就 'Lock file ... failed: Permission denied'
#      然后 Fatal 退出。-nolock 是 Xvfb 自己的开关（-help 原文 "disable the locking
#      mechanism"），完全不碰锁文件，是从 X 服务器这一层绕过去，与 proot 参数无关。
#
# 关于色深（v1.1.20 实测结论，别再改回去）：
# 以前第一档是 "1x1x8 +extension GLX +render"，照抄参考项目「QQ 不画东西，1 个像素
# 8 位够用」。这个判断是错的 —— 屏小不小无所谓，**色深决定 Chromium 的 GPU 进程能不能
# 初始化**。同一台机器上逐档实测（模拟器 800x600，跑完看 QQ 日志）：
#   1x1x8          → ANGLE 走 GLX 后端，报 "GLX is not present"，
#                    "Initialization of all EGL display types failed"，
#                    "Exiting GPU process due to errors during initialization"（必挂）
#   640x480x16     → "No suitable EGL configs found"（EGL 没有 16 位配置，同样必挂）
#   640x480x24     → 一条 GL 报错都没有，GPU 进程正常，二维码正常输出
# 8 位的 Xvfb 根本没有可用的 GL；16 位 EGL 配不出 surface；只有 24 位能让 mesa 的
# swrast 正常出 EGL config。
#
# GPU 进程一挂，Android 上的 Chromium 没有软件合成兜底：连挂三次后
# "GPU process isn't usable. Goodbye."（gpu_data_manager_impl_private.cc:415）
# 把主进程一起带走，伴随 sandbox/linux/services/proc_util.cc:114 的
# "Check failed: . : Operation not permitted"（那是 GPU 进程退出时的清理动作，
# 是症状不是病因）。真机 Android 16 上必现；x86_64 模拟器上能降级继续跑，
# 所以「模拟器能起、真机起不来」—— 根因就在这一行色深上。
XVFB_VARIANTS="640x480x24|
1280x800x24|
640x480x24|-nolock
1280x800x24|-nolock"

xvfb_dump_log() {
  local id="$1" n="${2:-12}" ln
  [ -s "$RUNDIR/xvfb-$id.log" ] || return 0
  while IFS= read -r ln; do log "  [xvfb] $ln"; done < <(sed -n "1,${n}p" "$RUNDIR/xvfb-$id.log" 2>/dev/null)
  return 0
}

start_xvfb() {
  local id="$1" d="$2" i v screen extra tries next
  mkdir -p /tmp/.X11-unix 2>/dev/null || true
  chmod 1777 /tmp/.X11-unix 2>/dev/null || true
  # 显示号也可能被占：上次守护被强杀（或 proot 整个重启）留下的 Xvfb 还攥着
  # /tmp/.X11-unix/NN，而 pick_display 只看 socket 文件在不在 —— 文件被我们 rm 掉之后
  # 号还在老进程手里，新 Xvfb 一启动就 "Server is already active for display N" 秒退，
  # 于是每一档参数都会失败，看起来就像「Xvfb 起不来」。所以一个号的各档全失败时换下一个号，
  # 最多试 3 个号。
  tries=1
  next=""
  while [ -n "$d" ]; do
  while IFS='|' read -r screen extra; do
    [ -n "$screen" ] || continue
    rm -f "/tmp/.X$d-lock" "/tmp/.X11-unix/X$d" 2>/dev/null || true
    : > "$RUNDIR/xvfb-$id.log" 2>/dev/null || true
    # -ac：关掉访问控制。用 xvfb-run 时它会临时生成 Xauthority，QQ 那边要是没拿到
    # 这份 cookie 就会连不上；自己起的时候干脆不启用鉴权，省掉这一整类问题。
    # -nolisten tcp：只走 unix socket，不占端口，也少一层失败可能。
    # shellcheck disable=SC2086
    Xvfb ":$d" -screen 0 "$screen" -ac -nolisten tcp $extra >> "$RUNDIR/xvfb-$id.log" 2>&1 &
    XPIDS[$id]=$!
    # 真机上实测：Xvfb 起得慢的时候 QQ 会先跑起来，然后 20 多秒后报
    # "Missing X server or $DISPLAY" 退出，supervisor 再拉一遍，看起来就像「一直启动不起来」。
    # 所以每一档最多等 12 秒，并且全程盯着 Xvfb 有没有死。
    for i in $(seq 1 120); do
      if [ -S "/tmp/.X11-unix/X$d" ]; then
        XVFB_SCREEN_USED="$screen ${extra:-（无扩展）}"
        XVFB_DISP="$d"
        return 0
      fi
      kill -0 "${XPIDS[$id]}" 2>/dev/null || break
      sleep 0.1
    done
    # 这一档失败：把进程和它留下的 socket / 锁文件清干净，否则 pick_display 会一直
    # 跳过这个号，重试几次就把 40~99 全占满，后面再也挑不到可用显示号。
    kill -TERM "${XPIDS[$id]}" 2>/dev/null || true
    unset "XPIDS[$id]"
    rm -f "/tmp/.X$d-lock" "/tmp/.X11-unix/X$d" 2>/dev/null || true
    log "实例 $id 的 Xvfb 用「$screen ${extra:-（无扩展）}」起不来（显示号 :$d），Xvfb 自己说："
    xvfb_dump_log "$id" 14
  done <<< "$XVFB_VARIANTS"
  # 各档在这个显示号上都失败 -> 换一个显示号再整套试一遍
  if [ "$tries" -ge 3 ]; then break; fi
  next="$(next_display_after "$d")"
  if [ -z "$next" ]; then
    log "实例 $id 的显示号 40~99 已经全被占满了，没法再换号"
    break
  fi
  log "实例 $id 换显示号 :$d -> :$next 再试一次（第 $((tries + 1)) 个号）"
  d="$next"
  tries=$((tries + 1))
  done
  return 1
}

# ---------- 进程操作 ----------
kill_group() {
  [ -n "${2:-}" ] || return 0
  kill -"$1" -"$2" 2>/dev/null
  kill -"$1" "$2" 2>/dev/null
  return 0
}

# 按 cmdline 兜底清理属于这个实例的残留进程（进程组收不干净时用）
sweep() {
  local sig="$1" qqdir="$2" p c
  [ -n "$qqdir" ] || return 0
  for p in /proc/[0-9]*; do
    # 并发退出时 /proc/<pid> 可能刚好消失；先判断可读再读，否则每轮都会往
    # 日志里刷 "No such file or directory"（用户在日志页会看到这些噪声）。
    [ -r "$p/cmdline" ] || continue
    { c=$(<"$p/cmdline"); } 2>/dev/null
    case "$c" in
      *"$qqdir"*) kill -"$sig" "${p#/proc/}" 2>/dev/null ;;
    esac
  done
  return 0
}

# 兜底清扫：完全不看 PIDS/QDIR 两张表，直接按 cmdline 扫出所有属于本沙盒的 NapCat 进程。
# 为什么需要：start_inst 里 L252 先后台起 xvfb-run、L253 才写 PIDS[$id]，TERM 若正好落在
# 这两条之间，cleanup 里那两张表都查不到这个刚起的实例，它就成了孤儿继续占着 WebUI 端口。
sweep_orphans() {
  local sig="$1" p c
  for p in /proc/[0-9]*; do
    [ -r "$p/cmdline" ] || continue
    { c=$(<"$p/cmdline"); } 2>/dev/null
    case "$c" in
      *xvfb-run*|*Xvfb*|*/opt/QQ/qq*|*/opt/qqbot-inst/*) kill -"$sig" "${p#/proc/}" 2>/dev/null ;;
    esac
  done
  return 0
}

purge_inst() {
  local id="$1" rid
  rid=$(risk_id "$id")
  log "清理实例 $id 的数据（QQ 副本 / 登录数据 / 日志）"
  rm -rf "$INSTBASE/$id" "/root/qqinst/$id" "$LOGDIR/qq-$id.log" "$RUNDIR/$id.pid"
  # 实例都删了，它的风控标记/偏移/命中词也一起清掉，否则原生层会一直显示
  # 「某个已经不存在的实例需要验证」。这里是「用户删实例」才走到的路径，
  # 和降载无关，降载期间也不会主动触发。
  rm -f "$STATE_DIR/risk-$rid.flag" "$STATE_DIR/risk-$rid.off" \
        "$STATE_DIR/risk-$rid.last" "$STATE_DIR/risk-$rid.word" 2>/dev/null
}

stop_inst() {
  local id="$1" pg
  pg="${PIDS[$id]:-}"
  if [ -n "$pg" ] && [ -d "/proc/$pg" ]; then
    log "停止实例 $id（pid $pg）"
    kill_group TERM "$pg"
    sweep TERM "${QDIR[$id]:-}"
    # Xvfb 是自己起的，进程组和 QQ 不是一个（set -m 下每个后台任务自成一族），
    # 只 kill_group 会把它留下占着 /tmp/.X11-unix/XN，下次换号又多一层变量。
    if [ -n "${XPIDS[$id]:-}" ]; then kill -TERM "${XPIDS[$id]}" 2>/dev/null; fi
    STOP[$id]=1
  else
    unset "PIDS[$id]" "QDIR[$id]" "XPIDS[$id]"
    STOP[$id]=0
  fi
  return 0
}

# ---------- 崩溃现场记录器（v1.1.22，抽成函数以便无头模式复用）----------
setup_crashwatch() {
  # ---- 崩溃现场记录器（v1.1.22）----
  # 真机上 NapCat 的 worker 以「退出码 11」反复退出，日志里没有任何 native 层的报错，
  # proot 里又没有 root / debuggerd，拿不到 tombstone。这里用 LD_PRELOAD 注入一个
  # 只负责「死前把现场写下来」的小库：信号号 / si_code / 故障地址 / pc / lr / sp /
  # 栈顶 512B / 完整的 /proc/self/maps。LD_PRELOAD 会被 QQ 的所有子进程继承，
  # 所以崩在 worker 里也能抓到。有了 maps，pc 减去对应段的 base 就知道崩在哪个 .so。
  # 编译失败、源码缺失、没有 g++ 一律静默跳过 —— 它只影响诊断，绝不影响启动。
  CRASH_DIR="/qqbot-cache/crashes"
  mkdir -p "$CRASH_DIR" 2>/dev/null
  # 真机上 worker 每 15 秒崩一轮，不清理会攒出几百个一百多 KB 的文件，
  # 错误报告也会被撑爆。只留最近 4 份（够看出是不是同一个崩点）。
  ls -1t "$CRASH_DIR"/crash-*.log 2>/dev/null | tail -n +5 | while IFS= read -r _old; do
    rm -f "$_old" 2>/dev/null
  done
  _cw_src="/qqbot-opt/sandbox/crashwatch.c"
  _cw_so="/qqbot-cache/crashwatch.so"
  if [ -f "$_cw_src" ]; then
    if [ ! -f "$_cw_so" ] || [ "$_cw_src" -nt "$_cw_so" ]; then
      if command -v g++ >/dev/null 2>&1; then
        if g++ -shared -fPIC -O2 -o "$_cw_so.tmp" "$_cw_src" -ldl 2>"$CRASH_DIR/cw-build.log"; then
          mv -f "$_cw_so.tmp" "$_cw_so" 2>/dev/null
          log "崩溃现场记录器就绪（$_cw_so）"
        else
          rm -f "$_cw_so.tmp"
          log "警告：崩溃现场记录器编译失败，跳过（不影响启动），见 $CRASH_DIR/cw-build.log"
        fi
      fi
    fi
  fi
  [ -f "$_cw_so" ] || _cw_so=""
  # 无头分支用的是同一个库，统一成大写变量名给 start_headless 用
  CW_SO="$_cw_so"
}

# ---------- 无头模式（v1.2.8 / v1.2.9）----------
# NapCat 官方支持「不启 QQ 客户端界面」：napcat.mjs 直接跑在 QQ 自带的 Electron 里
# （Electron 在 --ozone-platform=headless 下可以当纯 node 用）。参考项目 astrbot-pc 在
# Windows 上就是这么跑的（services.py 里 node.exe napcat.mjs，压根不启 QQ.exe）。
#
# 为什么值得切：完整 LinuxQQ 是 Electron 应用，实测 8 个进程占 810~930 MB，还要再起一个
# Xvfb + mesa 软件渲染 —— 那套「1x1x8 必挂 / 16 位配不出 EGL / GPU 崩三次直接 Goodbye」
# 的坑全是它带来的。无头模式只跑 NapCat 本体，实测 4 个进程 285 MB。
#
# worker 有两种进程模型，由 NapCat 自己的 NAPCAT_FORCE_NODE_PROCESS 决定：
#   util（=0）：Electron 的 utilityProcess。不需要 X，最省，x86_64 上稳。
#               但真机 arm64 上必崩：1.2.8 的错误报告（20261022-142708-19079）里 worker
#               每 2 秒 SIGSEGV 一次，pc 落在 napi2native.linux.arm，
#               "Worker进程退出，退出码: 11" —— 正是上游 PR #1984 要绕的那个问题。
#   fork（=1）：child_process.fork(process.execPath)。子进程同样是完整的 Electron，
#               拿不到父进程的 --ozone-platform=headless（父进程参数不会传下去），
#               必须有 DISPLAY，所以得为它起一个 Xvfb（12 MB）。
#
# v1.2.9 的结论（1.2.8 真机崩溃之后重新量的）：
#   · x86_64 模拟器：util 档 4 个进程 285 MB，最省，默认用它；
#   · arm64 真机：util 档必崩；fork 档虽然不崩，但 fork 出来的是第二个完整 Electron，
#     x86_64 上实测 10 个进程 1.05 GB，比「完整客户端 + Xvfb」的 0.9 GB 还高 —— 对真机没收益。
#     所以真机默认回到 1.2.7 那条成熟路线（完整客户端 + Xvfb），无头只给模拟器用。
#   · 想在任何机器上试无头：写 /qqbot-cache/napcat-headless.on（Termux 侧
#     ~/.qqbot/cache/napcat-headless.on），出问题删掉这个文件即可，不用重装。
#
# 手写覆盖：/qqbot-cache/napcat-mode.txt 里写 util / fork / gui
#           （Termux 侧 ~/.qqbot/cache/napcat-mode.txt），改完重启服务生效。
MODE_OK_FILE=/qqbot-cache/napcat-mode.ok
HL_MODE=""
HL_DISP=""
HL_WHY=""

# 无头模式开关（1.2.9 起默认值按架构区分）。
#   强制关：/qqbot-cache/napcat-headless.off（Termux 侧 ~/.qqbot/cache/napcat-headless.off）
#   强制开：/qqbot-cache/napcat-headless.on
# 为什么 arm64 默认关：1.2.8 的真机崩溃报告（20261022-142708-19079，Redmi / Android 16 /
# arm64）里，无头模式走 Electron utilityProcess 时 NapCat 的 native 插件
# napi2native.linux.arm 必然 SIGSEGV（si_code=1，退出码 11），20 秒一轮重启，AstrBot 收不到
# 任何消息；换成 fork 档能绕开那个崩溃，但 fork 出来的是第二个完整 Electron，实测内存比
# 「完整客户端 + Xvfb」还高（x86_64 上 1.05 GB vs 0.9 GB），对真机没有意义。
# 所以在真机上保留 1.2.7 那条成熟路线，无头只用于 x86_64 模拟器；真机想试可以写
# napcat-headless.on 强制打开（出问题时删掉这个文件即可，不用重装）。
# 本机架构 amd64 / arm64。**不能用 uname 判断**：MuMu 这类 x86_64 模拟器会把内核 uname
# 伪装成 aarch64（common.sh 的 qq_detect_arch 里有同样的说明），start-sandbox.sh 已经把真实
# 架构通过环境变量 QQBOT_ARCH 传进来了，优先用它。
napcat_arch() {
  case "${QQBOT_ARCH:-}" in
    amd64|arm64) printf '%s' "$QQBOT_ARCH"; return 0 ;;
  esac
  case "$(uname -m 2>/dev/null)" in
    x86_64|amd64) printf 'amd64' ;;
    *) printf 'arm64' ;;
  esac
}

napcat_headless_on() {
  [ -f /qqbot-cache/napcat-headless.on ] && return 0
  [ -f /qqbot-cache/napcat-headless.off ] && return 1
  [ "$(napcat_arch)" = "arm64" ] && return 1
  return 0
}

# 手动指定 worker 模式；文件不存在 / 内容不认识时输出空（= 自动）
napcat_mode_override() {
  local f=/qqbot-cache/napcat-mode.txt v=""
  [ -s "$f" ] || return 0
  v=$(head -n 1 "$f" 2>/dev/null | tr -d '\r' | tr 'A-Z' 'a-z')
  v="${v%% *}"
  case "$v" in util|fork|gui) printf '%s' "$v" ;; esac
  return 0
}

# 上次跑通的那一档：自动模式下优先用它，避免每次重启都把注定失败的档重试一遍
napcat_mode_saved() {
  local v=""
  [ -s "$MODE_OK_FILE" ] || return 0
  v=$(head -n 1 "$MODE_OK_FILE" 2>/dev/null | tr -d '\r ')
  case "$v" in util|fork) printf '%s' "$v" ;; esac
  return 0
}

napcat_mode_save() {
  case "${1:-}" in
    util|fork) printf '%s\n' "$1" > "$MODE_OK_FILE" 2>/dev/null || true ;;
  esac
  return 0
}

# 把 QQ 的 NapCat 注入补齐：安装包里没有 resources/app/loadNapCat.js、main 仍指向
# application.asar 时，QQ 起来也不会加载 NapCat（WebUI 端口永远不通）。幂等；失败只告警。
ensure_napcat_injection() {
  local qdir="${1:-}" id="${2:-}" app main="" stub=""
  [ -n "$qdir" ] || return 1
  app="$qdir/resources/app"
  if [ ! -d "$app" ]; then
    log "警告：找不到 $app，跳过 NapCat 注入检查"
    return 0
  fi
  if [ ! -s "$app/app_launcher/napcat/napcat.mjs" ]; then
    log "警告：NapCat 本体不在 $app/app_launcher/napcat，跳过注入检查"
    return 0
  fi
  stub="$app/loadNapCat.js"
  if [ ! -s "$stub" ]; then
    cat > "$stub" <<'JSEOF'
const path = require('node:path');
const { pathToFileURL } = require('node:url');
import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);
JSEOF
    if [ -s "$stub" ]; then
      log "实例 $id 补写了 loadNapCat.js（内置镜像里的 QQ 没有注入过）"
    else
      log "警告：实例 $id 写 $stub 失败，NapCat 可能不会被加载"
      return 0
    fi
  fi
  # 入口字段只可能出现在顶层（腾讯这份 package.json 里 "main" 全局仅一处），
  # 所以先用 sed 原地替换 —— 真机上 python3 不一定在 PATH 里（venv 自检就报过
  # .venv/bin/python 不可执行），把命中率最高的做法放在最前面，不起进程也更稳。
  pj="$app/package.json"
  if [ ! -s "$pj" ]; then
    log "警告：实例 $id 的 $pj 不存在，NapCat 可能不会被加载"
    return 0
  fi
  if grep -q '"main"[[:space:]]*:[[:space:]]*"\./loadNapCat\.js"' "$pj" 2>/dev/null; then
    return 0          # 已经是注入后的状态，别每次启动都动文件
  fi
  main=$(sed -n 's/^[[:space:]]*"main"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$pj" 2>/dev/null | head -n 1)
  sed -i.bak 's#^\([[:space:]]*\)"main"[[:space:]]*:[[:space:]]*"[^"]*"#\1"main": "./loadNapCat.js"#' "$pj" 2>/dev/null
  rm -f "$pj.bak"
  if ! grep -q '"main"[[:space:]]*:[[:space:]]*"\./loadNapCat\.js"' "$pj" 2>/dev/null \
     && command -v python3 >/dev/null 2>&1; then
    # sed 没命中（缩进/写法变了）才走 python3：它能保证 JSON 结构不被改坏
    python3 - "$pj" <<'PYEOF' 2>/dev/null || true
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding='utf-8-sig'))
d['main'] = './loadNapCat.js'
json.dump(d, open(p, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
PYEOF
  fi
  if grep -q '"main"[[:space:]]*:[[:space:]]*"\./loadNapCat\.js"' "$pj" 2>/dev/null; then
    log "实例 $id 已把 QQ 入口改成 loadNapCat.js（原来是 ${main:-空}）"
  else
    log "警告：实例 $id 改写 $pj 失败（原 main=${main:-空}），NapCat 可能不会被加载"
  fi
  return 0
}

# 端口是否有人在听（/proc/net/tcp 的本地地址是 16 进制、大写）
port_listen() {
  local port="${1:-}" hex
  case "$port" in ''|*[!0-9]*) return 1 ;; esac
  hex=$(printf '%04X' "$port" 2>/dev/null) || return 1
  grep -q ":$hex " /proc/net/tcp 2>/dev/null && return 0
  grep -q ":$hex " /proc/net/tcp6 2>/dev/null && return 0
  return 1
}

# 等一个刚拉起的无头 NapCat 真正就绪。0=好；失败时原因写在 $HL_WHY。
# 判据不能只看日志里的 "NapCat.Core Version"：真机实测那一行打出来约 2 秒后 worker 才
# SIGSEGV，按它会误判成功 —— 1.2.8 就是这样每 15 秒重启一轮、用户完全连不上。
# 这里要求三件事同时成立：WebUI 端口真的在听、进程还活着、之后 6 秒内日志里没有 worker 退出。
hl_wait_ready() {
  local pid="$1" logf="$2" off="$3" port="$4" i=0 chunk
  while [ "$i" -lt 160 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then HL_WHY="died"; return 1; fi
    chunk=$(tail -c +$((off + 1)) "$logf" 2>/dev/null | tr -d "\000")
    case "$chunk" in
      *"Worker进程退出"* | *"主进程退出"* | *"GPU process isn't usable"* | \
      *"The platform failed to initialize"* | *"incorrect permissions on /dev/shm"*)
        HL_WHY="crash"; return 1 ;;
    esac
    if port_listen "$port"; then
      sleep 6 &
      wait $! 2>/dev/null
      if ! kill -0 "$pid" 2>/dev/null; then HL_WHY="died-after-ready"; return 1; fi
      chunk=$(tail -c +$((off + 1)) "$logf" 2>/dev/null | tr -d "\000")
      case "$chunk" in
        *"Worker进程退出"* | *"主进程退出"*) HL_WHY="crash-after-ready"; return 1 ;;
      esac
      return 0
    fi
    sleep 0.5 &
    wait $! 2>/dev/null
    i=$((i + 1))
  done
  HL_WHY="timeout"
  return 1
}

# 起一档无头 NapCat：$8=mode（util|fork），$9=Chromium 附加参数。
# 成功时 HL_PID 有效并返回 0；失败返回 1。
hl_start_one() {
  local id="$1" qqdir="$2" nap="$3" insthome="$4" logf="$5" np="$6" tok="$7" mode="$8" extra="${9:-}"
  local t0 pid disp="" envs=() e
  # 日志目录在全新部署的第一次启动时还不存在。重定向 `<` 失败是 shell 自己报的，
  # 后面的 2>/dev/null 压不住，会在 supervisor.log 里留一条红色噪音，所以先建目录。
  mkdir -p "$(dirname "$logf")" 2>/dev/null || true
  t0=0
  [ -f "$logf" ] && t0=$(wc -c < "$logf" 2>/dev/null | tr -d " \n")
  [ -n "$t0" ] || t0=0

  # worker 进程模型：util 强制 0，fork 强制 1；用户在 qq-env.txt 里写过的就尊重用户
  for e in "${QE[@]}"; do
    case "$e" in
      NAPCAT_FORCE_NODE_PROCESS=*)
        if [ -n "${_qe_mode:-}" ]; then envs=( "${envs[@]}" "$e" )
        elif [ "$mode" = "fork" ]; then envs=( "${envs[@]}" "NAPCAT_FORCE_NODE_PROCESS=1" )
        else envs=( "${envs[@]}" "NAPCAT_FORCE_NODE_PROCESS=0" ); fi ;;
      *) envs=( "${envs[@]}" "$e" ) ;;
    esac
  done

  if [ "$mode" = "fork" ]; then
    # fork 出来的子进程是完整 Electron，必须有 DISPLAY。给它一个 Xvfb（约 12 MB）。
    disp=$(pick_display)
    XVFB_SCREEN_USED=""
    if ! start_xvfb "$id" "$disp"; then
      HL_WHY="xvfb"
      log "实例 $id 无头 fork 模式要给子进程一个 Xvfb，但 Xvfb 起不来（详见上面的 xvfb 日志）"
      return 1
    fi
    disp="${XVFB_DISP:-$disp}"
    log "实例 $id 无头 fork 模式：Xvfb 已就绪（DISPLAY=:$disp），worker 走纯 Node fork"
  fi

  log "实例 $id 尝试无头模式[$mode]（附加参数: ${extra:-无}）"
  # 必须 cd 到 napcat 目录再起：napcat.mjs 用相对路径找 ../major.node 与 config/
  if [ "$mode" = "fork" ]; then
    (
      cd "$nap" || exit 1
      exec env "${envs[@]}" DISPLAY=":$disp" LD_PRELOAD="$CW_SO" \
        QQBOT_CRASH_DIR="$CRASH_DIR" QQBOT_CRASH_TAG="$id" \
        HOME="$insthome" \
        NAPCAT_WEBUI_SECRET_KEY="$tok" \
        NAPCAT_WEBUI_JWT_SECRET_KEY="${tok}_jwt" \
        NAPCAT_WEBUI_PREFERRED_PORT="$np" \
        "$qqdir/qq" --no-sandbox --ozone-platform=headless $EXTRA $extra napcat.mjs
    ) >> "$logf" 2>&1 &
  else
    (
      cd "$nap" || exit 1
      exec env "${envs[@]}" LD_PRELOAD="$CW_SO" \
        QQBOT_CRASH_DIR="$CRASH_DIR" QQBOT_CRASH_TAG="$id" \
        HOME="$insthome" \
        NAPCAT_WEBUI_SECRET_KEY="$tok" \
        NAPCAT_WEBUI_JWT_SECRET_KEY="${tok}_jwt" \
        NAPCAT_WEBUI_PREFERRED_PORT="$np" \
        "$qqdir/qq" --no-sandbox --ozone-platform=headless $EXTRA $extra napcat.mjs
    ) >> "$logf" 2>&1 &
  fi
  pid=$!

  if hl_wait_ready "$pid" "$logf" "$t0" "$np"; then
    HL_PID=$pid
    HL_MODE="$mode"
    HL_DISP="$disp"
    return 0
  fi
  log "实例 $id 无头模式[$mode]这一档没起来（$HL_WHY，参数: ${extra:-无}）"
  kill -KILL "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  # 为这一档起的 Xvfb 收掉：外层要么换档（会重新 pick_display），要么退回完整客户端
  if [ -n "$disp" ] && [ -n "${XPIDS[$id]:-}" ]; then
    kill -TERM "${XPIDS[$id]}" 2>/dev/null
    unset "XPIDS[$id]"
    rm -f "/tmp/.X$disp-lock" "/tmp/.X11-unix/X$disp" 2>/dev/null || true
  fi
  return 1
}

# 起一个无头 NapCat；成功时把 pid 交给全局 HL_PID 并返回 0，所有档都失败返回 1。
# 调用前 QE / CW_SO / CRASH_DIR 必须已经准备好（QE 在 start_inst 里已按默认补过
# NAPCAT_FORCE_NODE_PROCESS=1）。
start_headless() {
  local id="$1" qqdir="$2" nap="$3" insthome="$4" logf="$5" np="$6" tok="$7"
  local arch ov cached cand cands=() rest=() c off tries

  # Chromium 多进程要用的共享内存。start-sandbox.sh 已经把宿主侧的
  # $QQBOT_DEVDIR/shm（chmod 1777）绑到了 /dev/shm；这里再兜一次权限。
  mkdir -p /dev/shm 2>/dev/null
  chmod 1777 /dev/shm 2>/dev/null
  # 日志目录可能还不存在（全新部署第一次启动时 /qqbot-cache/logs 还没建），
  # 否则下面 hl_start_one 里 `wc -c < $logf` 会先报一句 No such file or directory。
  mkdir -p "$(dirname "$logf")" 2>/dev/null || true

  arch=$(napcat_arch)
  ov=$(napcat_mode_override)
  if [ "$ov" = "gui" ]; then
    log "实例 $id 由 napcat-mode.txt 指定为完整客户端模式（不用无头）"
    return 1
  fi

  if [ -n "$ov" ]; then
    cands=( "$ov" )
  elif [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
    # 真机 arm64：utilityProcess 里跑 QQ 的 native 插件必崩（napi2native SIGSEGV，退出码 11）
    cands=( fork util )
  else
    cands=( util fork )
  fi

  # 上次成功的那一档提到最前面（只有自动模式才参考）
  if [ -z "$ov" ]; then
    cached=$(napcat_mode_saved)
    if [ -n "$cached" ]; then
      rest=()
      for c in "${cands[@]}"; do [ "$c" = "$cached" ] || rest=( "${rest[@]}" "$c" ); done
      cands=( "$cached" "${rest[@]}" )
      log "实例 $id 上次跑通的是无头模式[$cached]，先试它"
    fi
  fi

  for cand in "${cands[@]}"; do
    # 第一档就带 --disable-dev-shm-usage：实测 proot 里 Chromium 建共享内存时对 /dev/shm
    # 会报 EACCES（bash 自己 touch 却能写，是 Chromium 的 create+link 路径问题），
    # 它一失败就是 FATAL: incorrect permissions on /dev/shm + GPU 连崩三次 Goodbye。
    # 用 /tmp 就没这个坑（/tmp 在 rootfs 里，容量跟 /data 一样）。
    tries=( "--disable-dev-shm-usage" "" )
    for off in "${tries[@]}"; do
      if hl_start_one "$id" "$qqdir" "$nap" "$insthome" "$logf" "$np" "$tok" "$cand" "$off"; then
        [ -z "$ov" ] && napcat_mode_save "$cand"
        return 0
      fi
      # 环境本身有问题（Xvfb 起不来 / 主进程直接死）就不用再换参数试了
      case "$HL_WHY" in xvfb|died) break ;; esac
    done
  done
  return 1
}

start_inst() {
  local e="$1" id rest tok np op uin QQDIR INSTHOME LOGF NAP CONF SRC EXTRA DISP
  id="${e%%|*}"; rest="${e#*|}"
  tok="${rest%%|*}"; rest="${rest#*|}"
  np="${rest%%|*}"; op="${rest#*|}"

  if [ -x "$INSTBASE/$id/QQ/qq" ]; then
    QQDIR="$INSTBASE/$id/QQ"
  elif [ "$id" = "$ACTIVE" ] && [ -x "$QQBASE/qq" ]; then
    QQDIR="$QQBASE"
  else
    QQDIR="$INSTBASE/$id/QQ"
    log "为实例 $id 准备 QQ 目录（硬链接复制，几乎不占额外空间）"
    mkdir -p "$INSTBASE/$id"
    rm -rf "$QQDIR"
    cp -al "$QQBASE" "$QQDIR" 2>/dev/null || cp -a "$QQBASE" "$QQDIR"
  fi

  # beta.5 P1-3：记录本次启动时间；Java 用它过滤 cp -al 继承来的旧 per-uin 配置。
  # 老 Java 不读这个文件；写失败只忽略，绝不影响启动。
  touch "$QQDIR/.qqbot-start" 2>/dev/null || true

  INSTHOME="/root/qqinst/$id"
  LOGF="$LOGDIR/qq-$id.log"
  NAP="$QQDIR/resources/app/app_launcher/napcat"
  CONF="$NAP/config"
  SRC="$QQBASE/resources/app/app_launcher/napcat/config"

  if [ "$CONF" != "$SRC" ]; then
    # 硬链接复制让实例共用同一份文件，而 NapCat 会就地改写 config / cache / logs。
    # 曾实测两个实例的 cache/qrcode.png 是同一个 inode（nlink=2）：两个 QQ 号显示同一张
    # 登录二维码、webui token 互相覆盖。这几个可变目录必须换成实例自己的真实副本，
    # 其余文件继续硬链接以节省空间。
    if [ ! -f "$QQDIR/.qqbot-sep" ]; then
      python3 - "$NAP" <<'PY'
import os, sys, shutil
root = sys.argv[1]
n = 0
for sub in ('config', 'cache', 'logs', 'plugins'):
    d = os.path.join(root, sub)
    if not os.path.isdir(d):
        continue
    for dp, dns, fns in os.walk(d):
        for fn in fns:
            p = os.path.join(dp, fn)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if os.path.islink(p) or not os.path.isfile(p) or st.st_nlink <= 1:
                continue
            tmp = p + '.qqbotnew'
            shutil.copy2(p, tmp)
            os.replace(tmp, p)
            n += 1
print('[napcat] 分离实例私有文件 %d 个（config / cache / logs）' % n)
PY
      : > "$QQDIR/.qqbot-sep"
    fi
    if [ -d "$SRC" ] && [ ! -e "$CONF/napcat.json" ]; then
      mkdir -p "$CONF"
    fi
    if [ -d "$SRC" ] && [ "$CONF/napcat.json" -ef "$SRC/napcat.json" ]; then
      log "为实例 $id 分离 NapCat 配置目录"
      rm -rf "$CONF"
      mkdir -p "$CONF"
      # 必须用 cp -r：cp -a 带 --preserve=links，源文件 nlink>1 时会再次建立硬链接，等于没复制
      cp -r "$SRC/." "$CONF/" 2>/dev/null
    fi
  fi

  # onebot11.json 每次启动都删掉重建（内容完全由本实例的端口决定）；
  # webui.json 不再删 —— v1.1.31 起由下面的 write_webui 自己写，好带上局域网开关。
  rm -f "$CONF/onebot11.json"
  mkdir -p "$INSTHOME"
  EXTRA="--user-data-dir=$INSTHOME/.config/QQ"
  # QQ 的「能活下来」的参数阶梯，见下面启动段的长注释。
  # 第一档是「什么额外参数都不加」（1.1.18 及更早的行为）—— 只要 Xvfb 是 24 位色深，
  # 这一档就该成功，什么都不用加。下面几档是万一还有别的机型卡在 GPU 上时的退路：
  #   --disable-gpu 千万别用！它只是不让 Chromium *用* 硬件加速，GPU 进程照样起、
  #   EGL 照样初始化；而一旦带了它，GPU 进程崩掉时 Chromium 判定「没有软件合成可退」，
  #   直接 "GPU process isn't usable. Goodbye." 把主进程带走 —— 1.1.19 就是栽在这上面。
  QQ_TRIES=( "-"
             "--disable-gpu-sandbox"
             "--use-gl=angle --use-angle=swiftshader"
             "--in-process-gpu" )

  # 参数覆写文件：手机上现成的「不重装也能换参数」开关。
  # 注意路径必须落在沙盒能看见的 /qqbot-cache 下 —— run-napcat.sh 是跑在 proot 里的，
  # Termux 侧的 $QQBOT_HOME 在这里根本不存在（1.1.21 就是因为写成了 $QQBOT_HOME，
  # 展开成 "/qq-args.txt"，这个开关一直没生效过）。
  # /qqbot-cache 对应 Termux 侧 ~/.qqbot/cache。
  # 用法（应用里 更多 → 终端）：
  #   echo '--disable-dev-shm-usage' > /data/data/com.termux/files/home/.qqbot/cache/qq-args.txt
  #   bash /data/data/com.termux/files/usr/opt/qqbot/stop-sandbox.sh
  #   nohup setsid bash /data/data/com.termux/files/usr/opt/qqbot/start-sandbox.sh >/dev/null 2>&1 &
  # 内容非空时只试这一档，不再走上面的阶梯 —— 方便一台机器一种参数地试到能跑为止。
  # 想回到自动阶梯，把文件删掉或清空即可。
  _ovr="/qqbot-cache/qq-args.txt"
  if [ -s "$_ovr" ]; then
    _ov="$(tr -d '\r' < "$_ovr" | tr '\n' ' ')"
    case "$_ov" in
      "-") _ov="" ;;
    esac
    QQ_TRIES=( "$_ov" )
    log "实例 $id 使用 qq-args.txt 里指定的参数: ${_ov:-无}"
  fi

  # ---- 传给 QQ 的环境变量（v1.1.25）----
  # 默认值只有一个：NAPCAT_FORCE_NODE_PROCESS=1，这是 worker SIGSEGV（退出码 11）的
  # 根治开关。真机崩溃现场：worker 里崩在
  #   napi2native.linux.arm64.node(gum_elf_module_load+0x880)
  #   signal=11 SIGSEGV si_code=1(SEGV_MAPERR) si_addr=0x550bd1f3f8（5 份崩溃记录一模一样）
  # 也就是 NapCat 注入的 frida-gum 在 Electron 的 worker 进程里拿到了一个坏指针。
  # 上游 NapCat 的 PR #1984 正是为这个加的开关：「fix: 修复 WebUI 白屏与 arm64/proot 下
  # Worker SIGSEGV」，napcat.mjs 里 NAPCAT_FORCE_NODE_PROCESS !== "1" 时才会走
  # e.utilityProcess.fork（Chromium 进程模型），设成 1 就走 child_process.fork 的纯 Node
  # 子进程，绕开 Chromium 那套进程模型，崩溃随之消失。
  # 想回到老行为：把 NAPCAT_FORCE_NODE_PROCESS=0 写进 qq-env.txt（覆写文件优先）。
  #
  # 也留了 qq-env.txt 覆写入口（一行一个 KEY=VALUE），方便现场换参数。
  QE=()
  # 覆写文件：一行一个 KEY=VALUE，追加进 QQ 的启动环境。
  #   echo 'NAPCAT_DISABLE_MULTI_PROCESS=1' > /data/data/com.termux/files/home/.qqbot/cache/qq-env.txt
  #   bash /data/data/com.termux/files/usr/opt/qqbot/stop-sandbox.sh
  #   nohup setsid bash /data/data/com.termux/files/usr/opt/qqbot/start-sandbox.sh >/dev/null 2>&1 &
  # 删掉文件即恢复默认。
  _envf="/qqbot-cache/qq-env.txt"
  if [ -s "$_envf" ]; then
    _envn=0
    while IFS= read -r _ln || [ -n "$_ln" ]; do
      _ln="$(printf '%s' "$_ln" | tr -d '\r')"
      case "$_ln" in ''|'#'*) continue ;; esac
      case "$_ln" in *=*) QE=( "${QE[@]}" "$_ln" ); _envn=$((_envn + 1)) ;; esac
    done < "$_envf"
    [ "$_envn" -gt 0 ] && log "实例 $id 追加 qq-env.txt 里的 $_envn 个环境变量"
  fi

  # 覆写文件里没写 NAPCAT_FORCE_NODE_PROCESS 时，补上默认值 1（理由见上面那段注释）。
  # 用环境变量而不是改 napcat.mjs：升级 / 重装 NapCat 时不会被覆盖，出问题也好回退。
  _qe_mode=""
  for _e in "${QE[@]}"; do
    case "$_e" in NAPCAT_FORCE_NODE_PROCESS=*) _qe_mode="$_e" ;; esac
  done
  if [ -z "$_qe_mode" ]; then
    QE=( "${QE[@]}" "NAPCAT_FORCE_NODE_PROCESS=1" )
    log "实例 $id 的 NapCat worker 走 fork 模式（NAPCAT_FORCE_NODE_PROCESS=1，绕开 Electron UtilityProcess）"
  else
    log "实例 $id 的 NapCat worker 模式由 qq-env.txt 指定：$_qe_mode"
  fi

  write_onebot "$CONF" "$op"

  # 局域网访问开关：宿主设置页会建/删 /qqbot-state/lan.on（沙盒里就是 state 目录）。
  _lan=0
  [ -f /qqbot-state/lan.on ] && _lan=1

  # token 与 WebUI 端口都以 bots.json 里「本实例」的字段为准（多实例各用各的 token，
  # 不是写死的常量）。解析失败 / 字段缺失时退回上面 read_bots 解析好的值，
  # 那个兜底值本身就是 zhuxin / 6099（或 bots.json 里的真实值），所以不会退化。
  tok=$(napcat_inst_value "$id" token "$tok")
  np=$(napcat_inst_value "$id" napcatPort "$np")

  # 容器起来时 NapCat 的 host 还没定，这里按开关写死，避免它默认只监听 127.0.0.1
  # 而用户以为「开了开关就能连」。
  write_webui "$CONF" "$np" "$tok" "$_lan"

  # 本实例的 QQ 号（uin）：config/napcat_<uin>.json 存在时 NapCat 会优先用它，
  # 所以 write_napcat 需要它来一并合并。拿不到就传空，那边会跳过这一步并打一行日志。
  uin=$(napcat_inst_value "$id" qq "")

  # 反检测：每次启动前把本实例的 napcat.json 补成「bypass 六键全 true + autoTimeSync」。
  # 放在启动 QQ 之前，所以老设备不用重装 NapCat，实例重新拉起就生效。
  if write_napcat "$CONF" "$id" "$uin"; then
    log "实例 $id 反检测 bypass 已全部开启（bypass 6 键 + autoTimeSync）"
  else
    log "警告：实例 $id 反检测 bypass 补丁失败，已跳过（不影响启动）"
  fi

  log "启动实例 $id（WebUI 端口 $np，WebUI token $tok，日志 $LOGF）"
  # ---- 无头模式优先（v1.2.8）：不启 QQ 客户端，省一半以上内存、也不用 Xvfb ----
  if napcat_headless_on; then
    setup_crashwatch
    if start_headless "$id" "$QQDIR" "$NAP" "$INSTHOME" "$LOGF" "$np" "$tok"; then
      PIDS[$id]=$HL_PID
      QDIR[$id]="$QQDIR"
      STOP[$id]=0
      echo "$HL_PID" > "$RUNDIR/$id.pid"
      log "实例 $id 的 NapCat 已用无头模式跑起来（worker=$HL_MODE${HL_DISP:+，Xvfb DISPLAY=:$HL_DISP}）"
      sleep 3 &
      wait $! 2>/dev/null
      return 0
    fi
    log "实例 $id 无头模式没起来，退回完整 QQ 客户端（Xvfb）模式"
  elif [ "$(napcat_arch)" = "arm64" ]; then
    log "实例 $id 走完整客户端模式：arm64 真机的无头模式会让 NapCat native 插件 SIGSEGV（1.2.8 崩溃报告已确认）；想试验可建 $HOME/.qqbot/cache/napcat-headless.on"
  fi
  DISP="$(pick_display)"
  XVFB_SCREEN_USED=""
  if ! start_xvfb "$id" "$DISP"; then
    # start_xvfb 内部已经把每一档参数逐个试过、并各自打印了 Xvfb 自己的报错，
    # 这里只补两条环境信息，然后**不启动 QQ** —— 否则 QQ 起来只会报
    # "Missing X server or $DISPLAY" 自杀，supervisor 再拉，看起来像一直启动不起来。
    log "实例 $id 的 Xvfb 所有参数组合都没起来，本轮先不启动 QQ（5 秒后自动重试）"
    dump_procs
    # 光测「能不能写」是不够的：真机上 /tmp 完全可写，但 link(2) 直接 EPERM，
    # 而 Xvfb 起显示号第一步就是 link() 一个 /tmp/.X<n>-lock，失败即 Fatal 退出。
    # 这两项必须分开报，否则症状会伪装成「磁盘满/权限不对」。
    log "  [xvfb] /tmp 可写: $({ : > /tmp/.qqbot-wtest && echo yes && rm -f /tmp/.qqbot-wtest; } 2>/dev/null || echo no)"
    log "  [xvfb] link(2) 可用: $({ : > /tmp/.qqbot-ltest && ln /tmp/.qqbot-ltest /tmp/.qqbot-ltest2 2>/dev/null && echo yes; rm -f /tmp/.qqbot-ltest /tmp/.qqbot-ltest2; } 2>/dev/null || echo no)"
    log "  [xvfb] 若是 link(2) 不可用：说明启动器没带 --link2symlink，重启一次服务即可自动纠正"
    return 1
  fi
  # start_xvfb 可能换过显示号（原号被上次残留的 Xvfb 占着），以它实际用的为准
  DISP="${XVFB_DISP:-$DISP}"
  log "实例 $id 的 X 已就绪（DISPLAY=:$DISP，参数 $XVFB_SCREEN_USED）"
  # 参考项目的 launcher 里有一句 trap "" SIGPIPE：QQ 往已经关掉的日志管道里写时
  # 会被 SIGPIPE 直接打死，症状是「刚起来就没了」且日志里什么都没有。
  trap '' PIPE
  #
  # QQ 是 Electron 壳，Chromium 在 Android 上最脆的一环是 GPU 子进程：它初始化
  # EGL/GL 一旦失败就退出，连挂三次后 gpu_data_manager 打出
  # "GPU process isn't usable. Goodbye."（gpu_data_manager_impl_private.cc:415）
  # 把主进程一起带走，shell 里看到的就是 "Trace/breakpoint trap"（SIGTRAP）。
  # 同一现场还常伴随 sandbox/linux/services/proc_util.cc:114 的
  # "Check failed: . : Operation not permitted" —— 那是 GPU 进程退出时的清理动作，
  # 是症状不是病因，别被它带偏。
  #
  # 病因在 Xvfb 的色深，不在 QQ 的参数（v1.1.20 实测，见 XVFB_VARIANTS 上面那段）：
  # 8 位没有可用 GL、16 位 EGL 配不出 surface，只有 24 位能让 GPU 进程正常初始化。
  # 所以第一档就该是「什么额外参数都不加」，后面几档只在别的机型还卡 GPU 时兜底。
  QQ_OK=0
  QQ_USED="-"

  # 内置镜像里的 QQ 是「未注入」状态：安装包没带 resources/app/loadNapCat.js，
  # package.json 的 main 还指向 application.asar。而解压内置镜像时 50-napcat 会被直接
  # 标记为已完成，注入那一步永远不会再跑 —— 完整客户端模式下 NapCat 就没被加载，
  # WebUI（6099）永远探测不通，supervisor 只会反复「NapCat 端口探测不通」。
  # 每次拉起 QQ 前把注入补齐即可（幂等；失败只告警，绝不拦启动）。
  ensure_napcat_injection "$QQDIR" "$id"

  setup_crashwatch


  for qf in "${QQ_TRIES[@]}"; do
    [ "$qf" = "-" ] && qf=""
    log "实例 $id 启动 QQ（附加参数: ${qf:-无}）"
    # 日志是追加写的，只扫「这次启动之后新增的那一段」，否则上一档留下的
    # "Exiting GPU process..." 会让下一档一开局就被判失败。
    qq_off=$(wc -c < "$LOGF" 2>/dev/null | tr -d ' \n')
    [ -n "$qq_off" ] || qq_off=0
    # shellcheck disable=SC2086
    env "${QE[@]}" LD_PRELOAD="$_cw_so" \
    QQBOT_CRASH_DIR="$CRASH_DIR" QQBOT_CRASH_TAG="$id" \
    HOME="$INSTHOME" DISPLAY=":$DISP" \
    NAPCAT_WEBUI_SECRET_KEY="$tok" \
    NAPCAT_WEBUI_JWT_SECRET_KEY="${tok}_jwt" \
    NAPCAT_WEBUI_PREFERRED_PORT="$np" \
    "$QQDIR/qq" --no-sandbox "$EXTRA" $qf >> "$LOGF" 2>&1 &
    qq_pid=$!
    qq_n=0
    qq_verdict=""
    while [ "$qq_n" -lt 60 ]; do
      if ! kill -0 "$qq_pid" 2>/dev/null; then qq_verdict="died"; break; fi
      # QQ 会往自己日志里写二进制，先 tr 掉 NUL，否则命令替换会断在半截上。
      qq_chunk=$(tail -c +$((qq_off + 1)) "$LOGF" 2>/dev/null | tr -d '\000')
      case "$qq_chunk" in
        *"GPU process isn't usable"* | *"Exiting GPU process due to errors during initialization"*)
          qq_verdict="gpu"
          break
          ;;
      esac
      # NapCat 打出内核版本 / 二维码地址，说明 Chromium 主体已经活过初始化。
      # 不能拿 "resourcesPath:" 当判据：它在 GPU 进程崩之前就写出来了，
      # 真机上它出现 0.7 秒之后才 "Goodbye"，拿它判成功会永远卡在第一档。
      case "$qq_chunk" in
        *"NapCat.Core Version"* | *"二维码解码URL"*)
          qq_verdict="ok"
          break
          ;;
      esac
      qq_n=$((qq_n + 1))
      sleep 0.5 &
      wait $! 2>/dev/null
    done
    # 撑满 30 秒：既没报 GPU 错、也没走到 NapCat，只要进程还活着就算成功（慢机器）
    if [ -z "$qq_verdict" ]; then
      if kill -0 "$qq_pid" 2>/dev/null; then qq_verdict="ok-slow"; else qq_verdict="died"; fi
    fi
    case "$qq_verdict" in
      ok | ok-slow)
        QQ_OK=1
        QQ_USED="${qf:--}"
        break
        ;;
      gpu)
        log "实例 $id 这一档的 GPU 进程没能初始化（参数: ${qf:-无}），换下一档"
        ;;
      *)
        log "实例 $id 这一档没活下来（pid $qq_pid 已退出，参数: ${qf:-无}），换下一档"
        ;;
    esac
    kill -KILL "$qq_pid" 2>/dev/null
    wait "$qq_pid" 2>/dev/null
  done

  if [ "$QQ_OK" != 1 ]; then
    # 一条路都没走通：不能登记 pid（否则主循环以为它还活着，从此只管重启不重试参数），
    # 顺手把自己刚起的 Xvfb 收掉，不然每 5 秒重来一次会把 40~99 的显示号逐个占满。
    if [ -n "${XPIDS[$id]:-}" ]; then kill -TERM "${XPIDS[$id]}" 2>/dev/null; fi
    unset "XPIDS[$id]"
    log "实例 $id 的 QQ 各档参数全部没能活下来，本轮放弃（5 秒后自动重试）"
    # 按日志里出现的症状指名病根，别让人自己猜。
    if tail -n 80 "$LOGF" 2>/dev/null | grep -q "Worker进程退出"; then
      log "  病根：NapCat 的 Worker 子进程崩了（'Worker进程退出，退出码: 11' 就是 SIGSEGV）。"
      log "  这一层跟 X 和 GPU 都无关，是 native 代码在 proot 里跑崩了。"
      log "  换参数试的办法：把参数写进 <Termux>/home/.qqbot/cache/qq-args.txt，再重启服务，"
      log "  例如 --disable-dev-shm-usage 或 --no-zygote，试到能跑为止（删掉文件恢复自动阶梯）。"
    elif tail -n 80 "$LOGF" 2>/dev/null | grep -q "GPU process isn't usable"; then
      log "  病根：GPU 进程起不来。若色深已是 24 位，就是 /proc 拒了 GPU 子进程；"
      log "  若日志里有 'GLX is not present'，则是色深不是 24 位（当前 [$XVFB_SCREEN_USED]）。"
    else
      log "  若日志里有 'GLX is not present' 或 'Initialization of all EGL display types failed'："
      log "  说明 Xvfb 的色深不是 24 位（8 位没有可用 GL、16 位 EGL 配不出 surface），"
      log "  当前 X 参数是 [$XVFB_SCREEN_USED]，正常应当是 640x480x24 这类。"
      log "  若日志里是 'GPU process isn't usable' 且色深确实是 24 位，才是 GPU 子进程被 /proc 拒了。"
    fi
    return 1
  fi
  PIDS[$id]=$qq_pid
  QDIR[$id]="$QQDIR"
  STOP[$id]=0
  echo "$qq_pid" > "$RUNDIR/$id.pid"
  log "实例 $id 的 QQ 已跑起来（参数: ${QQ_USED}）"
  sleep 3 &
  wait $! 2>/dev/null
  return 0
}

cleanup() {
  [ "$CLEANED" = 1 ] && return 0
  CLEANED=1
  local id
  log "守护退出，停止全部实例"
  for id in "${!PIDS[@]}"; do
    kill_group TERM "${PIDS[$id]:-}"
    sweep TERM "${QDIR[$id]:-}"
    if [ -n "${XPIDS[$id]:-}" ]; then kill -TERM "${XPIDS[$id]}" 2>/dev/null; fi
  done
  # 表驱动的清理之外再按 cmdline 兜一遍，堵住「刚起还没登记」的窗口
  sweep_orphans TERM
  sleep 2 &
  wait $! 2>/dev/null
  for id in "${!PIDS[@]}"; do
    kill_group KILL "${PIDS[$id]:-}"
    sweep KILL "${QDIR[$id]:-}"
    if [ -n "${XPIDS[$id]:-}" ]; then kill -KILL "${XPIDS[$id]}" 2>/dev/null; fi
  done
  sweep_orphans KILL
  rm -f "$RUNDIR"/*.pid
  # 这里必须真的退出。trap 函数返回后 bash 会从被打断的 wait 处继续往下跑，
  # 于是主循环又转一轮、把刚停掉的实例全部重新启动；supervisor 那边等不到进程消失
  # 就 KILL，刚起的 xvfb/QQ 被丢成孤儿（ppid=1）继续占着 6099 端口 ——
  # 表现就是「关了 NapCat，端口还在、QQ 还活着」。
  exit 0
}
trap cleanup TERM INT EXIT

# 上次守护被强杀 / proot 整个重启时，会留下没人管的 Xvfb 与 QQ：它们还攥着
# /tmp/.X11-unix/NN，新 Xvfb 一启动就 "Server is already active for display N" 秒退，
# 所有参数组合全部失败 —— 现象就是「NapCat 怎么都起不来」。所以开机先扫一遍。
sweep_orphans TERM
sleep 2 &
wait $! 2>/dev/null
sweep_orphans KILL
rm -f /tmp/.X*-lock /tmp/.X11-unix/X* 2>/dev/null || true
log "已清理上次残留的 Xvfb / QQ 进程与 X 锁文件"

log "实例守护启动（每 5 秒检查一次 bots.json）"

while true; do
  ALL_IDS=()
  EN_IDS=()
  EN_LINES=()
  ACT=""
  BOTS_SKIP=""
  BOTS_SEEN=""
  # 注意：这里必须用进程替换（< <），循环体才跑在当前 shell 里，
  # BOTS_SKIP 才能带到循环外面；换成管道的话跳过标记就丢了。
  while IFS='|' read -r id tok np op en ac; do
    # 状态行：__SKIP__ 说明本轮 bots.json 不可信，整轮跳过。
    # 绝不能继续往下走——那会把所有实例都判成「已从 bots.json 删除」并 purge 掉登录数据。
    if [ "$id" = "__SKIP__" ]; then BOTS_SKIP="${tok:-未知原因}"; BOTS_SEEN=1; break; fi
    if [ "$id" = "__OK__" ]; then BOTS_SEEN=1; continue; fi
    [ -n "$id" ] || continue
    ALL_IDS+=("$id")
    [ -n "$ac" ] && ACT="$ac"
    if [ "$en" = "1" ]; then
      EN_IDS+=("$id")
      EN_LINES+=("$id|$tok|$np|$op")
    fi
  done < <(read_bots)

  # 连状态行都没读到（python3 缺失、脚本被打断等）同样按不可信处理：
  # 否则 ALL_IDS 为空，下面会把所有在跑的实例都判成「已删除」并 purge 掉登录数据。
  [ -n "$BOTS_SEEN" ] || BOTS_SKIP="读取 bots.json 失败（没有拿到状态行）"

  if [ -n "$BOTS_SKIP" ]; then
    log "bots.json 本轮不可信（$BOTS_SKIP），跳过本轮：不停止也不清理任何实例"
    sleep 5 &
    wait $! 2>/dev/null
    continue
  fi

  # 当前实例取 bots.json 的 active；它不在列表里就退回第一个启用实例
  ACTIVE="$ACT"
  if ! in_list "$ACTIVE" "${ALL_IDS[@]}"; then
    if [ "${#EN_IDS[@]}" -gt 0 ]; then ACTIVE="${EN_IDS[0]}"; else ACTIVE=""; fi
  fi

  # 1) 该停的停掉：从 bots.json 消失的（记录待清算）、被禁用的（保留数据）
  for id in "${!PIDS[@]}"; do
    if ! in_list "$id" "${ALL_IDS[@]}"; then
      [ "${PURGE[$id]:-0}" = "1" ] && continue
      PURGE[$id]=1
      stop_inst "$id"
    elif ! in_list "$id" "${EN_IDS[@]}"; then
      [ "${STOP[$id]:-0}" = "0" ] && stop_inst "$id"
    fi
  done

  # 2) 收割：TERM 后还没退出的补 KILL；已退出的销账并按需删数据
  for id in "${!PIDS[@]}"; do
    pg="${PIDS[$id]:-}"
    if [ -d "/proc/$pg" ]; then
      # 只有被 stop_inst 标记过（STOP>=1）的实例才在等待退出。健康实例的
      # STOP 必须保持 0；如果每轮都无条件自增，约 15 秒后就会被误判成
      # 「该强杀了」，于是刚启动的容器被反复杀死、永远起不来。
      if [ "${STOP[$id]:-0}" -ge 1 ]; then
        if [ "${STOP[$id]:-0}" -ge 2 ]; then
          log "强制结束实例 $id（pid $pg）"
          kill_group KILL "$pg"
          sweep KILL "${QDIR[$id]:-}"
          sleep 1 &
          wait $! 2>/dev/null
        fi
        STOP[$id]=$(( "${STOP[$id]:-0}" + 1 ))
      fi
    else
      # QQ 退出后 Xvfb 不会自己走，留着就会一直占着那个显示号，收掉它
      if [ -n "${XPIDS[$id]:-}" ]; then kill -TERM "${XPIDS[$id]}" 2>/dev/null; fi
      unset "PIDS[$id]" "QDIR[$id]" "XPIDS[$id]"
      STOP[$id]=0
      if [ "${PURGE[$id]:-0}" = "1" ]; then
        purge_inst "$id"
        unset "PURGE[$id]"
      fi
    fi
  done

  # 3) 启用的实例没在跑就启动 —— 但风控命中过的实例除外
  #    先扫一遍该实例日志的新增部分：命中就写 risk.json + 打 risk-<id>.flag。
  #    flag 在的时候不拉起它：反复重登会把「需要验证」升级成封号，
  #    解除只能由用户 / 原生层 riskClear()（删掉 flag）来做。
  for e in "${EN_LINES[@]}"; do
    id="${e%%|*}"
    risk_scan "$LOGDIR/qq-$id.log" "$id"
    if risk_blocked "$id"; then
      if [ "${RISK_SAID[$id]:-0}" != "1" ]; then
        log "[risk] 实例 $id 命中风控词「$(risk_word "$id")」，已暂停自动重启"
        RISK_SAID[$id]=1
      fi
      continue
    fi
    unset "RISK_SAID[$id]" 2>/dev/null
    [ -n "${PIDS[$id]:-}" ] && continue
    [ "${PURGE[$id]:-0}" = "1" ] && continue
    start_inst "$e"
  done

  sleep 5 &
  wait $! 2>/dev/null
done
