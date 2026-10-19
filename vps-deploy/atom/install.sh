#!/usr/bin/env bash
# 公告与上报服务（qqbot-atom，监听 127.0.0.1:8800）的一键安装。
#
# 幂等：重复跑就是把 server.py / unit 覆盖成最新、把 nginx 里那段 location 整块替换，
# 不会重复追加、也不会动 announce.json 和那两个 .jsonl（增量数据不能被安装脚本清掉）。
#
# 用法：
#   sudo bash install.sh                 # 装文件 + 建服务 + 改 nginx
#   sudo bash install.sh --no-nginx      # 只装服务，nginx 自己改
#   sudo bash install.sh --prefix=/opt/qqbot-atom --nginx-conf=/etc/nginx/conf.d/zhuxin-site.conf
#
# 跑之前 server.py 和 qqbot-atom.service 必须在同一个目录里。
set -euo pipefail

if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
    SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
    SRC_DIR="$(pwd)"
fi

PREFIX="/opt/qqbot-atom"
UNIT="/etc/systemd/system/qqbot-atom.service"
NGINX_CONF="/etc/nginx/conf.d/zhuxin-site.conf"
DO_NGINX=1

while [ $# -gt 0 ]; do
    case "$1" in
        --no-nginx)     DO_NGINX=0 ;;
        --prefix=*)     PREFIX="${1#*=}" ;;
        --nginx-conf=*) NGINX_CONF="${1#*=}" ;;
        -h|--help)      sed -n '2,14p' "${BASH_SOURCE[0]:-$0}"; exit 0 ;;
        *)              echo "不认识的参数：$1" >&2; exit 2 ;;
    esac
    shift
done

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m[x] %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "要用 root 跑：sudo bash $0"

for f in server.py qqbot-atom.service; do
    [ -f "$SRC_DIR/$f" ] || die "找不到 $SRC_DIR/$f —— 这个脚本要和 server.py / qqbot-atom.service 放在同一目录"
done

# ---------------------------------------------------------------------------
# 1. 装文件
# ---------------------------------------------------------------------------
say "安装到 $PREFIX"
install -d -m 755 "$PREFIX"
# 只覆盖代码，不碰 announce.json / crash.jsonl / stats.jsonl
install -m 644 "$SRC_DIR/server.py" "$PREFIX/server.py"
install -m 644 "$SRC_DIR/qqbot-atom.service" "$UNIT"
chmod 755 "$PREFIX"
echo "  $PREFIX/server.py"
echo "  $UNIT"

# ---------------------------------------------------------------------------
# 2. 建服务
# ---------------------------------------------------------------------------
command -v systemctl >/dev/null 2>&1 || die "没有 systemd，这个包得自己起进程"

say "重载 systemd 并启动 qqbot-atom"
systemctl daemon-reload
systemctl enable --quiet qqbot-atom.service 2>/dev/null || systemctl enable qqbot-atom.service
systemctl restart qqbot-atom.service

# 等端口起来（最多 9 秒）
ok=0
for _ in $(seq 1 30); do
    if curl -fsS -m 2 http://127.0.0.1:8800/api/atom-health >/dev/null 2>&1; then ok=1; break; fi
    sleep 0.3
done
if [ "$ok" != "1" ]; then
    warn "8800 还没通，先看看日志："
    systemctl status qqbot-atom.service --no-pager | head -20 || true
    journalctl -u qqbot-atom.service -n 30 --no-pager || true
    die "qqbot-atom 起不来"
fi
echo "  8800 已就绪：$(curl -fsS -m 2 http://127.0.0.1:8800/api/atom-health)"

# ---------------------------------------------------------------------------
# 3. nginx 反代（整块替换，幂等）
# ---------------------------------------------------------------------------
if [ "$DO_NGINX" != "1" ]; then
    warn "跳过了 nginx（--no-nginx）。四段 location 见本脚本下面的 NGINXBLOCK"
    exit 0
fi

if [ ! -f "$NGINX_CONF" ]; then
    warn "没找到 $NGINX_CONF，跳过反代。请把下面这段手工加进站点的 server{} 里："
    cat <<'NGINXBLOCK'
    location = /api/announce {
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 15s;
    }
NGINXBLOCK
    exit 1
fi

say "改 nginx：$NGINX_CONF"
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

cat > "$TMPD/block.inc" <<'NGINXBLOCK'
    # >>> qqbot-atom begin（install.sh 维护：重跑会整块替换；要改就改仓库里的 install.sh）
    # 公告 / 崩溃聚合 / 匿名统计 → qqbot-atom.service（监听 127.0.0.1:8800）
    # 前端只拿 JSON，站点页面里不放脚本。
    location = /api/announce {
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 15s;
    }
    location = /api/crash {
        client_max_body_size 64k;
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 15s;
    }
    location = /api/stats {
        client_max_body_size 64k;
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_read_timeout 15s;
    }
    location = /api/atom-health {
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 15s;
    }
    # docs/接口约定-1.1.30.md 第 11 节的形状，和 /api/announce 读同一份 announce.json
    location = /api/app-config.json {
        proxy_pass http://127.0.0.1:8800;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 15s;
    }
    # <<< qqbot-atom end
NGINXBLOCK

# 先把「应该长什么样」算到临时文件，跟现网比一比；一样就什么都不做，
# 免得每跑一次就多一个 .bak 文件、还白 reload 一次 nginx。
python3 - "$NGINX_CONF" "$TMPD/block.inc" > "$TMPD/new.conf" <<'PY'
import sys

conf_path, block_path = sys.argv[1], sys.argv[2]
BEGIN = "# >>> qqbot-atom begin"
END = "# <<< qqbot-atom end"

with open(block_path, "r", encoding="utf-8") as f:
    block = f.read().rstrip("\n") + "\n"
with open(conf_path, "r", encoding="utf-8") as f:
    src = f.read()

# 1) 去掉上一次插入的整块（连同它前面那个空行），保证重复跑不会堆叠
out, skip = [], False
for line in src.splitlines(True):
    if BEGIN in line:
        skip = True
        if out and out[-1].strip() == "":
            out.pop()
        continue
    if END in line:
        skip = False
        continue
    if not skip:
        out.append(line)
src = "".join(out)

# 2) 插到一个稳定的锚点前面。锚点都找不到就停手，别乱写
anchors = ["    # 静态资源缓存 7 天", "    location / {", "    gzip on;"]
lines = src.splitlines(True)
pos = None
for anchor in anchors:
    for i, line in enumerate(lines):
        if line.startswith(anchor):
            pos = i
            break
    if pos is not None:
        break
if pos is None:
    sys.exit("找不到插入锚点，请手工把 atom 的四段 location 加进 %s" % conf_path)

lines.insert(pos, block + "\n")
sys.stdout.write("".join(lines))
PY

if cmp -s "$TMPD/new.conf" "$NGINX_CONF"; then
    echo "  nginx 配置已是最新，不动它（不备份、不 reload）"
else
    BACKUP="$NGINX_CONF.bak-atom-$(date +%Y%m%d-%H%M%S)"
    cp -p "$NGINX_CONF" "$BACKUP"
    echo "  备份：$BACKUP"
    # 用 cat > 覆盖，保留原文件的 inode 与权限位
    cat "$TMPD/new.conf" > "$NGINX_CONF"
    echo "  已插入四段 location"

    say "nginx -t"
    if nginx -t; then
        systemctl reload nginx 2>/dev/null || nginx -s reload
        echo "  nginx 已重载"
    else
        warn "nginx -t 没过，回滚到 $BACKUP"
        cat "$BACKUP" > "$NGINX_CONF"
        die "nginx 配置有问题，已还原（服务本身已经装好并在跑）"
    fi
fi

# ---------------------------------------------------------------------------
# 4. 收尾自检
# ---------------------------------------------------------------------------
say "自检"
systemctl is-active qqbot-atom.service
curl -fsS -m 5 http://127.0.0.1:8800/api/atom-health; echo

cat <<'TIP'

下一步可以这样验（在任意一台能上网的机器上）：
  curl -sS -i https://xn--15t839a.xyz/api/atom-health
  curl -sS    https://xn--15t839a.xyz/api/announce?ver=1.1.30
加一条公告：直接改 /opt/qqbot-atom/announce.json（服务每次请求现读，不用重启）。
TIP
