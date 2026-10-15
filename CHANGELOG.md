# 更新日志

本文件格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

版本号规则：`MAJOR.MINOR.PATCH`，对应 `versionCode = MAJOR*10000 + MINOR*100 + PATCH`。

- **MAJOR**（X.0.0）：不兼容改动，例如换沙盒方案、需要重装 APK 才能升级
- **MINOR**（0.X.0）：新增功能
- **PATCH**（0.0.X）：修 bug、文案、性能

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
- **引入语义化版本号制度**：采用 `MAJOR.MINOR.PATCH`，`versionCode = MAJOR*10000 + MINOR*100 + PATCH`；本版本 `versionName = 1.0.0`、`versionCode = 10000`。

### 说明

- 安装包文件名 `AstrBot.apk`，包名保持 `com.termux`。
- 安装前需先卸载官方 / 应用商店 / F-Droid 版 Termux（包名冲突，不能共存）。
- 首次部署约需下载 250 MB，耗时 20~60 分钟。
