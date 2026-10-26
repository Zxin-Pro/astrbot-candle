# AstrBot烛版

> **一个把 Linux QQ + NapCat + AstrBot 装进 Android 手机的开箱即用包。**
> 装上 APK、打开、等它自己跑完，手机里就有了一台 7×24 在线的 QQ 机器人服务器 —— 全程不需要输入任何命令，也不需要 root。

[![Release](https://img.shields.io/github/v/release/Zxin-Pro/astrbot-candle?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/Zxin-Pro/astrbot-candle/releases/latest)
[![License](https://img.shields.io/github/license/Zxin-Pro/astrbot-candle?label=GPL-3.0)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Zxin-Pro/astrbot-candle/total?label=%E4%B8%8B%E8%BD%BD)](https://github.com/Zxin-Pro/astrbot-candle/releases)

| 项目 | 值 |
| --- | --- |
| 应用名 | **AstrBot烛版** |
| 控制台标题 | **烛之控制台** |
| 安装包 | `AstrBot-1.3.3-arm64.apk`（`609,382,213`，约 581 MB；**自带装好的运行环境镜像，第一次部署就是解压它，全程不用联网**） |
| 包名 | `zhuxin.astrbot`（1.2.1 及更早为 `com.termux`） |
| 当前版本 | **v1.3.3（正式版）**（versionCode `10303`，可直接覆盖 1.3.3-beta.10 / beta.9 / beta.8 / beta.7 / beta.6 / beta.5 / beta.4 / beta.3 / beta.2 / beta.1 或 1.3.2 安装） |
| 安装包 SHA-256 | `E95489C21A2F031989D558904ADA652E3B6196FAA925A975DC859A1D6A797CEA` |
| 签名证书 SHA-256 | `1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953`（v2+v3） |
| 项目地址 | [github.com/Zxin-Pro/astrbot-candle](https://github.com/Zxin-Pro/astrbot-candle) |
| 下载安装包 | 官网 [烛心.xyz](https://xn--15t839a.xyz/#/astrbot) 的 AstrBot 下载页与 [Releases 页面](https://github.com/Zxin-Pro/astrbot-candle/releases/latest) 提供**正式版**（约 581 MB，**站内直接下载，不用跳 GitHub**）；`-beta.N` 测试版只手动发给验收的人，不进官网、不进 latest |
| 官方网站 | [烛心.xyz](https://烛心.xyz) |
| 官方 QQ 群 | **1127997343** |

---

## 一、项目简介

**AstrBot烛版** 是一个 Android 应用，它把一整套「QQ 机器人运行环境」打包成了一次安装：

- 打开应用 → 自动在手机里建立一个 **Ubuntu 24.04 的 proot 沙盒**（无需 root、无需刷机、无需 Termux 命令行）；
- 在沙盒里自动部署 **Linux QQ 3.2.34-53644** + **NapCat v4.18.38** + **AstrBot v4.28.2**；
- 三者**出厂即预接线**：NapCat 通过反向 WebSocket 连到 AstrBot 的 aiocqhttp 平台（`ws://127.0.0.1:6199/ws`）；
- 用户界面是一个**本地 HTML 控制台**（WebView 加载 `file:///android_asset/qqbot/index.html`），有 **主页 / 机器人 / 日志 / 更多** 四个页签，扫码、开关服务、看日志、改端口全在界面里点。
- 从 **1.3.0** 起：**界面整体换成 iOS 液态玻璃风**，提供跟随系统 / 浅色 / 深色三档主题，以及 8 套强调色、8 套背景色调、6 套渐变预设和 0–100 的玻璃透明度滑块（全部记在 localStorage，重开保留），「外观」卡从「诊断信息」折叠搬到「更多」页顶层；毛玻璃只留在顶栏、底栏胶囊与弹窗遮罩，卡片不模糊，静止 CPU 仍为 0%。左上角图标跟随登录 QQ 号头像（12 小时缓存，未登录回落原图标），「更多 → 外观」可把带头像的快捷方式钉到桌面；顶栏「烛之控制台」支持双击改名（最长 16 字）。访问地址卡的 Token / 账号 / 密码改成读 NapCat 与 AstrBot 的真实配置文件，找不到时显示 —；清单再删 VIBRATE、桌面小部件与快捷设置磁贴，普通权限只剩 6 条，电池优化改走系统总列表。
- 从 **1.2.13** 起：**关于页读真实版本、关闭进程真关但下次打开自动恢复、重装后不再卡「正在启动」、新手引导整套移除**。「更多 → 关于」里的 AstrBot 版本不再写死 `v4.28.2`：桥方法 `envVers()` 直接读 `<home>/ubuntu/opt/AstrBot/astrbot/__init__.py` 的 `__version__`（5 分钟缓存），沙盒里现在是 **4.29.1**，读不到显示 `—`、绝不编一个数字。通知栏「关闭进程」改成真的关掉应用：写 `state/offline` → 脱离进程跑 `stop-sandbox.sh` → 收常驻通知 → `killProcess`（带 5 秒兜底的 `KillWatchdog`），同时留一张 `state/closed` 便条，**下次进应用时 `QqbotActivity.onCreate` 把便条和 offline 标记一起清掉、沙盒照常自动启动**，不用再点「启动服务」；界面上自己点的「紧急下线」不写这张便条，仍然是「别再自己起来」，要用卡片上的「启动服务」恢复。修好**重装后主页一直写「正在启动」**：`paintHero()` 里「全绿」（NapCat / AstrBot / OneBot 都在线）判定提到 `bootPending()` 的 120 秒开机窗口**前面**，全绿优先，「重启服务」也会重置窗口。**移除新手引导**：首页「第 N/5 步」引导卡与「发一条测试消息」整套删掉，`onboard` 相关 HTML / CSS / JS 清干净；顺手补回 `refreshExtras()` 开头被连带删掉的 `var E = ext(), now = Date.now();`（缺这行时函数每次抛 `E is not defined` 又被外层 try/catch 吞掉，表现为下线后卡片永远写「沙盒运行中」、也不出现「启动服务」按钮）。
- 从 **1.2.10** 起：**模型配置终于读得对了，改参数也不用再重启沙盒**。AstrBot v4.29 启动时会把 `cmd_config.json` 里的 provider 拆成 `provider[]` + `provider_sources[]`（密钥 / API 地址搬到后者），App 原来只翻 `provider[]`，于是「配过模型、第二次打开又显示没配置」；现在按 AstrBot 运行时的合并顺序读，并优先显示默认对话模型指向的那条。保存模型 / 人格改成走 AstrBot 的 HTTP 接口**热生效**（`provider-sources` → `providers` → `personas` → `system-config` 四步），实测一秒内卡片就变、机器人不中断，只有接口够不着时才回落到停沙盒改文件的旧流程。另外：主页模型卡显示中转站主机名 `worldcodes.online`（「复制 / 打开」给的是带邀请码的注册地址 `https://worldcodes.online/register?invite_code=CZLT`）、第一次点「控制台」弹账号密码卡可复制、NapCat 直达链接自带 token、登录 QQ 后自动回填 QQ 号、AstrBot 冷启动时 OneBot 磁贴显示「启动中」不再误报未连接。
- 从 **1.2.9** 起：**修好真机上 NapCat 反复崩溃** —— 1.2.8 把无头模式默认开在了所有机型上，但真机 arm64 上 NapCat 的 native 插件在 Electron utilityProcess 里一加载就 SIGSEGV（`退出码: 11`，pc 落在 `napi2native.linux.arm`），20 秒一轮重启、AstrBot 收不到任何消息。现在**真机默认回到完整客户端（Xvfb）模式**，无头模式（285 MB）只留给 x86_64 模拟器；架构判定改用 App 传进 proot 的 `QQBOT_ARCH`（x86_64 模拟器会把 `uname` 伪装成 aarch64），「无头起来了」的判据也从「日志出现 NapCat.Core Version」改成「端口在听 + 进程活着 + 6 秒无崩溃」。想手动指定：`$HOME/.qqbot/cache/napcat-mode.txt` 写 `util` / `fork` / `gui`。
- 从 **1.2.8** 起：**NapCat 改无头模式运行** —— 不再为每个实例拉起整套 Electron QQ 客户端（8 个进程、810~930 MB）和 Xvfb 虚拟屏，改为 `qq --ozone-platform=headless napcat.mjs` 只跑 NapCat 本体，实测 QQ 侧内存降到 **285 MB**（4 个进程），省下约 870 MB；扫码、面板、收发消息都不变，起不来会自动退回老的 Xvfb 流程，也能用 `$HOME/.qqbot/cache/napcat-headless.off` 手动回退。（1.2.9 起无头模式只对 x86_64 模拟器默认生效，真机默认走完整客户端，原因见上一条。）
- 从 **1.2.5** 起：**首次部署改回官方镜像源** —— 默认不再从自己站点下 604 MB 整包镜像，改成「下载 Ubuntu base（约 30 MB）→ apt 从清华 / 中科大 / 阿里镜像站装依赖」的在线流程，耗时通常 10~30 分钟。整包镜像没有删，改成手动开启：部署前 `export QQBOT_BUNDLE=1` 才会去 `QQBOT_CDN` 下 `bundle-<arch>.tar.xz`（离线 / 内网 / 镜像站不可用时用）。这一版**只发一个包**：arm64 与 amd64 两个包逐条比对 724 个条目，只有 `assets/qqbot-scripts.zip` 的时间戳不同、内容完全相同，架构由运行时按设备 ABI 选镜像，所以合并成 `AstrBot-1.2.5-arm64.apk` 一个包。
- 从 **1.2.4** 起：**修好 arm64 真机部署必挂的 proot 缺 loader** —— 之前打包只带 proot 本体，没带 `libexec/proot/loader` 与 `loader32`，proot 一启动就报 `proot error: execve("/system/bin/sh"): No such file or directory` + `the loader was not found`，第 1 步 `10-termux` 的 proot 自检三次全挂；现在两个 loader 随 bootstrap 一起发（`build/libexec/proot/`），`.so` 文件总长仍是 **29,392,992 字节**。**覆盖升级也能补上 loader**：`TermuxInstaller` 只在 `files/usr` 不存在时解包 `.so`，所以同一对文件又放进 `assets/qqbot-scripts.zip`（`build/opt/qqbot/libexec/proot/`），`steps/10-termux.sh` 发现 `$PREFIX/libexec/proot/loader` 缺失就从 `$PREFIX/opt/qqbot/libexec/proot/` 补装并 `chmod 755`，补不上会打警告。**清单再收两条权限**：删掉 `REQUEST_INSTALL_PACKAGES` 与 `WRITE_EXTERNAL_STORAGE`，现在只剩 8 条（`INTERNET` / `ACCESS_NETWORK_STATE` / `FOREGROUND_SERVICE` / `WAKE_LOCK` / `VIBRATE` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `RECEIVE_BOOT_COMPLETED` / `POST_NOTIFICATIONS`），`RunCommandService` 仍 `android:exported=false`；应用内更新是交给系统浏览器下载，所以不需要装包权限。**保留项**：`android:sharedUserId` 不能删（删了已装的 1.2.3 会报 `INSTALL_FAILED_UID_CHANGED`，等于强制卸载重装）；`TermuxDocumentsProvider` 与 `TermuxOpenReceiver$ContentProvider` 必须保持 `android:exported="true"`（改成 `false` 会让应用启动即崩 `SecurityException: Provider must be exported`）。
- 从 **1.2.3** 起：**修好 bootstrap 装不上**（弹「Unable to install bootstrap」）—— 包名改成 `zhuxin.astrbot` 后，bootstrap 里 `termux-bootstrap-second-stage.sh` 的前缀没跟着改写，`dpkg` 的 admindir 仍指 `/data/data/com.termux/files/usr/var/lib/dpkg`，第二阶段必然失败、`usr/` 会被清空；现在改写 `.so` 时先把整份脚本按新前缀重写。**32 个 ELF 的程序头不再被改坏**：上一版用 lief 改 RUNPATH，它会为了塞下更长的字符串给这些文件新增 `PT_LOAD` 段（`bin/apt` 11→12 段等），模拟器能跑、真机 Android 16 的 linker 直接 `execve` 失败；现在改成**原地等长改写** —— `/data/data/<包名>/files/usr` 缩成 `/data/data/<包名>/u`、`files/home` 缩成 `/h`，由应用启动时建的符号链接（`files/u → files/usr`、`files/h → files/home`）解析，ELF 节头 / 程序头 / 偏移一个字节都不动。**清单收敛降误报**：删掉 7 条从未使用的权限（`READ_LOGS` / `DUMP` / `WRITE_SECURE_SETTINGS` / `PACKAGE_USAGE_STATS` / `MANAGE_EXTERNAL_STORAGE` / `SYSTEM_ALERT_WINDOW` / `com.android.alarm.permission.SET_ALARM`），`RunCommandService` 从导出改成不导出。
- 从 **1.2.2** 起：**Android 包名从 `com.termux` 改为 `zhuxin.astrbot`**（桌面、设置、通知里显示的都是新包名），不再和官方 Termux 冲突；`android:sharedUserId` 仍保留 `com.termux`（去掉会让已装版本报 `INSTALL_FAILED_UID_CHANGED`，等于强制卸载重装）。因为包名变了，**装了 1.2.1 及更早版本的手机不能直接覆盖升级**：请先在旧版「更多 → 备份与恢复」导出备份，卸载旧版（会清掉旧包名下的沙盒数据），装好 1.2.2 后再导回；1.2.2 之后同签名可以继续覆盖升级。同时修复 bootstrap 尾部截断导致的 `dlopen ... invalid shdr offset/size: 29391584/1408`、改包名后第二阶段 `postinst` 失败导致 `usr/` 被删，以及 curl 编译期 CA 路径写死导致的 `curl (77)`。
- 从 **1.2.1** 起：**APK 回归精简包**（约 **25 MB**，arm64 / amd64 双架构）—— 1.2.0 把约 600 MB 沙盒镜像塞进安装包，既占空间又会被部分安全扫描误报；当时首次部署按架构从官网下载镜像（断点续传 + SHA-256 校验，失败自动回落到在线安装；1.2.5 起改回官方镜像在线安装），沙盒、QQ 登录态和配置都保留（从 1.2.1 及更早升级到 1.2.2 需先卸载，见上）。
- 从 **1.1.38** 起：NapCat 起来但账号还没登录时，会自动弹出扫码窗口（二维码 + 说明，登录成功后自己关掉），不用再去「日志」页翻二维码；手动入口在机器页实例的 `⋯` 里。
- 从 **1.1.37** 起：浅色主题改「清新」方向——多色柔和渐变画布、磨砂玻璃卡片、彩色图标块，主操作是蓝紫渐变胶囊但一屏只留一颗。
- 从 **1.1.36** 起：浅色主题重做——画布压暗、卡片纯白、hero 改品牌深蓝，四个常用动作合成一条，实例块去掉盒中盒。
- 从 **1.1.35** 起：删掉实例后主页不再残留那张删不掉的幽灵卡；一个实例都没有时首屏显示「还没有实例」，不再是「一切正常」。
- 从 **1.1.34** 起：**首屏只剩三块** —— 一句话说清状态的深色 hero、四个常用动作（面板 / 控制台 / 日志 / 重启）、一条实例；自动部署、组件开关、机器人连接、访问地址四张卡收进「更多工具 ▾」，三条彩色告警并成一张卡，每页底部加了落款。
- 从 **1.1.33** 起：界面按 **v3 视觉层「克制的精确」**重做 —— 颜色收回到状态点和异常文字上，主按钮改「淡底 + 描边」，圆角收成三档，另外补上了此前缺失的**浅色主题**；修掉实例卡「删除」按钮被 `.btns button.small` 盖掉、从不变红的老问题。
- 从 **1.1.32** 起：界面按 **v2 视觉层**整体重做（两级玻璃材质、中性近黑画布、悬浮底栏、六套背景预设降饱和），主页把「今日概览」并进「运行状态」合成单张**状态总览**卡；「更多」页新增**重启服务**（两步确认 + 45 秒倒计时）；修掉 AstrBot 日志被 `OSError [Errno 38]` 刷屏的问题（单次运行最多刷到 12965 条 / 8.5 MB），以及重启期间「紧急下线」卡误报「已下线」。
- 从 **1.1.31** 起：**上传日志整份脱敏**（令牌 / 账号 / 群号 / 手机号 / 邮箱 / 二维码链接全部抹掉，端口与时间戳保留）；**风控改成滑动窗口**（默认 600 秒内 2 次才暂停，暂停 6 小时后自动解除）；设置页新增**局域网访问开关**（默认只监听本机）；部署时**自动获取 NapCat / AstrBot 最新版本**（查不到就回退内置版本）
- 从 **1.1.30** 起：新增 18 项功能（应用图标、重启自启、网络自愈、风控识别、省电引导、降载、紧急下线、常驻通知卡片、桌面小组件、快捷磁贴、今日概览、深色模式、实例卡片、新手引导、备份恢复、存储占用、应用内公告、崩溃看板、匿名统计）
- 从 **1.1.29** 起：控制台**每次打开都会静默检查更新**（有新版本才弹窗，可在弹窗里选「以后再说」）；
  NapCat 面板与 AstrBot 控制台改由**系统默认浏览器**打开；AstrBot 冷启动的 40–60 秒里界面会显示
  「AstrBot 正在启动」；内置**《隐私政策》**（`更多 → 关于与支持 → 隐私政策`），逐条写清权限用途与数据去向。

首次部署会从官方镜像（清华 / 中科大 / 阿里）在线安装运行环境：先下载约 **30 MB** 的 Ubuntu base，再由 apt 分很多小包安装依赖，耗时约 **10~30 分钟**（取决于网速）；1.2.2 起同签名可继续覆盖升级（**1.2.4 → 1.2.5 直接覆盖安装**，数据保留），不用重新部署，但 **1.2.1 及更早版本必须先卸载再装**（卸载会清掉旧包名下的沙盒数据，请先在旧版「更多 → 备份与恢复」导出备份，装好后再导回）。之后每次打开都是秒进，机器人会自动拉起。

---

## 二、它到底做了什么

这个仓库**不是**从零写的 Android 应用，而是一条「**重新打包开源 Termux APK**」的流水线：

1. **基底**：取开源的 Termux APK（上游包名 `com.termux`，本版本对应 Termux v0.118.3），重打包后把应用包名改为 `zhuxin.astrbot`。选择复用 Termux 是因为它已经把 Android 上运行 Linux 所需的那一套（`libtermux-bootstrap.so`、`TermuxInstaller`、`$PREFIX` 目录布局）都做好了。
2. **注入脚本**：Termux 的 `lib/arm64-v8a/libtermux-bootstrap.so` 是一个 **ELF 头 + 内嵌 ZIP 段 + 动态段/节头表尾部** 的混合体（ELF 头 1328 字节，内嵌 ZIP 段 29388903 字节，尾部 2761 字节，文件总长 29392992 字节）。我们用一个**自己写的 Java 工具** `RepackBootstrap` 打开这个内嵌 ZIP，把 `build/` 目录下的沙盒脚本（部署流程、启停脚本、守护循环、`profile.d` 钩子）作为新条目塞进去，再按原布局写回。
   - 内嵌 ZIP 段在原始包里是**填满的**，所以要塞进新文件就必须删掉一些用不到的条目（man/info/doc 文档、C 头文件、测试二进制等），这些被删的条目部署流程永远不会执行，安全。
3. **换回 APK**：用 `ApkReplace` 把重新打包好的 `.so` 换回 APK 的 `lib/arm64-v8a/` 下。
4. **改包名并重新签名**：`[3c/5]` 用 `tools/reloc_bootstrap.py` 对 `libtermux-bootstrap.so` 内嵌的用户态做**原地等长改写** —— 长路径 `/data/data/<包名>/files/usr` 缩成 `/data/data/<包名>/u`、`files/home` 缩成 `/h`，由应用启动时建的符号链接（`PrefixAlias`：`files/u → files/usr`、`files/h → files/home`）解析，替换后剩余字节用 NUL 填满，`.so` 仍是 29,392,992 字节、程序头 / 节头 / 偏移一个字节都不动（这是 `dlopen` 与真机 `execve` 都能过的前提）；`[4b/5]` 用 `out/patch_manifest.py` 重写二进制 `AndroidManifest.xml` 的字符串池（package、`com.termux.RUN_COMMAND` 权限、documents/filereceiver/files authorities）；`out/patch_smali.py` 只替换不是 `com.termux.app.` 的引用（Java 类仍在 `com.termux.app` 包）。最后用 [uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) 重新签名，产出可安装的 `AstrBot-<版本号>.apk`（当前版本即 `AstrBot-1.3.0-arm64.apk`，只发这一个包）。
5. **原生控制台**：`ui/src/` 下的 Java 源码（`com.termux.qqbot.*`）编译成 dex、转成 smali、和 `ui/assets/qqbot/index.html` 一起由 apktool 打进 APK。

装到手机上以后：

- 首次打开 → 应用把内嵌 ZIP 里的脚本解到 Termux 的 `$PREFIX` 下 → 运行部署流程 → 下载并解开 Ubuntu 24.04 根文件系统 → 在沙盒里依次装好依赖、Linux QQ、NapCat、AstrBot → 自动接线 → 拉起服务；
- 沙盒内有一个常驻的守护循环 `run-all.sh` 看护 NapCat 与 AstrBot，Termux 侧有 `watchdog.sh`，Android 侧有前台服务 `QqbotGuardService`，**三层防掉线**；
- 沙盒时区**跟随 Android 系统时区**（不再固定 UTC，日志时间不会差 8 小时）。

---

## 三、系统要求

| 项目 | 要求 |
| --- | --- |
| 系统版本 | **Android 8.0 及以上** |
| CPU 架构 | **arm64-v8a**（绝大多数真机）；x86_64 模拟器也能直接装同一个包（本机 MuMu 上测过） |
| 存储空间 | 建议预留 **3 GB** 以上（Ubuntu 根文件系统 + QQ + NapCat + AstrBot + 依赖） |
| 内存 | 建议 **4 GB** 以上；每多开一个 QQ 实例都要多占一份 Linux QQ 进程的内存 |
| 网络 | 首次部署需要能访问 Ubuntu 镜像站（清华 / 中科大 / 阿里）与 GitHub 等下载源，用来下载 Ubuntu base（约 30 MB）并由 apt 安装依赖；第 4~6 步还要访问腾讯下载站与 GitHub（慢的话可在「设置 → 下载代理」里填代理）。手动开启整包镜像模式（`export QQBOT_BUNDLE=1`）时还需要能访问 `QQBOT_CDN`。 |
| Root | **不需要**（基于 proot 的用户态沙盒） |

> 本版本**只发一个包**（文件名带 arm64）：模拟器也能直接装，包内容与真机包相同，架构由运行时按设备 ABI 选镜像。

---

## 四、安装步骤

1. **先卸载 1.2.1 及更早的 AstrBot烛版**。
   1.2.1 及更早的包名是 `com.termux`，1.2.2 改成了 `zhuxin.astrbot`，**不能直接覆盖安装**。卸载会清掉旧包名下的沙盒数据，请先在旧版「更多 → 备份与恢复」导出备份，装好 1.2.2（或更新版本）后再导回。
   官方 Termux / 应用商店版 / F-Droid 版与 1.2.2 起的包名不同，**不再冲突**，可以共存。
2. 到官网 [烛心.xyz](https://xn--15t839a.xyz/#/astrbot) 的下载页（或 [Releases 页面](https://github.com/Zxin-Pro/astrbot-candle/releases/latest)）下载 `AstrBot-1.3.0-arm64.apk`（只发一个包，模拟器也能直接装；文件名后缀就是版本号），传到手机，点击安装。
3. 安装时系统会提示「**未知来源应用**」，需要允许安装。
   - **小米 / Redmi（HyperOS）用户**：如果被拦下、提示「应用缺少隐私政策」且没有「继续安装」，
     这是小米安装器的风险提示，不是包坏了。关掉手机管家的「安装监控 / 安全守护 / 应用防护」、
     开飞行模式再装、或电脑上 `adb install -r` 都能装进去，详见 [使用说明](使用说明.md) 第二 / 十七节。
4. 打开应用，**先读完免责声明弹窗并点「我已阅读并同意」**（确认状态会存在手机里，以后不再弹）。
5. 看到「烛之控制台」即安装成功。**首次启动会自动开始部署**（见下一节）。

---

## 五、首次使用

首次打开应用会**自动开始部署**，主页最上面的「自动部署」卡里会显示进度条、八步清单和实时日志：

| 步骤 | 内容 | 大致下载量 |
| --- | --- | --- |
| 1 | 准备 Termux 工具（proot / tar / xz / curl） | 很小 |
| 2 | 下载并解包 Ubuntu 24.04 根文件系统 | 约 29 MB |
| 3 | 在沙盒内安装运行依赖（Python / 图形库） | 视镜像源而定 |
| 4 | 安装 Linux QQ | 约 198 MB |
| 5 | 安装 NapCat 并注入 QQ | 约 34 MB |
| 6 | 安装 AstrBot 与 WebUI | 约 9 MB |
| 7 | 把 NapCat 与 AstrBot 对接起来 | 无 |
| 8 | 安装 `qqbot-*` 命令 | 无 |

**注意事项**

- 部署期间请**把应用保持在前台、屏幕不要熄灭**，也不要从后台划掉。
- 首次部署会从官方镜像（清华 / 中科大 / 阿里）在线安装运行环境（只需一次），耗时 **10~30 分钟**。
- **中途出错、退出、断电都可以重来**：每完成一步都会在手机里留下完成标记，重新打开应用会自动从没做完的那一步接着跑，**不会从头下载**。
- 部署全部完成后，应用会**自动把 NapCat 和 AstrBot 拉起来**，首次启动需要几分钟。
- 部署完成后就可以去**扫码登录 QQ** 了（见「八、常见问题」）。

---

## 六、四大页签说明

界面底部四个页签，右上角还有一个常驻的**设置齿轮**。

### 1. 主页

从上到下依次是：

- **顶部标题栏**：「烛之控制台」+ 一行副标题（N 个 NapCat 实例 · 当前用的是哪个）。
- **运行状态（首屏大字）**：一句话结论 —— 「一切正常 / 正在启动 / 服务已停止」，下面是今日在线 / 消息 / 异常三格与本次运行时长；点「状态详情」展开五个组件格子（部署 / 后台服务 / NapCat / AstrBot / OneBot 通道，端口都是当前实例的真实端口）。
- **当前模型**：一行「模型名 · 人格 · 密钥尾号 · 改完立刻生效」，点一下直接打开「模型与人格」。
- **通知栏权限提醒**（按需出现）：没授权时提示「常驻通知上的『关闭进程』就用不了」，点「开启」弹系统权限框，授权后自动消失。
- **四个常用动作**：NapCat / 控制台 / 日志 / 重启。
- **实例卡**：每个 QQ 号一张卡，显示昵称、已运行时长 / 未运行、最近掉线时间（在线状态取实时端口探测）。
- **更多工具**（默认折叠）：自动部署卡（进度条、八步清单、实时日志、开始/继续/停止/重新部署）、组件开关卡、机器人连接卡、访问地址卡。

### 2. 机器人

每个 QQ 号就是一个「**实例**」，一个实例一张卡片。卡片上显示 QQ 号、Token、登录地址、OneBot 地址，按钮有：

| 按钮 | 作用 |
| --- | --- |
| **复制 Token** | 复制这个实例的登录 Token |
| **停止容器 / 启动容器** | 单独关掉或拉起这个实例的容器（停止约 5 秒生效，启动约需 30 秒） |
| **设为当前** | 把这个实例设为「当前实例」 |
| **编辑** | 展开表单，改名称、QQ 号、Token、端口 |
| **删除** | 删除这个实例（两步确认） |

页面底部是一个整宽的「**新增实例**」按钮。

### 3. 日志

三张卡片：

| 卡片 | 内容 |
| --- | --- |
| **扫码登录二维码** | QQ 登录二维码**图片**、实例下拉框、「刷新二维码」、二维码更新时间 |
| **NapCat / AstrBot 实时日志** | 两个容器的输出**左右并排**显示，每 2 秒自动刷新；支持「精简模式」（去掉颜色转义码、滤掉 NapCat 的十六进制噪声行） |
| **其它日志文件** | 下拉选择任意日志文件查看（`qq.log`、`astrbot.log`、`supervisor.log`、`deploy.log`、按实例分的 `qq-bot1.log` 等） |

- 「清空」与「删除」都是**两步确认**（第一次点变成「确认清空 / 确认删除」，6 秒内再点一次才执行）。
- 日志文件不会无限膨胀：单个日志超过 20 MB 时会被自动截断成末尾 2 MB。
- 日志框被「×」收起来后，回主页点「运行状态 → 查看日志」可以一次性全部恢复。

### 4. 更多

- **关于与支持**：版本信息、官方网站、官方群。
- **外观**：控制台的外观选项。
- **模型与人格**：中转站密钥、模型名、人格提示词在应用内直接配好，保存时自动停沙盒 → 写配置 → 重新拉起，不用进 AstrBot 控制台（见《使用说明》第十三节）。
- **检查更新**：GitHub 连不上时会同时显示**官网最新版本号**，两个来源互不阻塞。
- **终端**：进入沙盒终端的入口（给想自己敲命令的高级用户）。

### 设置（右上角齿轮）

卡片依次是：**组件重装**（单独重装 NapCat 或 AstrBot，两步确认，端口与登录数据保留）、**端口设置**（留空＝自动分配；改完点「保存并重启服务」约 15 秒生效）、**下载代理**（支持 `http://` / `https://` / `socks5://`，留空＝直连）、**维护**（重新部署 / 查看状态 / **修复沙盒依赖** / 重置部署标记）、**关于**（各组件版本与安装路径前缀）。

---

## 七、端口一览

| 项目 | 端口 |
| --- | --- |
| NapCat WebUI（第 1 个实例） | **6099** |
| NapCat OneBot / aiocqhttp（第 1 个实例） | **6199** |
| AstrBot 控制台 | **6185** |
| 第 2 个实例 | WebUI **6098** / OneBot **6198**，以此类推 |

- **OneBot 端口恒等于同实例的 WebUI 端口 + 100。**
- 端口**留空表示自动分配**，自动分配时会主动避开已被占用的端口（包括 AstrBot 的 6185）。
- 想改端口：「设置 → 端口设置」，改完点「保存并重启服务」。
- **默认 Token**：第 1 个实例的 NapCat WebUI 登录 Token 是 **`zhuxin`**；第 2 个实例 `zhuxin2`，第 3 个 `zhuxin3`，以此类推。
  > 从老版本升级上来的 `qqbot` / `qqbot2` 会自动迁移成 `zhuxin` / `zhuxin2`，不需要手动改。

---

## 八、多实例说明

想用多个 QQ 号当机器人，就在「机器人」页多建几个实例。

- **每个实例的登录数据、配置、端口都是独立的**，互不干扰，扫码登录时各扫各的。
- 新增实例时会**自动挑一对空闲端口**；如果手填的端口和别的实例冲突，界面会自动改成一对空闲端口并提示「端口与其它实例冲突，已改为 X / Y」。
- **每个实例都是一个完整的 Linux QQ 进程，内存占用较大**。手机内存不大的话，建议只开 1~2 个。
- 「当前实例」是主页「访问地址」卡、查询二维码、实时日志列头默认跟随的那个实例，用实例卡上的「设为当前」切换。
- 删除实例是**两步确认**，会把这个实例的 QQ 登录数据、配置和日志一起清掉。

---

## 九、常见问题（FAQ）

### Q1：首次部署很慢，是不是卡住了？

不是。首次部署要从官方镜像（清华 / 中科大 / 阿里）在线安装运行环境：先下载约 30 MB 的 Ubuntu base，再由 apt 分很多小包安装依赖，耗时 **10~30 分钟**都很正常，取决于你的网速和到镜像站的连通性。

- 部署期间会**全屏盖一层进度面板**（环形百分比 + 当前第几步 + 最近一行日志）；点「放到后台，继续部署」可以收起，回「主页 → 自动部署」卡里看明细。
- 进度可以在「主页 → 自动部署」卡里实时看到，也可以到「日志」页看 `deploy.log`。
- 如果卡在下载不动，去「**设置 → 下载代理**」填一个代理（例如 `http://127.0.0.1:7890`），保存后重新部署。
- **如果卡在第 3 步（在沙盒内安装运行依赖）**：先去「**设置 → 维护 → 修复沙盒依赖**」点一次，等它跑完再回主页点「**继续部署**」。
  这是 1.1.5 修掉的老坑 —— 部署期间看门狗重启沙盒会打断 `apt`，被中断的 `dpkg` 会让之后每次重试都报
  `E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the problem.`；
  现在部署期间看门狗会暂停守护，第 3 步开头也会自动先跑一次 `dpkg` 自愈。
- 中途退出 / 断电都没关系，**重新打开会自动从断掉的那一步接着跑，不会重新下载已完成的步骤**。

### Q2：扫码登录 QQ 时，手机扫自己屏幕上的二维码没反应？

**二维码必须用另一台设备的 QQ 来扫**，手机自己扫自己屏幕上显示的二维码是不行的。

最省事的做法：

1. 手机和电脑连**同一个 Wi-Fi**；
2. 在「主页 → 访问地址」卡里拿到形如 `http://192.168.1.23:6099` 的 WebUI 地址，并点 Token 右边的「复制」拿到登录 Token（默认 `zhuxin`）；
3. 在电脑浏览器打开那个地址，填入 Token，页面里就会出现 QQ 登录二维码；
4. 用**要当机器人的那个 QQ**（在另一台设备上）扫码并确认登录。

也可以直接在「日志」页最上面的「扫码登录二维码」卡里看二维码图片，再用另一台设备扫。

> 扫码设备打不开页面时，先确认两台设备确实在同一个 Wi-Fi，且路由器没有开「AP 隔离」。

### Q3：AstrBot 控制台的初始账号密码是什么？

- 地址：**http://127.0.0.1:6185**（主页「访问地址」卡里有「应用内打开 AstrBot 控制台」按钮）
- 账号：`astrbot`
- 初始密码：`Astrbot123`

**首次登录后请立即修改密码。** 登录后还要在「配置」里填上大模型（OpenAI / DeepSeek / 通义千问 等）的 API Key，机器人才能回话。

### Q4：NapCat WebUI 的登录 Token 是什么？

默认是 **`zhuxin`**（第 2 个实例 `zhuxin2`，以此类推）。可以在「主页 → 访问地址」卡或实例卡上点「复制 Token」直接复制。

### Q5：机器人不回话 / 掉线了怎么办？

按顺序排查：

1. 「主页」首屏的大字看结论（一切正常 / 正在启动 / 服务已停止）；点「状态详情」看四个组件格子；NapCat 或 AstrBot 离线就点「更多工具 → 组件开关」里的开关重启一下。
2. 看「日志」页的实时日志，NapCat 侧是否显示已登录，AstrBot 侧是否报错。
3. 到 AstrBot 控制台确认大模型 API Key 填了、额度没超。
4. 本版本有三层防掉线守护（沙盒内 `run-all.sh` + Termux 侧 `watchdog.sh` + Android 前台服务 `QqbotGuardService`），正常情况下进程被系统杀掉后会自动拉回；如果反复掉线，可以在「设置 → 维护 → 查看状态」里跑一次状态检查并把结果贴到 issue 里。

### Q6：怎么提 issue？

到本项目的 GitHub Issues 页面提交。为了让问题能被快速定位，请尽量附上：

- 手机型号、Android 版本、CPU 架构；
- 应用版本号（见「设置 → 关于」）；
- 问题现象与复现步骤；
- **相关日志**（「日志」页里复制对应日志，尤其是 `deploy.log` / `qq.log` / `astrbot.log`）；
- 如果和某个第三方组件有关（NapCat / AstrBot / Linux QQ），也请说明它们的版本。

> 也欢迎加入官方 QQ 群 **1127997343** 反馈。

---

## 十、免责声明要点

首次打开应用会弹出**完整免责声明**，请先读完再点同意。要点如下：

- 本软件是一个**本地运行环境管理器**：它只在你的手机本地建一个 Linux 沙盒，并在里面安装 **NapCat** 与 **AstrBot** 两个第三方开源项目。这两个项目由各自的作者独立开发与维护，**本软件不对它们的功能、稳定性与后续更新作任何保证**。
- 本软件**不是腾讯官方产品**，与腾讯公司及 QQ 团队**没有任何关联**，也未获得其授权或认可。
- 使用第三方客户端登录**可能违反平台服务条款**，存在**账号被限制或封禁的风险**，请自行评估并承担后果。
- 本软件**不收集、不上传任何数据**：QQ 登录凭据、聊天记录、配置与日志**全部只保存在本机**，随时可以自行删除。
- 本软件按「**现状**」提供，不附带任何明示或暗示的担保。
- **请勿用于任何违法违规用途。**

弹窗上两个按钮：「**复制全文**」把声明全文复制下来；「**我已阅读并同意**」同意并继续（确认状态存在 `~/.qqbot/disclaimer.ok`，之后重启应用不会再弹）。

---

## 十一、开源协议

本项目仓库地址：[**https://github.com/Zxin-Pro/astrbot-candle**](https://github.com/Zxin-Pro/astrbot-candle)。

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整协议原文见仓库根目录的 [LICENSE](LICENSE)。

选择 GPL-3.0 是因为本项目基于 GPL-3.0 的开源 **Termux** 重新打包，属于其衍生作品，必须沿用同一协议。

> 本项目内嵌或下载的第三方组件各自遵循其原有协议：Termux（GPL-3.0）、Ubuntu 24.04 根文件系统及各 apt 包（各自的自由软件协议）、Linux QQ（腾讯，专有）、NapCat（见其仓库协议）、AstrBot（见其仓库协议）。

---

## 十二、目录结构说明

```
qqbot-sandbox/
├─ build/                       # 要注入 bootstrap 的沙盒文件（解包后落在 Termux 的 $PREFIX 下）
│  ├─ bin/proot-x86_64          # proot 本体
│  ├─ libexec/proot/loader、loader32 # arm64 真机必需的 proot 用户态 loader（随 bootstrap 解包）
│  ├─ etc/profile.d/50-qqbot-sandbox.sh
│  └─ opt/qqbot/
│     ├─ common.sh              # 公共函数（路径、日志、端口）
│     ├─ deploy.sh              # 部署流程总入口
│     ├─ link.sh                # NapCat ↔ AstrBot 对接
│     ├─ reinstall.sh           # 组件重装
│     ├─ delbot.sh              # 删除实例
│     ├─ start-sandbox.sh / stop-sandbox.sh
│     ├─ steps/                 # 部署的八步：10-termux ~ 90-summary
│     └─ sandbox/               # 沙盒内脚本：run-all.sh（守护循环）、run-napcat.sh、run-astrbot.sh 等
├─ ui/
│  ├─ src/com/termux/qqbot/     # 原生控制台 Java 源码（Activity / Bridge / Task / Client）
│  └─ assets/qqbot/index.html   # 控制台前端（WebView 加载 file:///android_asset/qqbot/index.html）
├─ ios/                         # iOS 版（远程控制台，实验，见 ios/README.md）
│  ├─ AstrBotIOS/               # SwiftUI 外壳：WKWebView + 回环 HTTP + 90 个桥方法的分派表
│  ├─ shared/AndroidShim.js     # 把 window.Android.xxx() 变成同步 XHR 的垫片
│  ├─ remote-agent/             # 装在宿主上的 Python 代理（把沙盒能力暴露成 HTTP）
│  └─ tools/                    # 工程生成、网页同步、无头浏览器验收脚本
├─ tools/                       # 自写 Java 工具与 bootstrap 扩展文件
│  ├─ RepackBootstrap.java      # 往内嵌 ZIP 段注入 / 删除条目
│  ├─ ApkReplace.java           # 替换 APK 内某个条目
│  ├─ DexToSmali.java           # dex → smali
│  ├─ VerifyBootstrap*.java     # 注入结果校验
│  └─ ext/                      # 要覆盖进 bootstrap 的原始文件
├─ docs/                        # 文档
│  ├─ BUILD.md                  # 从源码构建
│  ├─ 功能候选-200.md
│  └─ 性能优化.md
├─ build-ui.ps1                 # 构建第一步：编译原生控制台（javac → d8 → smali → apktool b）
├─ build-apk.ps1                # 构建第二步：注入脚本 → 换 .so → 对齐 → 重新签名
├─ dev/                         # 开发期的探索 / 校验脚本，不参与构建（见 dev/README.md）
├─ VERSION                      # 版本号唯一来源，build-ui.ps1 读它写进 APK
├─ CHANGELOG.md                 # 更新日志
├─ LICENSE                      # GPL-3.0 全文
├─ README.md                    # 本文件
├─ 使用说明.md                   # 面向使用者的完整说明书
├─ .gitattributes               # 换行符统一为 LF（保证 .sh 在设备上可执行）
└─ .gitignore
```

> 目录 `_research/`（上游 Termux APK、uber-apk-signer）、`_sdk/`（android.jar、apktool.jar、d8）、`_apkwork/`（apktool 工作区）、`out/`（构建产物）都不入库，见 [.gitignore](.gitignore)。

想从源码自己构建一个 APK，请看 [**docs/BUILD.md**](docs/BUILD.md) —— 里面写了需要准备哪些工具、两个脚本的执行顺序，以及 bootstrap 注入的偏移数学。
构建时默认就把 `out\bundle-<架构>.tar.xz` 打进安装包，产出约 600 MB 的全量包 `AstrBot-<版本>-<架构>.apk`（现在只发这一种）；`-Update` 只是热修逃生门，打出来的是 25 MB 瘦包、文件名带 `-update`，不会覆盖全量包。

---

## iOS 版（远程控制台 · 实验）

仓库里还有一份 **iOS 版**，在 [`ios/`](ios/) 目录，它和 Android 版的定位不一样，先看清楚再决定要不要用：

**iOS 不允许 App 在手机上 fork/exec 进程，后台也没法长跑一个 Linux 沙盒** —— 所以「把 AstrBot 装进 iPhone」在系统层面就走不通，proot、Ubuntu 镜像、常驻守护这一整套在 iOS 上不成立。iOS 版因此做成了 **远程控制台**：

- **界面还是同一个**：把 Android 版那个 `ui/assets/qqbot/index.html` **原样**塞进 App，一行不改。页面上 200 多处 `Android.xxx()` 同步取值，靠一个注入的垫片（[`ios/shared/AndroidShim.js`](ios/shared/AndroidShim.js)）翻译成 **同步 XHR**，打到 App 内的回环 HTTP 服务（`127.0.0.1:17870`，只绑回环）。
- **本机负责设备能力**：通知授权、剪贴板、相册选背景图、头像下载、外链与 QQ 群跳转、统计/崩溃看板这些在手机本地实现。
- **服务器负责沙盒能力**：在真跑着 AstrBot 的那台机器上装一个纯标准库的 Python 代理（[`ios/remote-agent/`](ios/remote-agent/)，`astrbot_bridge_agent.py`），App 通过它读写状态、开关服务、看日志、执行部署命令。同一个局域网直连，跨网络走 SSH 隧道。

| 能力 | Android 版 | iOS 版 |
| --- | --- | --- |
| 在本机跑 AstrBot | 有（proot 沙盒） | 没有（系统不允许） |
| 控制台界面 | `file:///android_asset/qqbot/index.html` | 同一个文件，由回环 HTTP 提供 |
| 桥调用 | `addJavascriptInterface` | 同步 XHR → `POST /__bridge` |
| 头像 / 背景图 | `file://` 直读 | 走回环 `/__img/`（WKWebView 拦 `file://`）+ `data:` URL |
| 选图 | `ACTION_GET_CONTENT` | PHPicker |
| 省电白名单横幅 | 会弹 | 恒不弹（iOS 没有这个概念） |
| 更换图标 / 内置终端 | 有 | 不挂这两个方法，页面自动降级 |

构建（需要 macOS + Xcode 14 以上，最低 iOS 16.0）：

```bash
python3 ios/tools/sync_web.py        # 把 ui/assets/qqbot 与 shared 同步进 App 资源
python3 ios/tools/gen_appicon.py     # 生成 1024 应用图标
python3 ios/tools/gen_xcodeproj.py   # 生成 ios/AstrBotIOS.xcodeproj
open ios/AstrBotIOS.xcodeproj        # 选个 Team 就能跑模拟器
```

⚠️ **这份 iOS 代码还没有在真机或 Mac 上编译过。** 本仓库的开发环境是 Windows，没有 Xcode，所以 Swift 层只做了静态一致性检查（[`ios/tools/check_bridge.py`](ios/tools/check_bridge.py) 9 项全过）和网页层的无头浏览器验收（[`ios/tools/boot_check.py`](ios/tools/boot_check.py) 15 条断言全过，截图在 [`ios/tools/shots/`](ios/tools/shots/)），第一次 `xcodebuild` 大概率还要修几个编译错。详见 [`ios/README.md`](ios/README.md) 与 [`ios/PROJECT.md`](ios/PROJECT.md)。

---

## 版本号制度

本项目采用 **语义化版本号**：`MAJOR.MINOR.PATCH`，对应的 Android `versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

| 位 | 什么时候 +1 | 例子 |
| --- | --- | --- |
| **MAJOR**（X.0.0） | 不兼容改动，例如换沙盒方案、需要重装 APK 才能升级 | 2.0.0 |
| **MINOR**（0.X.0） | 新增功能 | 1.1.0 |
| **PATCH**（0.0.X） | 修 bug、文案、性能 | 1.0.1 |

当前版本：**versionName `1.3.0`，versionCode `10300`**。历次变更见 [CHANGELOG.md](CHANGELOG.md)。

版本号的唯一来源是仓库根目录的 `VERSION` 文件（内容形如 `1.0.2`），`build-ui.ps1` 打包时自动换算成 `versionCode` 写进 APK。
