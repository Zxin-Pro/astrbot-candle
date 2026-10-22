#!/data/data/zhuxin.astrbot/files/usr/bin/bash
# 打包一次失败现场并发给作者（由用户在界面上点「上传错误日志」才会执行）。
#
# 为什么要有这个：真机上的失败原因往往只在用户手机里能看到，截图只能看到一部分，
# 而「第 2 步 / 第 3 步失败」在模拟器上又复现不出来。把诊断、日志尾部、机型信息
# 打包上传，作者就能拿到完整现场去改代码。
#
# 隐私：只发版本 / 机型、部署与运行日志；上传前整份脱敏并做密钥自检。
#       不收集任何聊天内容（本应用也拿不到 QQ 的消息）。
# 用法：upload-log.sh [版本号]
. "$PREFIX/opt/qqbot/common.sh"

# 上传目的地：GitHub 私有仓库 Zxin-Pro/astrbot-errlogs 的 reports/ 目录。
# 为什么不再走自建站点：站点与 VPS 都靠一台机器撑着（2026-10-22 整机失联过一次），
# 而 GitHub 是外部依赖里最稳的那个，仓库还能直接翻历史版本对比。
# 认证用一把「只能读写这一个仓库」的细粒度令牌，由构建时注入到
# $PREFIX/opt/qqbot/errdump-token.txt（不在源码里、不在 git 里）。
API="https://api.github.com/repos/Zxin-Pro/astrbot-errlogs/contents"
DIR="reports"
TOKF="$PREFIX/opt/qqbot/errdump-token.txt"
VER="${1:-unknown}"
TMP="$QQBOT_CACHE/errreport.txt"
OUT="$QQBOT_CACHE/errreport.out"
RID="$(date +%Y%m%d-%H%M%S 2>/dev/null)-$RANDOM"

have() { command -v "$1" >/dev/null 2>&1; }

# ---- 1) 现场快照（1.2.11 起只留排障必需的段落）----
{
  echo "===== AstrBot 烛版 错误报告 ====="
  echo "report_id : $RID"
  echo "app_ver   : $VER"
  echo "time      : $(date "+%F %T %Z" 2>/dev/null)"
  # 机型只留型号 / 品牌；abi、内核、HOME、PREFIX、磁盘与 ulimit 这些设备指纹
  # 一律不进报告 —— 排障看的是下面几段日志，不是这台手机长什么样。
  echo "model     : $(getprop ro.product.model 2>/dev/null) / $(getprop ro.product.brand 2>/dev/null)"
  echo "android   : $(getprop ro.build.version.release 2>/dev/null) (sdk $(getprop ro.build.version.sdk 2>/dev/null))"
  echo
  echo "----- 部署步骤标记 -----"
  ls -1 "$QQBOT_STEPS" 2>/dev/null
  echo "deploy.done: $([ -f "$QQBOT_HOME/deploy.done" ] && echo yes || echo no)"
  echo
  echo "----- 部署日志尾部 300 行 (deploy.log) -----"
  tail -n 300 "$QQBOT_HOME/logs/deploy.log" 2>/dev/null || echo "(无)"
  echo
  echo "----- 服务日志尾部 60 行 (supervisor.log) -----"
  tail -n 60 "$QQBOT_HOME/logs/supervisor.log" 2>/dev/null || echo "(无)"
  echo
  # NapCat（QQ/Electron）的日志：这里才有 "Missing X server"、GLX 失败、二维码之类
  # 真正说明问题的行。以前只有部署日志，NapCat 起不来的报告里什么线索都没有。
  echo "----- NapCat 实例日志尾部 120 行 -----"
  for f in "$QQBOT_CACHE"/logs/qq-*.log "$QQBOT_CACHE"/logs/qq.log; do
    [ -f "$f" ] || continue
    echo "### $f（$(wc -c < "$f" 2>/dev/null) 字节）"
    tail -n 120 "$f" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g'
    echo
  done
  echo "----- 崩溃现场（crashwatch 抓的）-----"
  _found_crash=0
  for f in "$QQBOT_CACHE"/crashes/crash-*.log; do
    [ -f "$f" ] || continue
    _found_crash=1
    _csz=$(wc -c < "$f" 2>/dev/null || echo 0)
    echo "### $f（${_csz} 字节，修改于 $(date -r "$f" "+%F %T" 2>/dev/null)）"
    # maps 很长但正是定位的关键：崩溃地址落在哪个段、那段是哪个 .so，全靠它。
    if [ "${_csz:-0}" -eq 0 ]; then
      echo "(空文件)"
    elif [ "${_csz:-0}" -gt 204800 ]; then
      sed -n '1,160p' "$f" 2>/dev/null
      echo "……（余下省略，全文共 $(wc -l < "$f" 2>/dev/null) 行 / ${_csz} 字节）"
    else
      cat "$f" 2>/dev/null
    fi
    echo
  done
  [ "$_found_crash" = 1 ] || echo "(没有崩溃记录：可能是正常退出，或这版还没带 crashwatch)"
  echo "----- 组件关键文件是否在位 -----"
  _APP="$QQBOT_ROOTFS/opt/QQ/resources/app"
  for p in "$_APP/app_launcher/napcat/napcat.mjs" \
           "$_APP/app_launcher/napcat/package.json" \
           "$_APP/loadNapCat.js" \
           "$_APP/package.json"; do
    if [ -f "$p" ]; then
      echo "有  $p（$(wc -c < "$p" 2>/dev/null) 字节）"
    else
      echo "缺  $p"
    fi
  done
  echo "--- app_launcher/napcat 目录内容 ---"
  ls -1 "$_APP/app_launcher/napcat" 2>/dev/null | head -n 40 || echo "(目录不存在)"
  echo "--- app/package.json 的 main 字段 ---"
  grep -o '"main"[[:space:]]*:[[:space:]]*"[^"]*"' "$_APP/package.json" 2>/dev/null || echo "(读不到)"
  echo
  echo "----- 沙盒自检结果 (doctor.txt) -----"
  cat "$QQBOT_HOME/doctor.txt" 2>/dev/null || echo "(还没跑过「检查运行环境」)"
} > "$TMP" 2>&1

# ---- 1.4) 上报前脱敏自检（v1.2.11）----
# qq_redact_file 的规则表在 1.5 MB 截断之后才跑；这一道放在截断之前，专门补三类
# 「一眼就是凭据」的形态：sk- 密钥、github_pat_ 令牌、token= / password= 直接跟值。
# 为什么必须在截断前：截断点可能正好落在一串密钥中间，先截断就只剩半截，规则再也
# 认不出来；先扫一遍也能把整份报告里的命中数算全（两边的数字最后合并显示）。
#
# 只用 ASCII 模式：toybox 的 grep -a 对多字节 pattern 支持不稳（同一条中文 pattern
# 在 GNU grep 有结果、在手机上一条不出），所以这里不写任何中文 pattern；某条规则
# 跑失败只当作「没命中」，绝不中断上传。
N_SELF=0

# 就地打码：优先「写临时文件再 cat 回原路径」（与 common.sh 的 qq_redact_file 同一套
# 做法 —— 个别机型 sed -i 会改写 inode 与权限位，报告随后可能被别的进程读不到）；
# 临时文件写不出来再退回 sed -i。
qq_selfcheck_mask() {
  _qm_expr="${1:-}"
  _qm_file="${2:-}"
  [ -n "$_qm_expr" ] && [ -f "$_qm_file" ] || return 1
  if sed -E "$_qm_expr" "$_qm_file" > "$_qm_file.mask" 2>/dev/null \
     && cat "$_qm_file.mask" > "$_qm_file" 2>/dev/null; then
    rm -f "$_qm_file.mask" 2>/dev/null
    return 0
  fi
  rm -f "$_qm_file.mask" 2>/dev/null
  if sed -i -E "$_qm_expr" "$_qm_file" 2>/dev/null; then
    return 0
  fi
  return 1
}

# 一条规则：grep -a 数命中条数（-o 一条一行），有命中就地打码，成功才回显条数。
qq_selfcheck_one() {
  _qo_file="${1:-}"
  _qo_pat="${2:-}"
  _qo_expr="${3:-}"
  _qo_n=$(grep -a -o -E -e "$_qo_pat" "$_qo_file" 2>/dev/null | wc -l 2>/dev/null | tr -d ' \n')
  case "${_qo_n:-}" in ''|*[!0-9]*) _qo_n=0 ;; esac
  [ "$_qo_n" -gt 0 ] || { printf '0'; return 0; }
  if qq_selfcheck_mask "$_qo_expr" "$_qo_file"; then
    printf '%s' "$_qo_n"
  else
    printf '0'
  fi
  return 0
}

# 自检入口：扫描并打码，最后把命中总数打到 stdout（与 qq_redact_file 同一种返回）。
# 单独跑：把本段抽出来 · qq_redact_selfcheck /path/to/report
qq_redact_selfcheck() {
  _qs_file="${1:-}"
  _qs_total=0
  [ -n "$_qs_file" ] && [ -f "$_qs_file" ] || { printf '0'; return 1; }
  # sk- 开头的 API key（20 位以上才认定是密钥，避免误伤 sk-xxx 这种短词）
  _qs_n=$(qq_selfcheck_one "$_qs_file" 'sk-[A-Za-z0-9]{20,}' 's/sk-[A-Za-z0-9]{20,}/sk-***/g')
  _qs_total=$((_qs_total + _qs_n))
  # GitHub fine-grained PAT
  _qs_n=$(qq_selfcheck_one "$_qs_file" 'github_pat_[A-Za-z0-9_]+' 's/github_pat_[A-Za-z0-9_]+/github_pat_***/g')
  _qs_total=$((_qs_total + _qs_n))
  # token= / password= 后面直接跟值（大小写都算）
  _qs_n=$(qq_selfcheck_one "$_qs_file" '([Tt][Oo][Kk][Ee][Nn]|[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd])=[^[:space:]]+' 's/([Tt][Oo][Kk][Ee][Nn]|[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd])=[^[:space:]]+/\1=***/g')
  _qs_total=$((_qs_total + _qs_n))
  printf '%s' "$_qs_total"
  return 0
}

N_SELF=$(qq_redact_selfcheck "$TMP" 2>/dev/null)
case "${N_SELF:-0}" in ''|*[!0-9]*) N_SELF=0 ;; esac

SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
# 上限 1.5 MB：正常报告 50~200 KB，超了说明某个日志爆了，截断尾部保留。
if [ "${SZ:-0}" -gt 1572864 ]; then
  tail -c 1572864 "$TMP" > "$TMP.2" 2>/dev/null && mv -f "$TMP.2" "$TMP"
  SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)
fi

# ---- 1.5) 整份脱敏（v1.1.31）----
# 规则表覆盖的是账号 / 群号 / 手机号 / 邮箱这类成片出现的东西，仍然放在截断之后：
# 先脱敏再截断的话，被切掉那一段里的命中数统计不到，而且截断点可能正好落在一串
# 被打码的字符中间。（1.2.11 的自检段是另一回事：几类固定密钥形态必须赶在截断前
# 认出完整的串，见上面 1.4。）
# 端口、文件大小、时间戳、版本号都不在规则里，原样保留 —— 排查全靠它们。
N_RED=0
if type qq_redact_file >/dev/null 2>&1; then
  N_RED=$(qq_redact_file "$TMP" 2>/dev/null)
fi
case "${N_RED:-0}" in ''|*[!0-9]*) N_RED=0 ;; esac
TOT_RED=$((N_RED + N_SELF))
SZ=$(wc -c < "$TMP" 2>/dev/null || echo 0)

if ! have curl; then
  echo "上传失败：这台手机的 Termux 里没有 curl（第 1 步没装完？）"
  exit 2
fi

# ---- 2) 上传 ----
# 令牌由构建时写进 $PREFIX/opt/qqbot/errdump-token.txt，只对这个仓库有 Contents 读写权。
# 没有令牌就直接说清楚，不要拿 401 让用户猜。
TOK=""
if [ -f "$TOKF" ]; then
  # 构建时写的是「无 BOM 的一行」，但这个文件可以被用户手改：Windows 记事本另存会
  # 加 UTF-8 BOM（EF BB BF），而 tr -d 只删空格 / CR / LF / TAB，删不掉这三个字节，
  # 带着 BOM 的 Authorization 头 GitHub 只回 401。先看头三字节，是 BOM 就从第 4 字节读。
  _tok_bom=$(printf '\357\273\277')
  _tok_head=$(head -c 3 "$TOKF" 2>/dev/null)
  if [ "$_tok_head" = "$_tok_bom" ]; then
    TOK=$(dd if="$TOKF" bs=1 skip=3 2>/dev/null | tr -d ' \r\n\t')
  else
    TOK=$(tr -d ' \r\n\t' < "$TOKF" 2>/dev/null)
  fi
fi
if [ -z "$TOK" ]; then
  echo "上传失败：这台手机上没有报告令牌（$TOKF 不存在或是空的）。"
  echo "本机已经把报告存在 $TMP，装一版带令牌的包之后再点一次「上传错误日志」。"
  exit 3
fi

# 版本号要进文件名和 commit message，先掐掉可能捣乱的字符。
VER=$(printf '%s' "$VER" | tr -c 'A-Za-z0-9._+-' '_' 2>/dev/null)
[ -z "$VER" ] && VER=unknown

# base64：GNU coreutils 用 -w0，busybox 不认 -w，退到 openssl，再退到 tr 去换行。
B64=$(base64 -w0 "$TMP" 2>/dev/null)
if [ -z "$B64" ] && have openssl; then
  B64=$(openssl base64 -A -in "$TMP" 2>/dev/null)
fi
if [ -z "$B64" ]; then
  B64=$(base64 "$TMP" 2>/dev/null | tr -d '\r\n')
fi
# 拼进 JSON 的 base64 必须是干净的一行：三条路线里有的会带换行 / CR / 空格。
B64=$(printf '%s' "$B64" | tr -d ' \r\n\t')
if [ -z "$B64" ]; then
  echo "上传失败：这台手机的 base64 用不了，报告没法编码。"
  exit 4
fi

# 请求体可以到 2 MB，绝不能塞命令行参数（argv 上限会炸），先落盘再 --data-binary @。
BODY="$QQBOT_CACHE/errreport.req.json"
{
  printf '{"message":"report %s %s","branch":"main","content":"' "$RID" "$VER"
  printf '%s' "$B64"
  printf '"}\n'
} > "$BODY" 2>/dev/null
if [ ! -s "$BODY" ]; then
  echo "上传失败：请求体没写出来（$BODY），磁盘是不是满了？"
  exit 5
fi

# 45 秒必须短于 Java 侧 55 秒超时（QqbotBridge.uploadLog 传给 runShell 的 55），
# 否则进程会被上层先杀掉，只剩半截输出，用户看到的是「上传没有回音」。
CODE=$(curl -sS --max-time 45 -o "$OUT" -w "%{http_code}" \
  -X PUT \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer $TOK" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @"$BODY" \
  "$API/$DIR/$RID-$VER.txt" 2>"$QQBOT_CACHE/errreport.err")
CURLRC=$?
rm -f "$BODY" 2>/dev/null

if [ "$CODE" = "200" ] || [ "$CODE" = "201" ]; then
  echo "已上传，编号 $RID（仓库里的文件名 $RID-$VER.txt）"
  if [ "$TOT_RED" -gt 0 ]; then
    if [ "$N_SELF" -gt 0 ]; then
      echo "报告已脱敏 $TOT_RED 处（含二次自检 $N_SELF 处；账号 / 群号 / 手机号 / 邮箱 / 令牌 / 二维码链接）。"
    else
      echo "报告已脱敏 $N_RED 处（账号 / 群号 / 手机号 / 邮箱 / 令牌 / 二维码链接）。"
    fi
  else
    echo "报告里没有发现需要脱敏的内容。"
  fi
  echo "作者会很感谢你 —— 报告里带了机型、系统版本和部署日志尾部。"
  exit 0
fi
CID=$(cat "$QQBOT_CACHE/errreport.err" 2>/dev/null | head -n 1)
echo "上传失败：（GitHub 返回 HTTP $CODE，curl 退出码 $CURLRC）${CID}"
# 400 = 请求体不合法；401/403 = 令牌失效或权限不够；404 = 仓库名/分支不对；
# 409 = 同名文件冲突；422 = 同名的报告已经在了（换个编号即可）。
case "$CODE" in
  000|"") echo "没连上 GitHub（curl 退出码 $CURLRC：28 是超时，6/7 是 DNS 或连接失败），先确认手机能上外网。" ;;
  400) echo "请求体被 GitHub 拒了（400）：报告内容或 base64 编码不合法，可以手动把 $TMP 传上去。" ;;
  401|403) echo "令牌无效或权限不够（只剩 Contents 读写也不行时，请重新生成一把细粒度令牌）。" ;;
  404) echo "仓库或分支找不到：检查 $API 与 branch=main。" ;;
  409) echo "同名报告已经存在（409），等一秒再点一次「上传错误日志」，编号会变。" ;;
  413) echo "报告太大了，GitHub 不收。" ;;
  422) echo "GitHub 说这次提交不合法（422）：多半是同名文件撞车，等一秒再点一次会换新编号。" ;;
esac
echo "本机已经把报告存在 $TMP，网络恢复后可以再点一次「上传错误日志」。"
exit 1