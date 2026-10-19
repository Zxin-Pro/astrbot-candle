#!/bin/bash
# 沙盒内：把 bots.json 里每个 NapCat 实例同步成 AstrBot 的一个 aiocqhttp 平台。
#
# 每个 NapCat 实例自己的 onebot11.json（反向 WebSocket 指向 AstrBot）由 run-napcat.sh
# 在启动该实例时写入，因为实例的 QQ 目录到底是 /opt/QQ 还是
# /opt/qqbot-inst/<id>/QQ 只有它自己知道（当前实例优先用 /opt/QQ）。
# 这里只负责 AstrBot 侧的平台配置。
set -e

BOTS_JSON=/qqbot-cache/bots.json
[ -s "$BOTS_JSON" ] || { echo "[link] 缺少 $BOTS_JSON"; exit 1; }

# AstrBot 只在启动时读一次平台列表，运行期没有任何热加载接口（v1.1.22 查过
# astrbot/dashboard/api/platform.py 与 services/platform_service.py，只有
# webhook 注册，没有「新增一个 aiocqhttp 监听口」的入口）。所以：
#
#   * 新增实例（需要 AstrBot 多监听一个端口）—— 这是唯一必须重启的情形。
#   * 删除实例 —— AstrBot 上那个平台就变成一个没人连的监听口，不影响任何功能。
#     **不为此重启**（以前会重启，用户反馈「对接不该重启」）。
#     等将来因为别的原因重启时，下面的写配置步骤会顺手把它清掉。
#   * 停用实例 / 只改 token 之类 —— 端口清单根本没变，AstrBot 一个信号都不收。
#
# 端口清单取「bots.json 里的所有实例」，不看 enable：aiocqhttp 的反向 WS 服务端
# 只是个安静听着的口，没人连完全无害。
PLAN=$(python3 - "$BOTS_JSON" <<'PY'
import json, os, sys
try:
    root = json.load(open(sys.argv[1], encoding='utf-8-sig'))
except Exception:
    root = {}
bots = root.get('bots') or []
if not bots:
    bots = [{'id': 'bot1', 'onebotPort': 6199}]
ports = sorted(set(int(b.get('onebotPort') or 6199) for b in bots))
old = []
acfg = '/opt/AstrBot/data/cmd_config.json'
if os.path.exists(acfg):
    try:
        a = json.load(open(acfg, encoding='utf-8-sig'))
    except Exception:
        a = {}
    old = sorted(set(int(p.get('ws_reverse_port') or 0) for p in (a.get('platform') or [])
                     if p.get('type') == 'aiocqhttp' and str(p.get('id', '')).startswith('napcat')))
need = [p for p in ports if p not in old]
extra = [p for p in old if p not in ports]
print('%d|%s|%s|%s|%s' % (1 if need else 0,
                          ','.join(map(str, ports)), ','.join(map(str, old)),
                          ','.join(map(str, need)), ','.join(map(str, extra))))
PY
)
NEEDNEW=${PLAN%%|*}
_REST=${PLAN#*|}
NEWPORTS=$(echo "$_REST" | cut -d'|' -f1)
OLDPORTS=$(echo "$_REST" | cut -d'|' -f2)
NEEDPORTS=$(echo "$_REST" | cut -d'|' -f3)
EXTRAPORTS=$(echo "$_REST" | cut -d'|' -f4)
echo "[link] OneBot 端口 需要 [$NEWPORTS] / 当前 [$OLDPORTS]"

astrbot_pid() { pgrep -f 'AstrBot/.venv/bin/python main.py' 2>/dev/null | head -n 1; }

if [ "$NEEDNEW" = "0" ]; then
  APID=$(astrbot_pid)
  if [ -n "$EXTRAPORTS" ] && [ -z "$APID" ]; then
    # AstrBot 没在跑：这是免费清理遗留平台的最好时机
    echo "[link] AstrBot 没在跑，顺手清掉已删实例的遗留端口 [$EXTRAPORTS]"
  else
    if [ -n "$EXTRAPORTS" ]; then
      echo "[link] 已删实例的端口 [$EXTRAPORTS] 还挂在 AstrBot 上，不影响使用，不为此重启"
    else
      echo "[link] 平台列表与当前配置一致，不动 AstrBot（不重启、不断开已连上的实例）"
    fi
    echo "[link] 完成（未触碰 AstrBot）"
    exit 0
  fi
else
  APID=$(astrbot_pid)
  # 写 cmd_config.json 之前必须先把它停掉：AstrBot 退出时会把内存里的旧配置
  # 写回磁盘，先写后停会被它覆盖。停掉之后由外层 run-all.sh 的守护循环自动拉起。
  if [ -n "$APID" ]; then
    echo "[link] 新增实例端口 [$NEEDPORTS]，重启 AstrBot 让它开始监听（约 30 秒）"
    kill -TERM "$APID" 2>/dev/null || true
    n=0
    while [ "$n" -lt 20 ]; do
      kill -0 "$APID" 2>/dev/null || break
      sleep 0.5
      n=$((n + 1))
    done
    kill -KILL "$APID" 2>/dev/null || true
  else
    # AstrBot 被组件开关关着的时候这里必然匹配不到进程。这不是异常，也不用卡：
    # 配置照样会被原子改写，等用户把 AstrBot 打开就生效 —— 但提示语必须说实话。
    echo "[link] AstrBot 当前被组件开关关闭，配置已写好，打开时自动生效（无需重启）"
  fi
fi

python3 - "$BOTS_JSON" <<'PY'
import json, os, sys

try:
    root = json.load(open(sys.argv[1], encoding='utf-8-sig'))
except Exception as e:
    print('[link] bots.json 解析失败:', e)
    sys.exit(1)

# 与上面的 PLAN 保持一致：所有实例都写进去，不看 enable。
bots = root.get('bots') or []
if not bots:
    bots = [{'id': 'bot1', 'token': 'zhuxin', 'napcatPort': 6099, 'onebotPort': 6199}]

ports = sorted(set(int(b.get('onebotPort') or 6199) for b in bots))
for b in bots:
    print('[link] 实例 %s -> OneBot 端口 %d' % (b.get('id'), int(b.get('onebotPort') or 6199)))

acfg = '/opt/AstrBot/data/cmd_config.json'
os.makedirs('/opt/AstrBot/data', exist_ok=True)
a = {}
if os.path.exists(acfg):
    try:
        a = json.load(open(acfg, encoding='utf-8-sig'))
    except Exception:
        a = {}
plats = [p for p in (a.get('platform') or [])
         if not (p.get('type') == 'aiocqhttp' and str(p.get('id', '')).startswith('napcat'))]
for oport in ports:
    plats.append({
        'id': 'napcat_%d' % oport,
        'type': 'aiocqhttp',
        'enable': True,
        'ws_reverse_host': '0.0.0.0',
        'ws_reverse_port': oport,
        'ws_reverse_token': '',
    })
a['platform'] = plats
# 写临时文件再 os.replace 原子替换：json.dump 直接写目标路径是截断式写入，
# 此刻读到它的进程（比如刚被重新拉起的 AstrBot）会拿到半截 JSON。
tmp = '%s.qqbot-%d.tmp' % (acfg, os.getpid())
with open(tmp, 'w', encoding='utf-8') as fh:
    json.dump(a, fh, indent=2, ensure_ascii=False)
os.replace(tmp, acfg)
print('[link] AstrBot 平台端口:', ports)
PY

echo "[link] 完成"
