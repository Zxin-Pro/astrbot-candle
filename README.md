# AstrBot 烛版

> 把 **Linux QQ + NapCat + AstrBot** 装进 Android 手机的开箱即用包。
> 装 APK → 打开 → 等它自己跑完，手机里就有了一台 7×24 在线的 QQ 机器人服务器。无需 root，无需敲任何命令。

[![Release](https://img.shields.io/github/v/release/Zxin-Pro/astrbot-candle?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/Zxin-Pro/astrbot-candle/releases/latest)
[![License](https://img.shields.io/github/license/Zxin-Pro/astrbot-candle?label=GPL-3.0)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Zxin-Pro/astrbot-candle/total?label=%E4%B8%8B%E8%BD%BD)](https://github.com/Zxin-Pro/astrbot-candle/releases)

**最新正式版** [v1.3.5](https://github.com/Zxin-Pro/astrbot-candle/releases/tag/v1.3.5) ｜ **下载** [Releases](https://github.com/Zxin-Pro/astrbot-candle/releases/latest) · [官网 烛心.xyz](https://xn--15t839a.xyz/#/astrbot)（站内直下，不跳 GitHub）
**包名** `zhuxin.astrbot` ｜ **versionCode** `10305` ｜ **官方 QQ 群** 1127997343

---

## 它是什么

一条「重新打包开源 Termux APK」的流水线，加上一个本地控制台：

- 打开应用即可在手机里建立一个 **Ubuntu 24.04 proot 沙盒**（proot 用户态，不需要 root、刷机或 Termux 命令行）；
- 沙盒内自动部署 **Linux QQ 3.2.34-53644 + NapCat 4.18.38 + AstrBot 4.28.2**（离线镜像内置版本；在线部署时会先查上游最新版）；
- 三者**出厂即预接线**：NapCat 通过反向 WebSocket 连到 AstrBot 的 aiocqhttp 平台（`ws://127.0.0.1:6199/ws`）；
- 界面是一个本地 HTML 控制台（WebView 加载 `file:///android_asset/qqbot/index.html`），**主页 / 机器人 / 日志 / 更多** 四个页签 —— 扫码、开关服务、看日志、改端口全在界面里点；
- 三层守护防掉线：沙盒内 `run-all.sh` + Termux 侧 `watchdog.sh` + Android 前台服务 `QqbotGuardService`。

从源码怎么做出这个包，见 [docs/BUILD.md](docs/BUILD.md)。

## 快速开始

1. **下载**：首次安装 / 换机装**全量包**（约 581 MB，内含 arm64 离线镜像，首次部署不联网解压即用）；已装 1.3.3 / 1.3.4 / 1.3.5-beta.5 / beta.6 装**增量包**（约 25 MB）。
2. **安装**：允许「未知来源应用」。小米 / HyperOS 若提示「应用缺少隐私政策」且没有「继续安装」，关掉手机管家的安装监控、或开飞行模式再装、或用 `adb install -r`，不是包坏了。
3. **打开**：读完免责声明点「我已阅读并同意」，随后自动开始 9 步部署（解包镜像 → 依赖 → Linux QQ → NapCat → AstrBot → 接线 → 启动）。
4. **部署期间**：保持应用在前台、屏幕常亮；退出、断电都可以重来，重新打开会从断点续跑，不会重复下载。
5. **扫码登录**：用**另一台设备**的 QQ 扫「日志」页的二维码（手机扫自己屏幕上的码不行）。

> **升级注意**：只有 `1.3.5-beta.7`（`103057`，当日试行的 6 位制临时代码）和已撤回的 `1.3.6-beta.1`（`10306`）会被系统按降级拒装、需卸载一次；其余版本都能直接覆盖。卸载会清空沙盒数据，请先在「数据与存储 → 配置备份」导出。

> **安全提示**：2026-10-29 起全量包已重制（旧包镜像曾含运行期配置与凭据，28 个旧包已下架），请只从 [Releases](https://github.com/Zxin-Pro/astrbot-candle/releases) 或官网下载，处置说明见 [docs/SECURITY-REBUILD-b4.md](docs/SECURITY-REBUILD-b4.md)。

## 系统要求

| 项目 | 要求 |
| --- | --- |
| 系统版本 | Android 8.0 及以上 |
| CPU | arm64-v8a（x86_64 模拟器可直接装同一个包，架构由运行时按 ABI 选镜像） |
| 存储 | 建议预留 3 GB 以上 |
| 内存 | 建议 4 GB 以上；每多开一个 QQ 实例多占一份 Linux QQ 进程内存 |
| 网络 | 首次部署需访问 Ubuntu 镜像站（清华 / 中科大 / 阿里）与 GitHub；慢可在「更多 → 下载代理」填代理 |
| Root | 不需要 |

## 界面

| 页签 | 内容 |
| --- | --- |
| **主页** | 一句话状态总览（一切正常 / 正在启动 / 服务已停止）、四个常用动作（NapCat / 控制台 / 日志 / 重启）、实例卡；「更多工具」折叠区含部署进度、组件开关、机器人连接、访问地址 |
| **机器人** | 每个 QQ 号一张实例卡：复制 Token、启停容器、设为当前、编辑端口、删除（两步确认）；底部「新增实例」 |
| **日志** | 扫码登录二维码（图文）、NapCat / AstrBot 实时日志（左右并排、2 秒刷新、可精简）、任意日志文件；单文件超 20 MB 自动截断保留末尾 2 MB |
| **更多** | 7 组设置：运行状态 / 提醒与报告 / 升级与扩展 / 外观与模型 / 连接与端口 / 数据与存储 / 系统与维护 —— 含组件重装、端口、下载代理、维护与修复、终端、关于 |

外观为 iOS 液态玻璃风，支持跟随系统 / 浅色 / 深色三档主题、8 套强调色与玻璃透明度滑块。

## 端口与默认凭据

| 项目 | 值 |
| --- | --- |
| NapCat WebUI（实例 1） | `6099` |
| NapCat OneBot / aiocqhttp（实例 1） | `6199`（恒为同实例 WebUI 端口 + 100） |
| AstrBot 控制台 | `6185` |
| NapCat Token | `zhuxin`（第 N 个实例为 `zhuxinN`） |
| AstrBot 账号 / 初始密码 | `astrbot` / `Astrbot123`（**首次登录请立即修改**，并填上大模型 API Key） |

端口留空＝自动分配，会自动避开已占用端口（含 AstrBot 的 6185）。改端口在「更多 → 连接与端口」，保存后约 15 秒生效。

## 版本号规则

`versionCode = MAJOR*10000 + MINOR*100 + PATCH`，**测试版与正式版同号**：`1.3.5-beta.7` 与 `1.3.5` 都是 `10305`，验收通过后去掉后缀重打、可直接覆盖。

版本号唯一来源是仓库根目录的 `VERSION`；构建时用 `versionCode.floor` 拦住任何回退（新 code 不得低于已发出的最高 code，版本名不得倒退），违反直接构建失败。详细规则与历史对照见 [docs/VERSIONING.md](docs/VERSIONING.md)。

## 常见问题

- **首次部署很慢？** 在线装依赖 10~30 分钟属正常。卡在下载就去「更多 → 下载代理」填代理后重新部署；卡在第 3 步先进「更多 → 维护 → 修复沙盒依赖」，回来点「继续部署」。
- **扫码没反应？** 二维码必须用另一台设备的 QQ 扫；两台设备需在同一 Wi-Fi，且路由器没开 AP 隔离。
- **机器人不回话 / 掉线？** 首屏看结论 → 「组件开关」重启对应组件 → 「日志」页确认 NapCat 已登录、AstrBot 无报错 → 检查模型 API Key 与额度。
- **多开会不会很吃内存？** 每个实例都是完整的 Linux QQ 进程，内存紧张建议只开 1~2 个。

更多排错、备份迁移、隐私说明见 [使用说明.md](使用说明.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [使用说明.md](使用说明.md) | 面向使用者的完整说明书（安装、界面、排错、隐私） |
| [docs/BUILD.md](docs/BUILD.md) | 从源码构建：bootstrap 注入、包名重写、签名 |
| [docs/VERSIONING.md](docs/VERSIONING.md) | 版本号规则与历史对照 |
| [docs/UPSTREAM.md](docs/UPSTREAM.md) | 上游组件版本对照与回退开关 |
| [CHANGELOG.md](CHANGELOG.md) | 完整变更记录 |
| [ios/README.md](ios/README.md) | iOS 远程控制台（实验，只能遥控，iPhone 本机跑不了沙盒） |

## 目录结构

```
build/        注入 bootstrap 的沙盒文件（部署流程、启停、守护循环、profile.d 钩子）
ui/           原生控制台 Java 源码（com.termux.qqbot.*）+ assets/qqbot/index.html
tools/        自写 Java 工具：RepackBootstrap / ApkReplace / DexToSmali / VerifyBootstrap*
ios/          iOS 远程控制台（SwiftUI 外壳 + 宿主端 Python 代理）
docs/         构建、版本、上游对照等文档
build-ui.ps1  构建第一步：编译控制台（javac → d8 → smali → apktool b）
build-apk.ps1 构建第二步：注入脚本 → 换 .so → 对齐 → 重新签名
VERSION       版本号唯一来源
```

`_research/`、`_sdk/`、`_apkwork/`、`out/` 为本地构建产物，不入库。

## 免责声明

本软件是**本地运行环境管理器**：只在本机建一个 Linux 沙盒，并在其中安装 NapCat 与 AstrBot 两个第三方开源项目，不对它们的功能、稳定性与更新作任何保证。本软件**不是腾讯官方产品**，与腾讯公司及 QQ 团队无任何关联。

使用第三方客户端登录**可能违反平台服务条款**，存在账号被限制或封禁的风险；本软件**不收集、不上传任何数据**，登录凭据、聊天记录、配置与日志全部只存在本机。软件按「现状」提供，不附带任何担保，**请勿用于任何违法违规用途**。完整声明见应用内首次启动弹窗。

## 开源协议

以 **GPL-3.0** 发布（基于 GPL-3.0 的 Termux 重新打包，属其衍生作品），全文见 [LICENSE](LICENSE)。内嵌或下载的第三方组件各循其原有协议：Termux（GPL-3.0）、Ubuntu 24.04 及各 apt 包、Linux QQ（腾讯，专有）、NapCat、AstrBot（见各自仓库）。

本项目地址：[**github.com/Zxin-Pro/astrbot-candle**](https://github.com/Zxin-Pro/astrbot-candle)
