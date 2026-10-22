# 从源码构建 AstrBot烛版

本文说明如何从本仓库的源码重新构建出可安装的 `AstrBot-<版本>-<架构>.apk`（当前版本 1.2.5，发布产物 `AstrBot-1.2.5-arm64.apk`，只发这一个包）。

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
> 从 **1.1.30** 起，`build-ui.ps1` 还多了两段（都幂等、ps1 本身保持纯 ASCII）：
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
# 默认出精简包；-Arch 仍可用（arm64 / amd64），但两包内容相同，发布只发 arm64 一个
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Arch arm64
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Arch amd64
```

> `-Arch amd64|arm64` 现在默认出**精简包**（约 25 MB）：不含 `assets/qqbot-bundle.tar.xz`。
> 1.2.5 起首次部署默认走官方镜像在线安装（Ubuntu base + apt），不再默认调 `qq_bundle_pull()`；
> 只有部署前 `export QQBOT_BUNDLE=1` 才会从 `QQBOT_CDN` 拉 `bundle-<arch>.tar.xz`（见第四节）。
> `-Arch` 参数仍存在，但两个架构的包内容相同（724 个条目里只有 `assets/qqbot-scripts.zip` 的时间戳不同），发布只发 arm64 一个。
> 需要**全量包**（镜像打进 APK、可离线安装）时加 `-Bundle`：`build-apk.ps1 -Arch arm64 -Bundle`
> → `AstrBot-<版本>-arm64.apk`（约 600 MB）；`-Arch amd64 -Bundle` → `AstrBot-<版本>-amd64.apk`（约 630 MB）。
> `-Update` 已废弃：参数只为兼容旧命令行保留，不再产出 `AstrBot-<版本>-<架构>-update.apk`，
> 也不再写 `assets/qqbot-update-pkg` 标记 —— 更新包概念已取消。

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

精简包与 `-Bundle` 全量包同名，后构建的会覆盖先构建的；发布前确认根目录里放的是约 25 MB 的精简包。
`-Arch` 仍存在（arm64 / amd64 都能构建），但两个包内容相同（724 个条目里只有 `assets/qqbot-scripts.zip` 的时间戳不同）；1.2.5 起发布只发 arm64 一个，模拟器也直接装这个包。

> **发布只发一个精简包**：只上传 `AstrBot-<版本>-arm64.apk` 到 GitHub Release；模拟器也直接装这个包（内容与真机包相同）。
> 全量包不再作为 Release 资产，更新包与 `AstrBot-<版本>-<架构>-update.apk` 已取消。
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
| **1.2.5（当前）** | `versionName` / 派生 `versionCode` | `1.2.5` / `10205` |
| 1.2.4（上一版） | `versionName` / 派生 `versionCode` | `1.2.4` / `10204` |
| 1.2.3 | `versionName` / 派生 `versionCode` | `1.2.3` / `10203` |

本轮 1.2.5 只发一个安装包：`AstrBot-1.2.5-arm64.apk`，**26,095,357 字节**，SHA-256 是 `BD320D31A9C92CDF68013079DED128267BE25CAB3B98DA1353C56F47CF206B0C`；签名证书 SHA-256 仍是 `1e08a903aef9c3a721510b64ec764d01d3d094eb954161b62544ea8f187b5953`（v2+v3）。

规则：`versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

- **MAJOR**（X.0.0）：不兼容改动，例如换沙盒方案、需要重装 APK 才能升级；
- **MINOR**（0.X.0）：新增功能；
- **PATCH**（0.0.X）：修 bug、文案、性能。

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

---

## 八、发布到站点

官网 `https://xn--15t839a.xyz` 的下载卡不是手写的，由 VPS 上的 `astrbot_download_sync.py` 每天从 GitHub Releases 同步生成：
`zhuxin-astrbot-dl.service`（oneshot）+ `zhuxin-astrbot-dl.timer`（每日）跑 `/data/www/zhuxin-site/scripts/astrbot_download_sync.py`，
把安装包镜像到 `/data/www/zhuxin-site/downloads/`（最多留 4 个版本），再改写 `data/astrbot.json`。本仓库的 `vps-deploy/astrbot_download_sync.py` 只是源文件。

1.2.5 起 Release 只放一个精简包（`AstrBot-<版本>-arm64.apk`，约 25 MB），模拟器也直接装这个包；全量包与更新包都不再上传；
沙盒镜像按第四节单独传到 VPS 的 `/dl/`，不随 GitHub Release 走。

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
6. **沙盒镜像（约 580～604 MB）不走 GitHub Release**：只传到 VPS 的 `/data/www/zhuxin-site/dl/`（见第四节）；
   GitHub 上只有约 25 MB 的精简包，本机直接上传即可，不需要再走 SFTP 中转。
