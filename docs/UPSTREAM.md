# 上游组件版本与升级策略

本文件记录 AstrBot 烛版内置/依赖的每一个上游组件：**仓库里钉的是哪个版本、实物里装的是哪个版本、上游最新是什么、能不能升、怎么回退**。
所有数字都来自仓库文件或镜像实物，不写"大概"。采样时间 **2026-11-04**。

## 一、版本对照表

| 组件 | 仓库 pin（出处） | 镜像实物（证据） | 上游最新 | 是否落后 | 本版动作 |
| --- | --- | --- | --- | --- | --- |
| AstrBot | `v4.28.2`，`build/opt/qqbot/common.sh:31` `QQBOT_ASTRBOT_VER` | `4.28.2`，`out/bundle-arm64.tar.xz` 内 `./opt/AstrBot/astrbot/__init__.py` 的 `__version__` | `v4.29.4`（2026-10-31） | 是 | 不升级，只审计 |
| NapCat | `v4.18.38`，`common.sh:30` `QQBOT_NAPCAT_VER` | `4.18.38`，`out/napcat/napcat.mjs:8389` 的版本常量 | `v4.18.42`（2026-10-30） | 是 | 不升级，只审计 |
| Linux QQ | `3.2.34-53644`，`common.sh:29` `QQBOT_QQ_VER`（deb 名见 `common.sh:551`） | `3.2.34-53644`，镜像 `./opt/QQ/resources/app/package.json` | 未核实（官网下载页由 JS 渲染，数据源 `config1.js` 仍是 2020 年旧表） | 未核实 | 不升级 |
| Python | 不钉小版本，跟随 Ubuntu noble（`sandbox/30-apt.sh:255`） | `3.12.3-1ubuntu0.17`，镜像 `var/lib/dpkg/status` | 未核实 | — | 不动 |
| Ubuntu base | `ubuntu-base-24.04.5-base-<arch>.tar.gz`，`common.sh:550` | `24.04.5 LTS noble`，镜像 `usr/lib/os-release` | `24.04.5`（2026-09-08） | 否 | 不动 |
| Node / Electron | 不单独安装，用 QQ 自带 Electron（`sandbox/run-napcat.sh:1339-1342`） | 随 QQ 版本 | 随 QQ | — | 不动 |

**两个容易读错的地方：**

1. `state/versions.json` 只是"查到的最新版"缓存（`common.sh:1739`），**不代表已装**。文档里"沙盒里现在是 4.29.1"指的是作者机上在线升级后的实测值，不是镜像内置值。判断"装了什么"只有一处权威：`$QQBOT_CACHE/cache/astrbot-ver`、`cache/napcat-ver`（`sandbox/sysup.sh:42` 的注释写明）。
2. `out/napcat/package.json` 里的 `"version":"0.0.1"` 是上游 monorepo 的根版本，**不是 NapCat 版本**；`napcat.mjs` 里的常量才是。

## 二、为什么不直接升

`steps/60-astrbot.sh:7` 与 `steps/50-napcat.sh:8` 在**在线部署**时会先查上游最新，pin 只作下载失败时的回退值。也就是说装好之后的实际版本本来就会漂。真正需要谨慎的是"把 pin 和镜像一起抬高"，因为：

- AstrBot 4.29.4 的 `requirements.txt`（64 行）比仓库内快照（63 行）多了 `opentelemetry-api>=1.45.1,<2`、`opentelemetry-sdk>=1.45.1,<2`、`pyotp>=2.9.0`、`reportlab>=5.0.0`、`xlrd>=2.0.2`。`faiss-cpu` 仍是 `>=1.12.0`。这些 wheel 在 aarch64 + proot 上能不能装成，**仓库里没有证据**，必须真机验证（重点看 `faiss-cpu`、`reportlab`、`opentelemetry-*`，以及代理下 uv 解析耗时）。
- AstrBot 两版的 `requires-python` 都是 `>=3.12`，noble 的 python3 是 3.12，**Python 不是阻塞项**；安装只读 `requirements.txt`，不读 `pyproject.toml`，所以 pyproject 里抬高的 `pip>=26.2.2` / `certifi>=2026.10.19` / `faiss-cpu>=1.14.3` 不会强行生效。
- NapCat 升级要看四处硬编码：`sandbox/50-napcat.sh:49-56` 改 `opt/QQ/resources/app/package.json` 的 `main` 注入 `loadNapCat.js`；`sandbox/50-napcat.sh:135-140` 与 `run-napcat.sh:276-302` 是同一套 9 键 schema 的两份实现（注释要求同步改）；`run-napcat.sh:245-269` 写 `webui.json` 并保留用户键；`run-napcat.sh:1156-1157` 每次启动重建 `onebot11.json`。
- Linux QQ 换版最麻烦：deb 名由 `common.sh:551` 用 `QQBOT_QQ_VER` 拼，下载基址 `common.sh:28` 钉死了 `QQNT/9.9.36/beta/9ee04bef`；`sandbox/40-qq.sh:44-58` 还要按 `package.json` 的 `version` 改 `~/.config/QQ/versions/config.json`；`run-napcat.sh:1193-1230` 依赖 `NAPCAT_FORCE_NODE_PROCESS=1` 绕开 Electron UtilityProcess（上游 PR #1984），这个开关若被移除，worker 会回到 SIGSEGV 退出码 11。

## 三、回退开关（都不需要重装 APK）

| 想回退什么 | 怎么做 | 出处 |
| --- | --- | --- |
| AstrBot 版本 | `qq-env.txt` 或 `~/.qqbot/env.sh` 里写 `QQBOT_ASTRBOT_VER_OVERRIDE=v4.28.2`，重跑 `steps/60-astrbot.sh` | `common.sh:50-51`、`:1794-1796`（override 优先级最高） |
| NapCat 版本 | 写 `QQBOT_NAPCAT_VER_OVERRIDE=v4.18.38` 重跑 `steps/50-napcat.sh`；更稳的是把旧 `NapCat.Shell.zip` 放进 `$QQBOT_HOME/payload/` 走离线分支 | `common.sh:50-51`、`steps/50-napcat.sh:24-27` |
| QQ 版本 | 把旧 deb 放进 `$QQBOT_CACHE` 同名文件（`steps/40-qq.sh:5-11` 优先用缓存），重跑 `steps/40-qq.sh` + `steps/50-napcat.sh`（换 QQ 必须重新注入 NapCat） | `common.sh:28`、`:551`、`steps/40-qq.sh:5-11` |
| 整套运行环境 | 用旧的 `out/bundle-arm64.tar.xz`（历史快照：`583,012,780 B`，内含 AstrBot 4.28.2 / NapCat 4.18.38 / QQ 3.2.34-53644）重跑 `build-apk.ps1 -Arch arm64` | `build-apk.ps1:167-184` |
| 用户数据 | `backup.sh all` 打快照（含 QQ 登录态），`restore.sh <快照>` 回滚；应用内「配置与备份」走 `AndroidCfg` 的 manifest 容器 | `build/opt/qqbot/backup.sh:4-13`、`restore.sh:4-14` |

**升级前固定动作**：把 `$QQBOT_CACHE` 下的 `NapCat.Shell.zip`、`AstrBot-src.tar.gz`、`AstrBot-dashboard.zip` 连同 `astrbot-ver` / `napcat-ver` 一起拷出来，再 `backup.sh all` 打一份含 QQ 登录态的快照。

**升级成功的判据**（不是"进程还活着"）：`run-napcat.sh:914` 的三条同时成立 —— WebUI 端口真的在听 + 进程活着 + 6 秒内没有 worker 退出；否则翻 `$QQBOT_LOGS` 与 `xvfb-$id.log`。

## 四、升级到 4.29.4 / 4.18.42 的验收清单（下一版执行）

1. 真机（ARM64、Android 15/16）在线部署走一遍，确认 4.29.4 的依赖全部装成，`astrbot` 能起、dashboard 能开。
2. 核对 `cache/astrbot-ver` 与 `state/versions.json` 的一致性，确认文档里的"运行中版本"读的是前者。
3. NapCat 4.18.42 起来后核对 `webui.json` 与 `onebot11.json` 的键没有增减；`bots.json` 里的端口不被改写。
4. 跑一遍 OneBot 收发（发一条消息、收一条消息），确认 `ws://127.0.0.1:<onebotPort>/ws` 双向通。
5. 记录升级后的 `versionCode` / 包体积 / SHA-256，并按 `docs/VERSIONING.md` 更新 `versionCode.floor`。
6. 任一步失败：按第三节的开关回退，并把失败日志附进 CHANGELOG 的"说明"段。
