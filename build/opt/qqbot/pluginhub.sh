#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 插件中心用的文件操作脚本（1.3.5-beta.2）。
#
# 为什么单独写个脚本：插件市场在设备上要做的只有「列目录 / git clone / 删目录 / 把坏插件挪开」
# 这几件事，全是文件操作，不需要 python3（Termux 侧本来也没有 python3，JSON 一律由 Java 桥解析）。
# 放进脚本的好处是用户在终端里能照着跑一遍，出问题时不用连 IDE 猜。
#
# 用法：
#   pluginhub.sh list                 列插件目录（一行 JSON）
#   pluginhub.sh clone <id> <repo>    下载插件到 data/plugins/<id>，过程中打 [NN] 进度行
#   pluginhub.sh remove <id>          删插件目录与它的配置文件
#   pluginhub.sh hide <id>            把插件目录挪进 .disabled/（应急用）
#   pluginhub.sh show <id>            从 .disabled/ 挪回插件目录
#
# 关于「启用/停用」：真正的开关不在目录名上，而在 data/shared_preferences.json 的
# inactivated_plugins 列表里（见 AstrBot star_manager.py 的 turn_off_plugin/turn_on_plugin）。
# 只改目录名 AstrBot 照样会 import 它，所以桥里优先走面板接口、其次改 shared_preferences.json；
# 这里的 hide/show 只在上面两条路都走不通、需要先把这个插件挪出插件目录时用。
#
# 失败一律以一行 JSON 收尾（{"ok":false,"err":"..."}）并 exit 0，不拿非零退出码去炸调用方。

# common.sh 只用来拿 $QQBOT_* 变量；老版本 APK 里可能没有它，所以缺了也照样干活。
. "${PREFIX:-/data/data/zhuxin.astrbot/files/usr}/opt/qqbot/common.sh" 2>/dev/null || true

PH_HOME="${HOME:-/data/data/zhuxin.astrbot/files/home}"
PH_ROOT="$PH_HOME/ubuntu/opt/AstrBot"
PH_DATA="$PH_ROOT/data"
PH_PLUGINS="$PH_DATA/plugins"
PH_CONFIG="$PH_DATA/config"
PH_HIDE="$PH_PLUGINS/.disabled"

# JSON 字符串转义。控制字符直接删（进度行都是单行，用不上 \n）。
ph_esc() {
  printf '%s' "${1:-}" \
    | tr -d '\000-\010\013-\037\177' \
    | tr '\t' ' ' \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# 失败出口：给 Java 侧一句人话，退出码仍是 0。
ph_fail() {
  printf '{"ok":false,"err":"%s"}\n' "$(ph_esc "${1:-失败}")"
  exit 0
}

# 插件 id 只能是目录名，带 / 或 .. 的一律不信，别让它拼出任意路径。
ph_valid() {
  case "${1:-}" in
    ''|.|..) return 1 ;;
    *[!A-Za-z0-9_.-]*) return 1 ;;
  esac
  return 0
}

ph_list() {
  printf '{"ok":true,"dir":"%s","items":[' "$(ph_esc "$PH_PLUGINS")"
  first=1
  for d in "$PH_PLUGINS"/*; do
    [ -d "$d" ] || continue
    id="${d##*/}"
    case "$id" in .*) continue ;; esac
    hasconf=false
    [ -f "$d/_conf_schema.json" ] && hasconf=true
    [ "$first" -eq 1 ] || printf ','
    first=0
    printf '{"id":"%s","hasConf":%s}' "$(ph_esc "$id")" "$hasconf"
  done
  printf ']}\n'
}

# 一个仓库地址可能要试好几个源：直连 github 在部分网络下会卡住，
# 所以按 common.sh 里的镜像表（QQBOT_GH_MIRRORS）依次重试，全部失败才报错。
ph_sources() {
  printf '%s\n' "$1"
  for m in ${QQBOT_GH_MIRRORS:-https://ghfast.top/ https://gh-proxy.com/}; do
    case "$m" in
      */) printf '%s\n' "$m$1" ;;
      *) printf '%s\n' "$m/$1" ;;
    esac
  done
}

# git 自己不会超时：网络半通时它会一直挂着。有 timeout 就套一层（Termux 的 coreutils 自带）。
ph_git() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 300 git "$@"
  else
    git "$@"
  fi
}

ph_clone() {
  id="${1:-}"
  repo="${2:-}"
  ph_valid "$id" || ph_fail "插件名不合法"
  case "$repo" in
    https://*|http://*|git@*) ;;
    *) ph_fail "下载地址不对劲：$repo" ;;
  esac
  command -v git >/dev/null 2>&1 || ph_fail "这台手机的 Termux 里没有 git，先在终端执行 pkg install git 再来"
  mkdir -p "$PH_PLUGINS" || ph_fail "插件目录建不出来"

  tmp="$PH_PLUGINS/.plugin-install-$id"
  echo "[5] 准备下载 $id"
  set -o pipefail
  ok=0
  n=0
  for src in $(ph_sources "$repo"); do
    n=$((n + 1))
    rm -rf "$tmp"
    if [ "$n" -eq 1 ]; then echo "[12] 正在连接下载源"; else echo "[12] 换一个下载源再试（第 $n 个）"; fi
    # --progress 是为了让 git 在管道里也吐百分比（默认只有 tty 才吐）；
    # tr '\r' '\n' 把进度行里的回车拆开，sed 只取百分数。
    ph_git -c core.progress=true clone --progress --depth 1 "$src" "$tmp" 2>&1 \
      | tr '\r' '\n' \
      | while IFS= read -r line; do
          case "$line" in
            *"Receiving objects:"*'%')
              p=$(printf '%s' "$line" | sed -n 's/.*Receiving objects: *\([0-9][0-9]*\)%.*/\1/p')
              if [ -n "$p" ]; then echo "[$((20 + p * 6 / 10))] 正在下载（${p}%）"; fi
              ;;
            *"Resolving deltas:"*'%')
              p=$(printf '%s' "$line" | sed -n 's/.*Resolving deltas: *\([0-9][0-9]*\)%.*/\1/p')
              if [ -n "$p" ]; then echo "[$((80 + p / 5))] 正在解包（${p}%）"; fi
              ;;
            *fatal:*|*error:*)
              echo "[!] $(printf '%s' "$line" | cut -c1-180)"
              ;;
          esac
        done
    rc=${PIPESTATUS[0]}
    if [ "$rc" -eq 0 ] && [ -f "$tmp/main.py" ]; then ok=1; break; fi
    if [ "$rc" -eq 0 ] && [ -f "$tmp/$id.py" ]; then ok=1; break; fi
    echo "[!] 这个源没成（退出码 $rc），换下一个"
    rm -rf "$tmp"
  done

  if [ "$ok" -ne 1 ]; then
    rm -rf "$tmp"
    ph_fail "下载失败：几个下载源都试过了。可能是网络不通，或者这个仓库已经改名、删掉了"
  fi
  if [ ! -f "$tmp/main.py" ] && [ ! -f "$tmp/$id.py" ]; then
    rm -rf "$tmp"
    ph_fail "下载下来了，但里面没有 main.py，不像是 AstrBot 插件"
  fi

  echo "[95] 正在放进插件目录"
  rm -rf "${PH_PLUGINS:?}/$id"
  mv "$tmp" "$PH_PLUGINS/$id" || { rm -rf "$tmp"; ph_fail "移动到 plugins 目录失败"; }
  echo "[100] 装好了，点重载让它生效"
  printf '{"ok":true,"id":"%s"}\n' "$(ph_esc "$id")"
}

ph_remove() {
  id="${1:-}"
  ph_valid "$id" || ph_fail "插件名不合法"
  found=0
  if [ -d "$PH_PLUGINS/$id" ]; then rm -rf "${PH_PLUGINS:?}/$id"; found=1; fi
  if [ -d "$PH_HIDE/$id" ]; then rm -rf "${PH_HIDE:?}/$id"; found=1; fi
  if [ -f "$PH_CONFIG/${id}_config.json" ]; then rm -f "$PH_CONFIG/${id}_config.json"; found=1; fi
  [ "$found" -eq 1 ] || ph_fail "没找到这个插件，可能已经删掉了"
  echo "[100] 已删除 $id"
  printf '{"ok":true,"id":"%s"}\n' "$(ph_esc "$id")"
}

ph_hide() {
  id="${1:-}"
  ph_valid "$id" || ph_fail "插件名不合法"
  [ -d "$PH_PLUGINS/$id" ] || ph_fail "插件目录不在，没东西可挪"
  mkdir -p "$PH_HIDE" || ph_fail "建不出 .disabled 目录"
  rm -rf "${PH_HIDE:?}/$id"
  mv "$PH_PLUGINS/$id" "$PH_HIDE/$id" || ph_fail "挪不动，可能有文件被占用"
  printf '{"ok":true,"id":"%s"}\n' "$(ph_esc "$id")"
}

ph_show() {
  id="${1:-}"
  ph_valid "$id" || ph_fail "插件名不合法"
  [ -d "$PH_HIDE/$id" ] || ph_fail "没有挪开过的这个插件"
  rm -rf "${PH_PLUGINS:?}/$id"
  mv "$PH_HIDE/$id" "$PH_PLUGINS/$id" || ph_fail "挪不回来，可能有文件被占用"
  printf '{"ok":true,"id":"%s"}\n' "$(ph_esc "$id")"
}

case "${1:-}" in
  list) ph_list ;;
  clone) shift; ph_clone "${1:-}" "${2:-}" ;;
  remove) shift; ph_remove "${1:-}" ;;
  hide) shift; ph_hide "${1:-}" ;;
  show) shift; ph_show "${1:-}" ;;
  *)
    ph_fail "用法：pluginhub.sh list | clone <id> <repo> | remove <id> | hide <id> | show <id>"
    ;;
esac
