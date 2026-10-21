# 更新日志

本文件格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

版本号规则：`MAJOR.MINOR.PATCH`，对应 `versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

- **MAJOR**（X.0.0）：不兼容改动，例如换沙盒方案、需要重装 APK 才能升级
- **MINOR**（0.X.0）：新增功能
- **PATCH**（0.0.X）：修 bug、文案、性能

---

## [1.2.0] - 2026-10-21

### 新增

- **内置离线版**：安装包直接带上整套沙盒镜像（Ubuntu 24.04 + Linux QQ 3.2.34 + NapCat v4.18.38 + AstrBot v4.28.2
  以及它的 Python 虚拟环境）。第一次打开把约 600 MB 的 `bundle.tar.xz` 解压成 2.2 GB 的沙盒，**全程不需要联网**，
  不用再等 apt / pip 下载（原来首次部署要下约 1.1 GB）。
- **双架构构建**：`build-apk.ps1 -Arch arm64|amd64` 分别打两种包 —— arm64 给真机、amd64 给 x86_64 模拟器。
  解压前会核对包内的 `BUNDLE_ARCH` 标记，架构不符自动丢弃内置包并回落到联网部署，不会把错的镜像装进去。
- 「更多 → 存储占用」多一行**内置离线包**状态：已就绪（显示体积与架构）/ 正在释放（带百分比）/ 已用于部署（解压后删除，不再占空间，
  不会白占 600 MB）。进度在「更多」页每 4 秒刷新，不受存储统计 60 秒节流限制。
- 桥接方法 `bundleInfo()`：返回内置包的体积、架构、释放进度、是否已用于部署，UI 不用打开 600 MB 资源就能显示。
- **「更多 → 模型与人格」**：不用打开 AstrBot 控制台，在 App 里四栏填完就能用 —— 接口地址（默认烛心中转站）、密钥、模型名、人格提示词。模型名可以点「获取列表」让中转站自己报（请求 `{base}/models`），点一下即填入输入框。
  保存流程是**先停沙盒再落盘**：写 `cmd_config.json` 里的 provider（id 固定 `candle`，重复保存按 id 覆盖，不会越存越多）与 `data_v4.db` 的 `personas` 表（id `zhuxin`，`default` 是内置常量不能覆盖），写完自动重新拉起，全程约 40 秒，进度用「正在停止服务 / 正在写配置 / 正在启动服务」回报。**密钥留空＝沿用已保存的那把**，所以只改人格或换模型不会把密钥抹掉。
- **部署大标志**：部署期间全屏盖一层进度面板 —— 环形百分比、第几/共几步、这一步在干什么、最近一行日志尾部，底下小字提示「部署期间请保持应用在前台」。某一步超过 1 分钟没有新输出时补一句「通常是在编译或下载，慢是正常的」，避免被当成卡死。
  点「放到后台，继续部署」收起后 **90 秒内不再自动弹回**，进度照旧在部署台里走；部署完成时闪一下「部署完成」自动消失。
- **检查更新多一行官网版本**：`api.github.com` 在国内经常连不上，所以在「检查更新」下面再读一次官网静态 JSON（`/data/astrbot.json`）里的版本号，写成「官网最新 x.y.z（发布日期）· 已是最新 / 有新版本」。两个来源互不阻塞，GitHub 挂了也能看出线上是哪个版本。
- **通知栏「关闭进程」**：常驻通知第 4 个动作从「下线」换成「关闭进程」—— 停沙盒**并把守护服务一起收掉**，通知栏不再留常驻条。「下线」只停沙盒、守护还在，下次打开面板还能看见状态；「关闭进程」是明确不想让它再自愈时用的一键关干净，所以不再弹二次确认。

### 变更

- 沙盒运行环境改为**按架构交叉组装**：apt 只下目标架构的 deb（360 个包，310 arm64 + 50 arch:all），
  Python 依赖用 `pip download --platform manylinux2014/2_17/2_28_aarch64` 取 144 个 wheel，
  装配完逐个校验 ELF `e_machine`（`b700` = AArch64），确认 arm64 包里没有 x86_64 残留。
- 构建产物按架构命名 `AstrBot-<版本>-<架构>.apk`，不再用通配符清理旧包（此前一次构建会把另一个架构的包一起删掉）。
- 构建脚本不再写死 JDK 路径：`build-ui.ps1` / `build-apk.ps1` 改成**先认 `JAVA_HOME`、再认 PATH 上的 `javac.exe`**，JDK 17 及以上都能构建；`build-apk.ps1` 的 `$ROOT` 也从绝对路径改成 `$PSScriptRoot`，仓库挪盘不用改脚本。
- `tools/*.java` 在每个构建脚本开始时**按需自动重编译**（缺 `.class` 或源码比 class 新时才编）。这些 `.class` 是 gitignore 的产物，换机器或换 JDK 后旧 class 会因 `UnsupportedClassVersionError` 跑不起来。

### 修复

- **构建可能带着上一轮的 smali 出厂**：`DexToSmali` 失败时返回码被 `2>$null` 吞掉、脚本照旧往下走，于是把**上一次构建的** `ui/smali` 打进包里 —— dex 与 smali 来自不同提交，编出来的包看不出任何异常。现在 `DexToSmali` 返回码非 0 直接中断，并把「拷 smali 到 `_apkwork`」改成 `-ErrorAction Stop`。
- **内置包解压成功后又被在线分支删掉重建**：`steps/20-rootfs.sh` 的内置包分支少了 `exit 0`，解压完继续往下走，
  在线分支第一件事就是 `rm -rf` 沙盒目录 —— 2.2 GB 的树瞬间变回 84 MB 的空壳，后续步骤却带着「已完成」戳全跳过，
  最后卡在 `python3: command not found`。补上收尾与 `exit 0`，并补建 `dev/proc/sys/run/tmp/opt`、给 `/tmp` 1777。
- **`--link2symlink` 标记是粘性的**：`common.sh` 的文件系统体检通过后只删探测文件、不撤标记，之后每次进 proot 都把 `link()`
  模拟成符号链接，留下 `var/lib/dpkg/status -> .l2s.status0001` 这类悬空链；换台设备后 dpkg 报
  `error creating new backup file '/var/lib/dpkg/status-old': Operation not permitted`。体检通过即撤标记。
- **打包前做权限归一**：沙盒是在 Android 的 proot 里建的，tar 原样记下了 135 个 `drwx------`、105 个 `-rw-------`，
  换设备换个 uid，proot 就 lstat EPERM。统一成 755 / 644（`etc/shadow`、`etc/gshadow` 640，`/root` 700，`/tmp` 1777）。
- **d8 不能处理匿名内部类**：`kickBundleCopy` 里的匿名 `Runnable` 让 d8 直接内部错误崩掉，改成具名顶层类 `BundleCopyTask`。
- Termux 的 `tar -xJf` 在 PATH 里没有 `xz` 时只报 `Cannot exec`，改用 `"$PREFIX/bin/xz" -dc | tar -xf -` 管道形式，
  并用 `set -o pipefail` 防止 xz 中途失败被 tar 的 0 退出码掩盖。

## [1.1.38] - 2026-10-20

### 新增

- **NapCat 启动后账号还没登录时，自动弹出扫码窗口**：容器已经跑起来、但还没有 QQ 登录时，
  以前只能自己去「日志」页翻二维码，新人往往就卡在「容器运行中却登不上」。
  现在会自己弹一张卡片：二维码 + 三行说明（用另一台手机扫 / 约 2 分钟过期会自动换 / 登录成功后自动关掉、以后不用再扫）。
- 「机器人」页实例的 `⋯` 菜单补回「扫码登录」入口（`v4` 收起次要按钮时漏掉的）。

### 修复

- 判定「还在等扫码」只看 `qrcode.png` 的 mtime：等扫码时 NapCat 每两分钟左右重画一张，
  登录成功后就不再写这个文件，mtime 自然变旧 —— 不依赖日志文本，也不受脱敏、轮转影响。
- 防打扰：手动关掉后 10 分钟内不再自动弹；同一张二维码（mtime 未变）不重复弹；
  有别的弹窗开着、正在输入框里打字、容器没在跑时都不弹。
- 二维码过期只把提示文字改成「已过期，点刷新重新获取」，不再误判成登录成功而收掉窗口。

## [1.1.37] - 2026-10-20

### 变更

- **浅色主题重做成「清新」方向（v6 视觉层）**：画布改为浅蓝→淡紫→薄薄荷的多色柔和渐变，
  卡片变半透明白的磨砂玻璃，圆角放大，图标块带轻微色相。
  参考的是同类 App 的材质语言，没有照搬它的布局与配色。
- 顶栏左侧图标改用应用自身的图标，构建脚本同步把它拷进 APK（`assets/qqbot/appicon.png`）。
- 主操作改蓝紫渐变胶囊，但同时限制「一屏只留一颗实心按钮」，其余一律白底细边蓝字，
  避免「更多」页一屏五颗实心块把层级压平。

### 修复

- 暗色下画布发褐：深色背景预设透过遮罩染上来，加厚暗色遮罩后回到中性近黑。
- 内容较短的页面，落款悬在半空 —— `fitFoot()` 改成「装得下就撑满」，落款贴到底栏上方。

## [1.1.36] - 2026-10-20

这一版重做**浅色主题**。起因是 1.1.35 装机后用户的原话：「把浅色模式的gui也修饰一下 太单调了现在」。

### 变更

- **浅色不再是「一片白」**：原来画布 `#f3f4f6`、卡片 `#fff`、磁贴只差 3~5% 亮度，整页糊在一起。
  现在画布压到 `#eef1f7` 并加两层极淡的品牌色光晕（左上蓝、右上天蓝），卡片纯白、磁贴 `#f4f6fa`、
  边框收到 7.5% 透明度——靠亮度差和 1px 边分层，不靠阴影。
- **浅色 hero 从近黑灰改成品牌深蓝**：`linear-gradient(158deg,#1a3259,#13203b 48%,#0c1526)`，
  左上补一层淡蓝冷光，状态光（绿/黄/红）照旧从右下渗出。深色 hero 保持原样。
- **四个常用动作合成一条**：原来是四块各自飘着的白方块，现在是一条卡片、四等分、中间用发丝线分隔，
  图标换成 28px 淡蓝圆角块。深浅两套主题共用同一套结构。
- **实例块不再是「卡里套卡」**：机器人页的实例原来是白面 + 1px 描边，放在白卡里像两层盒子；
  浅色改成 `#f7f9fc` 的浅面、去掉阴影，当前实例改用淡蓝面 + 更实的蓝边。
- **设置页图标块、提示卡、分隔行统一**：`lkic` 图标块改淡蓝底蓝图标；顶部提示卡加 3px 品牌色左条
  （2px 在白卡上看不见）；「更多工具」两侧加发丝线，做成标准的「展开更多」分隔行；
  「返回主页」从带框按钮改成纯文字蓝链接。
- **落款不再在正文和它之间挖空档**：`fitFoot()` 原来无条件把整页撑到满屏，内容短的页面会在
  正文与落款之间留一大片空白。现在只在内容已近满屏（差 ≤64px）时才顶到底。

## [1.1.35] - 2026-10-20

这一版修的是**实例删不干净**。

### 修复

- **删掉实例后主页还留着一张删不掉的卡**：`state/instances.json` 里留了旧键，
  而主页的实例列表是拿「bots.json 的实例 ∪ instances.json 的键」并起来渲染的，
  于是被删掉的实例会以一张幽灵卡的形式永远挂在那里（还能点进去、点删除也没反应）。
  - 原生侧：`InstancesState.update()` 原来带 `out.length() > 0` 的前置条件，
    **删掉最后一个实例时序列化结果是空表，这个条件让它永远不写盘**，旧记录就这么留下来了。
    现在只要 bots.json 读得到就照实写（包括写空表）；读不到才跳过，避免把「读不到」
    当成「没有实例」把状态抹掉。
  - 前端侧：实例列表改成**只认 bots.json**，`instances.json` 只用来补运行时长、
    掉线与头像这些运行时字段，不再贡献实例条目。
  - 删除时（`doDelBot`）立刻把该实例从内存里的 `instances` 表摘掉并重绘，
    不用等原生那边开 proot 跑完删除脚本（那一步要十几秒到一分钟）。
- **一个实例都没有时，首屏却说「一切正常」**：现在 hero 显示「还没有实例」，
  并提示去「机器人」页新增；顶栏也会显示「0 个 NapCat 实例 · 当前：-」。
- **当前实例的展开菜单里还挂着「设为当前」**：点了只会把容器重启一遍、白掉一次线。
  现在只对备用实例显示。
- **实例卡在离线时写「在线 未运行」**：改成离线下只写「未运行」。

## [1.1.34] - 2026-10-20

这一版是**首屏重塑**（v4 视觉层）。起因是 1.1.33 装机后用户的原话：
「还是不够炫酷 要简约 一打开软件要赏心悦目 任何不常用的按钮全部收起来」。

### 变更

- **首页只剩三块**：一张深色 hero、四个常用动作、一条实例。
  - 深色 hero 用**一句话说清当前状态**：`一切正常` / `服务已停止` / `正在启动` / `部分服务已关闭` 等，
    下面一行小字解释，再往下是今日在线/消息/异常/流量四格。
    状态灯的颜色只在「不是一切正常」时才是黄/红，正常时是安静的绿点。
  - hero 右下角有实时走动的「已运行 3:12:02」，左上角「状态详情」才展开原来那五个磁贴。
  - 冻结顺带修掉一个误报：冷启动头 30 秒原来越报「服务已停止」，现在按启动横幅的状态显示「正在启动」，
    就绪后自动变过来。
- **四个常用动作**取代原来需要滑两屏找的按钮：NapCat 面板、AstrBot 控制台、日志、重启。
  其中「重启」会直接切到更多页并弹出重启确认，不用再翻菜单。
- **次要卡片全部收进「更多工具 ▾」**：自动部署、组件开关、机器人连接、访问地址四张卡默认不显示；
  一键展开后仍然全在。没部署过的新机器例外——部署台会自动冒出来，不会藏起来让人找不到。
- **三条彩色提示并成一张卡**：崩溃、省电、启动进度原来是三张各占一整屏宽的彩色卡摞在一起
  （像 Android 4.x 的通知栏），现在合并成一张卡里的三行，每条左边一个图标、右边一个动作按钮。
- **每页底部加了落款**：「烛之控制台 · v1.1.34 / 本地运行，数据不出设备」，
  内容短的页面靠它把版面顶到底，不再是一屏内容加半屏空白。
- **入口动效**：首屏各块入场时向上浮 9px 逐渐显形（0.42s），系统开了「减少动效」时自动关掉。
- **实例卡按钮收成一个 ⋯**：原来每张实例卡横排五个按钮（复制 Token / 停止 / 设为当前 / 编辑 / 删除），
  现在只留「设为当前」，其余四项点 ⋯ 展开。
- **更多页的存储占用与崩溃看板**收进「诊断信息」，不再默认占两张卡的高度。
- 机器人页、日志页、更多页同步换了排版与间距，五个页签的卡片间距、标题字号、按钮高度统一。

### 修复

- 页脚版本号取不到（一直是「—」）：改为从 `state()` 里读 `appVersion`，并在每次刷新时补写一次。

---

## [1.1.33] - 2026-10-20

这一版只做一件事：**把 v2 那套「玻璃 + 状态色铺满」的界面推翻重做**（v3 视觉层）。
起因是 1.1.32 装机后用户的原话：「太廉价感了」。

### 变更

- **v3 视觉层 · 克制的精确（Quiet Precision）**：设计方向从「材质（玻璃/发光）」
  换成「排版（字号/留白/亮度差）」，参考 Linear / Vercel / Raycast 这类把颜色收起来的界面。
  仍然**只覆盖样式，不改任何类名、不动一行 JS**。
  - 四条硬规则：状态色只许出现在 ≤8px 的点和真正的异常文字上；一屏最多一个强调按钮，
    其余全幽灵；面的层级靠「亮度差 + 1px 描边」不靠阴影光晕；圆角收成三档（卡 16 / 控件 10 / 小件 8）。
  - 去掉四类「廉价感」来源：顶栏 4 个绿胶囊 + 引导 4 个绿勾 + 状态 4 个绿数字的绿色泛滥；
    公告（蓝紫）/ 崩溃（棕）/ 省电（橙黄）三张彩色卡摞叠；一行五个按钮三种处理没有主次；
    卡片与画布亮度差太小导致整屏浮在雾里。
  - 整屏不再出现任何实心荧光蓝大按钮：主按钮统一改成「淡底 + 描边 + 亮字」，
    危险操作改成红字描边（原来 `button.danger` 的优先级打不过 `.btns button.small`，
    实例卡里的「删除」其实一直是灰色普通按钮，这次一并修好）。
  - 进度条从蓝→绿渐变改回纯色；开关从亮绿改中性轨道 + 强调色；日志框改深底等宽。
- **补上浅色主题**：v3 初版只写了 `:root[data-theme="dark"]`，而主题默认值是 `auto`
  跟系统走，模拟器处于浅色时套的还是旧配色。现在浅色有独立的一套令牌
  （画布压暗到 `#f3f4f6` 让白卡浮起、强调文字用深蓝 `#1d4ed8` 而非浅蓝）。
- **修浅色画布发浊**：早期遗留的一份重复浅色令牌块排在更后面，把 `--bg-veil` 改回了
  40% 不透明，深色预设渐变（熔金）直接透上来，整屏呈橄榄绿。删掉重复块并把遮罩提到 ~94% 后正常。
- **背景预设重新调**：暗色遮罩从 30% 提到 ~66%，预设渐变只当一层淡淡色偏，
  不再让「熔金」把整屏染成棕色；浅色同理压到只剩一点色偏。
- **设置页外观主题三档**（跟随系统 / 浅色 / 深色）的未选中文字从注释色改为次级色。

### 修复

- 实例卡「删除」按钮此前一直不是红色：`button.danger`（0,1,1）打不过
  `.btns button.small`（0,2,1），属于 v1 起就存在的老问题。

---

## [1.1.32] - 2026-10-20

这一版只有两件事：**把界面重做一遍**，以及**把那条刷屏的 AstrBot 报错按死**。

### 新增

- **「更多」页新增「重启服务」**：以前改完端口 / 局域网开关想让它生效，得退回主页
  去找按钮，「更多」里只有「紧急下线」。现在这里有一张正经的重启卡：两步确认
  （重启会中断正在收发的消息）、点击后 45 秒倒计时、结束后自动回显结果。
  用的是已有的 `Android.action("restart")`，没有新增桥方法，也不改 Java。

### 变更

- **界面整体重做（v2 视觉层）**：令牌、材质、排版全部换过一轮，但**没有动类名**——
  五千行 JS 全部按 id/class 绑定，重写选择器体系等于把交互一起推倒。
  - 层级改成「两级玻璃 + 一个光源」：卡片是内层（最实）、按钮胶囊是次级（半透）、
    顶栏底栏是环境层（最透，靠 blur 兜住可读性）。阴影只在下方，不做四向发光。
  - 暗色画布从偏蓝紫的近黑换成中性近黑（`#08090d`）。原来的蓝紫底会让整屏
    看起来像游戏 UI，颜色应该只留给状态与操作。
  - 底栏改成悬浮胶囊，`body` 下边距跟着加到 118px，内容不会被压在栏下面。
  - 六套背景预设统一降饱和（.30 → .10–.16），壁纸感收掉，玻璃卡片才是主角。
  - 圆角整体放大一档（卡片 22px），动效放慢到 200/300ms —— 高级感来自「不急」。
  - 跑通 `prefers-reduced-motion` 分支：极光漂移动画、页签入场、全部过渡都会关掉。

- **主页重新排版**：原来的「今日概览」和「运行状态」是两张卡，打开应用第一眼要看的
  信息被拆成两块。现在合成一张**状态总览**：五个组件状态在上（值贴底对齐，2×2
  网格里的数字在同一条基线上），今日数据作为第二层补充，中间用一条分隔线接。

### 修复

- **AstrBot 日志被 `Errno 38` 刷屏**：proot 沙盒里 asyncio 的 accept 循环偶尔会拿到
  `OSError: [Errno 38] Function not implemented`（ENOSYS）。现场实测一次 6 分钟的
  运行刷了 **12965 条**，`astrbot.log` 被撑到 8.5 MB，「上传错误日志」跟着把报告顶到
  534 KB 的截断线附近；控制台日志页全是红字，用户只会以为 AstrBot 坏了 —— 而实际
  上控制台、OneBot 通道、插件全部正常（同时刻 6185 登录接口返回 200）。

  这个问题**不是必现**的：最小复现（proot 里起 asyncio server、宿主侧连进去）accept
  完全正常，所以改 proot 参数这条路走不通。这一版改成在 AstrBot 侧兜底：
  `sandbox/sitecustomize.py` 通过 `PYTHONPATH` 自动加载，**只**拦「accept 回调里的
  ENOSYS」这一种，折叠成每 5 分钟一行汇总；KeyError、插件异常、端口占用这些真正要看
  的照原样抛出。真机验证：注入 5 条 ENOSYS 收敛成 1 行，同时注入的 ValueError 正常透出。

- **重启时「紧急下线」卡会误报已下线**：重启是先停再起，原生在 stop 阶段会写
  `state/offline`，如果那一轮 refresh 正好落在窗口里，下线卡就闪一句「已下线」——
  用户点的是重启，看到这个只会以为点错了。真机验收时实际复现，现在倒计时期间
  一律不认 offline 标记，改显示「重启中，稍后自动恢复」。

---

## [1.1.31] - 2026-10-20

这一版四个改动，都是「出事时少踩坑」和「装好之后不用管」这两类：

### 修复

- **上报日志整份脱敏**：以前只把 `bots.json` 里的 token 抹掉，其余账号、群号、
  令牌、二维码链接原样上传。现在 `common.sh` 新增 `qq_redact_file`，在
  **1.5 MB 截断之后**统一过一遍规则表：JSON 里的 `token / secret / password /
  api_key`、`Authorization: Bearer`、`sk-` 开头的 key、`AIza` 开头的 Google key、
  `https://txz.qq.com/p?k=`、URL 里的 `?token=&access_token=&key=`、`uin / qq /
  user_id / group_id / 账号 / 群号` 后面的 5–12 位数字、大陆手机号、邮箱，
  以及 NapCat 自己打的两处散文形态 `WebUI token xxx` / `[WebUi] WebUi Token: xxx`
  和 `NAPCAT_WEBUI_SECRET_KEY=xxx` 这类环境变量。
  **端口、文件大小、时间戳、sha256 一律保留**（排障要用的东西不能抹），上传成功后
  回显「报告已脱敏 N 处」。实测一份 534 KB 的报告命中 6 处，人工核对无泄露。

### 变更

- **风控从「命中即停」改成滑动窗口**：以前日志里出现一个风控词就立刻暂停自动重启，
  网络抖一下、NapCat 打一行无害提示都会误伤。现在按
  `QQBOT_RISK_WINDOW`（默认 600 秒）窗口内累计 `QQBOT_RISK_HITS`（默认 2 次）
  才真正暂停：第一次只写日志 + 通知，不改状态；`risk-<id>.hits` 存最近 20 笔时间戳。
  窗口计数**按不同秒去重**——宿主看门狗和沙盒守护会同时扫同一份日志，同一次命中
  可能各记一笔，按行数算等于把阈值砍半（这个 bug 是实测抓到的：注入 2 行日志
  计出 4 次）。
- **风控暂停会自己过期**：`risk-<id>.flag` 的 mtime 超过 `QQBOT_RISK_TTL`
  （默认 21600 秒 = 6 小时）就自动删 flag 与 `risk.json`，恢复自动重启，
  不用手动去「我知道了」。读不到 mtime 时绝不误删（fail-closed）。
  `qq_risk_clear()` 一并清 `risk-<id>.hits`。
- **局域网访问开关**：默认仍然只监听 `127.0.0.1`。设置页新增一张卡，打开后写
  `$HOME/.qqbot/state/lan.on`，重启服务后 AstrBot 控制台监听 `0.0.0.0:6185`、
  NapCat WebUI 监听 `[::]:6099`（`webui.json` 的 `host` 自己写，不再删文件重建）；
  关掉即回到本机。OneBot 的 6199 不受影响（那是 NapCat 自己 bind 的）。
- **组件版本自动获取**：`common.sh` 新增 `qq_latest_ver napcat|astrbot`，查
  GitHub Releases 的 `tag_name`，缓存 24 小时到 `state/versions.json`；
  采纳前先核对资源名（NapCat 要有 `NapCat.Shell.zip`，AstrBot 要有
  `AstrBot-<tag>-dashboard.zip`），缺了就退回内置版本并记一行日志——
  **查不到绝不让部署失败**。只接受与内置版本同大版本的 tag；
  `QQBOT_NAPCAT_VER_OVERRIDE` / `QQBOT_ASTRBOT_VER_OVERRIDE` 仍可强制指定。
  `steps/50-napcat.sh` 与 `steps/60-astrbot.sh` 改用查到的版本拼下载地址，
  AstrBot 的源码包与控制台前端保证同一个 tag。

### 说明

- 设置页的局域网开关旁写明「改完需要重启服务生效（更多 → 重启）」；开关状态
  不进 `bots.json`（那份文件按白名单重建，会被丢掉）。
- 脱敏规则表在 `build/opt/qqbot/common.sh` 的 `QQBOT_REDACT_RULES`，要加词直接
  在表里追加一行 `模式@@替换`，格式是 POSIX ERE。
- 本版 `bash -n` 全过（36 个脚本），`index.html` 两段内联脚本 `node --check` 过。

## [1.1.30] - 2026-10-19

NapCat 在真机上跑稳之后，这一版补齐「装得上、看得懂、断了自己能爬回来」的一整套：
新图标 + 18 项功能。全部改动都在本机与自建服务端，不引入任何第三方 SDK，
不增依赖、不改包名、不动端口与实例算法，也不碰 proot loader 补丁。

### 新增

- **应用图标**：换成原画「烛心♡」（猫耳少女 + 竖排霓虹「烛心」+ 白猫）。
  `tools/make-icons.py` 从 1254×1254 源图按每档 LANCZOS 直接缩放，出方形
  `mipmap-{mdpi..xxxhdpi}/ic_launcher.png`（48/72/96/144/192）、圆形
  `ic_launcher_round.png`（内容 84% 居中）、自适应前景
  `drawable-{mdpi..xxxhdpi}/ic_foreground.png`（108/162/216/324/432，内容 72% 居中再右移 2.5%，
  按 86% 会把左侧「烛」字剪掉）；≤96px 档做 UnsharpMask + 亮度提升 + 灯管芯辉光，
  48px 下仍能认出「烛」字。背景 `drawable/ic_launcher_background.xml` 为深紫近黑 `#FF1A0A12`。
  `build-ui.ps1` 第 3d 段负责把 `ui/assets/icon/res/**` 烤进 `_apkwork`，并先删掉会压过 PNG 的
  矢量 `ic_foreground.xml`，整个过程幂等。
- **重启补登（功能 3）**：`QqbotBootReceiver` 收到 `BOOT_COMPLETED / QUICKBOOT_POWERON /
  MY_PACKAGE_REPLACED` 后拉起前台服务，`QqbotGuardTask` 在 `state/offline` 不存在时恢复沙盒，
  5 分钟内回到在线；启动路径加 `static synchronized` 防双容器。
- **网络切换自愈（功能 2）**：`NetworkWatch` 注册 `NetworkCallback`，30 秒 debounce（系统补发
  的瞬时抖动会被 `lastTriggerMs` 吃掉），`NetRecover` 先等 NapCat 端口再启 AstrBot；
  Wi-Fi ↔ 移动数据来回切之后不需要手动重启。
- **风控识别（功能 4）**：沙盒 `qq_risk_scan` 增量扫 `qq-$id.log`（`.off` 偏移），命中
  `build/opt/qqbot/sandbox/risk-words.txt`（84 词，可热改）就写 `state/risk.json` 并**暂停自动重启**；
  主页红横幅 + 通知（与「进程崩溃」区分），原生 `Android.riskInfo()/riskClear()` 对接。
- **省电引导（功能 5）**：`PowerGuide` 按 `MANUFACTURER` 分支跳对应省电设置页（小米 HyperOS、
  华为 EMUI、OPPO ColorOS、vivo OriginOS、三星 One UI…），失败退回应用详情页；每个版本首次打开
  在主页出琥珀色提醒条（`#powBar`），「知道了」写 `power.json` 不再重复；`更多` 页另有常驻卡片。
- **电量/温度降载（功能 6）**：`BatteryMon` 走 `ACTION_BATTERY_CHANGED`，电量 <15% 或温度 >42℃
  写 `state/degrade`（看门狗轮询 15→30 秒、暂停日志轮转、只记 warn），回到 >25% 且 <38℃ 自动撤销，
  带迟滞、可逆、不删数据。
- **紧急下线（功能 9）**：`stop-sandbox.sh` 保留登录态，通知栏 / 桌面小组件 / 快捷磁贴 / 控制台
  四处入口统一先弹二次确认（Activity extra `confirm_stop` / `toggle`，`AskStop`、`AskToggle` 具名
  Runnable 打 `window.askStop()/askToggle()`），确认后写 `state/offline` + `cache/svc/watchdog.off`，
  10 秒内 6099/6185 不可连；再点一次启动即恢复。
- **常驻通知卡片（功能 11）**：`QqbotGuardService.buildNoti` 显示在线实例数与最近自检时间，
  四个 Action（重启 / 看日志 / 扫码 / 下线）。
- **桌面小组件（功能 12）**：`QqbotWidgetProvider` + `res/layout/widget_status.xml`（2×1，
  在线状态灯 + 时长 + 启停按钮），点击进应用，按钮走同一套确认。
- **快捷磁贴（功能 13）**：`QqbotTileService`（`BIND_QUICK_SETTINGS_TILE`），显示「在线 x/y」，
  点按走 `startActivityAndCollapse(PendingIntent)`。
- **今日概览（功能 15）**：`TodayStats` 汇总今日在线时长/消息数/异常数/流量，跨天清零，读取 <50ms。
- **深色模式（功能 16）**：light / dark / auto 三档，CSS 变量 + `prefers-color-scheme` + `data-theme`，
  二维码在两种主题下都可扫。
- **实例卡片（功能 17）**：`InstancesState` + `AvatarCache`（LRU 50），显示头像、昵称、
  已运行时长、最近掉线时间。
- **新手引导（功能 18）**：装好 → 扫码 → 拉群 → 发测试 → 开保活，五步按真实状态判定，
  可跳过（`localStorage.qqbot_onboard_done`）。
- **一键备份 / 恢复（功能 50）**：`sandbox/backup.sh`、`restore.sh` 打 tar.gz + sha256，
  SAF 选目录/选文件（`REQ_BACKUP_SAVE` / `REQ_RESTORE_OPEN`），恢复前自动快照、失败回滚。
- **存储占用（功能 54）**：`storage.sh` 统计五个目录 + top10 大文件 + 一键清缓存
  （只清 apt/pip/npm 缓存与旧日志，不碰登录、配置、插件）。
- **应用内公告（功能 56）**：`/api/announce` + ETag/304、5 秒超时静默、可关顶部条，最小版本只提示。
- **崩溃聚合看板（功能 58）**：`CrashBoard` 把崩溃按「模块 + 偏移 + 信号」归并成签名，
  `state/crash-counts.json` 计数；上报默认关闭，只发签名 + 版本 + 次数。
- **匿名统计（功能 59）**：默认关闭，首次开启弹说明，安装随机 UUID（`state/stats-id`）可重置，
  只统计在线时长、消息数、异常数、版本、OS、架构。
- **服务端**：新增 `vps-deploy/atom/`（systemd `qqbot-atom`，127.0.0.1:8800），nginx 精确匹配反代
  `/api/announce`、`/api/crash`、`/api/stats`、`/api/atom-health`、`/api/app-config.json`；
  POST 需带 `X-Qqbot-Atom: 1`，60 秒 20 次限流；crash 按 `sig+ver` 合并、stats 按 `id+day+ver` 覆盖。

### 修改

- **构建链**：`build-ui.ps1` 新增第 3e 段，把 `ui/assets/android-res/**` 拷进 `_apkwork/res`，
  并把 `ui/assets/android/widget-manifest.xml` 插到 `</application>` 之前（已有 provider 就跳过，
  幂等；ps1 仍保持纯 ASCII）；`build-apk.ps1` 新增 2b 段，打包前把根 `VERSION` 写进
  `build/opt/qqbot/VERSION`，zip 完再删掉 —— 崩溃聚合与 doctor 读的就是它。
- **停止链路**：`stop-sandbox.sh` 在参数解析后**立刻**写 `watchdog.off` 与 `state/offline`
  （原来是最后写，中间 4~14 秒的窗口会让看门狗把刚停的沙盒又拉起来）；`watchdog.sh` 主循环
  同时认 `state/offline`，`restart_sandbox` 不再 `rm` 掉停止标记；`stop-sandbox.sh` 优雅窗口
  30→4 秒（整条停止从 19 秒降到 10 秒内）。
- **守护任务**：`QqbotGuardTask` 每 tick 写 `state/ui.json`，异常类状态全部走
  `StateIO` 原子写；新增 6 小时周期的 `UploadState.periodic` 上报。
- **控制台**（`ui/assets/qqbot/index.html` 3925 → 5260 行）：主页省电提醒条、风控红横幅、
  崩溃横幅、今日概览、新手引导、实例卡；`更多` 页备份/存储/崩溃看板/统计/省电策略卡；
  自动更新弹窗、深色模式三档、隐私政策弹窗。

### 修复

- **「从文件恢复」点了没反应**：JS 调 `brCall1("restorePick","all")`，而桥上是无参的
  `restorePick()`（带参的是 `restorePickScope(String)`），异常被 `brCall1` 吞掉后页面照样提示
  「已打开文件选择器」。改成先试 `restorePickScope` 再退回无参，返回空就报错。
- **停止后沙盒自己又活了**：见上面「停止链路」；四入口二次确认也统一成「入口只唤起面板、
  由面板判状态」，避免服务在 targetSdk 28 下被 `Background activity launch blocked` 拦掉。
- **下线确认只改 UI 不改进程**：`stopDo()` 原来把「桥没回话」当成功，界面显示已下线而沙盒照跑；
  改成 `ok === false` 或空返回一律报「下线失败：原生没有响应」。
- **小组件文案与事实相反**：卡片用 `alive && serviceAlive` 判断，沙盒已下线仍显示「停止」；
  改成 `alive && serviceAlive && online > 0`。
- **页面 4 秒自动刷新会把用户顶走**：`refreshAll()` 记录/恢复 `pageYOffset`，二次确认弹框在
  刷新后也能保持展开。
- **`更多 → 从文件选择恢复` 按钮文案与行为不符**：统一成「在浏览器打开 / 恢复」口径。

### 说明

- 从 1.1.29 升上来只需装新 APK、重启服务；沙盒脚本会随 APK 自动同步
  （`scripts.log` 里 `synced=40 failed=0`）。
- 图标换成 PNG 之后安装包增大 430,601 B（图标 398,270 B + 新代码约 32 KB），
  超出最初「增量 ≤300 KB」的预算，属于质量优先的取舍：48px 下仍能认字靠的就是这些档位。
- 匿名统计与崩溃上报**默认关闭**，开了也只发聚合后的数字与签名，不含路径、时间、账号、聊天内容。
- 不再支持 `run-as com.termux`（1.1.28 起去掉 `debuggable`），调试请用 `adb root`。

## [1.1.29] - 2026-10-19

1.1.28 是同一批改动的第一个包，发出去之后发现自动更新弹窗里的更新说明带了两行废字
（「AstrBot 烛版 1.1.28」和「[1.1.28] - 2026-10-19」，弹窗标题里本来就写着版本号）。
这一版只做这一处收尾，功能与 1.1.28 完全一致。

### 修复

- **更新弹窗里的说明更干净**：自动跳过发布页正文开头的版本标题行与日期行，
  正文里的 Markdown 记号（`#`、`**`、`-`、链接）也先剥掉再展示，最多六行、220 字。

### 说明

- 1.1.28 / 1.1.29 的四项改动（打开即检查更新并弹窗、AstrBot 启动提示、
  WebUI 走系统浏览器、隐私政策 + 去掉 debuggable）相同；直接用 1.1.29 即可。
- 不用重装沙盒：装完新 APK、重启服务即生效。

## [1.1.28] - 2026-10-19

NapCat 已经能在真机上跑起来了，这一版处理的是「用起来」和「装得上」的问题：
启动要等多久、面板在哪儿打开、每次打开要不要提醒更新、以及小米手机装包时那句「缺少隐私政策」。

### 新增

- **每次打开自动检查更新**：控制台一加载就查一次（不再受 12 小时节流），查到新版本自动弹窗，
  「去下载」走系统浏览器，「以后再说」记住这个版本、下次不再打扰。查不到或超时都静默，
  不打扰正常使用；被免责声明挡在后面时，点完「同意」会补弹。
- **AstrBot 冷启动提示**：运行状态卡片里出现一行琥珀色小字
  「AstrBot 正在启动，通常需要 40–60 秒，请稍候」。以前这段时间磁贴只写「离线」，
  容易被当成装坏了；AstrBot 起来后这行自己消失，守护进程没跑的时候也不显示。

### 修改

- **WebUI 一律用系统浏览器打开**：NapCat 面板与 AstrBot 控制台的按钮改成
  「在浏览器打开 …」，点击后不再跳应用内的 WebView，而是交给系统默认浏览器打开
  `http://127.0.0.1:6099` / `http://127.0.0.1:6185`（仍然只放行本机地址）。
  好处是能正常走浏览器的密码管理、扫码页和 AstrBot 管理后台。

### 修复

- **小米手机安装时提示「缺少隐私政策」不给装**：排查结论是小米 HyperOS 的
  「应用包管理组件」+ 安全中心做的风险判定，命中的是《风险应用审查标准》里
  「应用隐私政策未向用户明示」这一条，跟 Android 平台的 manifest 属性无关
  （`android:privacyPolicy` 这个属性根本不存在）。这一版能做的我们都做了：
  1. 控制台新增**《隐私政策》**页：`更多 → 关于与支持 → 隐私政策`，
     首次启动的免责声明弹窗里也有入口。写清「不收集个人信息」「唯一会上传的是
     你主动提交的错误日志」「七类权限各自干什么用」「数据存在哪、怎么删」「联系方式」。
  2. 从 manifest 去掉 `android:debuggable="true"` —— debug 包是被判定「被重新打包」的
     显式标记。
  3. 把这条剥离固化成构建步骤（`build-ui.ps1`，幂等），以后每次构建都不会再带 debug 标记。
- **说明**：小米的拦截发生在安装器里、由云端与本地缓存共同判定，APK 里补隐私政策
  **不保证**能解除拦截。真机上仍然有效的手段都在安装器侧：
  关掉手机管家的「安装监控 / 安全守护 / 应用防护」，或对「应用包管理组件」卸载更新 +
  清除数据 + 禁止联网，或直接 `adb install -r`。这些已经写进《使用说明》的安装章节。

### 说明

- 不用重装沙盒：装完新 APK、重启服务即生效。
- 从本版起 `adb shell run-as com.termux …` 不再可用（debuggable 已移除）。
  需要看应用私有目录请用 `adb root`，临时排障仍可用 `adb logcat`。

## [1.1.27] - 2026-10-19

和 1.1.26 是同一个修复（proot loader 节头清零），只多了一层保险 —— 真机没到手上，
补丁万一跟某台设备的 proot 不兼容，不能把「NapCat 崩」换成「沙盒起不来」。

### 加固

- **起沙盒前先探一次**：`start-sandbox.sh` 在用打补丁的 loader 之前先跑一句最短自检
  （`proot -0 /system/bin/echo`），跑不通就立刻退回系统 loader、把现场存到
  `~/.qqbot/logs/loader-probe.log`，并写 `~/.qqbot/proot-loader.off` 标记 —— 之后不再尝试。
  删掉那个标记就会重新试一次。
- **部署自检失败也留标记**：`qq_proot_selftest()` 的自动回退同样会写这个标记，
  避免「回退一次、下个脚本又重建补丁、再挂一次」的循环。
- **「检查运行环境」多一个状态**：`loader补丁:` 现在有「已停用（proot-loader.off 存在）」这一档。

### 说明

- 修复内容与 1.1.26 完全一致，装在真机上行为相同；只是多了一道「不兼容就自动退回」的保险。
- 依旧不用重装沙盒：脚本随 APK 下发，装完重启服务即生效。

## [1.1.26] - 2026-10-19

1.1.25 的判断被真机报告推翻了：`NAPCAT_FORCE_NODE_PROCESS=1`（走 fork）**没有**绕开崩溃，
真机装上 1.1.25 后日志变成了 `[NapCat] [Fork] Worker进程意外退出`，退出码依旧是 11。
这一版找到了真正的根因，并且不用改 NapCat、不用换 QQ、不用重装沙盒。

### 修复

- **真根因：proot 的 loader 节头表在映射之外**。Termux 那份 proot 会把
  `$PREFIX/libexec/proot/loader`（18136 字节，`e_shoff=0x45d8`）映进每个被追踪的进程，
  但只映射该文件的两段 PT_LOAD：
  `00200000-00201000 r--p off 0`（这一段 filesz 只有 0x120）和
  `5400000000-5400001000 r-xp off 0x4000`。而这个 loader 的节头表在文件偏移 0x45d8，
  落在两段映射之外。NapCat 注入的 frida-gum 在枚举模块时把 0x200000 当模块基址、
  按 `e_shoff` 去读节头，算出来的 `0x200000 + 0x45d8 = 0x2045d8` 没有任何映射，
  于是 `SIGSEGV / SEGV_MAPERR`，`si_addr` 正是 `0x2045d8` —— 与真机崩溃记录逐字节吻合。
  崩溃指令随后的拷贝步长正好是 64 字节（`Elf64_Shdr` 的大小），进一步印证。
- **修法：给 loader 打一份「节头清零」的副本**。把 loader 复制到 `~/.qqbot/proot-loader`，
  把 `e_shoff`(偏移 0x28) 与 `e_shnum`/`e_shstrndx`(偏移 0x3C) 清零，再用 `PROOT_LOADER`
  指向这份副本。内核执行时只看 program header，节头表只是给工具看的，清零不影响 proot；
  gum 拿不到节头（`e_shnum=0` 时它直接跳过整段拷贝循环），也就不会再越界读。
  **不改 Termux 的安装文件**，删掉这个环境变量即可完全回退。
- **自检带自动回退**：如果某台设备的 proot 不认这份替代 loader，`qq_proot_selftest()`
  会发现自检失败，自动退回系统 loader 再试一次（并把原报错存到 `proot-selftest.log.patched`），
  宁可少一层保护，也不让沙盒起不来。
- **诊断补全**：「检查运行环境」新增一行 `loader补丁:`（已启用 / 已生成但没挂上 / 未启用，
  并打印原 loader 的 `e_shoff`）；「上传错误日志」新增 `proot loader 补丁` 段，
  下次再有崩溃能直接看出补丁到底有没有生效。

### 说明

- 1.1.25 的 `NAPCAT_FORCE_NODE_PROCESS=1` 保留（上游推荐路径，无害）；真正的修复与它无关。
- x86_64 模拟器上不走这条逻辑：那边用随包内置的静态 proot，没有独立 loader 文件，
  本来就不会踩到这个坑。
- 这一版**不需要重装沙盒**：脚本随 APK 的 `assets/qqbot-scripts.zip` 下发，装上新 APK 后
  `syncScripts()` 会覆盖到 `$PREFIX/opt/qqbot`，重启服务即生效。

## [1.1.25] - 2026-10-19

真机上 NapCat 的 Worker 子进程「退出码 11」这一版**修掉了**。根因不是我们打包的问题，
是 NapCat 让 Worker 走 Electron 的 `UtilityProcess`（Chromium 进程模型），在 arm64 + proot 下
注入的 native 补丁会踩到坏指针。上游对此有官方开关，我们把它默认打开。

### 修复

- **Worker SIGSEGV（退出码 11）**：真机崩溃现场是
  `signal = 11 (SIGSEGV)`、`si_code = 1 (SEGV_MAPERR)`、`si_addr = 0x550bd1f3f8`，
  崩在 `.../app_launcher/napcat/native/napi2native/napi2native.linux.arm64.node(gum_elf_module_load+0x880)`，
  5 份崩溃记录完全一致 —— 即 NapCat 注入的 frida-gum 在解析符号表时踩了空指针。
  现在默认设 `NAPCAT_FORCE_NODE_PROCESS=1`，NapCat 改用 `child_process.fork` 起纯 Node 子进程，
  不再走 Chromium 那套进程模型，崩溃消失。这正是上游 PR #1984 的修复路径。
- **默认值由脚本注入，用户不用操作**：`sandbox/run-napcat.sh` 启动 QQ 时先扫 `qq-env.txt` 里有没有
  同名键，没有就补上 `NAPCAT_FORCE_NODE_PROCESS=1`，并在 `supervisor.log` 里写明当前模式
  （`走 fork 模式` / `由 qq-env.txt 指定：…`）。想把行为改回去，写
  `~/.qqbot/cache/qq-env.txt` 一行 `NAPCAT_FORCE_NODE_PROCESS=0` 即可，不用重装。
- **老设备不用重装沙盒**：脚本随 APK 的 `assets/qqbot-scripts.zip` 下发，装上新 APK 时
  `syncScripts()` 会自动覆盖到 `$PREFIX/opt/qqbot` 并重启容器，实例重启后即生效。
- **WebUI 白屏一并消除**：这条上游修复的另一半就是白屏 —— Worker 崩掉之后 6099 只剩空页面。

### 说明

- 模拟器实测（x64 版 QQ + amd64 rootfs）：`supervisor.log` 打出「NapCat worker 走 fork 模式」，
  Worker 的 `cmdline` 是 `/opt/QQ/qq --no-sandbox .../napcat.mjs`（不再有 `--type=utility`），
  `[Napi2NativeLoader] 加载成功`、`NapCat.Core Version: 4.18.38`、二维码正常生成，
  6099 的 WebUI 返回 200，AstrBot 侧 aiocqhttp 平台在线、OneBot 通道已连接。
- 已经排除的两个方向，写在这里省得再试：`NAPCAT_DISABLE_BYPASS=1` 无效（真机日志里 bypass
  六键早在崩溃前就跑完了，`[PacketHandler] 初始化成功` 在崩之前）；`NAPCAT_DISABLE_MULTI_PROCESS=1`
  更糟（单进程下跑到 `prepare write and writev hooks` 就崩）。

## [1.1.24] - 2026-10-19

真机上 NapCat 的 Worker 子进程还是以「退出码 11」反复退出。这一版不带修复，
带的是**能看见崩在哪**的工具——上一次的崩溃记录器在真机上一份文件都没写出来。

### 修复

- **崩溃记录器终于能抓到 Chromium 的崩溃**：上一版是在加载时用自己的信号处理器
  覆盖一次，但 Chromium 启动时会调用 `sigaction()` 把自己的处理器装上去，直接把我们
  顶掉——所以真机上 `crashes/` 一直是空的。这一版自己导出一个 `sigaction()`：
  应用来装处理器时我们先记下来，实际装的是我们的；崩溃时先写现场，再把控制权
  交还给应用原本那个处理器，行为不变。**已在模拟器上用 `kill -SEGV` 验证抓取成功。**
- **崩溃现场多了「pc 所在段」**：直接把 `pc` 落在哪一段映射、是哪个 `.so`、
  段内偏移多少算出来写进文件，不用再拿 maps 手工对。
- **崩溃文件只留最近 4 份**：真机上每 15 秒崩一轮，不清理会攒出几百个一百多 KB
  的文件，把错误报告撑爆。
- **新增 `qq-env.txt`**：想给 QQ 传环境变量时，写
  `~/.qqbot/cache/qq-env.txt`（一行一个 `KEY=VALUE`）后重启服务即可，不用重装。

### 说明

- 用 `kill -SEGV` 在模拟器上对照确认：日志里的**「Worker进程退出，退出码: 11」
  就是 SIGSEGV**（不是普通退出码）。
- 试过 NapCat 自带的单进程开关 `NAPCAT_DISABLE_MULTI_PROCESS=1`（`napcat.mjs` 里
  直接 `await N()`，根本不 fork worker），**这条路不通**：单进程下 NapCat 在 QQ
  主进程里跑到 `prepare write and writev hooks` 就 SIGSEGV，模拟器上必崩。
  worker 子进程正是 NapCat 用来规避「在主进程里 hook 会崩」的，关掉只会更糟。

---

## [1.1.23] - 2026-10-19

真机上的 NapCat 现在停在「`/opt/QQ/.../app_launcher/napcat/napcat.mjs` 找不到」——
重装那一步把旧 NapCat 删掉了、新的没装进来，而界面还显示「100% + 四个绿勾」，
看着像装好了，其实是失败（退出码 1）。

### 修复

- **重装失败不再伪装成成功**：以前不管成功失败，进度条一律打满、四个阶段全打绿勾。
  现在失败时进度条变成红色、失败的那一步打红叉、百分比只算它前面已经完成的步骤，
  标题下面直接写「卡在「安装 NapCat 与 QQ」」。
- **重装中途失败不再把 NapCat 弄没**：`reinstall.sh` 原来直接 `rm -rf` 掉整个 napcat
  目录再装，`50-napcat.sh` 又是 `set -e`，中途任何一步失败就留下「目录空了、新的没来」
  的死状态。改成先把旧目录改名成 `/root/napcat.bak`，装完**实际校验**
  `napcat.mjs` 与 `package.json` 是否在位；校验不过自动把备份挪回去 ——
  最坏情况是「装的还是旧的」，而不是「彻底没有」。
- **错误报告带上重装日志**：`upload-log.sh` 新增「重装日志尾部 150 行」、
  「组件关键文件是否在位」（napcat.mjs / package.json / loadNapCat.js / app/package.json
  的 `main` 字段）、`app_launcher/napcat` 目录内容、`NapCat.Shell.zip` 是否还在、
  `qq-args.txt` 有没有覆写、崩溃记录目录清单。以前 `reinstall.log` 根本没进报告，
  只有一个退出码 1，完全看不出死在哪一行。

### 说明

- 上一版（1.1.22）卸掉了 Worker SIGSEGV 那一层：崩溃记录器已经在真机上跑起来了
  （报告里的「崩溃现场」段现在是空的，说明 QQ 根本没崩过，是模块压根没加载）。
  这一版修的是它后面那一层。

---

## [1.1.22] - 2026-10-19

真机上 NapCat 的 Worker 子进程一启动就 SIGSEGV（退出码 11），但日志只告诉我们「它崩了」，
不告诉我们在哪崩的。这一版加了崩溃现场记录器，把调用栈弄回来；同时把「改对接要不要重启
AstrBot」这件事分清楚了。

### 新增

- **崩溃现场记录器（`sandbox/crashwatch.c`）**：一个用 `LD_PRELOAD` 注入 QQ 的小型动态库，
  首次编译在手机上用 `g++` 现场完成（不额外打包二进制）。捕获 SIGSEGV/SIGABRT/SIGBUS/
  SIGILL/SIGFPE/SIGTRAP 时，把信号、`si_code`、出错地址、RIP/RSP/RBP、栈内容、
  `/proc/self/maps` 里的模块基址、以及**用 `dladdr` + `backtrace` 解出来的可读调用栈**
  写进 `cache/crashes/crash-<pid>-sigN.log`。信号处理函数里只做异步信号安全的事
  （预开 fd、静态缓冲、`write`），不会因为崩溃处理器自己再崩一次而丢掉现场。
  每个实例按 tag 分开落盘，单文件上限 256 KB。
- **错误报告带上崩溃现场**：上传日志现在会附上最新的崩溃文件，并额外采集运行期信息
  （磁盘/内存、proot 命令行、会不会是 link(2) 问题、沙箱各目录大小、端口监听情况、
  最近改动过的文件、dmesg 里的被杀记录）。以后再排查不用再猜。

### 修复

- **改对接不再重启 AstrBot**（`sandbox/link.sh`）：以前只要平台端口清单和现有配置不一致
  就 `kill -TERM` 掉 AstrBot 重启一次，约 30 秒，其它号的连接一起断。现在只有
  「**新增实例**，需要 AstrBot 多监听一个端口」才重启一次；**删除实例**只在 AstrBot 里留下
  一个没人连的监听口，不影响任何功能，等下次因为别的原因重启时顺手清掉；停用实例、
  改 token 之类端口清单根本没变，AstrBot 一个信号都不收。
  （查过 AstrBot 的代码：`astrbot/dashboard/api/platform.py` 与
  `services/platform_service.py` 只有 webhook 注册，运行期没有任何「新增 aiocqhttp 监听口」
  的热加载入口，所以这一处重启没法省。）
- **另外两个路径 bug**：`qq-args.txt` 覆写开关和崩溃目录都写在沙盒里，而沙盒内
  `$QQBOT_HOME` 是空的（它是 Termux 侧的变量），展开成了 `/qq-args.txt`，导致 1.1.21
  里这个开关一直没生效过。一并改成沙盒内的绝对路径 `/qqbot-cache/…`。
- **修 CHANGELOG 1.1.21 条目的乱码**：那次写入时中文被按 ASCII 编码吞成了 `?`
  （GitHub Release 正文和站点更新日志卡都跟着坏了），这一版已还原。

## [1.1.21] - 2026-10-18

1.1.20 把 Xvfb 的色深和 GPU 那关过了之后，NapCat 往前走了一大步，卡在 `Worker` 子进程
`退出码: 11`（SIGSEGV）。这一版不解决它，但让现场能看见：加了启动提示和重装进度，
任何一次启动、重装、出问题都不再是黑盒。

### 新增

- **首页启动提示条**：打开软件就能看到「正在启动容器…（N 秒）」，逐项打勾
  `后台服务 ✓` → `NapCat ✓` → `AstrBot ✓`，全部就绪后变成绿色的「容器已就绪 ·
  NapCat 在线 · AstrBot 在线（本次启动共 N 秒）」，8 秒后自动收起。失败时保持红条，
  并把下一步该做什么直接写在条上。
- **设置页重装进度条**：点重装 NapCat / 重装 AstrBot 后显示四步进度（50%~100%），
  阶段名读 `reinstall.log` 里脚本自己打的标记：`等待退出` / `清理旧文件` /
  `安装 NapCat 与 QQ` / `重建 OneBot 对接` / `恢复数据`，完成后显示完成时刻 HH:MM:SS。
  只有重装期间才轮询，平时不耗电。
- **「上传错误日志」搬到「设置 → 维护」，常驻**：以前这个按钮只在部署失败时才显示，
  部署成功但运行期出问题的人就没有入口了（用户就是这么被卡住的）。

### 修复

- **QQ 启动参数可覆写**（`sandbox/run-napcat.sh`）：在手机上加 `cache/qq-args.txt`，
  里面写什么就用什么启动 QQ，绕过默认的参数阶梯；删掉文件即恢复。远程排查时不用
  再为「换个参数试试」发一整版 APK。
- **失败日志按症状分诊**：QQ 起不来时，日志直接点名病根 —— 见到
  `Worker进程退出` 就说这是 NapCat 的 Worker 子进程 SIGSEGV；见到 X 就报色深；
  见到 `GPU process isn't usable` 才去查 24 位色深和 /proc。

### 说明

- 真机 Redmi 24069RA21C、Android 16 上，1.1.20 的 Xvfb 24 位色深修复生效了，
  GPU/EGL 报错全消失，NapCat 已经能打印 `NapCat.Core Version: 4.18.38`，最后死在 Worker
  子进程 `退出码: 11`。模拟器复现不了这个问题，所以这个版本的重点是「让你能看见现场」。

## [1.1.20] - 2026-10-18

1.1.19 把 QQ 的启动参数做成阶梯（`--disable-gpu` 之类）去绕 GPU 崩溃，真机上照样崩。
这一版找到了真病因：**Xvfb 的色深**。

### 修复

- **Xvfb 一律用 24 位色深**（`sandbox/run-napcat.sh` 的 `XVFB_VARIANTS`）：8 位屏没有可用的
  GL 配置，16 位配不出 EGL config，只有 24 位能让 QQ 里 Chromium 的 GPU 子进程初始化成功。
  原来那档 `1x1x8 +extension GLX +render` 是照抄参考项目 AstrBot-Bubble-Android-App 的，
  我们跟着抄错了一整版。同一台模拟器、同一份 rootfs 只换色深实测：8 位打出 20 条
  ANGLE/EGL 报错，16 位报 `No suitable EGL configs found`，24 位一条 GL 报错都没有、
  二维码正常输出。
- **`--disable-gpu` 从参数阶梯里删掉**：它只是不让 Chromium *用* 硬件加速，GPU 进程照样起、
  EGL 照样初始化；而一旦带了它，GPU 进程崩掉时判定「没有软件合成可退」，直接
  `GPU process isn't usable. Goodbye.` 把主进程带走 —— 1.1.19 就是栽在这上面。现在第一档
  是「什么额外参数都不加」，后面几档（`--disable-gpu-sandbox`、swiftshader、`--in-process-gpu`）
  只作别的机型兜底。
- **自检脚本同步**（`doctor.sh`）：逐档试起 Xvfb 的参数也从 8 位改成 24 位，否则用户在
  「检查运行环境」里看到的仍是必然失败的组合，还会误以为是环境坏了。

### 说明

- GPU 进程退出时常伴随 `sandbox/linux/services/proc_util.cc:114 Check failed: . :
  Operation not permitted`，那是它退出时的清理动作（枚举 `/proc/self/fd` 做沙箱自检），
  是症状不是病因，别再被它带偏。

---

## [1.1.19] - 2026-10-18

接 1.1.18 往下修：Xvfb 在真机上总算起来了，但 QQ 自己秒退，表现成
`Trace/breakpoint trap`，NapCat 照样起不来。

### 修复

- **QQ 启动改成参数阶梯**（`sandbox/run-napcat.sh`）：真机（Redmi 24069RA21C / Android 16）
  上 QQ 的 Chromium 会先起一个 GPU 子进程去初始化 EGL —— Xvfb 里没有 GLX，这一步必然打出
  `GLX is not present` / `Initialization of all EGL display types failed`；紧接着它还要枚举
  那个子进程的 `/proc/<pid>/fd` 来校验沙箱，而 Android 10 起 `/proc` 对非本进程大幅收紧，
  `fstatat()` 直接 EPERM：`FATAL:sandbox/linux/services/proc_util.cc:114
  Check failed: . : Operation not permitted (1)`。连撞三次后
  `GPU process isn't usable. Goodbye.` 把主进程一起带走。现在按
  `--disable-gpu` → `--disable-gpu --in-process-gpu` →
  再加 `--disable-dev-shm-usage --no-zygote` → 原样（1.1.18 的行为）四档依次试，留下第一档
  能活过观察期的，并把最终采用的参数写进服务日志，下次排障一眼就能看到走的哪一档。
- **「设置 → 维护」增加常驻的「上传错误日志」**：这个入口原来只挂在「部署失败 / 卡住」的
  弹窗上，可真实场景恰恰是「部署成功、服务起不来」，于是最需要它的时候偏偏找不到。

### 说明

- x86_64 模拟器的 `/proc` 规则和真机不同，同样的参数在模拟器上能跑起来，所以这个问题
  在模拟器上复现不了，只能在真机上验。

## [1.1.18] - 2026-10-18

修掉「真机上 NapCat 怎么都起不来、AstrBot 却一切正常」的根因。一句话：**部署期和运行期用的
proot 参数不一致**，运行期把 `--link2symlink` 丢了。

### 修复

- **运行期沙盒启动补回 `--link2symlink`**（`opt/qqbot/start-sandbox.sh`）：真机上 `link(2)` 直接
  返回 EPERM，而 Xvfb 起显示号的第一步就是给 `/tmp/.X<n>-lock` 建硬链接，建不了就
  `Lock file ... failed: Permission denied` + `【Fatal】 server error: Failed to establish socket`
  秒退，于是 NapCat 永远拉不起来。部署期（`qq_proot`）本来就按 `$QQBOT_HOME/link2symlink`
  标记加了这个参数，运行期却硬编码不加 —— 同一个手机，apt 装得上、QQ 起不来。AstrBot 是纯
  Python 用不到 `link(2)`，所以看起来「只有 NapCat 坏了」。
- **标记丢了会自愈**：运行期不再只信标记文件，标记不在时会在宿主侧实测一次 `link(2)`，
  判不出来就写回标记，避免「清过数据 / 换过安装路径」之后再踩一次。
- **Xvfb 多两档 `-nolock` 兜底**（`sandbox/run-napcat.sh`）：`XVFB_VARIANTS` 从 3 档扩到 5 档，
  后两档带 Xvfb 自己的 `-nolock`（`-help` 原文 "disable the locking mechanism"），完全不碰
  锁文件，从 X 服务器这层绕过去，与 proot 参数无关。正常设备行为不变（前 3 档仍然是原来的
  参数和顺序）。

### 说明

- **覆盖安装即可，不用重装、不丢数据**：`syncScripts()` 会按 APK 的 `lastUpdateTime` 覆盖
  `$PREFIX` 下的脚本，并自动重启容器让新参数生效。装完后 `logs/scripts.log` 里的
  `synced=N failed=0` 就是脚本已下发的证据。
- 早期那条「绝不能加 `--link2symlink`」的警告已删除：它只适用于「rootfs 在 proot **内部**
  用 tar 解压」的旧流程（tar 会为压缩包里的硬链接调 `link()`，被换成指向宿主绝对路径的
  `.l2s.*` 符号链接，导致 `libc.so.6` 悬空）。现在解压全部在宿主侧完成，运行期只有
  dpkg / Xvfb 这类零散 `link()` 会走到它。
- 真机失败诊断里新增单独一行 `[xvfb] link(2) 可用: yes/no`，不再把「链接建不了」伪装成
  「磁盘满 / 权限不对」。

---

## [1.1.17] - 2026-10-18

只修一处文案错位，但它是会让人**第一次登录就被拒**的那种：终端提示里的 Token 和实际生效的
Token 不是一个词。

### 修复

- **部署汇总与 `qqbot-help` 里打印的 NapCat WebUI Token 写错了**：一直写「Token 填 `qqbot`」，
  而实际生成到 `webui.json` / `bots.json` 里的是 **`zhuxin`**（1.1.x 品牌改名时改的）。
  Java 侧（新建实例默认 `zhuxin`、老设备迁移）与界面（实例卡回退值 `zhuxin`）当时都跟上了，
  只有 `steps/90-summary.sh` 和 `steps/70-launcher.sh` 这两处终端提示漏了 —— 照提示填 `qqbot`
  会被 NapCat WebUI 直接拒绝，用户很容易误判成「装坏了」。
- **默认实例的播种值同步改成 `zhuxin`**：`opt/qqbot/link.sh`、`opt/qqbot/sandbox/link.sh`
  生成 `bots.json` 时写的 token 由 `qqbot` 改为 `zhuxin`，与界面、`使用说明.md`、`README.md` 一致。

### 说明

- 存量设备不受影响：`run-napcat.sh` 里 `qqbot` / `qqbot2` … → `zhuxin` / `zhuxin2` … 的归一化
  逻辑保留（那条规则只服务已部署过的老 `bots.json`），并补了注释说明它的存在理由。

---

## [1.1.16] - 2026-10-18

真机自检结果：三档 Xvfb 参数**全部能起来**，`/tmp` 可写、Xvfb 不缺库、qq 二进制在，
但 6099 端口没监听、`qq-bot1.log` 停在 00:30 不再增长 —— 说明不是参数问题，
而是 QQ 压根没被拉起来（或起来就退了）。这一版针对「显示号被残留进程占着」和
「看不到守护到底在干什么」两个盲区。

### 修复

- **守护启动时先清扫上次残留的 Xvfb / QQ 进程和 X 锁文件**：上次守护被强杀、或 proot 整个重启后，
  老的 Xvfb 还攥着 `/tmp/.X11-unix/NN`。新 Xvfb 一启动就 `Server is already active for display N`
  秒退，三档参数全部失败 —— 现象和我们看到的完全一致。
- **一个显示号三档都失败时，换下一个显示号再整套试一遍**（最多 3 个号）。
  之前一个号失败就放弃本轮，而 `pick_display` 只看 socket 文件在不在，文件被清掉但号还被占着时
  会一直挑到同一个坏号。
- **X 就绪后按实际使用的显示号启动 QQ**：`start_xvfb` 换过号时，`DISPLAY` 变量以前不会跟着更新。
- **失败时打印进程表**：所有 Xvfb / QQ 进程的 cmdline 和 `/tmp/.X11-unix` 内容，
  直接看出「X 有没有在跑、QQ 有没有真的活着」。

### 自检增强

- 「检查运行环境」现在还会打印 **`supervisor.log` 末 30 行**、**`qq-*.log` 末 30 行**、
  以及当前所有 Xvfb / QQ 进程的完整命令行。以前只报文件大小，最关键的内容看不到。
## [1.1.15] - 2026-10-18

上一版把「X 起不来就不要启 QQ」这条补上了，但真机上还是起不来 —— 说明 **Xvfb 自己就没起来**。
这一版不再赌一种参数，而是逐档回退，并且把 Xvfb 自己说的话摆到用户面前。

### 修复

- **Xvfb 三档参数逐档回退**：`1x1x8 +GLX +render` → `640x480x24`（不要求 GLX）→ `1280x800x24`。
  真机上最可能的情况是 arm64 的 rootfs 用 `--no-install-recommends` 装出来没有 mesa 的 GLX 模块，
  Xvfb 一加 `+extension GLX` 就 `Fatal server error: could not enable GLX extension` 秒退；
  而原来失败后只会拿同一套参数再试一次，等于试了个寂寞。现在第二档不要求 GLX，纯软件也能起。
- **每档失败都把 Xvfb 自己的报错打出来**（前 14 行），并清掉它残留的 `/tmp/.X<n>-lock` 与 socket，
  否则 `pick_display` 会一直跳过那个号，重试几次把 40~99 全占满。
- **补装 mesa / xkb 相关包**：`libgl1 libglx-mesa0 libgl1-mesa-dri xkb-data x11-xkb-utils`
  （只对新装环境生效，老环境靠上面的回退）。
- **`trap '' PIPE`**：照参考项目的做法。QQ 往已关闭的日志管道写时会被 SIGPIPE 直接打死，
  症状正是「刚起来就没了、日志里什么都没有」。

### 新增

- **设置页 → 维护 → 检查运行环境**：`doctor.sh` 只读自检，报告 proot / supervisor / 三个端口 /
  NapCat 日志，并**在沙盒里真的把三档 Xvfb 各起一次**（各 10 秒），把 Xvfb 的输出原样列出来。
  结果存成 `~/.qqbot/doctor.txt`。
- **错误报告带上 NapCat 与 Xvfb 日志**：`upload-log.sh` 现在还会打包 `qq-*.log` 末 120 行、
  每个实例的 `run/xvfb-*.log` 前 60 行、以及 `doctor.txt`。以前 NapCat 起不来的报告里
  一条相关线索都没有，只能靠猜。
## [1.1.14] - 2026-10-17

真机日志把「NapCat 一直起不来」的真相说清楚了 —— 不是慢，是 QQ 每轮都在失败退出：

```
[17152:...:ERROR:ui/ozone/platform/x11/ozone_platform_x11.cc:256] Missing X server or $DISPLAY
[17152:...:ERROR:ui/aura/env.cc:257] The platform failed to initialize.  Exiting.
```

QQ 起来后等 20 多秒连不上 X，就退出；supervisor 5 秒后再拉一遍，于是看起来「十分钟都没启动好」。
也就是 Xvfb 根本没起来（或者起来又死了），而我们以前照旧把 QQ 拉起来 —— QQ 只能等死。

### 修复

- **Xvfb 起不来就不再硬拉 QQ**：先换显示号重试一次，仍失败就本轮跳过，5 秒后由 supervisor 再来，
  并把 Xvfb 的报错原样写进容器日志（`[xvfb] ...` 缩进行），下次截图就能看到它为什么起不来。
- **等待时间从 6 秒放宽到 20 秒**：真机上 Xvfb 冷启动可能更久，以前等到 6 秒就硬启 QQ。
- **Xvfb 加 `-ac -nolisten tcp`**：关掉访问控制，不再需要 Xauthority cookie（xvfb-run 会临时生成，
  QQ 那边拿不到就连不上）；只走 unix socket，少一层失败可能。
- **失败时清掉 `/tmp/.XN-lock` 和 `/tmp/.X11-unix/XN`**：否则 pick_display 会一直跳过这个号，
  重试几次就把 40~99 占满。
## [1.1.13] - 2026-10-17

真机上 NapCat 启动要十几分钟。照着参考项目 [AstrBot-Bubble-Android-App](https://github.com/MuFengDR/AstrBot-Bubble-Android-App)，
把它启动 QQ 的方式搬了过来。

### 修复

- **不再用 `xvfb-run`，自己起 Xvfb**：`xvfb-run` 是脚本，会先跑 mcookie/xauth、再用 `-a` 从 99 往下试号，
  每次启动都多几秒，proot 里 xauth 偶发卡住还会让 QQ 一直等 X。
  现在自己起 Xvfb，等 `/tmp/.X11-unix/XN` 出现就直接拉 QQ（最多等 6 秒，超时也照启，日志写进 `xvfb-<实例>.log`）。
- **虚拟屏幕从 1280x800x24 改成 1x1x8**：QQ 根本不需要真的画东西，1 个像素 8 位就够。
  1024x768x24 那种帧缓冲只会让 Electron 的软件渲染白干活 —— 在 arm64 手机上这一步能慢到分钟级。
- **每个实例固定分配自己的显示号（40~99 里找空的）**，启动前清掉 `X<n>-lock` 和 `/tmp/.X11-unix/XN` 残留，
  避免上次崩溃留下的旧 X 让新实例连不上。
- **停止 / 退出时把 Xvfb 一起收掉**：它和 QQ 不是一个进程组，以前只 kill QQ 会留下 Xvfb 占着显示号。
- **装机时预热字体缓存**（`fc-cache -f`）：QQ 第一次启动会自己扫一遍系统字体，手机上这步能吃掉几分钟，
  表现出来就是「NapCat 十分钟都没起来」。现在第 3 步顺手扫完。
## [1.1.12] - 2026-10-17

第 2 步的硬链接问题解决了（体检判定后自动走 `--link2symlink`），真机上第 2~5 步一路通过：
rootfs、运行依赖、Linux QQ、NapCat 全部装好。现在卡在第 6 步 —— 装 AstrBot 的 Python 依赖。

### 变更

- **改用 uv 装 Python 依赖**：pip 在这台手机上要解析上百个包的依赖树，真机实测几分钟没有任何输出。
  换成 uv（单个静态二进制，astral-sh/uv，参考项目也用它），解析 + 下载通常一分钟内完成。
  uv 从 GitHub 下载，失败会自动换 ghfast.top / gh-proxy.com / ghproxy.net 三个镜像；
  全都拿不到就退回原来的 pip，不会因此装不上。
- **每一步都标出「本来就慢」和大概耗时**：步骤列表里给慢步骤加旁注 ——
  `下载并解压约 29MB，1~3 分钟`、`装几十个包，2~6 分钟`、`下载约 200MB，5~20 分钟`、
  `装 Python 依赖，5~20 分钟（最慢的一步，没输出是正常的）`。
  部署中的提示也改成「当前：安装 AstrBot 与 WebUI。这一步已经 3 分钟没有新内容输出，
  装 Python 依赖 5~20 分钟（最慢的一步，没输出是正常的），没输出是正常的……」
- **依赖安装期间每 45 秒打一行心跳**：`[sandbox] 仍在安装依赖…（时间）`。
  以前这一步可能几分钟零输出，界面显示「日志 N 分钟没有新内容」，用户以为卡死就强杀，
  结果永远装不完。

### 修复

- **卡住的时候也能上传日志了**：以前只有「部署失败」才出现「上传错误日志」按钮；
  现在部署还在跑、但日志 4 分钟没动时，红框下面同样会出现这条询问 ——
  「这次像是卡住了（日志已经 N 分钟没有新内容）。要把现场发给作者看看吗？」
  这样卡在第 6 步这种情形也能把现场发出来，而不是干看着。
## [1.1.11] - 2026-10-17

按用户给的参考项目 [AstrBot-Bubble-Android-App](https://github.com/MuFengDR/AstrBot-Bubble-Android-App) 的做法，
补上了硬链接不可用时的解法：**proot 的 `--link2symlink`**。

那个项目在启动 proot 时无条件带 `--link2symlink`，apt 就能在红米这类「link(2) 直接 EPERM」的机器上正常工作。

### 修复

- **存储不支持硬链接时不再判死**：体检发现目标目录、HOME、cache 三处都建不了硬链接时，
  不再报「必须卸载重装」，而是写下 `~/.qqbot/link2symlink` 标记并继续部署。
- **`qq_proot` 读到标记就加 `--link2symlink`**：proot 会用 rename + symlink 模拟硬链接，
  不依赖底层文件系统，dpkg 备份 `/var/lib/dpkg/status` 这一步就能过了。
- **默认仍然关闭**：只有在真的需要时才开，避免正常机器上多出一堆 `.l2s.*` 符号链接。
  rootfs 解压始终在宿主侧完成、不经过 proot，所以不会像早期那样把 libc 换成悬空符号链接。
## [1.1.10] - 2026-10-17

用户真机（Redmi / Android 16 / arm64）上传的报告把第 2 步的真因抓出来了：

```
目录权限: drwx------ 16 10521 10521 .../files/home/ubuntu   ← 属主就是应用自己
写测试: 通过
硬链接: 失败   ln: ... Permission denied
```

写是能写的，但 `link(2)` 被系统拒绝。dpkg 每次装包第一步就要给 `/var/lib/dpkg/status` 建硬链接备份，
建不了就整条链全挂 —— 这正是之前第 3 步 `error creating new backup file '/var/lib/dpkg/status-old'` 的根因。

### 新增

- **体检横向对照**：不再只测沙盒目录，而是一次测 HOME、`~/.qqbot/cache`、`$PREFIX/tmp`、沙盒目录四个位置，
  分别记「可以 / 不行（错误原文）/ 写不进去」。这样能分清是「这台手机的存储整体不支持硬链接」
  还是「这个目录被弄脏了」——两者的处理方式完全不同。
- **报告里加上 SELinux 状态和标签**：`getenforce`、`/proc/sys/fs/protected_hardlinks`、`ls -Zd` 的目录标签，
  下一次报告就能看出是不是安全策略在拦。

### 修复

- **体检提前到下载之前**：以前是先下 29 MB、解压完才测，白等几分钟；现在进第 2 步就测，几秒出结论。
- **沙盒目录脏了会自动重建**：如果只有沙盒目录建不了硬链接、而 HOME / cache 可以，说明是这棵树的问题，
  会自动删掉重建再试一次（重建后必定重新解压，不会拿半棵树继续）。
- **报错文案分场景**：设备整体不支持时，直接说清「应用数据要放内部存储」并提示把这份日志发给作者，
  不再让用户对着「不支持硬链接」瞎猜。
## [1.1.9] - 2026-10-17

真机上第 2 步开始报「沙盒目录不可写，或者它所在的文件系统不支持硬链接」—— 那句是 1.1.8 新加的
体检，但把「写不进去」和「不能建硬链接」两种完全不同的毛病混成了一句话，用户拿着它没法处理，
作者也判断不了。这一版把体检拆细，并加上「把现场发给作者」的一键上传。

### 新增

- **失败后可以一键上传错误日志**：部署失败时，红框下面会出现一行询问 ——
  「这次失败要发给作者看看吗？」。点「上传错误日志」会把这次失败的现场打包上传：机型、
  安卓版本、架构、剩余空间、目录体检结果（含 `ls -ldn`、`df`、挂载选项）、`deploy.error`、
  部署日志末 300 行、服务日志末 60 行。**不含任何聊天内容**（本应用也读不到 QQ 消息），
  `bots.json` 里的 token 会打码。上传后显示一个编号，方便对上；上传失败会把报告留在手机上，
  网络好了可以再点一次。
- **目录体检分项报告**：写测试、硬链接测试、剩余空间各自单独测、单独说，失败时明确是
  「写不进去（带错误原文）」还是「这个文件系统不支持硬链接」，并给对应的处理办法。

### 修复

- **第 2 步不再报那句含混的话**：改成调用统一的 `qq_fsprobe`，能自己修就先修（补权限位、
  chmod 关键目录后重试），修不好才失败，且失败原因精确到具体哪一项。
- **空间不足会单独提示**：剩余空间低于 50 MB 时直接说「先清一点空间再试」，不再伪装成权限问题。
## [1.1.8] - 2026-10-17

真机（arm64）上第 3 步报的错终于被抓住了 —— 应用自己把原因显示出来了：
`dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied`。
这说明 apt 本身能下载（32 MB 的 libc6 包都下完了），失败的是 dpkg 往 `/var/lib/dpkg` 里写东西。

### 修复

- **第 3 步开始前先给 dpkg 数据库做体检并就地修好**：打印身份、`/var/lib/dpkg` 权限、剩余空间与 inode，
  然后补回权限位、清掉残留的 `status-old`/`status-new`，再真的写一个测试文件确认能写。
  能修就继续装，修不了立刻失败退出（几秒），不再白跑十几分钟。
- **`status` 若是符号链接就换回普通文件**：旧版本用过 proot 的 `--link2symlink`，会把 dpkg 的数据库文件
  换成指向宿主绝对路径的符号链接，沙盒里解析不到。
- **硬链接自检**：dpkg 备份 status 用的是 `link(2)`。沙盒所在存储建不了硬链接时（把应用数据放外置存储、
  某些 FUSE 挂载），apt 装任何包都会失败。现在解包完 rootfs 就先试一次，不行直接说清楚「应用要装在内部存储」。
- **下到一半的 .deb 一律先清掉**：真机上 libc6 的包是半个，`dpkg-deb` 解它时报
  `Broken pipe / <decompress> subprocess returned error exit status 2`，而 apt 认为包已在本地不会重下，
  于是每次重试都撞同一个坏包。
- **重建根文件系统之前先判断重建有没有用**：文件系统不支持硬链接、或旧目录删不掉时，
  直接给结论（去内部存储 / 卸载重装），不再让用户白等一次 30 MB 下载。
## [1.1.7] - 2026-10-17

### 新增

- **部署失败时直接把真正的原因写在部署卡上**。以前第 3 步没过只显示「中断于第 3 步」，真正的原因（镜像连不上、DNS 解析不了、空间不够、dpkg 卡住）埋在几百行 apt 输出里，等于没告诉用户，也没法反馈给我定位。现在每个步骤的失败信息都会写进 `~/.qqbot/deploy.error`，部署卡下面直接红框显示「上次失败原因：…」；沙盒内的 `30-apt.sh` 自己也会做预检和诊断 —— 空间不足 900 MB 时提前拦下并说清楚，四个镜像全挂时逐个 `getent hosts` 打印哪个域名解析不了、`/etc/resolv.conf` 是什么、还剩多少空间。部署成功后这个文件会被清掉，不会把上一轮的旧报错带到这一轮。
- **部署卡右上角新增「复制日志」按钮**。一键把部署日志末尾 200 行复制到剪贴板，卡住时直接粘给我就行，不用再教用户去日志页翻文件。
- **部署中会显示日志停了多久**。第 3 步装依赖动辄十几分钟，界面一直显示同一句话很容易被当成卡死。现在会显示「日志 N 分钟没有新内容」，超过 10 分钟会提示可能卡在下载或 dpkg 上。
- **部署没跑完时自动续跑（最多 3 次）**。最常见的「卡在第三步」其实是部署进程在 apt 装到一半时被打断（切走应用、锁屏、系统回收），界面上就永远停在「中断于第 3 步」。现在只要部署没跑完、又没在跑，就会隔 45 秒自动续一次，每次先自愈 dpkg 再接着装，靠步骤标记跳过已完成的步骤。只有真的往前走了才补充重试次数，避免「起来就挂」的部署无限循环；用户主动点「停止部署」后自动续跑会暂停 10 分钟。

### 变更

- 部署日志最后一行「服务已启动（supervisor pid ）」里的 pid 有时是空的（`sleep 3` 还没等到 `supervisor.pid` 写出来），现在会等文件出现再读。

## [1.1.6] - 2026-10-16

### 修复

- **真正修好「第 3 步永远装不上」——上次的 dpkg 自愈没有解开那把锁**。apt 判断「dpkg 被中断」的唯一依据是 `/var/lib/dpkg/updates/` 里有残留条目（`tmp.i` 与 `lock` 除外），而这个目录只有 `dpkg --configure -a` **成功**时才会被顺带清空。只要有一个包的维护者脚本持续失败（真机上就是 `libc-bin`），journal 就一直留着，apt 每次都在入口处直接拒绝，`--configure -a` 再怎么重试都过不去 —— 这就是为什么上一次加的「自愈」看起来跑了、报错却一模一样。现在修复脚本在确认 dpkg 已经不忙之后，会把这批 journal 移到 `/var/lib/dpkg/updates.stale/`（挪走而不是删除，方便事后回查），apt 的闸门这才算真正打开。整段修复也从原来的 5 步扩到 11 步：等 apt/dpkg 结束（最多 180 秒）→ `status-old` 兜底 → `dpkg --configure -a` 最多 3 次并打印真实报错 → `--force-all` 再试 → 仍脏就对每个残留包 `--remove --force-remove-reinstreq --force-depends` 后 `--reinstall` 装回 → `apt-get -f install` 收尾 → 清锁、清半截 .deb、重建目录 → 挪走 journal → 打印前后 journal 条数做健康判定。
- **部署的每一步失败后都会先自愈再重试，最多三次**。原来一步失败就直接中断部署，用户只能自己找按钮。现在每个步骤最多试 3 次，每次失败前先跑一次依赖修复（`fix-sandbox.sh --force`）再继续，日志里会写明是第几次尝试。
- **最后一道保险：第 3 步三次都没过，就重建整个根文件系统**。任何 dpkg 状态污染到无法挽救时，删掉 `~/ubuntu` 重新解包一份干净的 Ubuntu 再来 —— 代价只是重新下载 29 MB，而不是让用户永远卡在第 3 步。这个动作在一次部署里最多触发一次，避免系统性故障时反复重建；部署成功后会清掉标记，下次还能重新触发。
- **部署正在跑的时候，「修复沙盒依赖」会拒绝执行**。两个进程同时动 dpkg 只会互相抢锁、把状态搞得更乱（真机日志里就出现过 `dpkg frontend lock was locked by another process` 后的重试）。现在它会先看部署锁，正在部署就提示「部署正在运行中，现在不要去动 dpkg」。
- **dpkg 不再给维护者脚本开 pty，apt 也不再装推荐包**。proot 下的伪终端偶尔会让维护者脚本卡在 `read` 上，而无人值守的部署一旦卡住就等于这一步永不结束；关掉 pty 之后再配合 `Install-Recommends false`，第 3 步要处理的包数量也少了一截。
- **修掉「部署中断于第 9 步…（已完成 8/8 步）」这句自相矛盾的提示**。八个步骤标记都在、只剩最后写 `deploy.done` 的收尾没完成时，旧代码还是按「已完成数 + 1」算中断步数，于是 8 步全绿却说中断在第 9 步。现在这种状态会单独提示「八个步骤都已经跑完了，只差最后收尾没写完。点「继续部署」补一下就完成。」

### 变更

- **没装好之前，界面上所有运行时控件都显示「未安装」并且点不动**，只有「部署 / 继续部署 / 停止部署 / 修复沙盒依赖」这几个按钮、以及页面导航、外观设置、检查更新这类跟沙盒无关的入口可以点。之前未部署时组件开关会显示成绿色的「启动中」、实例卡显示「容器运行中」，点了却什么也不会发生 —— 现在五块状态磁贴、顶部胶囊、组件开关、实例卡统一显示灰色的「未安装」，点上去会提示「还没安装完成，先在上面点「继续部署」」（部署进行中则提示「还在部署中，等它跑完就能用了」）。

## [1.1.5] - 2026-10-16

### 修复

- **修好「第 3 步安装不了」——部署一旦卡在沙盒依赖安装，就会永远卡在同一个地方**（用户真机截图反馈：日志停在 `E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.`）。原因是那一步在被中断过的 dpkg 数据库上直接重跑 `apt-get install`，撞上「有包没配置完」就立刻退出；而「这一步已完成」的标记只在成功后才写，所以每次重开应用都从同一面墙重新撞一次，永远过不去。现在沙盒内先做一遍 **dpkg 自愈**（等正在跑的 apt/dpkg 结束 → 必要时用 `status-old` 恢复数据库 → `dpkg --configure -a` 重试 → `apt-get -f install` 修断裂依赖 → 清残留锁和半截 .deb → 重建缺失目录），再安装；单批安装失败还会**自愈后自动重试一次**。整个过程纯本地、不联网也能跑，重复执行无害。
- **主页部署卡多了一个「修复沙盒依赖」按钮**：卡在第 3 步时自动出现，文案也会告诉你「先点它、再点继续部署」，不用自己翻到设置页找。它跑完会顺带刷新部署台，不需要等下一轮轮询。设置页「维护」卡里也有同样的入口。
- **Termux 侧同样加了依赖修复**：原来只做 `dpkg --configure -a`，缺了 `apt-get -f install`，依赖关系被中断破坏时会以完全相同的报错再卡一次。
- **「已有部署在进行中，本次跳过」不再可能变成一句假成功**：判断依据从「那个 PID 还活着」改成「那个 PID 真的是部署脚本」。Android 会复用进程号，而部署要跑几十分钟，记录下来的 PID 很容易飘到一个毫不相干的进程上——那样新部署会直接以成功退出，界面显示一切正常、实际一步没跑，用户永远等不到结果。现在还会核对进程命令行，PID 对不上就当作陈旧标记清掉，照常部署。部署结束（含失败和手动中止）也会自动抹掉自己的 PID 文件，不会再把状态挂住。
- **下载的文件开始做完整性检查**。以前只判断「下载下来不是 0 字节」，于是镜像返回的 HTML 错误页、被超时截断的半包都会被当成成品缓存下来，之后每次重装都失败在「解包」那一步、而重装并不会清掉那个坏文件，只能靠用户手动清缓存。现在按文件类型设了体积下限（rootfs 25 MB、QQ 安装包 150 MB），并会识别网页错误页，不合格的立刻丢弃重试。
- **rootfs 解压到一半被打断，不再被当成「已完成」**。「已完成」的判据从「两个文件存在」扩充为关键目录与文件数量；解压结果缺胳膊少腿时会删掉残留目录并要求重新部署，而不是带着残缺的系统镜像继续往下走（那种情况下失败点显示在第 3 步、真正的原因却在第 2 步，非常难查）。原来那句判断还把「两个都不存在」写成了失败条件，等于只要有一个解出来就放行，也一并改正。
- **虚拟环境与两个配置文件不再接受「半成品」**。`python3 -m venv` 或写入配置的途中被杀，会留下「能跑但没 pip 的 venv」和「存在但解析不了的 JSON」，旧逻辑一律判定为健康而跳过修复，之后以完全不相干的报错炸掉且重跑永不恢复。现在 venv 会检查 pip 是否真的可用，两个 JSON 会检查能否解析，不合格就重建，并且改成先写临时文件再原子替换。
- **同一瞬间启动两份部署会被真正拦住了**。部署护栏原来靠「读 deploy.pid → 判断 → 写 deploy.pid」，读和写之间有个空窗：两个进程在同一瞬间启动时都读到「没有 pid 文件」，于是双双放行（真机日志里两行「发现陈旧的 deploy.pid…」交错出现，两份部署同时对 dpkg 下手，其中一份的 `dpkg --configure -a` 抢不到 frontend 锁、只能报错重试）。现在改成用 `set -o noclobber` 下的 O_EXCL 重定向抢一把原子锁：一次写成功、文件里立刻就有持有者 PID，空窗不存在；锁被 SIGKILL 留下时仍会按「持有者还是不是部署脚本」判定并接管，正常退出（含失败与手动中止）会自己把锁删掉。

## [1.1.4] - 2026-10-16

### 修复

- **在 arm64 手机上点「重新部署」，只会弹一句英文的「部署：start failed」，完全看不出为什么失败**（用户真机截图反馈）。以前 `startDeploy()` 无论成功失败都只回一个内部状态串，失败时既不写日志也不留原因，看起来就像按钮坏了。现在所有提示都换成了中文，并且**失败时会把部署日志（或服务日志）的最后一行一起显示出来**——那一行通常是 apt、pip 或 NapCat 真正报的错，照着它就能判断是没网、是磁盘满、还是某个包下载失败。日志里的 ANSI 颜色码会先剥掉，不会在提示里变成一串乱码。
- 对应的几句话也一并中文化：已经是「已经部署完成，不用重新开始」「部署正在跑，稍等一下」，没解包好是「Termux 基础环境还没解包好，退出重进一次应用再试」，脚本丢了是「找不到部署脚本，覆盖安装一次应用可以恢复」。服务启动按钮同理（「服务已经在跑了」「服务已启动」）。
- 顺手把按钮回显里 `start -> started` 这种只有开发者看得懂的字符串去掉：现在直接显示中文结果，没有结果就显示「操作 完成」。

### 变更

- **安装包文件名开始带版本号**：从这一版起，发布出去的安装包叫 `AstrBot-1.1.4.apk`（而不是以前的 `AstrBot.apk`）。下载目录里躺着好几个包的时候，一眼就能看出哪个是哪个，不用再去比对文件大小或哈希。构建脚本在签名完成后会按 `VERSION` 自动复制一份带版本号的副本到仓库根目录，官网「下载」栏与 Releases 页面的资产也统一用这个名字。

---

## [1.1.3] - 2026-10-16

### 修复

- **arm64 真机上部署一步都跑不起来，日志顶死在「proot 自检失败 / 该设备的架构与 proot 不匹配」**（本版唯一一处，但 100% 影响所有手机，模拟器上反而完全看不出来）：部署主脚本在**第一步开始之前**先做了一次 proot 自检，而 Termux 的 bootstrap 里**根本没有 proot**——它是第一步用 `apt` 装进来的。x86_64 模拟器走的是随包内置的 `proot-x86_64`（从装机第一秒就存在），所以自检通过、这个问题一直没暴露；arm64 手机上则是拿一个**还不存在的文件**去自检，`timeout` 返回 127，被当成「架构不匹配」直接 `exit 1`，八个步骤一个都不会执行。现在只有在 proot 确实存在时才做前置自检，不存在就记一句「proot 尚未安装…跳过前置自检，步骤 10 装好后会再检一次」，装好之后那一次才是真正有意义的检查。
- 自检失败时把 proot 自己的报错原样打进部署日志（缩进四格，与 `[QQBot]` 行区分）。以前这些都丢进了 `/dev/null`，失败时只能说一句笼统的「自检失败」，分不清到底是文件不存在、还是被系统拦了 `ptrace`。

---
## [1.1.2] - 2026-10-16

### 修复

- **v1.1.1 那个补丁其实没生效，真正的原因在这里：AstrBot 把 `cmd_config.json` 写成带 UTF-8 BOM 的文件，而我们读它时按纯 UTF-8 解析，直接抛 `JSONDecodeError: Unexpected UTF-8 BOM (decode using utf-8-sig)`**（沙盒内实测报错原文）。异常被 `link.sh` 里的 `except Exception: a = {}` 吞掉，于是「当前 AstrBot 已经监听的端口」永远算成空列表，跟「需要的端口」永远对不上 → `CHANGED` 永远是 1 → 每次对接都会 `kill -TERM` 掉 AstrBot 再让守护循环拉回来（约 30 秒，期间所有 QQ 号的 OneBot 通道一起断）。表现就是：**在机器人页停掉任意一个实例，AstrBot 也跟着重启一次**。现在所有读 JSON 的地方统一改用 `utf-8-sig` 解码（它同时兼容带 BOM 和不带 BOM 的文件），比对逻辑这才真正跑起来。
- 顺带把沙盒里所有读 JSON 的脚本都换成了 `utf-8-sig`：`link.sh`（bots.json、cmd_config.json）、`run-napcat.sh`（实例的 onebot11/webui 配置、bots.json）、`50-napcat.sh`（QQ 的 `package.json`、NapCat 配置）、`40-qq.sh`（QQ 的 `versions/config.json`）、`remove-instance.sh`（bots.json）。这些文件分别由 AstrBot、NapCat、QQ 和 App 自己写，谁加不加 BOM 不由我们决定——`utf-8-sig` 对两种情况都能读，写出去的一律不带 BOM。

---
## [1.1.1] - 2026-10-16

### 修复

- **停掉一个 NapCat 实例，会把 AstrBot 一起重启，其它号的连接也跟着断**（本版唯一一处，但影响所有多实例用户）：在机器人页点某个实例的「停止容器」后，AstrBot 会在几秒内被终止再重新拉起（约 30 秒），期间**所有**实例的 OneBot 通道一起掉线，日志里只留下一句「AstrBot 结束：被信号 15 终止」。原因是这次点击会顺手触发一次「重新对接」，而沙盒里的 `link.sh` 在计算「AstrBot 该监听哪些 OneBot 端口」时**按实例是否启用过滤**——停用一个实例必然让端口清单发生变化，于是它按设计先停掉 AstrBot 再改配置、由守护循环拉回来。现在端口清单始终包含全部实例（停用的那个端口暂时没人连，aiocqhttp 的反向 WS 服务端只是安静地听着），清单没变化时 `link.sh` 直接返回、一个信号都不发给 AstrBot。只有真的新增或删除实例才会重启它。
- 附带的好处：应用每次冷启动时的自动对接、以及切换实例后的自动对接，现在都不会再重启 AstrBot 了——以前它们同样会把它杀掉重来一次。

---
## [1.1.0] - 2026-10-16

这一版加了一个「检查更新」，并顺手把版本号推到 1.1.0。

### 新增

- **「更多 → 关于与支持 → 检查更新」**：以前想知道有没有新版，只能自己去 GitHub 翻 release 列表。现在应用里点一下「检查更新」，它会去 GitHub 查最新版本，直接告出「有新版本 1.x.x 可以装」，并给出「下载新版本」和「查看更新内容」两个按钮——前者把安装包交给浏览器下载，下完点开就能覆盖安装（覆盖装不会动沙盒里的 QQ 登录状态）。
- **检查更新对国内网络做了兜底**：手机直连 `api.github.com` 经常超时，所以内置了直连 + 三个反代共四个地址，依次尝试，第一个能用的就用。四十五秒还没结果会明确说「检查超时」而不是一直转圈。
- **启动后静默自检**：打开应用九秒后会在后台查一次（同一台设备十二小时内只查一次），有新版本才弹提示，其余情况不打扰。

### 说明

- 查询全程在后台线程进行，前端每 500ms 取一次结果，所以点「检查更新」不会把界面卡住——这一点和「运行命令」不同，「运行命令」卡的是命令本身。
- 「查看更新内容」是在应用内展开的：新版说明在检查更新的同时就已经一起取回来了，点一下就地展开、再点收起，不用跳浏览器（跳过去还得自己登 GitHub，国内多半打不开）。只有那一版确实没写更新说明时才会退回打开 GitHub 页面。
- 「下载新版本」交给系统浏览器下载，应用本身不内置下载器；如果你的手机装了多个浏览器，会由默认浏览器接管。

---
## [1.0.4] - 2026-10-16

这一版是对整个应用做了一次逐行审计之后的集中修复：前端与原生两侧共 20 处缺陷，其中 4 处会让人以为「功能坏了」。

### 修复

- **执行一条不产生输出的命令，会让整个页面永久卡住**（本版最严重的一个）：「运行命令」里执行 `sleep 300`、或者任何等待输入的命令，超时判断形同虚设——命令不会自己结束，界面也不再刷新，之后所有按钮都像失灵，只能重启应用。现在超时真正生效，超时会终止命令并提示「命令超时没返回」。
- **失败也会提示成功**：删除实例、自动对接 AstrBot、停止/重启服务这几件事，失败时此前同样提示成功。更糟的是「删除实例」失败时界面已经把卡片去掉了，看着像删干净了，其实没删。现在这些都按命令的真实退出码回报，失败时把最后一行输出一起显示出来。
- **实例开关点了自己弹回去**：在机器人页把某个实例的开关打开，提示「已启动」，4 秒后它自己变回关闭。这是写盘失败时仍然回报成功造成的（开关其实是按磁盘状态显示的，磁盘没变）。现在写盘失败会明确提示「写入失败，改动没有落盘」，开关不会假开。
- **重启手机或应用被清理后，沙盒显示「运行中」但其实是死的**：进程号标记文件是残留的，而重启后那个号很可能被别的进程占用，于是界面显示运行中、点「启动」回一句「已在运行」、自动拉起也直接跳过，服务却一直没起来。现在会核对进程号对应的命令确实是沙盒本身，不对就重新拉起。
- **实例配置读坏时会静默换成单实例默认值**：如果配置文件损坏，程序此前会悄悄改用一份「主号 / 6099 / 6199」的默认配置显示出来；用户在这个假配置上点一次保存，真实的多实例配置就被覆盖了。现在会先把损坏的文件备份成 `bots.json.bad`，并提示「再点一次保存就用当前内容覆盖」，给你一次反悔的机会。
- **「自动分配空闲端口」的结果会被随后的保存覆盖**：点完自动分配、接着点「保存并重启服务」，输入框里还是你原来手填的旧端口，保存时按输入框的值回写，自动分配的结果就白分了。现在自动分配后输入框会立刻显示真正生效的端口。
- **错误提示会让页面整页冻结**：状态读取失败时，此前会直接跳过本轮全部渲染，磁贴、实例卡、端口、开关全部停在上一次的数值上，页面上只有标题旁一行小字。现在还是一样的文字，但会注明「显示的是上一次数据」，并且其余内容继续正常渲染。
- **部分按钮点了没反应**：这些按钮在底层调用抛异常时会中断整个点击处理，例如「全部对接 AstrBot」已经提示「正在对接 N 个实例…」却什么都没发生，组件开关点了既不提示也不变样。现在这些调用失败都会给出提示，界面不会停在半路。
- **「设为当前」之后并没有真的切过去**：NapCat 与 AstrBot 的对接参数是在容器启动时读的，切当前实例之后此前既不重新对接也不重启，界面上却已把标签改成「当前使用」。现在切换后会像保存实例一样自动重新对接并重启服务。
- **「自动刷新」这个名字有歧义**：这个开关其实只管日志页的 2 秒刷新，主页的 4 秒状态刷新不受它控制。现在改叫「日志自动刷新」。
- **编辑实例时若保存被拒，填的内容会丢**：写盘被拒（例如页面持有的是过期配置）时此前的做法是先关掉编辑框再判断，用户刚填的名字、QQ 号、Token、端口全部消失。现在会保留编辑框，让你改完直接重试。
- **申请电池优化白名单失败**：这个动作此前是在 WebView 的后台线程里打开的，属于未定义行为，结果是提示「这台设备打不开电池优化设置」。现在改到主线程执行，并会在少数没有申请页的机型上退回到电池优化总列表并给出指引。
- **日志行数会少一行**：当请求的最后一行是空行时，「复制日志」「查看日志」拿到的行数会比请求的少一行（请求 1 行时会返回空）。
- 另外修掉若干细节：异常消息里带反斜杠或换行时会让后续所有操作都提示「读取状态失败」；实例 id 现在会做合法性校验（此前含特殊字符会直接拼进命令）；打开图片选择器后若出错会泄漏文件句柄；下载代理写入失败会如实回报；「设为当前」等操作的提示更完整。

---
## [1.0.3] - 2026-10-16

### 修复

- **端口被自动避让后，输入框还停在你敲的那个冲突值上**（本版最容易被误解的一个）：在设置页把某个实例的 WebUI 端口填成 AstrBot 控制台的 6185 再保存，程序其实**已经**正确避让成了空闲端口并落盘（bots.json 里是 6099），但输入框仍显示 6185，看着就像「6185 改成功了」。原因是端口列表的重绘有一道「焦点还在输入框里就整段跳过」的保护（防止每 4 秒一次的刷新把正在敲的字冲掉），而在 WebView 里点按钮**不会**让输入框失焦，于是这道保护会一直生效。现在只要发生了避让，就显式失焦并立刻重绘一次，把真正生效的端口摆回输入框。
- **端口冲突提示一闪而过、等于没有**：发生避让时程序会先提示「端口冲突，已自动避让：…」，紧接着又提示「端口已保存，正在重启服务…」。一个页面同时只显示一条提示，后一条会立刻把前一条顶掉，于是用户永远看不到「你填的端口被改掉了」这件事。现在两种情况合并成一条提示，并且把「保存并重启服务」里 relink 的返回值改写到「维护」卡下面的输出框，不再抢占提示。
- **实例编辑框里的同类问题**：机器人页新增/编辑实例时手填冲突端口，避让提示同样会被随后的「已保存，正在自动对接…」顶掉。现在合并成一条完整提示。

---

## [1.0.2] - 2026-10-16

### 修复

- **「自动分配空闲端口」会把正常运行的配置改坏**（本版最严重的一个）：点一次这个按钮，配置里每个实例的端口就会被换成出厂默认的 6099 / 6199 / 6185。原因是它用「端口能不能连上」判断占用，而每个实例**自己正在监听的端口当然也连得上**，于是全被当成「被别人占了」跳过，从 6099 往下找必然落到默认值；更糟的是它只把新端口写进 `bots.json` 却**不重启容器**，主页随即按新端口去探测，NapCat 与 AstrBot 两个状态灯立刻变红「离线」而且一直不恢复，看起来就像把服务点坏了。现在改为**先沿用每个实例当前的端口**，只有和本次已分配的端口撞车时才重新挑；端口一个都没变时会明确提示「与当前一致，不需要重启」。
- **主页自动对接会把界面卡住十几秒**：主页每次冷启动都会自动跑一次 `link.sh` 做 NapCat ↔ AstrBot 对接（幂等操作）。此前这个调用走的是**同步**桥接口，而 `link.sh` 要开 proot 沙盒、改配置，十几秒起步，整段时间 WebView 的 JS 线程都被占住 —— 页面滚动、点击全部无响应，看起来就像「点了没反应」。现在改走新增的后台动作 `linkbg`，界面立即恢复响应。
- **「组件开关」和「机器人连接」卡自相矛盾**：把 NapCat 的组件总开关关掉之后，主页顶部的状态胶囊说「已关闭」，下面「机器人连接」卡却仍然显示绿色「容器运行中」、按钮还写着「停止容器」。现在这张卡会区分三种情况：容器运行中 / 已停止（组件总开关关着）/ 容器已停止。
- **实例编辑框不拦 AstrBot 控制台端口**：手填 6185 会静默保存，容器随后绑不上端口。现在 6185 与当前的 AstrBot 控制台端口都会被判为冲突并给出提示。
- **守护开关可能显示得比现实更好看**：守护前台服务被系统回收之后，开关仍然显示「已开启」。现在会显示「开关已开 · 服务未运行」，并把状态灯转为橙色。
- 另外修掉若干细节：主页自动部署磁贴的「8/8 步」改为按实际步数显示；「其它日志文件」收起后的提示文案改成如实描述；「维护」卡里空着的命令输出框不再渲染成一条黑边空条；「运行命令」会先回显命令再执行；日志页的状态点跟随实际日志；下载代理测速的秒数只保留两位小数；停止容器的过程中，磁贴、胶囊与运行状态卡三处说法保持一致。

### 变更

- 后台守护的自检提示由「沙盒运行中 · 01:11:39」改为「沙盒运行中 · 最近自检 01:11:39」，明确这个时间是最近一次自检的时刻。

---
## [1.0.1] - 2026-10-16

### 修复

- **扫码登录提示里的端口是写死的**：日志页与「更多」页的扫码提示原本固定写 `http://手机IP:6099`，但主号自动分配到的 WebUI 端口是 **6098**。照着提示用电脑浏览器打开会连不上，而扫码登录恰恰是最常用的一条路径。现在提示里的端口跟随当前实例的实际 WebUI 端口。

### 变更

- 版本号改由仓库根目录的 `VERSION` 文件统一提供，`build-ui.ps1` 在打包时自动写入 `versionCode` / `versionName`。此前版本号只存在于本地的构建中间产物 `_apkwork/apktool.yml` 里，而该目录不进版本库，别人 clone 下来无从得知当前是哪个版本。

---

## [1.0.0] - 2026-10-16

首个开源发布版本。

### 新增

- **应用更名**：应用名改为「**AstrBot烛版**」，控制台标题为「**烛之控制台**」。
- **沙盒时区跟随系统**：沙盒内时区改为跟随 Android 系统时区（此前固定为 UTC，导致沙盒内日志的时间戳与手机本地时间相差 8 小时）。
- **三层防掉线守护**：
  - 第一层：沙盒内的 `run-all.sh` 守护循环，看护 NapCat 与 AstrBot 进程；
  - 第二层：Termux 侧的 `watchdog.sh`，看护沙盒本身；
  - 第三层：Android 前台服务 `QqbotGuardService`，看护 Termux 侧进程，降低被系统省电策略杀掉后无法自动恢复的概率。
- **首次打开显示免责声明**：以弹窗形式展示完整免责声明，提供「复制全文」与「我已阅读并同意」两个按钮；同意状态保存在 `~/.qqbot/disclaimer.ok`，之后重启应用不再弹出。
- **覆盖安装自动更新脚本**：bootstrap 只在首次解包，之后覆盖安装新版 APK 时，应用会拿 APK 的安装时间当版本标记，把 `opt/qqbot` 下的 29 个脚本重新同步进已解包的前缀目录，结果写进 `~/.qqbot/logs/scripts.log`。脚本先写临时文件再改名，因此不受已解包文件的属主权限限制。
- **引入语义化版本号制度**：采用 `MAJOR.MINOR.PATCH`，`versionCode = MAJOR*10000 + MINOR*100 + PATCH`；本版本 `versionName = 1.0.0`、`versionCode = 10000`。

### 说明

- 安装包文件名 `AstrBot.apk`，包名保持 `com.termux`。
- 安装前需先卸载官方 / 应用商店 / F-Droid 版 Termux（包名冲突，不能共存）。
- 首次部署约需下载 250 MB，耗时 20~60 分钟。
