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
# ---------------------------------------------------------------------------
# 心跳：依赖安装是本流程最久的一步（真机上 pip 要十几分钟），期间可能长达几分钟
# 一行输出都没有，用户看到「日志 N 分钟没有新内容」会以为卡死然后强杀。
# 每 45 秒打一行，既让界面知道还活着，也让部署日志有可读的时间线。
# ---------------------------------------------------------------------------
qq_hb() {
  _hbp=$1
  while kill -0 "$_hbp" 2>/dev/null; do
    sleep 45
    kill -0 "$_hbp" 2>/dev/null || break
    echo "[sandbox] 仍在安装依赖…（$(date +%H:%M:%S 2>/dev/null)）"
  done
}
qq_run_hb() {
  set +e
  "$@" &
  _p=$!
  qq_hb "$_p" &
  _hb=$!
  wait "$_p"
  _rc=$?
  kill "$_hb" 2>/dev/null || true
  wait "$_hb" 2>/dev/null || true
  set -e
  return $_rc
}

# ---------------------------------------------------------------------------
# 优先用 uv 装依赖。pip 在这类手机上要解析上百个包的依赖树，真机实测能卡十几分钟；
# uv 是单个静态二进制，解析 + 下载通常一分钟内解决（参考项目 AstrBot-Bubble-Android-App
# 用的也是 astral-sh/uv）。uv 拿不到或失败时自动退回 pip，不会因此装不上。
# ---------------------------------------------------------------------------
UV=/opt/astrbot-tools/uv
ensure_uv() {
  [ -x "$UV" ] && return 0
  # 路径一：直接从 PyPI 装。uv 自己在 PyPI 上有 manylinux 的 x86_64 / aarch64 wheel，
  # 走清华镜像几秒就能落地，完全不用碰 GitHub —— 真机上 GitHub 直连基本不可用，
  # release 镜像（ghfast / gh-proxy / ghproxy）又时好时坏，实测三个都失败过。
  echo "[sandbox] 从 PyPI 安装 uv"
  if ./.venv/bin/pip install --disable-pip-version-check -q -i "$PIPMIRROR" "${PIP_PROXY_ARGS[@]}" uv >/tmp/uv-pypi.log 2>&1; then
    if [ -x ./.venv/bin/uv ]; then
      echo "[sandbox] uv 已就绪：$(./.venv/bin/uv --version 2>/dev/null)"
      UV=./.venv/bin/uv
      return 0
    fi
  fi
  tail -n 4 /tmp/uv-pypi.log 2>/dev/null || true
  # 路径二：从 GitHub release 取静态二进制（PyPI 装不上时的兜底）。
  case "$(uname -m 2>/dev/null)" in
    aarch64|arm64) _uva=uv-aarch64-unknown-linux-gnu ;;
    x86_64|amd64)  _uva=uv-x86_64-unknown-linux-gnu ;;
    *) return 1 ;;
  esac
  mkdir -p /opt/astrbot-tools 2>/dev/null || true
  for _b in "https://ghfast.top/" "https://gh-proxy.com/" "https://ghproxy.net/" ""; do
    echo "[sandbox] 下载 uv（${_b:-直连}）"
    rm -f /tmp/uv.tar.gz
    if curl -fL --connect-timeout 15 --max-time 240 --retry 1 -o /tmp/uv.tar.gz \
         "${_b}https://github.com/astral-sh/uv/releases/latest/download/$_uva.tar.gz" 2>/dev/null; then
      rm -rf "/tmp/$_uva"
      if tar -xzf /tmp/uv.tar.gz -C /tmp 2>/dev/null && [ -x "/tmp/$_uva/uv" ]; then
        mv -f "/tmp/$_uva/uv" "$UV"
        chmod 755 "$UV"
        rm -rf "/tmp/$_uva" /tmp/uv.tar.gz
        UV=/opt/astrbot-tools/uv
        return 0
      fi
    fi
  done
  rm -f /tmp/uv.tar.gz 2>/dev/null || true
  return 1
}
echo "[sandbox] 安装 AstrBot 依赖（耗时较长，请耐心等待）"
DEPS_OK=0
if ensure_uv && "$UV" --version >/dev/null 2>&1; then
  echo "[sandbox] 使用 uv 安装（$("$UV" --version 2>/dev/null)）"
  # uv 走 HTTP(S)_PROXY 环境变量，代理已在上面 export；socks5 需要 uv 自己支持，失败就退回 pip。
  if qq_run_hb "$UV" pip install --python .venv/bin/python -r requirements.txt --index-url "$PIPMIRROR"; then
    DEPS_OK=1
  else
    echo "[sandbox] uv 安装失败，改用 pip 重试"
  fi
fi
if [ "$DEPS_OK" != "1" ]; then
  ./.venv/bin/python -m pip install --upgrade pip -i "$PIPMIRROR" "${PIP_PROXY_ARGS[@]}" || true
  if qq_run_hb ./.venv/bin/pip install -r requirements.txt -i "$PIPMIRROR" "${PIP_PROXY_ARGS[@]}"; then
    DEPS_OK=1
  else
    echo "[sandbox] 镜像安装失败，改用官方 PyPI 重试"
    qq_run_hb ./.venv/bin/pip install -r requirements.txt "${PIP_PROXY_ARGS[@]}" || { echo "[sandbox] AstrBot 依赖安装失败"; exit 1; }
    DEPS_OK=1
  fi
fi
echo "[sandbox] AstrBot 依赖安装完成"

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
