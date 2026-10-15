#!/bin/bash
# 从 /qqbot-cache/bots.json 里摘掉一个实例（沙盒内有 python3，宿主 Termux 侧没有）
# 用法: remove-instance.sh <id>
set -u
ID="${1:-}"
if [ -z "$ID" ]; then
  echo "用法: remove-instance.sh <id>"
  exit 1
fi
python3 - "$ID" <<'PY'
import json, os, sys
drop = sys.argv[1]
p = '/qqbot-cache/bots.json'
d = json.load(open(p, encoding='utf-8'))
bs = d.get('bots') or []
keep = [b for b in bs if str(b.get('id')) != drop]
# 删掉的如果正是当前实例，把 active 指向剩下第一个启用实例（没有启用的就指向第一个剩下的）
if str(d.get('active')) == drop:
    nxt = ''
    for b in keep:
        if b.get('enable', True) is not False:
            nxt = str(b.get('id'))
            break
    if not nxt and keep:
        nxt = str(keep[0].get('id'))
    d['active'] = nxt
d['bots'] = keep
# 写临时文件再 os.replace 原子替换：json.dump 直接写目标路径是截断式写入，
# 守护进程 / UI 侧只要在写的过程中读到它就会拿到半截 JSON（进而误判实例被删）。
tmp = '%s.qqbot-%d.tmp' % (p, os.getpid())
with open(tmp, 'w', encoding='utf-8') as fh:
    json.dump(d, fh, indent=2, ensure_ascii=False)
os.replace(tmp, p)
print('[remove] 剩余实例 %d 个，当前实例 %s' % (len(keep), d.get('active')))
PY
