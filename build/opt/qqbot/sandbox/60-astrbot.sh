#!/bin/bash
# 在 Ubuntu 沙盒内安装 AstrBot
set -e

SRC="/qqbot-cache/AstrBot-src.tar.gz"
DASH="/qqbot-cache/AstrBot-dashboard.zip"
PIPMIRROR="https://pypi.tuna.tsinghua.edu.cn/simple"

# ---------------------------------------------------------------------------
# 下载代理：由 App 的设置页写进宿主的 <QQBOT_HOME>/proxy.txt，再经 qq_proot 的
# "-b <QQBOT_CACHE>:/qqbot-cache" 绑定挂进来（本脚本里就是 /qqbot-cache/proxy.txt）。
# 宿主侧没有把整个 QQBOT_HOME 挂进来，所以只能读这份副本；qq_proot 另外还会把
# 代理以 http_proxy/https_proxy/all_proxy 环境变量传进来，这里是兜底。
# 代理为空/文件不存在时一律不使用代理。
# ---------------------------------------------------------------------------
PROXY_FILE="/qqbot-cache/proxy.txt"
PROXY_URL="${http_proxy:-}"
if [ -z "$PROXY_URL" ] && [ -s "$PROXY_FILE" ]; then
  # 末尾的 || true 是给 set -e 兜底：读不到文件时不能让整个安装脚本中断。
  PROXY_URL="$(tr -d '\r' < "$PROXY_FILE" 2>/dev/null | head -n 1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)"
  PROXY_URL="$(printf '%s' "$PROXY_URL" | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/" || true)"
fi
# pip 的代理参数用数组拼，空数组展开成零个参数，不会给 pip 塞进空字符串。
# 不能用 "A && B" 的写法：set -e 下条件为假时整个 && 列表返回非 0 会让脚本退出。
PIP_PROXY_ARGS=()
if [ -n "$PROXY_URL" ]; then
  echo "[sandbox] 使用下载代理 $PROXY_URL"
  export http_proxy="$PROXY_URL" https_proxy="$PROXY_URL" all_proxy="$PROXY_URL"
  export HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL" ALL_PROXY="$PROXY_URL"
  export no_proxy="127.0.0.1,localhost,::1" NO_PROXY="127.0.0.1,localhost,::1"
  PIP_PROXY_ARGS=(--proxy "$PROXY_URL")
else
  echo "[sandbox] 未配置下载代理，直连"
fi

[ -s "$SRC" ] || { echo "[sandbox] 缺少 $SRC"; exit 1; }

echo "[sandbox] 解压 AstrBot 源码"
rm -rf /tmp/ab
mkdir -p /tmp/ab
python3 /qqbot-opt/sandbox/extract.py tar "$SRC" /tmp/ab
DIR=$(find /tmp/ab -maxdepth 1 -mindepth 1 -type d | head -n 1)
[ -n "$DIR" ] || { echo "[sandbox] 源码解压失败"; exit 1; }
rm -rf /opt/AstrBot
mkdir -p /opt
mv "$DIR" /opt/AstrBot
rm -rf /tmp/ab

if [ -s "$DASH" ]; then
  echo "[sandbox] 安装控制台前端"
  rm -rf /tmp/abd
  mkdir -p /tmp/abd
  python3 /qqbot-opt/sandbox/extract.py zip "$DASH" /tmp/abd
  if [ -d /tmp/abd/dist ]; then
    rm -rf /opt/AstrBot/astrbot/dashboard/dist
    mkdir -p /opt/AstrBot/astrbot/dashboard
    mv /tmp/abd/dist /opt/AstrBot/astrbot/dashboard/dist
  fi
  rm -rf /tmp/abd
fi

echo "[sandbox] 创建 Python 虚拟环境"
cd /opt/AstrBot
# 平台标识由 common.sh 通过 proot 环境传入（_PYTHON_HOST_PLATFORM）。
# 若已有 venv 的平台与当前不一致（例如换过设备或早前装错架构），直接重建。
WANT="${_PYTHON_HOST_PLATFORM:-$(python3 -c 'import sysconfig;print(sysconfig.get_platform())')}"
if [ -d .venv ]; then
  CUR="$(./.venv/bin/python -c 'import sysconfig;print(sysconfig.get_platform())' 2>/dev/null || echo none)"
  # 只比平台字符串不够：python3 -m venv 是分步做的（先建目录 → 写 pyvenv.cfg → ensurepip 装 pip），
  # 恰好在中间被打断时 .venv/bin/python 已经能跑、平台也对，会被判成健康而跳过重建，
  # 然后炸在下面 pip --upgrade 那一行，报错形态是「pip 不存在/损坏」，跟真因毫无关系；
  # 更糟的是这个判定每次重跑都成立，等于半截 venv 被永久钉死，只能靠用户手动删目录。
  if [ "$CUR" != "$WANT" ] || [ ! -s .venv/pyvenv.cfg ] || [ ! -x .venv/bin/pip ] \
     || ! ./.venv/bin/pip --version >/dev/null 2>&1; then
    echo "[sandbox] 已有虚拟环境不可用（平台 $CUR / pip 缺失或损坏），重建"
    rm -rf .venv
  fi
fi
python3 -m venv .venv
echo "[sandbox] Python 目标平台：$(./.venv/bin/python -c 'import sysconfig;print(sysconfig.get_platform())')"

# pip 走 socks5 代理需要 PySocks，否则直接抛 "Missing dependencies for SOCKS support"，
# 而 venv 是全新的、里面没有它。必须赶在下面【任何】一次带 --proxy 的 pip 之前装上：
# 本脚本第 3 行就是 set -e，连紧跟其后的 --upgrade pip 都会当场把整步搞挂。
# 这一步故意不带 --proxy —— 要走代理才能下 pysocks 就没救了（鸡生蛋），
# 只能赌国内镜像直连可达；装不上也放行（|| true），非 socks5 代理根本用不到它。
case "$PROXY_URL" in
  socks5://*|socks5h://*)
    echo "[sandbox] 预装 PySocks（pip 走 socks5 代理需要）"
    ./.venv/bin/pip install pysocks -i "$PIPMIRROR" >/dev/null 2>&1 || true
    ;;
esac
./.venv/bin/python -m pip install --upgrade pip -i "$PIPMIRROR" "${PIP_PROXY_ARGS[@]}"

echo "[sandbox] 安装 AstrBot 依赖（耗时较长，请耐心等待）"
if ! ./.venv/bin/pip install -r requirements.txt -i "$PIPMIRROR" "${PIP_PROXY_ARGS[@]}"; then
  echo "[sandbox] 镜像安装失败，改用官方 PyPI 重试"
  ./.venv/bin/pip install -r requirements.txt "${PIP_PROXY_ARGS[@]}" || { echo "[sandbox] AstrBot 依赖安装失败"; exit 1; }
fi

echo "[sandbox] 预置 AstrBot 配置（自动接入本机 NapCat）"
# 控制台（dashboard）端口不在这里写：AstrBot 的解析顺序是
#   环境变量 DASHBOARD_PORT > ASTRBOT_DASHBOARD_PORT > cmd_config.json 的 dashboard.port
#   （见 astrbot/dashboard/server.py 的 DashboardServer.run()）
# 所以端口统一由 sandbox/run-astrbot.sh 用环境变量传进去，这里不碰 cmd_config.json
# 的 dashboard 段——那份文件是 AstrBot 自己会回写的，两处都写只会互相打架。
#
# 下面这份 platform 只是"首次安装"的初始值：真正生效的那份由 link.sh 按 bots.json
# 重建。这里的 ws_reverse_port 也按当前实例的 onebotPort 写，免得首次启动时
# AstrBot 先按 6199 监听、再被 link.sh 改端口重启一次。
BOTS_JSON=/qqbot-cache/bots.json
ONEBOT_PORT=
if [ -s "$BOTS_JSON" ]; then
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
mkdir -p /opt/AstrBot/data
# 判据是「非空且 JSON 能解析」，不是「文件存在」：写到一半被杀会留下半截 JSON，
# 文件在、但解析不了，AstrBot 启动就报错，而重跑时这里一直判为「已存在」永不修复。
# 写的时候先写 .tmp 再 mv（同目录 mv 是原子替换），不再制造半截文件。
if [ -s /opt/AstrBot/data/cmd_config.json ] \
   && python3 -c 'import json,sys;json.load(open(sys.argv[1]))' /opt/AstrBot/data/cmd_config.json 2>/dev/null; then
  echo "[sandbox] 已存在可用的 cmd_config.json，保持不变"
else
  # 只写 platform 段即可：AstrBot 启动时会自动补齐其余缺失键
  cat > /opt/AstrBot/data/cmd_config.json.tmp <<JSONEOF
{
  "platform": [
    {
      "id": "napcat",
      "type": "aiocqhttp",
      "enable": true,
      "ws_reverse_host": "0.0.0.0",
      "ws_reverse_port": $ONEBOT_PORT,
      "ws_reverse_token": ""
    }
  ]
}
JSONEOF
  mv -f /opt/AstrBot/data/cmd_config.json.tmp /opt/AstrBot/data/cmd_config.json
  echo "[sandbox] 已生成 /opt/AstrBot/data/cmd_config.json"
fi
echo "[sandbox] AstrBot 安装完成"
