#!/data/data/com.termux/files/usr/bin/bash
# 步骤 2：下载并解压 Ubuntu 24.04 rootfs（按设备架构自动选择 amd64 / arm64）
#
# 1.2.0 起多一条路：APK 里带了内置离线包（bundle.tar.xz）时，直接把整棵装好的
# rootfs 解出来，一次到位 —— 后面 30/40/50/60 四步（apt / QQ / NapCat / AstrBot）
# 全都已经是装好的状态，直接标成完成，全程不需要联网。
. "$PREFIX/opt/qqbot/common.sh"

ROOTFS="$QQBOT_ROOTFS"
TAR="$QQBOT_CACHE/$QQBOT_ROOTFS_TAR"
STAMP="$QQBOT_HOME/rootfs.arch"
BUNDLE="$QQBOT_HOME/bundle.tar.xz"
BUNDLE_OK="$QQBOT_HOME/.bundle-ok"
BUNDLE_PENDING="$QQBOT_HOME/.bundle-pending"
# 构建时写进 APK 的一行（amd64 / arm64），用来挡住「拿错架构的包装上去」
BUNDLE_ARCH_HINT="$QQBOT_OPT/BUNDLE_ARCH"
CUR=""
[ -f "$STAMP" ] && CUR="$(cat "$STAMP" 2>/dev/null)"

# ---------------------------------------------------------------------------
# 内置离线包：先在旁边等它从 APK 里拷出来。
# 拷贝是应用侧（Java）在后台做的，650 MB 大概要几十秒；部署脚本不能因为「文件还没到」
# 就去联网下载 —— 那正好是内置版要避免的事。
# ---------------------------------------------------------------------------
qq_bundle_ready() {
  [ -s "$BUNDLE" ] && [ -f "$BUNDLE_OK" ]
}

if [ ! -f "$QQBOT_HOME/deploy.done" ] && ! qq_bundle_ready && [ -f "$BUNDLE_PENDING" ]; then
  qqlog "正在释放内置离线包（APK 自带的沙盒镜像，无需联网）"
  _w=0
  while [ "$_w" -lt 600 ]; do
    qq_bundle_ready && break
    # Java 那边拷失败会把 .bundle-pending 删掉；这时别死等，回落到在线部署。
    [ -f "$BUNDLE_PENDING" ] || break
    sleep 2
    _w=$((_w + 2))
    [ $((_w % 20)) -eq 0 ] && qqlog "  等待内置包就位… ${_w}s"
  done
  if qq_bundle_ready; then
    qqok "内置离线包已就位（$(ls -l "$BUNDLE" | awk '{print $5}') 字节）"
  else
    qqwarn "内置包没能就位，回落到联网部署"
  fi
fi

# ---------------------------------------------------------------------------
# 1.2.1：APK 里不再塞 600 MB 镜像，先试着从官网拉一份装好的（内容与懒人包一样）。
# 拉不到就继续往下走在线安装，只是慢一些 —— 下载失败绝不能让部署中断。
# ---------------------------------------------------------------------------
# 别在「沙盒其实已经解压好了」的情况下再去下 600 MB：
# 上一次解压成功后会立刻删掉 bundle.tar.xz，如果部署在后面的步骤里被打断
# （用户杀进程、手机重启），重进时 deploy.done 还没有，光看 bundle 不存在
# 就会把整份镜像从头再下一遍。rootfs 里已经躺着 /opt/AstrBot 就说明解压过了。
if [ ! -f "$QQBOT_HOME/deploy.done" ] && ! qq_bundle_ready \
   && [ ! -f "$ROOTFS/opt/AstrBot/main.py" ]; then
  qq_bundle_pull "$BUNDLE" || qqwarn "云端镜像没下成，改用在线安装（慢一些，但能装完）"
fi

# ---------------------------------------------------------------------------
# 有内置包：整棵 rootfs 一次解出来，然后跳过已经装好的步骤。
#
# 什么时候该用内置包，判据有两条，缺一不可地看「现在这棵树到底能不能用」：
#   ① 架构标记跟设备不一致 —— 从没在这台机器上装过；
#   ② 这棵树不完整 —— 上次装到一半（比如联网装到 30-apt 就被杀、或 apt 失败后
#      被重建回 base），此时 rootfs.arch 已经写下，光看 ① 会把它当成「已完成」，
#      于是一棵只有 84 MB 的残树被当成装好的沙盒继续往下跑，最后卡在 link 报
#      「python3: command not found」。判据换成「整棵树的成品标志在不在」，才自洽。
# ---------------------------------------------------------------------------
BUNDLE_USE=0
if qq_bundle_ready; then
  if [ "$CUR" != "$QQBOT_ARCH" ]; then
    BUNDLE_USE=1
  elif [ ! -f "$ROOTFS/opt/AstrBot/main.py" ]; then
    qqlog "检测到沙盒不完整（缺 /opt/AstrBot），改用内置离线包重装"
    BUNDLE_USE=1
  fi
fi

if [ "$BUNDLE_USE" = "1" ]; then
  # 内置包是按架构编的，架构不符必须拒收 —— 解出一棵 amd64 的树到 arm64 手机上，
  # 表现是每一步都报「Exec format error」，而报错点在 30-apt，根因在第 2 步。
  BARCH="$(cat "$BUNDLE_ARCH_HINT" 2>/dev/null)"
  if [ -n "$BARCH" ] && [ "$BARCH" != "$QQBOT_ARCH" ]; then
    qqerr "内置包架构不匹配（包是 $BARCH，设备是 $QQBOT_ARCH），已忽略，改为联网部署"
    rm -f "$BUNDLE" "$BUNDLE_OK" "$BUNDLE_PENDING"
    BUNDLE_USE=0
  fi
fi

if [ "$BUNDLE_USE" = "1" ]; then
  qqlog "使用内置离线包解压沙盒（不需要联网）"
  qq_fsprobe "$ROOTFS"
  PRC=$?
  if [ "$PRC" = "3" ]; then
    qqlog "重建沙盒目录 $ROOTFS"
    chmod -R u+rwX "$ROOTFS" 2>/dev/null || true
    rm -rf "$ROOTFS" 2>/dev/null || true
  fi
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS" || { qqerr "沙盒目录建不出来"; exit 1; }
  # 总量取 xz 索引里的 uncompressed size，用来算百分比。拿不到就不显示百分比，
  # 绝不能因为这一句解析失败就让整个部署停下。
  TOTAL="$("$PREFIX/bin/xz" --robot -l "$BUNDLE" 2>/dev/null | awk -F'\t' '/^totals/{print $5}' | head -1)"
  case "$TOTAL" in
    ''|*[!0-9]*) TOTAL=0 ;;
  esac
  set +e
  # 解压走「xz 出流 + tar 读流」而不是 tar -xJf：
  # GNU tar 的 -J 是按名字去 exec 一个 xz，PATH 里没有就报 "xz: Cannot exec"；
  # 实测在 adb shell 这类非 Termux PATH 下必然失败，而 App 内跑时又依赖 PATH 恰好对。
  # 直接给 xz 绝对路径，两种环境都稳。
  #
  # 必须开 pipefail 并把整个管道放进子 shell：
  # 只看 tar 的退出码是不够的 —— xz 中途死了（被杀 / 磁盘满）时 tar 读到空流会正常返回 0，
  # 于是一次彻底失败的解压被当成成功，后面 4 步全被标成已完成，最终卡在 link 阶段报
  # 「python3: command not found」，而真因在解压那一行。
  (
    set -o pipefail
    "$PREFIX/bin/xz" -dc "$BUNDLE" | tar -xf - -C "$ROOTFS" --no-same-owner
  ) >/dev/null 2>"$QQBOT_CACHE/bundle-tar.err" &
  TPID=$!
  _last=""
  while kill -0 "$TPID" 2>/dev/null; do
    sleep 3
    if [ "$TOTAL" -gt 0 ]; then
      _c="$(du -sb "$ROOTFS" 2>/dev/null | cut -f1)"
      case "$_c" in ''|*[!0-9]*) _c=0 ;; esac
      _p=$(( _c * 100 / TOTAL ))
      # 只在整数百分比变化时打一行：解压要几分钟，每 3 秒一条会把日志刷成一片。
      if [ "$_p" != "$_last" ]; then
        _last="$_p"
        qqlog "  解压中 ${_p}%"
      fi
    fi
  done
  wait "$TPID"
  BRC=$?
  set -e
  if [ "$BRC" != "0" ]; then
    qqerr "内置包解压失败（tar 返回 $BRC，详见 $QQBOT_CACHE/bundle-tar.err）"
    rm -rf "$ROOTFS"
    exit 1
  fi
  if [ ! -x "$ROOTFS/bin/bash" ] || [ ! -d "$ROOTFS/var/lib/dpkg" ]; then
    qqerr "内置包解压后缺少关键目录，镜像可能损坏"
    rm -rf "$ROOTFS"
    exit 1
  fi
  # 上面的两个路径在「只解出一棵 ubuntu-base」的残缺树里同样存在，判据还不够。
  # 内置包按定义是把整台机器都装好了，所以再验一个只有装完才会有的东西：
  # AstrBot 主程序。它在 tar 里排在靠后的位置，能解出它就说明流是完整的。
  if [ ! -f "$ROOTFS/opt/AstrBot/main.py" ]; then
    qqerr "内置包只解出一部分（缺 /opt/AstrBot/main.py），已放弃本次离线安装"
    rm -rf "$ROOTFS"
    exit 1
  fi
  # 解压完立刻删掉，650 MB 不能留成永久占用。
  rm -f "$BUNDLE" "$BUNDLE_OK" "$BUNDLE_PENDING"
  echo "$QQBOT_ARCH" > "$STAMP"
  qqok "内置包已解压：$ROOTFS（$QQBOT_ARCH）"
  # 内置包是用 --exclude 打的，故意不带 dev / proc / sys / run / tmp：
  # 这些是运行期才挂载或由系统给出的目录，打进包里既没意义也会带进宿主设备节点。
  # 但解出来必须补空目录，否则 TMPDIR=/tmp 指不到东西，dpkg 的维护者脚本、
  # apt 的临时文件、pip 的构建目录全部会报 No such file or directory。
  mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/run" \
           "$ROOTFS/tmp" "$ROOTFS/root" "$ROOTFS/opt" 2>/dev/null || true
  chmod 1777 "$ROOTFS/tmp" 2>/dev/null || true
  qqok "已补齐运行时目录 dev / proc / sys / run / tmp"
  # 包里已经装好哪几步，就标哪几步完成。标记只在包内清单里写明的情况下才落，
  # 半成品的包不会让后面四步被误跳过。
  if [ -f "$ROOTFS/.qqbot-bundle" ]; then
    for _s in $(tr -d '\r' < "$ROOTFS/.qqbot-bundle" | tr ' ' '\n'); do
      case "$_s" in
        30-apt|40-qq|50-napcat|60-astrbot) : > "$QQBOT_STEPS/$_s" ;;
      esac
    done
    rm -f "$ROOTFS/.qqbot-bundle"
    qqok "内置包已包含的步骤已标记完成，跳过下载与安装"
  fi
  # 内置包这条路到此为止。下面那段是「下载 ubuntu-base 再解压」的在线流程，
  # 它的第一件事就是 rm -rf "$ROOTFS" —— 走到那里会把刚解好的 2.2 GB 整棵树
  # 覆盖成 84 MB 的裸 base，然后 30~60 步带着「已完成」的戳全部跳过，最后卡在
  # 65-link 报「python3: command not found」，而真因在第 2 步这一行。
  # （1.2.0 第一版就踩了这个：日志里能看到「内置包已解压」紧接着「解压 rootfs 到 …」。）
  # 收尾的 DNS / apt 源 / CA 这些内置包里已经写好了，不需要再跑一遍。
  qqok "步骤 20-rootfs 完成（内置包，跳过在线解压）"
  exit 0
fi

# ---------------------------------------------------------------------------
# 前置体检：在花时间下载 / 解压之前，先确认这个目录能写、能建硬链接。
# 真机（Redmi / Android 16）上就栽在硬链接这一层：dpkg 装包第一步要给
# /var/lib/dpkg/status 建硬链接备份，建不了就整条链全挂，而报错看起来像权限问题。
#
# 返回值 3 = 这个目录建不了硬链接、但 HOME / cache 可以 —— 说明目录本身脏了，
# 删掉重建一次通常就好，所以这里直接自愈，不让用户为这个理由重装应用。
# ---------------------------------------------------------------------------
qq_fsprobe "$ROOTFS"
PRC=$?
if [ "$PRC" = "3" ]; then
  qqlog "重建沙盒目录 $ROOTFS 后再试一次"
  chmod -R u+rwX "$ROOTFS" 2>/dev/null || true
  rm -rf "$ROOTFS" 2>/dev/null || true
  mkdir -p "$ROOTFS" 2>/dev/null || true
  qq_fsprobe "$ROOTFS"
  PRC=$?
  # 重建成功意味着原来那棵树已经没了，必须重新解压（即使之前标记过已完成）。
  [ "$PRC" = "0" ] && CUR=""
fi
[ "$PRC" = "0" ] || exit 1

# 「已完成」的判据必须够强：只看两个可执行文件的话，tar 解到一半被杀、但 bash 已经解出来的
# 残缺 rootfs 会被判成已完成，之后带着残缺树进 30-apt 再炸一次；
# 而失败点显示在第 3 步、真因其实在第 2 步，极难排查。
if [ "$CUR" = "$QQBOT_ARCH" ] && [ -x "$ROOTFS/bin/bash" ] && [ -x "$ROOTFS/usr/bin/env" ] \
   && [ -d "$ROOTFS/var/lib/dpkg" ] && [ -d "$ROOTFS/usr/lib" ] \
   && [ "$(ls -1 "$ROOTFS/usr/bin" 2>/dev/null | wc -l)" -gt 200 ]; then
  qqok "rootfs 已存在（$QQBOT_ARCH），跳过解压"
else
  if [ ! -s "$TAR" ]; then
    qqlog "下载 Ubuntu 24.04 $QQBOT_ARCH 基础 rootfs（约 29 MB）"
    # 体积下限 25 MB：rootfs 约 29 MB，低于这个数一定是错误页或被截断的残包。
    QQBOT_DL_MIN=$((25 * 1024 * 1024))
    qq_dl_mirror "$TAR" \
      "$QQBOT_TUNA/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$QQBOT_ROOTFS_TAR" \
      || { qqerr "rootfs 下载失败（可能只下到一半）"; rm -f "$TAR" "$TAR.part"; QQBOT_DL_MIN=1024; exit 1; }
    QQBOT_DL_MIN=1024
  fi
  qqlog "解压 rootfs 到 $ROOTFS"
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  # 这里必须用 Termux 自带的 tar 直接解包，不能走 proot：
  # proot 的 tracee 若是经 houdini 翻译的 arm64 程序，ptrace 寄存器布局会错配而挂死。
  # 直接解包还能避开设备节点（--exclude=dev），且不需要 root。
  set +e
  tar -xzf "$TAR" -C "$ROOTFS" --no-same-owner --exclude=dev --exclude='dev/*'
  RC=$?
  set -e
  # 这里原来是 &&（两个都不存在才算失败）—— 写反了：只要 bash 解出来就放过了残缺 rootfs。
  if [ ! -x "$ROOTFS/bin/bash" ] || [ ! -x "$ROOTFS/usr/bin/env" ]; then
    qqerr "rootfs 解压失败（tar 返回 $RC）"
    rm -rf "$ROOTFS"
    exit 1
  fi
  # tar 非 0 大多是「个别条目被跳过」（--exclude=dev 之类）；但关键目录都没了就绝不能
  # 让 stamp 落盘 —— 那会把一次失败的解包永久钉成「已完成」，之后每次重跑都直接跳过解压。
  if [ "$RC" != "0" ] && { [ ! -d "$ROOTFS/var/lib/dpkg" ] || [ ! -d "$ROOTFS/usr/lib" ]; }; then
    qqerr "rootfs 解压不完整（tar 返回 $RC），已删除残留目录，请重新部署"
    rm -rf "$ROOTFS"
    exit 1
  fi
  [ "$RC" != "0" ] && qqwarn "tar 返回 $RC（部分条目被跳过属正常）"
  echo "$QQBOT_ARCH" > "$STAMP"
fi

# ---------------------------------------------------------------------------
# 可写性 / 硬链接自检（真机上炸过一次，代价是用户卡在第 3 步一整晚）。
# dpkg 备份 status 用的是 link(2)：文件系统不支持硬链接时，apt 装任何包都会报
#   dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
# 而这个报错看起来像权限问题，实际是磁盘格式问题（FAT/exFAT、把应用数据放外置存储、
# 某些 FUSE 挂载都会这样）。在这里先试一次，早失败早说清楚。
# ---------------------------------------------------------------------------
# 解压后再确认一次：tar 会把目录的权限位改成跟包内一致，极端情况下会改坏。
qq_fsprobe "$ROOTFS" || exit 1
# 权限归一：rootfs 可能是旧版应用留下的、或从别处拷来的，属主/权限位一旦不对，
# 后面 dpkg 就会一路 Permission denied。只修关键目录，不做全树 chmod（手机上太慢）。
chmod -R u+rwX "$ROOTFS/var/lib/dpkg" "$ROOTFS/var/lib/apt" "$ROOTFS/var/cache/apt" \
  "$ROOTFS/var/log" "$ROOTFS/tmp" "$ROOTFS/root" "$ROOTFS/run" 2>/dev/null || true

qqlog "写入 DNS / apt 源 / 沙盒基础配置"
mkdir -p "$ROOTFS/etc/apt/apt.conf.d" "$ROOTFS/etc/apt/sources.list.d"
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > "$ROOTFS/etc/resolv.conf"
printf 'APT::Sandbox::User "root";\nAcquire::Retries "3";\n' > "$ROOTFS/etc/apt/apt.conf.d/99proot-sandbox.conf"
rm -f "$ROOTFS"/etc/apt/sources.list.d/ubuntu.sources "$ROOTFS"/etc/apt/sources.list.d/*.list 2>/dev/null
# CA 证书：Ubuntu 基础 rootfs 不带 ca-certificates，首次 apt 走 https 会报
# "No system certificates available"，pip/curl 也会证书校验失败。
# 这里先用 Termux 自带的 CA 捆绑包垫上，30-apt 装上 ca-certificates 后会被覆盖。
if [ -f "$PREFIX/etc/tls/cert.pem" ]; then
  mkdir -p "$ROOTFS/etc/ssl/certs"
  cp -f "$PREFIX/etc/tls/cert.pem" "$ROOTFS/etc/ssl/certs/ca-certificates.crt"
  cp -f "$PREFIX/etc/tls/cert.pem" "$ROOTFS/etc/ssl/cert.pem"
  qqok "已注入 CA 证书捆绑包（$(wc -c < "$ROOTFS/etc/ssl/certs/ca-certificates.crt" 2>/dev/null) 字节）"
else
  qqwarn "未找到 $PREFIX/etc/tls/cert.pem，本次仅使用 http 源"
fi
# amd64 用 /ubuntu/，arm64 必须用 /ubuntu-ports/（tuna 的 /ubuntu/ 不承载 arm64 的 Packages 索引，会 404）
UB_PORTS="${QQBOT_UBUNTU_PORTS%% *}"
printf 'deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-backports main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n' "$UB_PORTS" "$UB_PORTS" "$UB_PORTS" "$UB_PORTS" > "$ROOTFS/etc/apt/sources.list"
qqlog "apt 源：$UB_PORTS"
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/tmp" "$ROOTFS/root" "$ROOTFS/opt"
chmod 1777 "$ROOTFS/tmp" 2>/dev/null
printf 'LANG=C.UTF-8\n' > "$ROOTFS/etc/environment"
qqok "proot 沙盒已建立：$ROOTFS（$QQBOT_ARCH）"
