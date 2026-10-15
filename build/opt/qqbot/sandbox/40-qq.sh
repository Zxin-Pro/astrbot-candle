#!/bin/bash
# 在 Ubuntu 沙盒内解包安装 Linux QQ
set -e

# 选包：优先用宿主传进来的精确包名（steps/40-qq.sh 会把 QQBOT_QQ_DEB_NAME 当参数传进来）。
# 缓存里可能同时躺着 amd64 与 arm64 两个包，绝不能只按 ls 顺序取第一个 —— 拿错架构
# 的包装进沙盒，QQ 起不来还是 SIGILL，排查起来很费劲。
DEB=""
if [ -n "${1:-}" ] && [ -s "/qqbot-cache/$1" ]; then
  DEB="/qqbot-cache/$1"
fi
if [ -z "$DEB" ]; then
  # 回退：按本机架构 glob，并核对包名里的架构后缀
  for f in /qqbot-cache/linuxqq_*.deb; do
    [ -s "$f" ] || continue
    case "$f" in
      *"_${QQBOT_ARCH}.deb") DEB="$f"; break ;;
    esac
  done
fi
[ -s "$DEB" ] || { echo "[sandbox] 缺少 $QQBOT_ARCH 架构的 QQ 安装包（/qqbot-cache/linuxqq_*.deb）"; exit 1; }

echo "[sandbox] 解包 $DEB"
rm -rf /tmp/qqstage
mkdir -p /tmp/qqstage
# 不能用 dpkg-deb -x：它内部调用 GNU tar，而 proot 5.5.0 对「带目录 fd 的 *at
# 系统调用」（mkdirat / fchmodat）翻译不正确，tar 会报
#   tar: ./usr/share: Cannot mkdir: No such file or directory
# 并漏掉整个目录。改用 Python 的 tarfile（实测 1586 个条目 0 失败）。
python3 /qqbot-opt/sandbox/extract.py deb "$DEB" /tmp/qqstage

if [ ! -x /tmp/qqstage/opt/QQ/qq ]; then
  echo "[sandbox] 解包结果异常，缺少 opt/QQ/qq"
  ls -la /tmp/qqstage/opt/QQ 2>/dev/null | head -n 40
  exit 1
fi

echo "[sandbox] 安装到 /opt/QQ"
rm -rf /opt/QQ
mkdir -p /opt
mv /tmp/qqstage/opt/QQ /opt/QQ
rm -rf /tmp/qqstage

VER=$(python3 -c "import json;print(json.load(open('/opt/QQ/resources/app/package.json'))['version'])")
echo "[sandbox] QQ 版本：$VER"

mkdir -p /root/.config/QQ
if [ -f /root/.config/QQ/versions/config.json ]; then
  python3 - "$VER" <<'PY'
import json, sys
ver = sys.argv[1]
build = ver.rsplit('-', 1)[-1]
p = '/root/.config/QQ/versions/config.json'
d = json.load(open(p, encoding='utf-8'))
d['baseVersion'] = ver
d['curVersion'] = ver
d['buildId'] = build
json.dump(d, open(p, 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('[sandbox] 已更新 QQ 版本配置')
PY
fi

echo "[sandbox] Linux QQ 安装完成"
