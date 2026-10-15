#!/bin/bash
# 沙盒内：把 bots.json 里每个启用的 NapCat 实例同步成 AstrBot 的一个 aiocqhttp 平台。
#
# 每个 NapCat 实例自己的 onebot11.json（反向 WebSocket 指向 AstrBot）由 run-napcat.sh
# 在启动该实例时写入，因为实例的 QQ 目录到底是 /opt/QQ 还是
# /opt/qqbot-inst/<id>/QQ 只有它自己知道（当前实例优先用 /opt/QQ）。
# 这里只负责 AstrBot 侧的平台配置。
set -e

BOTS_JSON=/qqbot-cache/bots.json
[ -s "$BOTS_JSON" ] || { echo "[link] 缺少 $BOTS_JSON"; exit 1; }

# AstrBot 只在启动时读一次平台列表：新实例的 OneBot 端口不重启它就不会监听，
# 删掉的实例端口也会一直留着。所以先算出这次需要哪些端口，跟现有配置比对。
# 无论端口有没有变化（CHANGED=0 是 relink 最常见的路径），写 cmd_config.json
# 之前都必须先停掉 AstrBot：
#   * 顺序不能反——AstrBot 退出时会把内存里的旧配置写回磁盘，先写后停会被它覆盖；
#   * json.dump 是截断式非原子写，和运行中的 AstrBot 自己的配置回写并发交错，
#     会写出损坏的配置或被旧值覆盖掉。
# 停掉之后由外层 run-all.sh 的守护循环（wait 返回）自动把整套服务重新拉起，
# 所以 CHANGED=0 也一样会重启，不会把 AstrBot 停在停掉的状态。
PLAN=$(python3 - "$BOTS_JSON" <<'PY'
import json, os, sys
try:
    root = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    root = {}
bots = [b for b in (root.get('bots') or []) if b.get('enable', True) is not False]
if not bots:
    bots = [{'id': 'bot1', 'onebotPort': 6199}]
ports = sorted(set(int(b.get('onebotPort') or 6199) for b in bots))
old = []
acfg = '/opt/AstrBot/data/cmd_config.json'
if os.path.exists(acfg):
    try:
        a = json.load(open(acfg, encoding='utf-8'))
    except Exception:
        a = {}
    old = sorted(set(int(p.get('ws_reverse_port') or 0) for p in (a.get('platform') or [])
                     if p.get('type') == 'aiocqhttp' and str(p.get('id', '')).startswith('napcat')))
print('%d|%s|%s' % (0 if old == ports else 1, ','.join(map(str, ports)), ','.join(map(str, old))))
PY
)
CHANGED=${PLAN%%|*}
NEWPORTS=$(echo "$PLAN" | cut -d'|' -f2)
OLDPORTS=$(echo "$PLAN" | cut -d'|' -f3)
echo "[link] OneBot 端口 需要 [$NEWPORTS] / 当前 [$OLDPORTS]"

# 不论 CHANGED 是 0 还是 1，写盘前都要先停 AstrBot 并等它真的退出：等它退出不只是
# 为了不和它抢配置文件，也是为了让外层 run-all.sh 的守护循环尽快 wait 返回并重启服务。
APID=$(pgrep -f 'AstrBot/.venv/bin/python main.py' 2>/dev/null | head -n 1)
if [ -n "$APID" ]; then
  if [ "$CHANGED" = "1" ]; then
    echo "[link] 平台列表有变化，重启 AstrBot 使其生效（约 30 秒后新端口开始监听）"
  else
    echo "[link] 平台列表没有变化，仍先停 AstrBot 再原子改写配置（守护进程会自动重启服务）"
  fi
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
  # 配置照样会被原子改写，等用户把 AstrBot 打开就生效 —— 但提示语必须说实话，
  # 不然界面会告诉用户「已对接并重启服务」，而实际上什么都没重启。
  echo "[link] AstrBot 当前被组件开关关闭，配置已写好，打开时自动生效（无需重启）"
fi

python3 - "$BOTS_JSON" <<'PY'
import json, os, sys

try:
    root = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception as e:
    print('[link] bots.json 解析失败:', e)
    sys.exit(1)

bots = [b for b in (root.get('bots') or []) if b.get('enable', True) is not False]
if not bots:
    bots = [{'id': 'bot1', 'token': 'qqbot', 'napcatPort': 6099, 'onebotPort': 6199}]

ports = sorted(set(int(b.get('onebotPort') or 6199) for b in bots))
for b in bots:
    print('[link] 实例 %s -> OneBot 端口 %d' % (b.get('id'), int(b.get('onebotPort') or 6199)))

acfg = '/opt/AstrBot/data/cmd_config.json'
os.makedirs('/opt/AstrBot/data', exist_ok=True)
a = {}
if os.path.exists(acfg):
    try:
        a = json.load(open(acfg, encoding='utf-8'))
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
