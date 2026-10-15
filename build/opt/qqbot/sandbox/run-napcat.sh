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

declare -A PIDS QDIR STOP PURGE
CLEANED=0

log() { echo "[napcat] $*"; }

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
        d = json.load(open(cfg, encoding='utf-8'))
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
            with open(path, encoding='utf-8') as fh:
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
    with open(p, encoding='utf-8') as fh:
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
        # 历史默认 token 是 qqbot（宿主机 link.sh / 旧版本种进 bots.json 的）。这里在启动前
        # 就地把它当成 zhuxin：老设备不用改 bots.json 也能把 WebUI token 换过来。
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
#   * token 的历史默认值 qqbot 在这里归一成 zhuxin：老的 bots.json 由宿主机 link.sh 种下，
#     不改那个文件也能把 WebUI token 换过来。⚠️ 这条规则和 read_bots 里那条必须保持一致。
# 注意：沙盒里 /qqbot-cache 是宿主机 $QQBOT_CACHE 的映射，bots.json 直接可读。
napcat_inst_value() {
  local id="$1" key="$2" def="$3" v
  v=$(python3 - "$BOTS_JSON" "$id" "$key" <<'PY'
import json, sys

path, iid, key = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(path, encoding='utf-8') as fh:
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
  local id="$1"
  log "清理实例 $id 的数据（QQ 副本 / 登录数据 / 日志）"
  rm -rf "$INSTBASE/$id" "/root/qqinst/$id" "$LOGDIR/qq-$id.log" "$RUNDIR/$id.pid"
}

stop_inst() {
  local id="$1" pg
  pg="${PIDS[$id]:-}"
  if [ -n "$pg" ] && [ -d "/proc/$pg" ]; then
    log "停止实例 $id（pid $pg）"
    kill_group TERM "$pg"
    sweep TERM "${QDIR[$id]:-}"
    STOP[$id]=1
  else
    unset "PIDS[$id]" "QDIR[$id]"
    STOP[$id]=0
  fi
  return 0
}

start_inst() {
  local e="$1" id rest tok np op uin QQDIR INSTHOME LOGF NAP CONF SRC EXTRA
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

  # 每次启动都删掉这两个文件，让 NapCat 按本实例的 token 与端口重新生成
  rm -f "$CONF/webui.json" "$CONF/onebot11.json"
  mkdir -p "$INSTHOME"
  EXTRA="--user-data-dir=$INSTHOME/.config/QQ"

  write_onebot "$CONF" "$op"

  # token 与 WebUI 端口都以 bots.json 里「本实例」的字段为准（多实例各用各的 token，
  # 不是写死的常量）。解析失败 / 字段缺失时退回上面 read_bots 解析好的值，
  # 那个兜底值本身就是 zhuxin / 6099（或 bots.json 里的真实值），所以不会退化。
  tok=$(napcat_inst_value "$id" token "$tok")
  np=$(napcat_inst_value "$id" napcatPort "$np")

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
  HOME="$INSTHOME" \
  NAPCAT_WEBUI_SECRET_KEY="$tok" \
  NAPCAT_WEBUI_JWT_SECRET_KEY="${tok}_jwt" \
  NAPCAT_WEBUI_PREFERRED_PORT="$np" \
  xvfb-run -a --server-args="-screen 0 1280x800x24" "$QQDIR/qq" --no-sandbox $EXTRA >> "$LOGF" 2>&1 &
  PIDS[$id]=$!
  QDIR[$id]="$QQDIR"
  STOP[$id]=0
  echo "${PIDS[$id]}" > "$RUNDIR/$id.pid"
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
  done
  # 表驱动的清理之外再按 cmdline 兜一遍，堵住「刚起还没登记」的窗口
  sweep_orphans TERM
  sleep 2 &
  wait $! 2>/dev/null
  for id in "${!PIDS[@]}"; do
    kill_group KILL "${PIDS[$id]:-}"
    sweep KILL "${QDIR[$id]:-}"
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
      unset "PIDS[$id]" "QDIR[$id]"
      STOP[$id]=0
      if [ "${PURGE[$id]:-0}" = "1" ]; then
        purge_inst "$id"
        unset "PURGE[$id]"
      fi
    fi
  done

  # 3) 启用的实例没在跑就启动
  for e in "${EN_LINES[@]}"; do
    id="${e%%|*}"
    [ -n "${PIDS[$id]:-}" ] && continue
    [ "${PURGE[$id]:-0}" = "1" ] && continue
    start_inst "$e"
  done

  sleep 5 &
  wait $! 2>/dev/null
done
