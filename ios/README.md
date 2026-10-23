# AstrBot 烛版 · iOS

把 `qqbot-sandbox`（AstrBot 烛版手机版）搬到 iOS 上。**网页控制台一个字节都不改**，iOS 只补原生那一层。

## 为什么 iOS 版是「控制台」而不是「手机上跑 AstrBot」

Android 版靠 proot 在手机上开一个 Ubuntu 沙盒，把 NapCat + AstrBot 整套餐跑在本地。iOS 做不到：

- App 不能 `fork/exec` 任意二进制，也没有可写的根文件系统，proot 那套直接不成立；
- 后台常驻进程会被系统随时挂起，即使塞进一个常驻 loop 也保不住长连接。

所以 iOS 版的定位是**远程控制台**：界面、日志、机器人管理、二维码登录、备份恢复这些前端能力全都在，
真正跑 AstrBot 的那台机器（NAS/软路由/VPS/家里的电脑）通过一个轻量 HTTP 代理接进来。
和 Android 版的差别只在「谁在跑进程」，用户看到的那套 UI 是同一份。

## 架构：回环 HTTP + 同步 XHR

```
  WKWebView（原版 index.html，零改动）
        │  Android.xxx(...)  ← 同步调用，200+ 处调用点
        ▼
  AndroidShim.js（documentStart 注入）
        │  同步 XHR POST
        ▼
  本机回环服务 127.0.0.1:17870（App 内的 LoopbackServer）
        ├── GET /            原样吐出 Resources/web/index.html
        ├── GET /__caps      当前挂在 window.Android 上的方法名
        └── POST /__bridge   {m, a} → 分派 → 返回前端要的那一个字符串
                 │
                 ├── LocalBridge   通知 / 剪贴板 / 相册 / 头像 / 背景 / 统计 / 公告
                 └── RemoteClient  → 宿主：POST /api/qqbot/bridge（带 X-AstrBot-Key）
```

为什么要绕这么一圈，而不是直接用 `WKScriptMessageHandler`：

`index.html` 里全是 `var r = JSON.parse(Android.state())` 这种**同步取值**，一共 200+ 处。
`postMessage` 是异步的，改异步等于把整个前端重写一遍；而同源回环 HTTP 上的 `xhr.open(..., false)`
能原样保住同步语义——页面、垫片、原生层三方都不需要为 async 让步。
同源 + `text/plain` 请求体还顺带避开了 CORS 预检。

## 目录

```
ios/
├── CONTRACT.md              协议真源：90 个桥方法的归类、线格式、字段契约
├── AstrBotIOS/              SwiftUI App（17 个 .swift）
│   ├── App/AstrBotApp.swift
│   ├── Bridge/              BR / BridgeRouter（90 项分派表）/ LocalBridge / StatsBridge / Prefs
│   ├── Net/                 LoopbackServer（回环 HTTP）/ RemoteClient（宿主代理）
│   ├── UI/                  ConsoleView(WKWebView) / Settings / Onboarding / Root / Panel …
│   └── Resources/           Info.plist、web/（同步来的静态资源）、Assets.xcassets
├── shared/AndroidShim.js    注入的垫片（App 与 mock 共用同一份）
├── remote-agent/            宿主侧代理：astrbot_bridge_agent.py（纯标准库）+ README
└── tools/                   mock_bridge / boot_check / check_bridge / sync_web / gen_xcodeproj
```

## 构建（需要 macOS + Xcode 15，最低 iOS 16.0）

```bash
python3 ios/tools/sync_web.py        # ui/assets/qqbot → ios/AstrBotIOS/Resources/web
python3 ios/tools/gen_xcodeproj.py   # 生成 AstrBotIOS.xcodeproj（不需要 xcodegen）
open ios/AstrBotIOS.xcodeproj        # 真机运行前填好签名 Team
```

也可以用 XcodeGen：`cd ios && xcodegen generate`（`project.yml` 与生成器等价）。
CI 见 `.github/workflows/ios-build.yml`（macOS 模拟器构建，不签名）。

## 宿主侧

```bash
python3 ios/remote-agent/astrbot_bridge_agent.py --root ~/astrbot \
        --port 17880 --token 一串自己定的口令
```

它只监听宿主回环，跨网络请用 SSH 隧道（`ssh -L 17880:127.0.0.1:17880 host`）或反代，
不要直接把 17880 暴露到公网。App 里填宿主地址、端口、口令即可。

## 已验证到什么程度

**这台开发机是 Windows，没有 Xcode / swiftc，所以「能不能编译」目前没有机器证据。** 已经做的是：

1. `python ios/tools/check_bridge.py` —— 9 项静态一致性检查全过（Java 的 90 个 `@JavascriptInterface`
   与分派表一一对应、alwaysOn 不悬空、35 个 local 方法都有实现、index.html 的 59 个调用名都在表里、
   17 个 Swift 文件括号配平）。退出码 0。
2. `python ios/tools/boot_check.py` —— mock 代理 + 无头 Chrome 真跑一遍页面：15 条断言全 PASS
   （四个页签、副标题已刷新、`window.Android` 91 个方法、版本号/在线胶囊/今日概览/部署 100%、
   430 与 390 宽均无横向溢出、0 条 JS 报错），并出 430×932 与 390×844 两张截图。
3. `python ios/remote-agent/astrbot_bridge_agent.py --selftest` —— 11 项自检，打印 `SELFTEST OK`。
4. `ios/REVIEW.md` —— 逐文件人工编译审查（API 可用性、@MainActor 混用、跨文件符号），结论是「必错项为空」。
5. 宿主代理端到端联调 —— 真起 `astrbot_bridge_agent.py`，用与 `RemoteClient` 完全相同的线格式
   （`POST /api/qqbot/bridge` + `X-AstrBot-Key` + `{m,a}`）打了 18 个方法，状态码、令牌校验、1 MB 上限、
   `state` 的 25 个字段全部对得上。

### 审查发现并已修掉的运行时问题

`REVIEW.md` 里有四条是真会崩或真会卡死的，已经全部改掉（改完复跑了上面 1、2、3、5 四项）：

| 问题 | 症状 | 修法 |
| --- | --- | --- |
| `LoopbackServer` 主线程同步 bind | 端口被占时最坏 31×1.5s，会被启动看门狗 `0x8badf00d` 杀掉（点开即闪退、无崩溃栈） | `boot()` 改到 `DispatchQueue.global` 里起服务，单次 bind 超时降到 0.7s |
| `Content-Length` 为负 | `index(bodyStart, offsetBy: -1)` 直接 Fatal error | 夹到 `0...8MB`，守卫改成 `contentLength >= 0` |
| `UIHost.present` 直接 return | WKUIDelegate 的 `completionHandler` 永不调用，JS `alert()` 把整页挂死 | `present` 改成返回 `Bool`，没递上去就补调 `completionHandler()` |
| `NWConnection` 没设 `stateUpdateHandler` | 连接进 `.waiting/.failed` 时同步 XHR 永远不返回 | `accept` 里补上，失败即 `cancel` |

另外顺手修掉：`RootView` 三个 `.sheet` 挂同一节点会互顶（收成一个 `enum Sheet`）、`PanelView` 漏
`didFailProvisionalNavigation`（进度条停半路）、`Substring` 上没有 `trimmingCharacters`（大概率编译错）、
`powerGuide` 的 `jumped` 恒 `true`；顺序挪到前面：`state()` 的 `appVersion` 为空时由本机补 `BR.appVersion`、
`notiGranted` 由本机注入（页面只读这个字段来判断要不要弹「开启通知」横幅，它从不调 `notiState`）。

第二轮针对性复核（只查这 11 处改动）又揪出四条，也一并改了：`Shell` / `AppEnv` 的 `@Published`
都是主线程隔离的，`View` 在 Xcode 15 只有 `body` 带 `@MainActor`，所以 `pull()` / `boot()` 补上了
`@MainActor`、后台回主线程改用 `Task { @MainActor in }`；`present` 前多查一次
`presentedViewController`（转场中 UIKit 会静默丢弃 present，回调却不会来，JS 照样卡死）；
`openAppSettings` 不再用 `canOpenURL` 当门禁（`app-settings:` 没进白名单，会变成「说打不开、也真没跳」）；
`pull()` 在有弹层时保留请求标记，弹层关掉后再拉一次，不再丢面板请求。

换句话说：**前端整条链路是真的跑通过的，Swift 那层只做了静态审查 + 一轮针对性复核**。第一次上 Mac 大概率还要修几个编译错，
`README` 不装作它已经编译过。

## 与 Android 版的差异

| 能力 | Android | iOS |
| --- | --- | --- |
| 本机跑 AstrBot | 有（proot + Ubuntu） | 无，改连宿主 |
| `avatarIconPin`（换应用图标） | 有 | 不挂，前端走「这个安装包还不支持」 |
| `openTerminal`（内置终端） | 有 | 不挂，请用 SSH / 网页终端 |
| 省电策略横幅 | 各厂商加白引导 | `powerState.shown=true`，只留设置页一行字 |
| 头像缓存 | `file://` 直读 | 回环 `/__img/<name>`（WKWebView 拦 file://） |
| 相册选背景 | SAF | PHPicker，缩到长边 1600 JPEG 0.82 |
| 崩溃看板 | 真的读本地崩溃文件 | 恒空列表（iOS 侧还没接崩溃收集） |

## 安全

- 回环服务只绑 `127.0.0.1`（`requiredLocalEndpoint = .ipv4(.loopback)`），不接受外部连接；
- 宿主代理默认只绑回环，`--bind 0.0.0.0` 必须显式再给 `--lan`，否则退出码 2；
- 令牌走 `X-AstrBot-Key`，代理侧用 `secrets.compare_digest` 比；
- `readLog` / `clearLog` / `rmLog` 只认日志目录下的 `*.log`，挡路径穿越；
- Info.plist 开了 `NSAllowsArbitraryLoads`（要载入 `http://127.0.0.1` 和用户填的内网 http 地址），
  上架时需要向审核说明这是控制台类 App 的本地连接需求。

## 还没做的

- 真机/模拟器编译验证（需要 Mac）；
- iOS 侧崩溃收集（`crashBoard` 现在恒空）；
- 扫码登录那条链路要宿主侧也实现 `qr` / `qrShare`（代理目前对未配置方法回 `ok:false`）；
- 深浅色：原生外壳固定浅色，网页控制台自己的主题切换不受影响。