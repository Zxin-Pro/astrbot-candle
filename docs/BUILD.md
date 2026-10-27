# 从源码构建 AstrBot烛版

本文说明如何从本仓库的源码重新构建出可安装的 `AstrBot-<版本>-<架构>.apk`（当前版本 1.3.0，发布产物 `AstrBot-1.3.0-arm64.apk`，只发这一个包）。

> 构建过程**只能在一台配好工具链的 Windows 机器上**完成（脚本是 PowerShell + 本机 JDK）。
> 构建不联网、不跑 Gradle，也不需要 Android Studio。

---

## 一、构建产物

| 产物 | 路径 | 说明 |
| --- | --- | --- |
| 界面中间包 | `out\termux-ui-unsigned.apk` | apktool 重新打包的原生控制台 APK（**未签名**） |
| 注入后的 bootstrap | `out\libtermux-bootstrap.so` | 塞进了 `build/` 下所有脚本的 `.so` |
| 最终未签名包 | `out\termux-qqbot-unsigned.apk` | 换好 `.so`、待签名的 APK |
| **可安装包** | `out\signed\termux-qqbot-aligned-debugSigned.apk` | uber-apk-signer 对齐 + 签名后的成品（发布用副本由 `build-apk.ps1` 自动复制到仓库根目录，命名为 `AstrBot-<版本>-<架构>.apk`；`-Bundle` 全量包同名，只是体积不同） |

版本号（`versionCode` / `versionName`）不是写死在 APK 里的，而是由仓库根目录的 **`VERSION`** 文件决定：
内容形如 `1.0.2`，`build-ui.ps1` 打包时换算成 `versionCode = MAJOR*10000 + MINOR*100 + PATCH` 一并写进 `_apkwork\apktool.yml`。
要发新版本，改 `VERSION` 一行即可，同时记得在 [CHANGELOG.md](../CHANGELOG.md) 里补一条。

> 从 **1.1.29** 起，`build-ui.ps1` 还会顺手把 `_apkwork\AndroidManifest.xml` 里的
> `android:debuggable="true"` 删掉（幂等，删不到就跳过）。原因：debug 包是被小米安装器判成
> 「被重新打包 / 非官方」的显式标记，会更容易被拦下。副作用是 `adb shell run-as zhuxin.astrbot`
> 从这一版起不可用，要看应用私有目录请用 `adb root`。
> 从 **1.1.30** 起，`build-ui.ps1` 还多了两段（都幂等）：
> **3d** 把 `ui\assets\icon\res\**` 拷进 `_apkwork\res`（先删掉会压过 PNG 的矢量
> `drawable\ic_foreground.xml`）；**3e** 把 `ui\assets\android-res\**`（小组件布局/图标/背景）
> 拷进去，并把 `ui\assets\android\widget-manifest.xml` 插到 `</application>` 之前。
> 从 **1.2.3** 起，`build-ui.ps1` 又多了一段 **3c3 清单收敛**（同样幂等，重复跑只打印
> `manifest: nothing to trim (already clean)`）：1.2.3 先删掉 7 条源码里从未使用过的权限 ——
> `READ_LOGS`、`DUMP`、`WRITE_SECURE_SETTINGS`、`PACKAGE_USAGE_STATS`、`MANAGE_EXTERNAL_STORAGE`、
> `SYSTEM_ALERT_WINDOW`、`com.android.alarm.permission.SET_ALARM` —— 并把 `RunCommandService` 的
> `android:exported="true"` 改成 `false`（那是 Termux 给外部应用发命令的遗留接口，本应用不用；
> 一个导出的命令服务正是小米 / 腾讯 `a.gray.devicemaster.a` 的启发式特征之一）。
> **1.2.4 再删 `REQUEST_INSTALL_PACKAGES` 与 `WRITE_EXTERNAL_STORAGE`**（应用内更新交给系统浏览器下载，
> 不需要装包权限），清单里只剩 8 条：INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE / WAKE_LOCK /
> VIBRATE / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS。
> **`com.termux.permission.RUN_COMMAND` 这条自定义权限声明要留着** —— `TermuxOpenReceiver$ContentProvider`
> 还引用它，删了引用会悬空。
> **`android:sharedUserId` 与两个 ContentProvider 的 `exported` 不能动**：`sharedUserId` 保留
> （删掉会让已装的 1.2.3 报 `INSTALL_FAILED_UID_CHANGED`，等于强制卸载重装）；`TermuxDocumentsProvider`
> 与 `TermuxOpenReceiver$ContentProvider` 必须保持 `android:exported="true"`（改成 `false` 会让应用启动即崩
> `SecurityException: Provider must be exported`）。
> 有改动时打印 `manifest: trimmed N dangerous entries, RunCommandService unexported`。
> 同时 `build-apk.ps1` 的 **2b** 段会在打包前把根目录 `VERSION` 写进
> `build\opt\qqbot\VERSION`（打包后删除），崩溃聚合与 doctor 读的就是包内这个文件。
> 从 **1.3.3-beta.3** 起，`build-ui.ps1` 又多了一段 **3c4 窗口底色**（幂等）：把 `QqbotActivity` 的
> `android:theme` 换成 `@style/Theme.QqbotPage`，并在 `_apkwork\res\values`、`values-night` 里写
> `windowBackground`（`#fff3f6fc` / `#ff05060a`），`values-v31`、`values-night-v31` 再补
> `android:windowSplashScreenBackground` —— Android 12 起冷启动第一帧是系统启动屏，它**只认**这个属性，
> 改 `windowBackground` 不起作用（实测首帧仍是纯白/纯黑）。`_apkwork` 是解包目录、不进 git，
> 所以这几处必须由 ps1 生成，换台机器重新 `apktool d` 也不会丢。
> `build-ui.ps1` 里含中文注释，**必须存成 UTF-8 带 BOM**，否则 PowerShell 5.1 按 GBK 读会一片 ParserError。
> 所以**新增 manifest 条目或 res 资源必须写进 ps1**，直接手改 `_apkwork` 下次构建会被覆盖。
> 不要试图加 `android:privacyPolicy` —— 这个属性在 Android 里不存在，aapt2 会直接报错。

---

## 二、环境依赖

| 依赖 | 用途 | 本机示例路径 |
| --- | --- | --- |
| **JDK 17+** | 编译 Java 工具、`javac`、`d8`、签名 | 任意 JDK 17 及以上；脚本自动取 `JAVA_HOME`，没设就取 PATH 上的 `javac.exe` |
| **PowerShell 5.1** | 跑构建脚本（Windows 自带） | `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` |
| **apktool.jar** | 解开 / 重新打包 APK | `_sdk\apktool.jar` |
| **d8** | dex 编译器（把 class 编成 dex） | `_sdk\bt\android-14\lib\d8.jar`（入口类 `com.android.tools.r8.D8`） |
| **android.jar** | 编译时的 Android API 桩 | `_sdk\platform\android-34\android.jar` |
| **uber-apk-signer.jar** | 对齐 + 重新签名 | `_research\uber-apk-signer.jar` |
| **上游 Termux APK** | 作为注入基底与 pristine bootstrap 来源 | `_research\termux-arm64.apk` |
| **本仓库的 Java 工具** | 注入 / 替换 / dex→smali / 校验 | `tools\*.java`（构建脚本会按需自动编译成 `tools\*.class`，不需要手工编译） |

`_sdk\` 与 `_research\` **不入库**（见 `.gitignore`），需要自己准备：

- `_sdk\apktool.jar`：从 apktool 官方 release 下载（本仓库验证过 2.9.3）。
- `_sdk\platform\android-34\android.jar`：从 Android SDK 的 `platforms;android-34` 取。
- `_sdk\bt\android-14\lib\d8.jar`：从 Android SDK 的 `build-tools;34.x.x` 取。
- `_research\termux-arm64.apk`：Termux 官方 arm64 版 APK（本版本基底为 Termux v0.118.3，`minSdkVersion 24` / `targetSdkVersion 28`）。
- `_research\uber-apk-signer.jar`：从 uber-apk-signer 的 release 下载。

---

## 三、构建步骤

### 0. 准备

**路径都已自动定位，通常不需要改任何脚本**：

- `$JDK` / `$JAVA`：先看 `$env:JAVA_HOME`，没设就取 PATH 上的 `javac.exe` 同目录 —— 装一个 JDK 17 及以上并加进 PATH 即可，
- `$ROOT`：`build-ui.ps1` / `build-apk.ps1` 都取 `$PSScriptRoot`，仓库放哪个盘、从哪个目录调用都不影响；
- `tools\*.class`：`tools\*.java` 会在构建开始时**按需自动重编译**（缺 class 或源码更新时才编），不需要手动 `javac`；
- 若某个版本要求指定 JDK，直接 `$env:JAVA_HOME = 'C:\path\to\jdk'` 再跑脚本即可。

### 1. 先构建界面（build-ui）

```powershell
powershell -ExecutionPolicy Bypass -File .\build-ui.ps1
```

这一步依次做四件事：

1. `javac`：把 `ui\src` 下所有 `.java` 用 `-encoding UTF-8 -source 11 -target 11 -g -nowarn` 编到 `ui\classes\`；
2. `d8`：把 `ui\classes\` 下的 `.class` 编成 `ui\dex\classes.dex`（`--min-api 24`）；
3. `DexToSmali`：把 dex 转成 smali，拷到 `_apkwork\smali_classes30\`；同时把 `ui\assets\qqbot\index.html` 拷到 `_apkwork\assets\qqbot\index.html`；
4. `apktool b`：把 `_apkwork\` 重新打包成 `out\termux-ui-unsigned.apk`。

### 2. 再注入并签名（build-apk）

```powershell
# 默认出全量包（自带运行环境镜像）；-Arch 仍可用（arm64 / amd64），发布只发 arm64 一个
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Arch arm64
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Arch amd64
```

> `-Arch amd64|arm64` 现在默认出**全量包**（约 600 MB）：里面带着 `assets/qqbot-bundle.tar.xz`。
> 手机端第一次部署就是把这份镜像解压进 `/data`（2~5 分钟，不用联网），在线 apt 流程只在镜像缺失时才走
> （`export QQBOT_BUNDLE=1` 是「从 `QQBOT_CDN` 下镜像」的开关，APK 自带镜像时不需要它，见第四节）。
>
> **发版规矩（2026-10-24 起）：每版只出一个全量包。** 带 `-Arch` 构建时默认就把 `out\bundle-<arch>.tar.xz` 塞进 APK（约 600 MB），产物是 `AstrBot-<ver>-<arch>.apk`；手机端第一次部署就是把这份镜像解压出来，全程不需要联网。
>
> `-Update` 保留作热修逃生门（打 25 MB 瘦包、写入 0 字节 `assets/qqbot-update-pkg` 标记、文件名带 `-update`，不会覆盖全量包），正常发版不用它。

这一步分五小步（对应脚本日志里的 `[1/5]`～`[5/5]`）：

1. **[1/5] 取 pristine bootstrap**：从 `_research\termux-arm64.apk` 里解出 `lib/arm64-v8a/libtermux-bootstrap.so` 到 `out\_pristine-bootstrap.so`。
   > 注意：**永远从原始 stock APK 取**，不要从上一步的 UI APK 取 —— 注入的偏移数学、以及后面要用的尾部字节都必须基于原始布局。
2. **[2/5] 重新打包 bootstrap**：调 `tools` 下的 `RepackBootstrap`，把 `build/` 下的**所有文件**（递归，相对路径按 `/` 分隔）作为新条目加进内嵌 ZIP，同时 `--drop` 掉一批用不到的条目腾空间，输出 `out\libtermux-bootstrap.so`。
3. **[3/5] + [3c/5] 打包脚本并原地等长改写前缀（in-place, same length）**：
   先把 `build\opt\qqbot\**` 和 `build\etc\profile.d\50-qqbot-sandbox.sh` 打成 `assets/qqbot-scripts.zip`；
   再调 `tools\reloc_bootstrap.py`（用法：`reloc_bootstrap.py <repacked.so> <out.so> <新包名> <pristine.so>`），
   把 `libtermux-bootstrap.so` 内嵌用户态里写死的 `/data/data/com.termux` 前缀换成新包名。要点：

   - **只做等长替换，绝不重建 ELF**：长路径 `/data/data/<pkg>/files/usr` 缩写成 `/data/data/<pkg>/u`，
     `/data/data/<pkg>/files/home` 缩写成 `/data/data/<pkg>/h`；替换后用 `NUL` 把这条字符串剩下的字节填满。
     **文件长度、节头表、程序头表、所有偏移一个字节都不动** —— 产物 `.so` 必须仍是 **29,392,992 字节**，
     程序头 / 节头与 pristine **逐字节一致**。这是 `TermuxInstaller` 能 `dlopen` 它、以及沙盒里的
     `dpkg` / `apt` 在真机 Android 16 上能被 `execve` 的前提。
   - **短路径靠运行时符号链接解析**：`ui\src\com\termux\qqbot\PrefixAlias.java`（包声明必须是 `com.termux.app`，
     源文件放在 `qqbot\` 目录只是历史原因）在 Activity / GuardService / Shell 三个入口调 `PrefixAlias.ensure(...)`，
     幂等地建 `files/u -> files/usr`、`files/h -> files/home`；建不出来就静默失败（最坏回落到 `LD_LIBRARY_PATH`）。
     所以设备上 `/data/data/zhuxin.astrbot/u` 就是 `/data/data/zhuxin.astrbot/files/usr`。
   - **为什么不再用 lief（1.2.2 的做法）**：lief 改 `RUNPATH` 时为塞下更长的字符串，会给 ELF **新增一个 `PT_LOAD` 段**
     （`bin/apt` 11→12 段，`lib/libiconv.so`、`lib/liblz4.so`、`lib/libxxhash.so.0.8.3`、`libexec/coreutils/libstdbuf.so`
     等共 32 个文件），文件大小也跟着变。MuMu（ARM 转译）能跑，真机 Android 16 的 linker 直接拒绝 `execve`，
     表现为 second stage 失败 —— 用户看到的就是「Unable to install bootstrap」。
   - **second-stage 脚本要先换前缀再加料**：`etc/termux/bootstrap/termux-bootstrap-second-stage.sh` 必须
     **先 `patch_text()` 按新包名整体重写前缀、再 `patch_stage2()`**（插日志重定向到
     `~/.qqbot/logs/second-stage.log` 与 postinst 容错）。顺序反了或漏了 `patch_text`，`dpkg` 的 admindir 还指
     `/data/data/com.termux/files/usr/var/lib/dpkg`，第二阶段必挂、`usr/` 被清空，同样是「Unable to install bootstrap」。
   - **第 4 个参数必须传 pristine `.so`**：脚本要从它那里原样搬 2,761 B 尾部（动态段 + 节头表），并先校验
     `len(pristine) == len(repacked)`；只按「ELF 头 + 新 ZIP」写回会把 `.so` 写坏。
   - 跑完核对脚本输出：`elf_patched` 约 249、`com.termux_left=0`、`size=29392992`。

   > **1.2.4 起多带两份 proot loader**：`build\libexec\proot\loader` 与 `loader32` 会随 `build/` 一起进
   > `libtermux-bootstrap.so` 的内嵌 ZIP，全新安装解包到 `$PREFIX/libexec/proot/` —— arm64 真机上 proot 启动
   > guest 的 `/system/bin/sh` 就靠它，缺了会报 `proot error: execve("/system/bin/sh"): No such file or directory`
   > 加 `the loader was not found`。同一对文件还放在 `build\opt\qqbot\libexec\proot\`，会被打进
   > `assets/qqbot-scripts.zip`（落到 `$PREFIX/opt/qqbot/`），供覆盖升级时由 `steps/10-termux.sh` 补装到
   > `$PREFIX/libexec/proot/` 并 `chmod 755`。**两处必须同时保留**：前者管全新安装，后者管覆盖升级 ——
   > `TermuxInstaller` 只在 `files/usr` 不存在时解包 `.so`，覆盖升级不会重新解包 bootstrap。加进这两个文件后
   > `.so` 总长仍是 **29,392,992 字节**（空间靠 `--drop` 腾）。
4. **[4/5] + [4b/5] 换回 APK 并重写 manifest**：`ApkReplace2` 把 `.so` 和 `qqbot-scripts.zip` 换进 `out\termux-ui-unsigned.apk`（存在时）或 stock APK，输出 `out\termux-qqbot-unsigned.apk`；随后调 `tools\patch_manifest.py` 重写二进制 `AndroidManifest.xml` 的字符串池，把 `package`、`com.termux.RUN_COMMAND` 权限、`com.termux.documents` / `com.termux.filereceiver` / `com.termux.files` authorities 换成 `zhuxin.astrbot` 对应值。`tools\patch_smali.py` 只替换**不是** `com.termux.app.` 的引用 —— Java 类仍在 `com.termux.app` 包里，把这些类名一起改掉会让 `Class.forName` / `setClassName` 找不到类。
5. **[5/5] 签名**：调 uber-apk-signer 输出到 `out\signed\`。

### 3. 交付

`build-apk.ps1` 最后会自动把 `out\signed\termux-qqbot-aligned-debugSigned.apk` 复制到仓库根目录，
命名为 `AstrBot-<版本>-<架构>.apk`（`VERSION` 文件的内容就是版本号），这个文件就是发布用的安装包。

只出全量包以后，根目录里的 `AstrBot-<版本>-<架构>.apk` 就是约 600 MB 的全量包；只有跑 `-Update` 才会多出一个 `-update.apk`。
`-Arch` 仍存在（arm64 / amd64 都能构建），但两个包内容相同（724 个条目里只有 `assets/qqbot-scripts.zip` 的时间戳不同）；1.2.5 起发布只发 arm64 一个，模拟器也直接装这个包。

> **发布只发一个精简包**：只上传 `AstrBot-<版本>-arm64.apk` 到 GitHub Release；模拟器也直接装这个包（内容与真机包相同）。
> 全量包就是唯一的 Release 资产。
> 沙盒镜像单独传到 VPS 的 `/dl/` 目录（见第四节），不随版本发布。

> **两个脚本必须串行执行**：`build-ui.ps1` 会**写出** `out\termux-ui-unsigned.apk`，`build-apk.ps1` 会**读取并覆盖**它，
> 并行跑会互相踩踏，产物不可预测。**永远先 build-ui，再 build-apk。**
>
> 也可以直接跑 `out\rebuild-all.ps1`（如果本地有的话），它按顺序调用这两个脚本。

---

## 四、沙盒镜像托管

1.2.0 的「懒人包」把约 580～604 MB 的沙盒镜像打进 APK（`assets/qqbot-bundle.tar.xz`），安装包体积过大，
小米安全扫描会直接报「包含病毒 a.gray.devicemaster.a」。1.2.1 起 APK 只带程序（约 25 MB）。
1.2.5 起默认不走 `/dl` 的整包：首次部署改成「下载 Ubuntu base（约 30 MB）→ apt 从官方镜像站
（清华 / 中科大 / 阿里）装依赖」的在线流程；只有部署前 `export QQBOT_BUNDLE=1` 时，`steps/20-rootfs.sh`
才会调用 `common.sh` 的 `qq_bundle_pull()` 按设备架构拉 `bundle-<arch>.tar.xz`（离线 / 内网 / 镜像站不可用时用），
下不到就回落在线安装。

### 1. 上传到 VPS

镜像构建产物在仓库的 `out\` 下（`build\` 是随 APK 部署的脚本树，不放镜像），每个架构一份：

| 本地文件 | 大小 | VPS 路径 | 公开地址 |
| --- | --- | --- | --- |
| `out\bundle-arm64.tar.xz` | 583,010,488 B（约 556 MiB） | `/data/www/zhuxin-site/dl/bundle-arm64.tar.xz` | `https://xn--15t839a.xyz/dl/bundle-arm64.tar.xz` |
| `out\bundle-amd64.tar.xz` | 604,245,316 B（约 576 MiB） | `/data/www/zhuxin-site/dl/bundle-amd64.tar.xz` | `https://xn--15t839a.xyz/dl/bundle-amd64.tar.xz` |

上传后在 VPS 的同目录生成同名 `.sha256`（`sha256sum` 标准格式，`qq_bundle_pull()` 只取第一列）：

```bash
cd /data/www/zhuxin-site/dl
sha256sum bundle-arm64.tar.xz > bundle-arm64.tar.xz.sha256
sha256sum bundle-amd64.tar.xz > bundle-amd64.tar.xz.sha256
```

传完用 `stat -c %s` 和 `sha256sum` 核对一遍，确认与上表、本地文件一致。`dl/` 必须是支持
HTTP Range 的静态目录（`curl -C -` 断点续传依赖它），nginx 默认就支持。

### 2. 客户端下载行为

- 1.2.5 起默认不走这里：只有部署前 `export QQBOT_BUNDLE=1` 才会调用 `qq_bundle_pull()` 去 `/dl` 下整包；
  默认走官方镜像站（清华 / 中科大 / 阿里）在线安装。站点 `/dl/` 下两份镜像继续保留。
- 默认地址是 `https://xn--15t839a.xyz/dl`，定义在 `common.sh`：
  `QQBOT_CDN="${QQBOT_CDN:-https://xn--15t839a.xyz/dl}"`。换镜像源或临时改地址只要部署前
  `export QQBOT_CDN=https://…`，也可以直接改这一行。
- 先下到 `$QQBOT_HOME/bundle.tar.xz.part`，`curl -C -` 断点续传：网络中断后重试会接着
  `.part` 的已有字节下，不重新下整个镜像。
- 下完再取同目录的 `.sha256` 校验；校验不通过会删除 `.part` 并按失败处理，下次部署重新下载。
  站点没有 `.sha256` 时只告警、不阻断（兼容老目录）。
- 下载失败（断网、站点没有文件、体积明显不对）不会中断部署：`20-rootfs` 打一条警告后
  **自动回落到在线安装**（Ubuntu base + apt + pip），只是慢一些。
- 校验通过后把 `.part` 改名为 `bundle.tar.xz` 并写 `.bundle-ok`；两者都在时直接复用，
  不会重复下载。

> `-Bundle` 全量包读的就是同一份 `out\bundle-<arch>.tar.xz`，因此离线包与官网镜像内容完全一致。

## 五、bootstrap 注入原理

Termux 的 `lib/arm64-v8a/libtermux-bootstrap.so` 并不是一个普通的 `.so`，而是一个 **ELF 头 + 内嵌 ZIP 段 + 动态段/节头表尾部** 的「夹心」结构，三段都不能丢：

| 区段 | 偏移 / 长度 | 含义 |
| --- | --- | --- |
| ELF 头 | `0 .. 1327`（**1328** B） | ELF header + program headers；`$PREFIX_OFF = 1328` |
| 内嵌 ZIP 段 | `1328 .. 29390230`（**29388903** B） | ZIP local header + 数据 + EOCD；`$SECTION = 29388903` |
| 尾部 | `29390231 .. 29392991`（**2761** B） | 动态段 + 节头表；节头表 22 × 64 = **1408** B，`e_shoff = 29391584` |
| 文件总长 | **29392992** B | 1328 + 29388903 + 2761 |

也就是说：前 1328 字节是 ELF 头（给 Android 的 linker 看的），从偏移 1328 开始的 29388903 字节是一整段 ZIP 数据（给 `TermuxInstaller` 解包用的），最后 2761 字节是动态段与节头表。`TermuxInstaller` 会先 `System.loadLibrary("termux-bootstrap")` 再读 ZIP 字节，所以这个 `.so` 必须能被 `dlopen`：**节头表不能移位、不能丢**。

重打包后的写回顺序必须是：**pristine 的前 1328 字节 + 新 ZIP + 零填充 + pristine 的 2761 B 尾部**，ZIP 结束位置固定为 29390231。`tools\reloc_bootstrap.py` 就是按这个绝对偏移重建的；如果只写「ELF 头 + 新 ZIP」，`dlopen` 会报 `has invalid shdr offset/size: 29391584/1408`（1.2.2 修的就是这个截断问题）。之后的 `[3c/5]` 原地等长改写**不会动上面任何一个数字**：它只在 ZIP 内逐文件替换字符串、把剩余字节填 NUL，写完 `size=` 仍是 29392992；一旦这步改成「重建 ELF」（例如用 lief 改 RUNPATH），程序头 / 节头就会变，真机上不是 `dlopen` 失败就是 `execve` 失败。

注入流程（`tools\RepackBootstrap.java`，用法：`RepackBootstrap repack <in.so> <prefixOff> <sectionSize> <out.so> [--drop <prefix>]... [--add <rel>=<hostpath>]...`）：

1. 读入 `.so`，在偏移 1328 处切出 29388903 字节的 ZIP 段；
2. 用 ZIP 读取器打开它，按 `--drop` 删掉指定前缀的条目；
3. 按 `--add` 把 `build/` 下的文件逐个作为新条目写入（`Sort-Object FullName` 保证顺序稳定）；
4. 把新的 ZIP 段按同样的偏移写回、剩余空间补零，输出新的 `.so`（尾部保持 pristine 的 2,761 B 不变）。

**为什么必须 `--drop`**：原始包的内嵌 ZIP 段是**恰好填满** 29388903 字节的，段长是固定的、不能变，所以想塞进任何新文件，就必须先删掉等量（或更多）的旧条目腾出空间。

被 `--drop` 掉的都是**部署流程永远不会执行**的内容：

- `share/man`、`share/info`、`share/doc`：文档；
- `include/`：C 头文件（设备上永远不编译东西）；
- `libexec/installed-tests/`、`libexec/termux-am/`、`libexec/termux/`、`libexec/telnetd`、`libexec/syslogd`、`libexec/inetd`、`libexec/tftpd`、`libexec/rmt`、`libexec/frcode`：测试二进制与用不到的 helper；
- `share/aclocal`、`share/nano`、`share/bash-completion`、`share/readline`、`share/libgpg-error`、`share/awk`、`share/examples`、`share/zsh`、`share/tabset`、`share/pacman`：shell 补全与示例；
- `lib/bash/`、`lib/gawk/`、`lib/pkgconfig/`、`lib/cmake/`：可加载 builtin、gawk 扩展、构建系统元数据。

**故意保留**的：`share/LICENSES`、`share/terminfo`、`share/termux-keyring`、`var/lib/dpkg/**`、`lib/**/*.so`、`bin/**`、`etc/**`、`SYMLINKS.txt`。

> **安全性说明**：`TermuxInstaller.java` 在创建符号链接之前，会先为 `SYMLINKS.txt` 里的每个条目预先创建父目录（`ensureDirectoryExists`，第 174 行；创建链接在第 209 行），所以删掉 `SYMLINKS.txt` 指向的某个目录**不会**让解包失败 —— 悬空的链接目标在 Android 上是合法的，而且之前的构建里（`share/man|info|doc`）就已经这么发过了。

---

## 六、版本号

版本号采用语义化版本，**只改仓库根目录的 `VERSION` 一处**（`build-ui.ps1` 打包时会换算并写入
`_apkwork\apktool.yml` 的 `versionName` / `versionCode`）：

| 版本 | 字段 | 值 |
| --- | --- | --- |
| **1.3.4-beta.4（当前，测试版）** | `versionName` / 派生 `versionCode` | `1.3.4-beta.4` / `10304` |
| 1.3.4-beta.3 | `versionName` / 派生 `versionCode` | `1.3.4-beta.3` / `10304` |
| 1.3.4-beta.2 | `versionName` / 派生 `versionCode` | `1.3.4-beta.2` / `10304` |
| 1.3.4-beta.1 | `versionName` / 派生 `versionCode` | `1.3.4-beta.1` / `10304` |
| 1.3.3（正式版） | `versionName` / 派生 `versionCode` | `1.3.3` / `10303` |
| 1.3.3-beta.10 | `versionName` / 派生 `versionCode` | `1.3.3-beta.10` / `10303` |
| 1.3.3-beta.9 | `versionName` / 派生 `versionCode` | `1.3.3-beta.9` / `10303` |
| 1.3.3-beta.8 | `versionName` / 派生 `versionCode` | `1.3.3-beta.8` / `10303` |
| 1.3.3-beta.7 | `versionName` / 派生 `versionCode` | `1.3.3-beta.7` / `10303` |
| 1.3.3-beta.6 | `versionName` / 派生 `versionCode` | `1.3.3-beta.6` / `10303` |
| 1.3.3-beta.5 | `versionName` / 派生 `versionCode` | `1.3.3-beta.5` / `10303` |
| 1.3.3-beta.4 | `versionName` / 派生 `versionCode` | `1.3.3-beta.4` / `10303` |
| 1.3.3-beta.3 | `versionName` / 派生 `versionCode` | `1.3.3-beta.3` / `10303` |
| 1.3.3-beta.2 | `versionName` / 派生 `versionCode` | `1.3.3-beta.2` / `10303` |
| 1.3.3-beta.1 | `versionName` / 派生 `versionCode` | `1.3.3-beta.1` / `10303` |
| 1.3.2（正式版） | `versionName` / 派生 `versionCode` | `1.3.2` / `10302` |
| 1.3.2-beta.2 | `versionName` / 派生 `versionCode` | `1.3.2-beta.2` / `10302` |
| 1.3.2-beta.1 | `versionName` / 派生 `versionCode` | `1.3.2-beta.1` / `10302` |
| 1.3.1 | `versionName` / 派生 `versionCode` | `1.3.1` / `10301` |
| 1.3.0 | `versionName` / 派生 `versionCode` | `1.3.0` / `10300` |
| 1.2.10 | `versionName` / 派生 `versionCode` | `1.2.10` / `10210` |
| 1.2.9 | `versionName` / 派生 `versionCode` | `1.2.9` / `10209` |

1.3.1 起每版只有一个全量包。当前构建 `AstrBot-1.3.4-beta.4-arm64.apk`，`609,550,220`（约 581 MB，镜像在 APK 里），SHA-256 `07362864C513BE71613D7ECAA9658E0F2F380AC4594032B8ADB4951A9FC4D216`；增量包 `AstrBot-1.3.4-beta.4-arm64-update.apk`，`26,369,929`，SHA-256 `527162BD95FC6EACC81FDD8E81CDB07385765824E16237F75C92736475CEE417`。1.3.4-beta.4 沿用旧密钥（_research\debug.keystore）签名，`versionCode 10304` 高于 1.3.3 及全部 1.3.3 测试版的 `10303`，因此可直接覆盖安装。

1.3.4-beta.3 又新增 5 个 Java 类（`QqbotUpdater` / `QqbotUpdaterCore` 应用内更新、`QqbotHealthBridge` 熔断与备份、`QqbotIslandView` / `QqbotIslandBridge` 灵动岛），重写了 `QqbotModelBridge`（原生直连模型接口），新增脚本 `build/opt/qqbot/crashfuse.sh`（崩溃熔断），并给 `backup.sh` 加了快照 / 回滚模式，所以增量包从 `26,300,297` 涨到 `26,361,737`。build-ui.ps1 现在还会把 `SYSTEM_ALERT_WINDOW` 与 `REQUEST_INSTALL_PACKAGES` 两个权限幂等补进 `AndroidManifest.xml`（供灵动岛与应用内安装使用），并已从杀软规避的删除清单里移出。

1.3.4-beta.4 起灵动岛改成走系统「实时更新」通知：`QqbotIslandView.java`（悬浮窗小球）整份删除，`QqbotIslandBridge.java` 重写成纯桥（只读能力 / 开关 / 跳设置，不建窗），`QqbotGuardService.java` 里换成新渠道 `qqbot_live`（`ISLAND_ID=42330`）并用反射调 `setShortCriticalText` 与 `FLAG_PROMOTED_ONGOING`，Android 16 以下自动退回普通通知。扫码弹窗的「不再提示（本次运行）」落在新增的 `QqbotSession.java`（静态字段，不落盘），`QqbotNotifyBridge` 加了 `qrSkip` / `qrSkipped`，`QqbotGuardService.filterKinds()` 在本次运行内过滤掉「需要重新扫码」那类通知。

脚本侧这一版改的是 proot 探针：`common.sh` 里 `qq_proot_probe` 变成三档矩阵（默认 → `PROOT_NO_SECCOMP=1` → 再叠 `--link2symlink`），命中后写 `proot-noseccomp` 标记，`qq_proot_export_mode()` 在文件顶层调用一次，所有 source 它的脚本都自动沿用；`qq_proot_fail_rows()` 按关键字给四档人话文案。`deploy.sh` / `steps/10-termux.sh` / `steps/70-launcher.sh` / `diagnose.sh` / `fix-sandbox.sh` 相应接线。注意档位开关必须排在 `proot` 本体**之后**（放前面会被 `timeout` 当成命令，rc=127）。

UI 侧这一版只动 `ui/assets/qqbot/index.html` 与 `ui/assets/qqbot/plugins.html`：删掉更多页 `#runGuardCard` 整卡、`#islandCard` 改「实时更新」口径、二维码弹窗加 `qrskip`、全局按钮视觉层（`out/b4_buttons.css` 的内容整块并进 `index.html` 样式表末尾，块尾 `.btns > button` 12px / `.rowbtn` 10px 用来收回被 `--mi-ctl` 16px 压住的圆角；`.small` / `.tiny` 的视觉高度真抬到 44px，所以更多页卡片会变高）。

本版同时改了 Java、界面和脚本，增量包不再是「只有脚本」：新增 5 个 Java 桥（`QqbotNotifyBridge` / `QqbotShare` / `QqbotPluginBridge` / `QqbotModelBridge` / `QqbotDiagBridge`）、新页面 `ui/assets/qqbot/plugins.html`、脚本 `build/opt/qqbot/diagnose.sh` 与 `model-test.py`，所以增量从 `26,206,018` 涨到 `26,300,297`。`build-ui.ps1` 的资产复制也从「点名拷 `index.html` + `appicon.png`」改成整目录 `Copy-Item ui\assets\qqbot\*`——以前新增页面会静默漏包（APK 里没有、真机白屏），以后往 `ui/assets/qqbot/` 加文件不用再改脚本。自查命令：`python out/b2_apkcheck3.py` 会核对 APK 内 `assets/qqbot/index.html` 与源码同哈希、`qqbot-scripts.zip` 里的脚本与 `build/opt/qqbot/` 同哈希。

规则：`versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

- **MAJOR**（X.0.0）：不兼容改动，例如换沙盒方案、需要重装 APK 才能升级；
- **MINOR**（0.X.0）：新增功能；
- **PATCH**（0.0.X）：修 bug、文案、性能。

**预发布后缀**：`VERSION` 允许写 `1.3.2-beta.1` 这种带后缀的测试版，`build-ui.ps1` 会把它原样写进
`versionName`（应用「设置」页、崩溃上报里都看得到），`versionCode` 仍按数字段算 `10302`。
**不要为了区分 beta 去改 `versionCode`**：beta 与随后的正式版 `versionCode` 必须相同，
否则正式版对装了 beta 的手机就是「降级」，覆盖安装会被系统拒绝。

改完记得同步更新 `CHANGELOG.md`。

---

## 七、注意事项

1. **`.ps1` 与 bash 脚本保持纯 ASCII。**
   Windows PowerShell 5.1 读取**没有 BOM** 的 `.ps1` 时会按 ANSI（中文系统上是 GBK）解码，
   脚本里的非 ASCII 字面量会变成乱码，轻则提示文案错乱，重则**解析报错直接跑不起来**。
   需要中文提示时，优先放进 `.sh` 或前端 HTML 里。
2. **两个构建脚本必须串行。**
   `build-ui.ps1` 和 `build-apk.ps1` 都动 `out\termux-ui-unsigned.apk`，并行会互相覆盖。
3. **注入偏移是硬编码的。**
   `build-apk.ps1` 里的 `$PREFIX_OFF = 1328` 与 `$SECTION = 29388903` 只对**这一版**的上游 Termux bootstrap 成立。
   换上游 Termux 版本时，必须先重新测量这两个值（以及文件总长 29392992 与尾部 2761 B），否则会写出损坏的 `.so`。
   改写后的 `.so` 必须仍是 **29,392,992 字节**、程序头 / 节头逐字节与 pristine 一致 —— 这是 `dlopen` 能过的前提，也是沙盒里 `dpkg` / `apt` 在真机 Android 16 上能被 `execve` 的前提；发版前用 `cmp` 比对一遍 pristine 的尾部 2,761 B。
4. **1.2.1 及更早版本不能直接覆盖安装。**
   1.2.2 起包名从 `com.termux` 改成 `zhuxin.astrbot`，装过 1.2.1 及更早版本的设备**必须先卸载**再装（卸载会清掉旧包名下的沙盒数据，先让用户「更多 → 备份与恢复」导出备份）；官方 Termux 不再冲突。1.2.2 之后同签名可以继续覆盖升级（1.2.3 → 1.2.4 可直接覆盖）。
5. **`build/` 下新增或改名文件后不需要改脚本。**
   `build-apk.ps1` 是递归扫描 `build/` 的，加文件自动带上；但如果新文件需要新的 `--drop` 前缀腾空间，要手动加到 `$dropPrefixes` 里。
6. **构建脚本都在仓库根目录，`$ROOT` 自动定位。**
   `build-ui.ps1` 与 `build-apk.ps1` 都用 `$PSScriptRoot` 取仓库根目录，所以从任何工作目录调用都不会找错路径；
   只有 `$JDK` / `$JAVA` 需要能被找到（`JAVA_HOME` 或 PATH 上的 `javac.exe`）。
7. **不要提交构建产物。**
   `_research/`、`_sdk/`、`out/`、`_apkwork/`、`ui\classes\`、`ui\dex\`、`ui\smali\` 以及 `*.apk` / `*.dex` / `*.jar` / `*.so` 全部已被忽略。
8. **`_apkwork/` 需要先解包一次。**
   `build-ui.ps1` 只负责**重新打包**，不负责解包。首次构建前要先跑一次
   `java -jar _sdk\apktool.jar d -f -o _apkwork _research\termux-arm64.apk`，否则脚本会直接报
   `MISSING _apkwork - run apktool d first`。
9. **首次构建较慢。**
   `javac` + `d8` + `apktool b` 加起来通常要几分钟；`apktool b` 阶段最慢，属正常现象。

10. **清单里 `android:sharedUserId` 与两个 Termux ContentProvider 的 `exported` 不能动。**
    `android:sharedUserId="com.termux"` 必须保留：删掉会让已装的 1.2.3 覆盖安装时报
    `INSTALL_FAILED_UID_CHANGED`，等于强制用户卸载重装、清空沙盒。`TermuxDocumentsProvider` 与
    `TermuxOpenReceiver$ContentProvider` 必须保持 `android:exported="true"`：改成 `false` 会让应用启动即崩
    `SecurityException: Provider must be exported`。3c3 只能删权限，不能碰这三处。

11. **查界面问题可以用 `.webdebug` 开放 WebView 远程调试。**
   在设备的 `/data/data/zhuxin.astrbot/files/home/.qqbot/.webdebug` 放一个空文件再启动 App，
   `QqbotActivity` 会调用 `WebView.setWebContentsDebuggingEnabled(true)`；之后
   `adb forward tcp:19222 localabstract:webview_devtools_remote_<pid>` 就能用 CDP 读 DOM / 调页面函数。
   这个开关只认文件、默认关闭，发行包里不会对局域网暴露调试端口。

12. **NapCat 模式按架构分档（1.2.9 起），改 `run-napcat.sh` 时注意四点。**
    入口是 `napcat_headless_on()`：`/qqbot-cache/napcat-headless.on` 强制开、`napcat-headless.off` 强制关，都没有时按 `napcat_arch()` 判断 —— **arm64 返回 1（走完整客户端 + Xvfb，1.2.7 的老路线），x86_64 返回 0（走无头）**。
    ① 架构判定**不能用 `uname -m`**：x86_64 模拟器（MuMu）把内核 uname 伪装成 aarch64，必须用 `start-sandbox.sh` 传进 proot 的 `QQBOT_ARCH`（`common.sh` 的 `qq_detect_arch()` 读 `getprop ro.product.cpu.abi`）。
    ② 无头两档都保留：`util`（`NAPCAT_FORCE_NODE_PROCESS=0`，Electron utilityProcess，最省但 arm64 真机必崩）与 `fork`（`=1` + Xvfb，能绕开崩溃但内存 1.05 GB）。用户覆盖开关是 `$HOME/.qqbot/cache/napcat-mode.txt`（`util` / `fork` / `gui`）。
    ③ 第一档参数必须带 `--disable-dev-shm-usage`（proot 里 Chromium 用不了 `/dev/shm`，会 `GPU process isn't usable` 直接退出）。
    ④ 就绪判据是 `hl_wait_ready()`：**端口在听 + 进程活着 + 之后 6 秒无崩溃日志**，别退回「日志出现 `NapCat.Core Version` 就算成功」—— 真机上那行打完约 2 秒 worker 才 SIGSEGV，会误判成启动成功、实际每 20 秒重启一轮（1.2.8 的事故）。起不来要能自动退回 Xvfb 全流程，别把 `start_inst` 里那条回退删掉。
    改完可以用 `adb push` 直接替换设备上的 `files/usr/opt/qqbot/sandbox/run-napcat.sh` 快速验证，不必先出包；验证前先 `bash -n`（proot 里跑，PATH 要手工 export）。

13. **模拟器验收必须「删容器 + 全新部署」，不能只覆盖安装。**
    覆盖安装（`adb install -r`）会保留上一版部署出来的 rootfs、NapCat、AstrBot 与缓存，
    很容易把「上一版能跑」误当成「这一版能跑」，也会掩盖首次部署才走的步骤
    （20-rootfs → 30-apt → 40-qq → 50-napcat → 60-astrbot → 70-launcher）。
    每换一个版本包都按这个顺序走一遍：
    ```
    adb -s <serial> install -r AstrBot-<版本>-arm64.apk
    adb -s <serial> shell pm clear zhuxin.astrbot     # 清掉上一版部署的整个容器
    adb -s <serial> shell am start -n zhuxin.astrbot/com.termux.app.QqbotActivity
    ```
    之后看 `files/home/.qqbot/logs/deploy.log` 里步骤是否一路绿灯、`deploy.done` 是否写出，
    再看 `supervisor.log` 的 NapCat 模式与 `free -m` / `ps` 的内存，最后确认主页显示「一切正常」。
    用 `pm clear` 而不是手工删目录：`pm clear` 同时清掉 shared_prefs、appops 与缓存标记，最接近用户首次安装。

14. **模型 / 人格保存是热生效的，改 `AstrHot.java` 先看 `provider_sources`。**
    AstrBot v4.29 起，`cmd_config.json` 里的 `api_base` / `key` / `timeout` 归 `provider_sources[]`，
    `provider[]` 只留 `id` / `model` / `provider_source_id`（启动时 `migra_helper._migra_provider_to_source_structure` 会自动拆）。
    写侧顺序固定为 `PUT /api/v1/provider-sources/by-id` → `PUT /api/v1/providers/by-id` → `PUT /api/v1/personas/by-id`
    → `PUT /api/v1/system-config`（最后这个 body 是 **config 本体**，包一层 `{"config":…}` 不报错但 provider_id 改不到）；
    读侧 `AstrCfg.info()` 要按运行时的合并顺序 `{**provider_source, **provider}`（provider 扁平字段优先）。
    验收三件事：保存后 **1 秒内**主页卡片就变；`GET /api/v1/system-config` 里 `agent_runner.config.model.provider_id` 是 `candle`；
    落盘的 `provider[]` 里**没有** `api_base` / `key`（有的话说明写错了地方，下次启动会被迁移覆盖）。
    查问题时开 `~/.qqbot/.webdebug` + CDP 直接读 DOM 最快，测完记得删掉这个文件。

15. **改 `tools/patch_manifest.py` 时别把整包读进内存。**
    全量包里有一个 583 MB 的沙盒镜像，旧写法 `zin.read()` + `writestr()` 会在内存紧张的机器上
    直接 `MemoryError`（1.3.3-beta.8 构建时踩到，当时 MuMu 模拟器占着 24 GB 提交内存）。
    现在除 `AndroidManifest.xml` 走小字节数组外，其余条目一律 `zin.open()` → `zout.open()` 分块拷贝，
    峰值内存只有几 MB；签名前的 zipalign 由 uber-apk-signer 负责，不需要在这里补对齐。

---

## 八、发布到站点

官网 `https://xn--15t839a.xyz` 的下载卡不是手写的，由 VPS 上的 `astrbot_download_sync.py` 每天从 GitHub Releases 同步生成：
`zhuxin-astrbot-dl.service`（oneshot）+ `zhuxin-astrbot-dl.timer`（每日）跑 `/data/www/zhuxin-site/scripts/astrbot_download_sync.py`，
把安装包镜像到 `/data/www/zhuxin-site/downloads/`（最多留 4 个版本），再改写 `data/astrbot.json`。本仓库的 `vps-deploy/astrbot_download_sync.py` 只是源文件。

1.3.1 起每个 Release 的**主下载**是全量包（`AstrBot-<版本>-arm64.apk`，约 600 MB，镜像在里面，断网也能装完）；
站点同步 `pick_apk()` 只认它。测试版还会多放一个 `AstrBot-<版本>-update.apk`（约 25 MB），供应用内覆盖升级用。
沙盒镜像本体按第四节单独传到 VPS 的 `/dl/`，不单独作为 Release 资产。

注意事项：

1. **改动（patch）脚本后要重新部署**。`vps-deploy/astrbot_download_sync.py` 提交后不会自动上线，必须覆盖 VPS 上的
   `/data/www/zhuxin-site/scripts/astrbot_download_sync.py`，再手动跑一次（先 `--dry-run` 看输出，再去掉 `--dry-run` 正式同步）。
2. **同版本重打包、大小恰好相同也会自动重下**。脚本判断站内包是否新鲜时，先比字节数，再按 GitHub 资产自带的
   `digest`（`sha256:…`）核对本地文件哈希；大小一样但内容不同（同版本重新构建常见）会判为过期并重下，
   不会出现「页面写新 SHA-256、下载文件还是旧包」。反过来，大小与哈希都对得上就不会重复下载几十 MB。
3. `pick_apk()` 只认「**`state=uploaded` + 文件名带 `arm64` + 非 `-update`**」的资产：1.2.5 起每个 Release 只有一个 arm64 精简包；历史版本遗留的 amd64 资产与 `-update` 资产不会被当成主下载。
4. **上传断掉会在 GitHub 上留下 `state=starter` 的残骸**（`size` 是目标大小、`digest` 为空、下载 404，而且 `/releases/latest`
   不列它）。`pick_apk()` 会跳过非 `uploaded` 的资产，但残骸仍会干扰人工核对。传完精简包后务必用
   `/releases/{id}/assets` 确认 `state=uploaded` 且 `digest` 有值；一版里找不到可用包时脚本会沿用站内旧文件，
   不会把它从下载页上抹掉。
5. 站内文件名固定为 `AstrBot-<版本>.apk`；要强制换包，删掉 `downloads/AstrBot-<版本>.apk` 再跑同步。
6. **Upload（约 600 MB）用 `curl`，别用 PowerShell 的 `Invoke-RestMethod`**：PS 5.1 传大文件会在中途报
   `The underlying connection was closed`（本次 1.3.2-beta.1 已踩）。可直接断点重来：
   `curl.exe -sS -X POST --http1.1 -H @headers.txt -T AstrBot-<版本>-arm64.apk "<upload_url>?name=<文件名>"`，
   `headers.txt` 里放 `Authorization: token <PAT>` 与 `Content-Type: application/octet-stream` 两行。
   沙盒镜像本体另按第四节传到 VPS 的 `/data/www/zhuxin-site/dl/`，不单独作为 Release 资产。
7. **测试版不进站**：`pick_apk()` 跳过文件名含 `beta` 的资产，站点更新列表也跳过 GitHub 标了 pre-release
   的版本；`/releases/latest` 本身就不返回预发布。所以 beta 如果发到 GitHub，**必须勾上
   "Set as a pre-release"** —— 忘了勾，它就成了 latest，官网下载卡与应用内「检查更新」会直接把测试版推给所有人。
8. **同版本重打包后，Release 正文里的字节数 / SHA-256 要一起改**。站点历史卡片是照着 Release 正文（`body`）渲染的，
   资产换了而正文没换，用户按卡片上的哈希核对就会对不上（1.3.1 的全量包 10 月 24 日重传过，正文里还留着第一次的
   `609,341,253` / `2AB0B779…`，而实际资产是 `609,345,349` / `945E306A…`）。改法：
   `PATCH /repos/Zxin-Pro/astrbot-candle/releases/<id>`，body 只发 `{"body": "<新正文>"}`；
   不想等每日同步就同时对 `data/astrbot.json` 与 `data/astrbot_content.json` 做同样的字符串替换（改前 `cp -a` 备份）。

---

## 九、发布节奏：先 beta，验收通过再出正式版

从 1.3.2 起固定这个节奏，每一版都一样：

1. **改版本号**：`VERSION` 写成下一版加后缀，例如 `1.3.2-beta.1`；同一版本还要再发一次测试就递增 `-beta.2`。
2. **写更新日志**：`CHANGELOG.md` 增加 `## [1.3.2-beta.1] - YYYY-MM-DD` 一段，写清这次改了什么
   —— beta 段也要写全，验收的人就是照着它逐条测。正式版再单独写一段（不要直接改 beta 那段）。
3. **构建**（顺序不能反，`build-apk.ps1` 用的是 `build-ui.ps1` 产出的 UI APK）：
   `build-ui.ps1` → `build-apk.ps1 -Arch arm64 -Update` → `build-apk.ps1 -Arch arm64`。
4. **交付 beta**：把 `AstrBot-1.3.2-beta.1-arm64.apk`（全量）与 `AstrBot-1.3.2-beta.1-arm64-update.apk`
   （覆盖安装用）连同 SHA-256 发给验收的人；打 tag `v1.3.2-beta.1` 并 `git push origin refs/tags/v1.3.2-beta.1`。
   要发 GitHub，就用 `POST /repos/{owner}/{repo}/releases` 建 **`prerelease=true`** 的 Release（tag 填 `v1.3.2-beta.1`），
   两个包按 §八 第 6 条用 `curl` 上传。beta **不进官网、不推 GitHub latest**。
5. **验收通过 → 发正式版**：`VERSION` 去掉后缀写成 `1.3.2`，`CHANGELOG.md` 加正式版那段，
   重新走一遍第 3 步构建（`versionCode` 不变，装了 beta 的手机直接覆盖安装即可），打 tag `v1.3.2`，
   把全量包传到 GitHub Release（**不要**勾 pre-release，本 tag 成为 latest），官网同步脚本第二天会自动更新下载卡。
6. **收尾核对**：`/releases/tags/<tag>` 看 `prerelease` 与两个资产的 `state=uploaded`、`size`、`digest`；
   beta 阶段再确认 `/releases/latest` 仍是上一正式版；`git rev-list -n 1 <tag>` 与远端 `refs/tags/<tag>^{}` 一致。

beta 与正式版之间不需要卸载重装：同一 `versionCode` 的覆盖安装是允许的，数据（沙盒、账号、配置）全部保留。

### 9.1 直连 `github.com:443` 不通时怎么发

症状：`curl -o NUL -w '%{http_code}' https://github.com/` 反复返回 `000`，但 `https://api.github.com/` 返回 200、
`https://uploads.github.com/` 返回 302 —— 说明只是 `github.com` 这个域名/端口被卡，GitHub 的 API 和上传域名是通的。

这时不要等，用 REST API 把提交和 tag 直接写进远端：

1. `git credential fill` 取 token（`@('protocol=https','host=github.com','')` 用管道喂进去）。
2. 逐个文件 `POST /repos/{o}/{r}/git/blobs`（内容 base64），再 `POST /git/trees`（`base_tree` = 远端 HEAD 的 tree），
   然后 `POST /git/commits`（`parents` = 远端 HEAD），最后 `PATCH /git/refs/heads/main` 让 main 指向新提交。
3. annotated tag：`POST /git/tags`（`object`=新提交、`type=commit`）拿到 tag 对象 sha，再 `POST /git/refs` 建 `refs/tags/<tag>`。
4. `POST /releases`（`prerelease` / `draft` / `tag_name` / `body`），然后照 §八 第 6 条用 `curl` 传两个包。

三个坑：

- **Release 正文不要让 `Invoke-RestMethod` 发**：带中文长正文时它会把 JSON 撑到几十 MB（本次 47 MB）然后卡死不返回。
  改成 Python 拼 payload（`json.dumps(..., ensure_ascii=False)` 写 `out/rel-payload.json`），再用
  `curl -X POST -H @hdr --data-binary @out/rel-payload.json` —— 2098 字节、3.5 秒就建好。
- **`.ps1` 里有中文必须存 UTF-8 BOM**：`tools.write` 写出的是无 BOM UTF-8，PS 5.1 按 GBK 读会把 `）` 之类读坏，
  报 `The hash literal was incomplete` / `The string is missing the terminator`。写完用
  `[IO.File]::WriteAllText($p, $txt, [Text.UTF8Encoding]::new($true))` 补上 BOM。
- **API 造的提交和本地提交 SHA 不同**（author/committer 变成了 token 的账号）。等 `github.com` 恢复后收口：
  `git fetch --tags --force origin` → `git reset --hard origin/main` → `git tag -d <tag>` →
  `git fetch origin refs/tags/<tag>:refs/tags/<tag>`，让本地 tag 也指向远端那个 annotated tag 对象。

