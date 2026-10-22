#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 存储统计 / 缓存清理（功能 54，协议见 docs/接口约定-1.1.30.md 第 7 节）。
#
# 用法: storage.sh stats|clean
#   stats  → 写 $QQBOT_HOME/cache/storage.json：
#            {"ts":epoch,"total":N,"dirs":[{"key","name","path","bytes"}...],
#             "top":[{"path","bytes"}...最多 10 条]}
#            并在 stderr 打一行中文摘要
#   clean  → 只清缓存类文件（apt/pip/npm 缓存、/root/.cache、proot 临时目录、旧日志），
#            stdout 输出一行 FREED=<bytes>，绝不动登录数据 / 配置 / 插件 / 数据库
#
# total 的算法：rootfs 本身（它已经包含 AstrBot、插件、登录数据）+ 日志。
# 五个 dirs 是「分类展示」，加起来会大于 total（插件在 AstrBot 里、AstrBot 在容器里），
# 这里按真实磁盘占用算 total，避免重复计入让界面数字虚高一倍。

. "$PREFIX/opt/qqbot/common.sh" 2>/dev/null || { echo "ERR=common.sh 加载失败"; exit 1; }

OUT="$QQBOT_CACHE/storage.json"

say() { printf '[storage] %s\n' "$*" >&2; }

# 字节数转成人看的字符串（只用于摘要，精确值一律走 JSON）
human() {
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1073741824 ]; then
    printf '%s.%s GB' $((b / 1073741824)) $((b % 1073741824 * 10 / 1073741824))
  elif [ "$b" -ge 1048576 ]; then
    printf '%s.%s MB' $((b / 1048576)) $((b % 1048576 * 10 / 1048576))
  elif [ "$b" -ge 1024 ]; then
    printf '%s KB' $((b / 1024))
  else
    printf '%s B' "$b"
  fi
}

# 最大的 10 个文件。只扫「用户数据」那几个目录：整个 rootfs 动辄十几万文件，
# 在手机上 du -a 一遍要几十秒，而用户真正关心的就是 AstrBot / 插件 / 登录数据。
top_json() {
  local adir="$1" base sz pth n=0 sep=""
  {
    for base in "$adir" "$QQBOT_CACHE/logs" "$QQBOT_ROOTFS/root/qqinst" \
                "$QQBOT_ROOTFS/opt/qqbot-inst" "$QQBOT_CACHE/crashes"; do
      [ -d "$base" ] || continue
      if [ "${QQ_DU_B:-1}" = "1" ]; then
        du -ab "$base" 2>/dev/null
      else
        du -ak "$base" 2>/dev/null | while IFS=$'\t' read -r _s _p; do
          printf '%s\t%s\n' $((_s * 1024)) "$_p"
        done
      fi
    done
  } | while IFS=$'\t' read -r sz pth; do
        case "$sz" in ''|*[!0-9]*) continue ;; esac
        [ -f "$pth" ] || continue
        printf '%s\t%s\n' "$sz" "$pth"
      done | sort -rn | head -n 10 | while IFS=$'\t' read -r sz pth; do
        printf '%s{"path":"%s","bytes":%s}' "$sep" "$(qq_json_esc "$pth")" "$sz"
        sep=","
      done
}

cmd_stats() {
  local ts adir r a p l q total tmp
  mkdir -p "$QQBOT_CACHE" 2>/dev/null
  ts=$(date +%s)
  adir="$(qq_astrbot_data_dir)"
  r=$(qq_du_bytes "$QQBOT_ROOTFS")
  a=$(qq_du_bytes "$adir")
  p=$(qq_du_bytes "$adir/plugins")
  l=$(qq_du_bytes "$QQBOT_CACHE/logs")
  q=$(qq_du_bytes "$QQBOT_ROOTFS/root/qqinst")
  total=$((r + l))

  tmp="$OUT.$$"
  {
    printf '{"ts":%s,"total":%s,"dirs":[' "$ts" "$total"
    printf '{"key":"rootfs","name":"容器","path":"%s","bytes":%s},' "$(qq_json_esc "$QQBOT_ROOTFS")" "$r"
    printf '{"key":"astrbot","name":"AstrBot","path":"%s","bytes":%s},' "$(qq_json_esc "$adir")" "$a"
    printf '{"key":"plugins","name":"插件","path":"%s","bytes":%s},' "$(qq_json_esc "$adir/plugins")" "$p"
    printf '{"key":"logs","name":"日志","path":"%s","bytes":%s},' "$(qq_json_esc "$QQBOT_CACHE/logs")" "$l"
    printf '{"key":"qq","name":"登录数据","path":"%s","bytes":%s}' "$(qq_json_esc "$QQBOT_ROOTFS/root/qqinst")" "$q"
    printf '],"top":['
    top_json "$adir"
    printf ']}\n'
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$OUT" 2>/dev/null
  rm -f "$tmp" 2>/dev/null

  if [ -s "$OUT" ]; then
    say "统计完成：总计 $(human "$total")（容器 $(human "$r") / AstrBot $(human "$a") / 插件 $(human "$p") / 日志 $(human "$l") / 登录数据 $(human "$q")）"
    return 0
  fi
  say "统计失败：写不出 $OUT"
  return 1
}

cmd_clean() {
  local adir before after freed x f pid
  adir="$(qq_astrbot_data_dir)"

  # 清理目标（before/after 用同一份清单算差值，保证 FREED 可信）
  set -- "$QQBOT_ROOTFS/var/cache/apt/archives" \
         "$QQBOT_ROOTFS/root/.cache" \
         "$QQBOT_ROOTFS/root/.npm/_cacache" \
         "$QQBOT_CACHE/logs" \
         "$adir/logs"
  before=$(qq_du_bytes "$@")

  # 1) apt 下载下来的 .deb（装完就没用了）
  rm -f "$QQBOT_ROOTFS/var/cache/apt/archives/"*.deb 2>/dev/null
  # 2) pip / npm 的下载缓存
  rm -rf "$QQBOT_ROOTFS/root/.cache/pip" "$QQBOT_ROOTFS/root/.npm/_cacache" 2>/dev/null
  # 3) /root/.cache 下剩下的（uv / huggingface 之类，全是缓存，删了只会让下次装慢一点）
  if [ -d "$QQBOT_ROOTFS/root/.cache" ]; then
    for x in "$QQBOT_ROOTFS/root/.cache"/*; do
      [ -e "$x" ] || continue
      rm -rf "$x" 2>/dev/null
    done
  fi
  # 4) 轮转出来的旧日志（trim_logs 会原地 Truncate，这里清 .log.1 这类历史遗留）
  rm -f "$QQBOT_CACHE/logs/"*.log.1 2>/dev/null
  # 5) AstrBot 自己的日志：保留最新 1 个，其余删掉
  if [ -d "$adir/logs" ]; then
    ls -1t "$adir/logs"/*.log 2>/dev/null | tail -n +2 | while IFS= read -r f; do
      [ -n "$f" ] && rm -f "$f" 2>/dev/null
    done
  fi
  # 6) proot 临时目录：沙盒在跑的时候绝对不能动（那是活着的 proot 的临时文件，
  #    删掉会让正在运行的容器出奇怪的错），只有确认没在跑时才清。
  pid="$(cat "$QQBOT_HOME/supervisor.pid" 2>/dev/null)"
  if [ -z "$pid" ] || [ ! -d "/proc/$pid" ]; then
    for x in "$QQBOT_PTMP"/*; do
      [ -e "$x" ] || continue
      rm -rf "$x" 2>/dev/null
    done
  else
    say "沙盒正在运行（supervisor pid $pid），跳过 proot 临时目录"
  fi

  after=$(qq_du_bytes "$@")
  freed=$((before - after))
  [ "$freed" -ge 0 ] 2>/dev/null || freed=0
  say "清理完成：释放 $(human "$freed")（登录数据 / 配置 / 插件 / 数据库一律没动）"
  printf 'FREED=%s\n' "$freed"
  return 0
}

case "${1:-}" in
  stats) cmd_stats ;;
  clean) cmd_clean ;;
  *) printf '用法: storage.sh stats|clean\n' >&2; exit 2 ;;
esac
