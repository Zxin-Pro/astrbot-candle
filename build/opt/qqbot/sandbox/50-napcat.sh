#!/bin/bash
# 在 Ubuntu 沙盒内安装 NapCat 并注入 QQ
set -e

ZIP="/qqbot-cache/NapCat.Shell.zip"
APP="/opt/QQ/resources/app"
[ -s "$ZIP" ] || { echo "[sandbox] 缺少 $ZIP"; exit 1; }
[ -d "$APP" ] || { echo "[sandbox] 未找到 $APP，请先完成 QQ 安装"; exit 1; }

echo "[sandbox] 解压 NapCat"
rm -rf /tmp/napcat
mkdir -p /tmp/napcat
python3 /qqbot-opt/sandbox/extract.py zip "$ZIP" /tmp/napcat

SRC=""
if [ -d /tmp/napcat/NapCat ]; then SRC=/tmp/napcat/NapCat; fi
if [ -z "$SRC" ] && [ -d /tmp/napcat/napcat ]; then SRC=/tmp/napcat/napcat; fi
if [ -z "$SRC" ]; then
  SRC=$(find /tmp/napcat -maxdepth 2 -name napcat.mjs -printf '%h\n' | head -n 1)
fi
if [ -z "$SRC" ]; then
  echo "[sandbox] NapCat 解压结构异常"
  ls -la /tmp/napcat
  exit 1
fi
echo "[sandbox] NapCat 源目录：$SRC"

# 与官方安装器一致：config/plugins 不覆盖（cp -rn），其余全量覆盖（cp -rf）
mkdir -p "$APP/app_launcher/napcat"
for ITEM in "$SRC"/*; do
  BASE=$(basename "$ITEM")
  case "$BASE" in
    config|plugins)
      mkdir -p "$APP/app_launcher/napcat/$BASE"
      cp -rn "$ITEM/." "$APP/app_launcher/napcat/$BASE/" || true
      ;;
    *) cp -rf "$ITEM" "$APP/app_launcher/napcat/" ;;
  esac
done

echo "[sandbox] 写入 loadNapCat.js"
cat > "$APP/loadNapCat.js" <<'JSEOF'
const path = require('node:path');
const { pathToFileURL } = require('node:url');
import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);
JSEOF

echo "[sandbox] 修改 QQ package.json 的 main 字段"
python3 - <<'PYEOF'
import json
p = '/opt/QQ/resources/app/package.json'
d = json.load(open(p, encoding='utf-8'))
d['main'] = './loadNapCat.js'
json.dump(d, open(p, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('[sandbox] main ->', d['main'])
PYEOF

# 这个文件是"首次安装"的初始值：实例真正启动时 run-napcat.sh 的 write_onebot() 会按
# bots.json 里该实例的 onebotPort 重写它。这里也读 bots.json，是为了让初始值就和
# 实际端口一致（否则用户打开 NapCat 配置会看到一个用不上的 6199）。
# 注意 60-astrbot.sh 里那份 aiocqhttp 的端口同理，由 link.sh 统一同步。
BOTS_JSON=/qqbot-cache/bots.json
ONEBOT_PORT=
if [ -s "$BOTS_JSON" ]; then
  # 只从 bots 数组里找，并且按"同一个实例对象"匹配 "id" -> "onebotPort"：
  # 单个实例对象里这两个键之间没有别的引号字段（name / qq / token 的值都是
  # 不含引号的字符串），所以这个跨字段模式只会落在同一个对象上。
  ACT=$(sed -n 's/.*"active"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$BOTS_JSON" 2>/dev/null | head -n 1)
  BODY=$(sed -n 's/.*"bots"[[:space:]]*:[[:space:]]*\[//p' "$BOTS_JSON" 2>/dev/null)
  if [ -n "$ACT" ]; then
    ONEBOT_PORT=$(printf '%s\n' "$BODY" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$ACT"'"[^{}]*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
  fi
  [ -n "$ONEBOT_PORT" ] || ONEBOT_PORT=$(printf '%s\n' "$BODY" | sed -n 's/.*"onebotPort"[[:space:]]*:[[:space:]]*\([0-9]\{1,5\}\).*/\1/p' | head -n 1)
fi
case "${ONEBOT_PORT:-}" in
  ''|0|*[!0-9]*) ONEBOT_PORT=6199 ;;
esac

echo "[sandbox] 写入 NapCat OneBot11 默认配置（首次登录后自动连接 AstrBot，端口 $ONEBOT_PORT）"
NAPCAT_CONF="$APP/app_launcher/napcat/config"
mkdir -p "$NAPCAT_CONF"
if [ ! -f "$NAPCAT_CONF/onebot11.json" ]; then
  cat > "$NAPCAT_CONF/onebot11.json" <<JSONEOF
{
  "network": {
    "httpServers": [],
    "httpSseServers": [],
    "httpClients": [],
    "websocketServers": [],
    "websocketClients": [
      {
        "name": "astrbot",
        "enable": true,
        "url": "ws://127.0.0.1:$ONEBOT_PORT/ws",
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "reconnectInterval": 5000,
        "token": "",
        "debug": false,
        "heartInterval": 30000,
        "verifyCertificate": true
      }
    ],
    "plugins": []
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false,
  "imageDownloadProxy": "",
  "timeout": {
    "baseTimeout": 10000,
    "uploadSpeedKBps": 256,
    "downloadSpeedKBps": 256,
    "maxTimeout": 1800000
  }
}
JSONEOF
  echo "[sandbox] 已生成 $NAPCAT_CONF/onebot11.json"
else
  echo "[sandbox] 已存在 onebot11.json，保持不变"
fi

# ---------- 反检测：首次安装就把 bypass 与 autoTimeSync 打开 ----------
# 与 run-napcat.sh 的 write_napcat() 是同一份 python 逻辑（改一处必须同步改另一处）：
# bypass 六键强制 true、autoTimeSync 强制 true，其余键（含用户自定义键）原样保留，
# 文件不存在则按权威 9 键结构创建。
# 位置就在写 onebot11.json 之后：上面 L32-38 对 config/ 用的是 cp -rn（不覆盖已存在的），
# 所以以后重装也不会被发行包那份 all-false 的默认值冲掉。
# ⚠️ 本脚本开头有 set -e：这里显式用 if 判定退出码，补丁失败只告警，绝不中断安装。
# 注意只用 python3 就地合并 napcat.json，不碰 cp -rn，config 目录「不覆盖已有配置」的
# 语义完全不变。安装时还不知道 uin，napcat_<uin>.json 的合并由 run-napcat.sh 每次启动兜住。
if python3 - "$NAPCAT_CONF" "${ACT:-bot1}" "" <<'PY'
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
then
  :
else
  echo "[sandbox] 警告：NapCat 反检测 bypass 写入失败，不影响安装（run-napcat.sh 启动时会再补一次）"
fi

rm -rf /tmp/napcat
echo "[sandbox] NapCat 文件清单（前 20 项）"
ls "$APP/app_launcher/napcat" | head -n 20
echo "[sandbox] NapCat 安装完成"
