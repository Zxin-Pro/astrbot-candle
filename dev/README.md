# dev/ —— 开发期的探索与校验脚本

这里放的是**开发过程中用过的一次性脚本**：探测上游下载地址、核对包索引、校验 bootstrap 注入结果、成品 APK 交叉校验等。

> ⚠️ 它们**不是构建流程的一部分**，`build-apk.ps1` / `build-ui.ps1` 完全不依赖这个目录。
> 脚本里的路径与本机环境是**硬编码**的（写死了开发机的 `C:\Users\zjh19\...`、Git 的 `bash.exe`、本机 MuMu 模拟器的 adb 串），要复用请先改路径。

| 脚本 | 用途 |
| --- | --- |
| `check-pins.ps1` / `check-pins2.ps1` | 查 PyPI（清华镜像）上某个包的版本与文件名，用来核对 AstrBot 依赖的版本 pin |
| `check-pkgs.ps1` / `check-pkgs2.ps1` | 拉 Termux apt 的 `Packages` 索引，确认部署要用到的包在 Termux 仓库里都存在 |
| `check-sdk.ps1` | 探测 Android SDK 各组件的下载地址是否可用 |
| `check-urls.ps1` | 批量探测上游 URL（Termux apt Release、GitHub 加速镜像等）可达性 |
| `check-wheels.ps1` | 确认 AstrBot 的关键依赖在 PyPI 上有 **cp312 aarch64 wheel**，避免沙盒里现场编译 |
| `dl-sdk.ps1` | 下载 `apktool.jar` / `cmdline-tools` / `build-tools` 到 `_sdk/` |
| `dl-platform.ps1` | 下载 `platform-34-ext7_r03.zip`（即 `android.jar`）到 `_sdk/` |
| `extract-deb.ps1` | 用 `tools/ArTool.java` 拆开 Linux QQ 的 `.deb`（`debian-binary` / `control.tar.xz` / `data.tar.xz`） |
| `inspect-deb.ps1` | 列出 deb 成员表，并打印 `control` 里的依赖声明 |
| `read-pkg.ps1` | 从 `data.tar.xz` 里直接读出 QQ 的 `resources/app/package.json` |
| `zip-list.ps1` | 列 `_research/` 下各归档的大小与 `NapCat.Shell.zip` 的条目 |
| `probe-idx.ps1` | 探测 Ubuntu apt 索引路径 —— 就是这次排查发现 **arm64 必须用 `/ubuntu-ports/` 而不是 `/ubuntu/`** 的脚本 |
| `probe-ports.ps1` | 批量探测各镜像站的 `/ubuntu-ports` 各 suite 是否可用 |
| `run-verify-bootstrap.ps1` | 编译并运行 `tools/VerifyBootstrap2.java`，模拟 `getZip()` 校验注入后的 bootstrap |
| `verify-v2.ps1` | bootstrap 注入结果校验（条目数、ELF 前后缀字节一致性、偏移数学） |
| `verify-v3.ps1` | 从**签名后的 APK** 里抽出条目做交叉校验 |
| `verify-final.ps1` | 成品三重校验：解出的 `index.html`、bootstrap 内各脚本的 md5 与 `build/` 源文件逐一比对 |
| `syntax.ps1` / `finalize.ps1` | 对 `build/` 下所有 `.sh` 跑 `bash -n` 语法检查（两个脚本内容基本重复，留作参考） |
| `ls.ps1` | 列 `_research/` 与 `out/` 下的 deb / tar / xz / control 文件 |

---

## 依赖的 Java 工具

`tools/` 下的 `.java` 需要先编译成 `.class` 才能当 classpath 用（`.class` 不入库）：

```powershell
$JDK = 'C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin'
& "$JDK\javac.exe" -d tools tools\RepackBootstrap.java tools\ApkReplace.java tools\ArTool.java tools\DexToSmali.java tools\VerifyBootstrap2.java tools\ZipProbe.java
```

注意 `tools/Zx.java` 与 `tools/Zl.java` 依赖 JDK 内部 API，按 `docs/BUILD.md` 里的说明单独编译。
