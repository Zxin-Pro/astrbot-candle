# 从源码构建 AstrBot烛版

本文说明如何从本仓库的源码重新构建出可安装的 `AstrBot-<版本号>.apk`（当前版本为 `AstrBot-1.1.9.apk`）。

> 构建过程**只能在一台配好工具链的 Windows 机器上**完成（脚本是 PowerShell + 本机 JDK）。
> 构建不联网、不跑 Gradle，也不需要 Android Studio。

---

## 一、构建产物

| 产物 | 路径 | 说明 |
| --- | --- | --- |
| 界面中间包 | `out\termux-ui-unsigned.apk` | apktool 重新打包的原生控制台 APK（**未签名**） |
| 注入后的 bootstrap | `out\libtermux-bootstrap.so` | 塞进了 `build/` 下所有脚本的 `.so` |
| 最终未签名包 | `out\termux-qqbot-unsigned.apk` | 换好 `.so`、待签名的 APK |
| **可安装包** | `out\signed\termux-qqbot-aligned-debugSigned.apk` | uber-apk-signer 对齐 + 签名后的成品（发布用副本由 `build-apk.ps1` 自动按版本号复制到仓库根目录的 `AstrBot-<版本号>.apk`） |

版本号（`versionCode` / `versionName`）不是写死在 APK 里的，而是由仓库根目录的 **`VERSION`** 文件决定：
内容形如 `1.0.2`，`build-ui.ps1` 打包时换算成 `versionCode = MAJOR*10000 + MINOR*100 + PATCH` 一并写进 `_apkwork\apktool.yml`。
要发新版本，改 `VERSION` 一行即可，同时记得在 [CHANGELOG.md](../CHANGELOG.md) 里补一条。

---

## 二、环境依赖

| 依赖 | 用途 | 本机示例路径 |
| --- | --- | --- |
| **JDK 21** | 编译 Java 工具、`javac`、`d8`、签名 | `C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin` |
| **PowerShell 5.1** | 跑构建脚本（Windows 自带） | `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` |
| **apktool.jar** | 解开 / 重新打包 APK | `_sdk\apktool.jar` |
| **d8** | dex 编译器（把 class 编成 dex） | `_sdk\bt\android-14\lib\d8.jar`（入口类 `com.android.tools.r8.D8`） |
| **android.jar** | 编译时的 Android API 桩 | `_sdk\platform\android-34\android.jar` |
| **uber-apk-signer.jar** | 对齐 + 重新签名 | `_research\uber-apk-signer.jar` |
| **上游 Termux APK** | 作为注入基底与 pristine bootstrap 来源 | `_research\termux-arm64.apk` |
| **本仓库的 Java 工具** | 注入 / 替换 / dex→smali / 校验 | `tools\*.java`（已编译的 `.class` 直接放在 `tools\` 下当 classpath 用） |

`_sdk\` 与 `_research\` **不入库**（见 `.gitignore`），需要自己准备：

- `_sdk\apktool.jar`：从 apktool 官方 release 下载（本仓库验证过 2.9.3）。
- `_sdk\platform\android-34\android.jar`：从 Android SDK 的 `platforms;android-34` 取。
- `_sdk\bt\android-14\lib\d8.jar`：从 Android SDK 的 `build-tools;34.x.x` 取。
- `_research\termux-arm64.apk`：Termux 官方 arm64 版 APK（本版本基底为 Termux v0.118.3，`minSdkVersion 24` / `targetSdkVersion 28`）。
- `_research\uber-apk-signer.jar`：从 uber-apk-signer 的 release 下载。

---

## 三、构建步骤

### 0. 准备

把所有路径变量改成你自己机器上的实际路径（脚本里是硬编码的）：

- `build-ui.ps1` 里标注了 `EDIT THIS LINE` 的那一行 `$JDK`（改成你自己的 JDK 21 `bin` 目录）；
- `build-apk.ps1` 里同样位置的 `$JDK`（`$ROOT` 已由 `$PSScriptRoot` 自动定位，不用改）。

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
powershell -ExecutionPolicy Bypass -File C:\Users\zjh19\DSH\qqbot-sandbox\build-apk.ps1
```

这一步分四小步：

1. **取 pristine bootstrap**：从 `_research\termux-arm64.apk` 里解出 `lib/arm64-v8a/libtermux-bootstrap.so` 到 `out\_pristine-bootstrap.so`。
   > 注意：**永远从原始 stock APK 取**，不要从上一步的 UI APK 取 —— 注入的偏移数学必须基于原始字节布局。
2. **重新打包 bootstrap**：调 `tools` 下的 `RepackBootstrap`，把 `build/` 下的**所有文件**（递归，相对路径按 `/` 分隔）作为新条目加进内嵌 ZIP，同时 `--drop` 掉一批用不到的条目腾空间，输出 `out\libtermux-bootstrap.so`。
3. **换回 APK**：调 `ApkReplace`，把新 `.so` 换进 `out\termux-ui-unsigned.apk`（存在时）或 stock APK，输出 `out\termux-qqbot-unsigned.apk`。
4. **签名**：调 uber-apk-signer 输出到 `out\signed\`。

### 3. 交付

`build-apk.ps1` 最后会自动把 `out\signed\termux-qqbot-aligned-debugSigned.apk` 复制到仓库根目录，
命名为 `AstrBot-<VERSION>.apk`（`VERSION` 文件的内容就是版本号；当前 `VERSION` 是 `1.1.9`，产物即 `AstrBot-1.1.9.apk`，大小 25218222 字节），这个文件就是发布用的安装包。

> **两个脚本必须串行执行**：`build-ui.ps1` 会**写出** `out\termux-ui-unsigned.apk`，`build-apk.ps1` 会**读取并覆盖**它，
> 并行跑会互相踩踏，产物不可预测。**永远先 build-ui，再 build-apk。**
>
> 也可以直接跑 `out\rebuild-all.ps1`（如果本地有的话），它按顺序调用这两个脚本。

---

## 四、bootstrap 注入原理

Termux 的 `lib/arm64-v8a/libtermux-bootstrap.so` 并不是一个普通的 `.so`，而是一个 **ELF 头 + 内嵌 ZIP 段** 的混合体：

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `prefix`（`$PREFIX_OFF`） | **1328** | ELF 头长度；内嵌 ZIP 段从这里开始 |
| `sectionSize`（`$SECTION`） | **29388903** | 内嵌 ZIP 段的长度（字节） |
| 文件总长 | **29392992** | 1328 + 29388903 = 29392992 |

也就是说：文件的前 1328 字节是 ELF 头（给 Android 的 linker 看的），从偏移 1328 开始的 29388903 字节是一整段 ZIP 数据（给 `TermuxInstaller` 解包用的）。`TermuxInstaller` 在装机时按这个偏移切出 ZIP 段并解开，把 `$PREFIX` 目录铺好。

注入流程（`tools\RepackBootstrap.java`，用法：`RepackBootstrap repack <in.so> <prefixOff> <sectionSize> <out.so> [--drop <prefix>]... [--add <rel>=<hostpath>]...`）：

1. 读入 `.so`，在偏移 1328 处切出 29388903 字节的 ZIP 段；
2. 用 ZIP 读取器打开它，按 `--drop` 删掉指定前缀的条目；
3. 按 `--add` 把 `build/` 下的文件逐个作为新条目写入（`Sort-Object FullName` 保证顺序稳定）；
4. 把新的 ZIP 段按同样的偏移与长度写回，输出新的 `.so`。

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

## 五、版本号

版本号采用语义化版本，**必须同时改两处**：

| 位置 | 字段 | 当前值 |
| --- | --- | --- |
| `_apkwork\apktool.yml` | `versionName` / `versionCode` | `1.1.9` / `10109` |

规则：`versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

- **MAJOR**（X.0.0）：不兼容改动，例如换沙盒方案、需要重装 APK 才能升级；
- **MINOR**（0.X.0）：新增功能；
- **PATCH**（0.0.X）：修 bug、文案、性能。

改完记得同步更新 `CHANGELOG.md`。

---

## 六、注意事项

1. **`.ps1` 与 bash 脚本保持纯 ASCII。**
   Windows PowerShell 5.1 读取**没有 BOM** 的 `.ps1` 时会按 ANSI（中文系统上是 GBK）解码，
   脚本里的非 ASCII 字面量会变成乱码，轻则提示文案错乱，重则**解析报错直接跑不起来**。
   需要中文提示时，优先放进 `.sh` 或前端 HTML 里。
2. **两个构建脚本必须串行。**
   `build-ui.ps1` 和 `build-apk.ps1` 都动 `out\termux-ui-unsigned.apk`，并行会互相覆盖。
3. **注入偏移是硬编码的。**
   `build-apk.ps1` 里的 `$PREFIX_OFF = 1328` 与 `$SECTION = 29388903` 只对**这一版**的上游 Termux bootstrap 成立。
   换上游 Termux 版本时，必须先重新测量这两个值（以及文件总长 29392992），否则会写出损坏的 `.so`。
4. **重新签名后不能覆盖安装。**
   签名与官方 Termux 不同，装过官方 Termux（或旧签名版本）的设备**必须先卸载**再装，否则会报「应用未安装 / 签名冲突」。
5. **`build/` 下新增或改名文件后不需要改脚本。**
   `build-apk.ps1` 是递归扫描 `build/` 的，加文件自动带上；但如果新文件需要新的 `--drop` 前缀腾空间，要手动加到 `$dropPrefixes` 里。
6. **构建脚本都在仓库根目录，`$ROOT` 自动定位。**
   `build-ui.ps1` 与 `build-apk.ps1` 都用 `$PSScriptRoot` 取仓库根目录，所以从任何工作目录调用都不会找错路径；
   只有 `$JDK` 那一行需要按你自己的机器改。
7. **不要提交构建产物。**
   `_research/`、`_sdk/`、`out/`、`_apkwork/`、`ui\classes\`、`ui\dex\`、`ui\smali\` 以及 `*.apk` / `*.dex` / `*.jar` / `*.so` 全部已被忽略。
8. **`_apkwork/` 需要先解包一次。**
   `build-ui.ps1` 只负责**重新打包**，不负责解包。首次构建前要先跑一次
   `java -jar _sdk\apktool.jar d -f -o _apkwork _research\termux-arm64.apk`，否则脚本会直接报
   `MISSING _apkwork - run apktool d first`。
9. **首次构建较慢。**
   `javac` + `d8` + `apktool b` 加起来通常要几分钟；`apktool b` 阶段最慢，属正常现象。
