# AstrBotIOS 工程脚手架说明

本目录是 iOS App 的 Xcode 工程脚手架。**所有 .swift 源码都是既有的，本目录不修改任何一个 .swift 文件**，只负责把它们和资源组织成一个 Xcode 能直接打开、能编译出 .app 的工程。

## 一、目录结构

```
ios/
  AstrBotIOS/                    # target 源码与资源根目录
    App/AstrBotApp.swift         # @main 入口（既有源码）
    Bridge/*.swift               # JS 桥（既有源码）
    Net/*.swift                  # 回环 HTTP 服务端与远程客户端（既有源码）
    UI/*.swift                   # SwiftUI 界面（既有源码）
    Resources/
      Info.plist                 # 本脚手架编写
      Assets.xcassets/           # 本脚手架编写（AppIcon）
      web/                       # sync_web.py 从 ui/assets/qqbot 同步而来
      shared/AndroidShim.js      # sync_web.py 从 ios/shared 拷入
  shared/                        # 源目录：本地桥脚本
  tools/
    gen_xcodeproj.py             # 主路径：纯 Python 生成 .xcodeproj
    sync_web.py                  # H5 资源同步
    gen_appicon.py               # 1024x1024 图标生成
    mock_bridge.py               # 既有工具，与本脚手架无关
    boot_check.py                # 既有工具，与本脚手架无关
  project.yml                    # 备选路径：XcodeGen 工程描述
  AstrBotIOS.xcodeproj/          # gen_xcodeproj.py 生成（可直接 open）
  PROJECT.md                     # 本文件
.github/workflows/ios-build.yml  # macOS 上的模拟器构建流水线
```

## 二、在 Mac 上打开并运行

### 1. 生成工程

两条路径二选一，产物等价：

**路径 A（推荐，本仓库主路径）**

```bash
python3 ios/tools/sync_web.py        # 若 Resources/web 已是最新可跳过
python3 ios/tools/gen_appicon.py     # 需要 Pillow：pip3 install pillow
python3 ios/tools/gen_xcodeproj.py
```

**路径 B（XcodeGen）**

```bash
brew install xcodegen
cd ios && xcodegen generate
```

两条路径都会写出 `ios/AstrBotIOS.xcodeproj`。若两者结构出现分歧，以 `gen_xcodeproj.py` 为准（它是 CI 里实际使用的那一条）。

### 2. 打开与签名

```bash
open ios/AstrBotIOS.xcodeproj
```

首次打开必须做一次签名配置，否则真机运行会报 "Signing for AstrBotIOS requires a development team"：

1. 选中左侧 `AstrBotIOS` 工程 → TARGETS → `AstrBotIOS` → **Signing & Capabilities**；
2. Team 选择自己的 Apple ID（免费个人账号即可，7 天有效期）；
3. Bundle Identifier 默认 `com.astrbot.ios`，与他人冲突时改成 `com.<自己的名字>.astrbot.ios`；
4. 真机首次运行时，手机需在「设置 → 通用 → VPN 与设备管理」中信任该开发者证书。

模拟器运行不需要签名，直接 Cmd+R 即可。

### 3. 脚本自检

```bash
python3 ios/tools/gen_xcodeproj.py --check    # 只校验、不写盘
python3 ios/tools/gen_appicon.py --check      # 只校验图标，不重新生成
```

`--check` 会检查：花括号与圆括号配平、被引用的每个 24 位 UUID 都有 `\t\t<UUID> /* 注释 */ = {` 形式的定义、磁盘上每个 .swift 都恰好出现一次在 Sources 构建阶段、`INFOPLIST_FILE` 指向真实存在的文件、web/shared 两个 folder reference 在 Resources 阶段、内置 OpenStep 解析器能把 pbxproj 反解成对象树。

## 三、为什么 web 与 shared 是 folder reference（蓝色文件夹）

源码里有两条硬编码的资源路径：

- `Net/LoopbackServer.swift` 第 22 行：
  `(Bundle.main.resourceURL ?? Bundle.main.bundleURL).appendingPathComponent("web", isDirectory: true)`
- `UI/ConsoleView.swift` 第 126-127 行：
  `let root = Bundle.main.resourceURL` 然后 `root.appendingPathComponent("shared/AndroidShim.js")`

它们要求 bundle 里存在 **`web/` 与 `shared/` 两个目录**，而不是散落在 bundle 根目录下的一堆文件。所以工程里这两个条目用的是 `lastKnownFileType = folder`（Xcode 显示为蓝色文件夹）并加入 Resources 构建阶段：Xcode 会把整个目录原样拷进 `AstrBotIOS.app/web/` 与 `AstrBotIOS.app/shared/`，目录结构被完整保留。

如果按普通文件（黄色文件夹）加进 Resources，Xcode 会把 `index.html`、`appicon.png` 平铺到 bundle 根目录，运行时 `web/index.html` 就会 404，App 会白屏。

`ios/shared` 位于 target 目录之外，因此它的 file reference 用 `sourceTree = SOURCE_ROOT`（即 `ios/`）加相对路径 `shared` 定位。

`web/` 里的 `appicon.png` 必须和 `index.html` 一起同步：`index.html` 第 4743 行有 `<img id="hdAvatar" src="appicon.png">` 这类相对引用，缺图会导致 H5 界面缺角。`sync_web.py` 已经把它们一起拷贝过去。

## 四、Info.plist 里几个"看起来多余"的键

| 键 | 为什么必须存在 |
| --- | --- |
| `NSAppTransportSecurity.NSAllowsArbitraryLoads` + `NSAllowsLocalNetworking` | App 通过 `http://` 访问局域网内 AstrBot 宿主（默认不明文放行会被 ATS 拦死）。`NSAllowsLocalNetworking` 单独放行局域网地址，是 App Store 审查更接受的口径。 |
| `NSLocalNetworkUsageDescription` | iOS 14 起访问局域网（含 127.0.0.1 之外的本地网络）会弹权限框，缺这条描述字符串会直接崩。文案为中文。 |
| `NSPhotoLibraryUsageDescription` | 设置页允许选相册图片当界面背景图（PhotoPicker.swift）。 |
| `LSApplicationQueriesSchemes` = `mqqapi, mqq, weixin, wx` | 用 `canOpenURL` 判断能否跳转 QQ/微信时，不声明就会被静默拒绝。 |
| `UILaunchScreen` = `{}` | 空字典即使用系统默认启动屏。缺这个键的 App 在某些 iOS 版本上会被强制以 320x480 兼容模式运行。 |
| `UIRequiresFullScreen` = `true` | iPad 只声明了 Portrait 与 PortraitUpsideDown 两种方向。App Store 校验要求「不支持全部四种方向的 iPad App 必须设为全屏」；不加这条，iPad 分屏下布局会被压缩到不可用。 |
| `ITSAppUsesNonExemptEncryption` = `false` | 只用系统 TLS，不含自定义加密。声明后每次上传 App Store Connect 不用再回答出口合规问卷。 |
| `CADisableMinimumFrameDurationOnPhone` = `true` | 允许在 ProMotion 设备上跑满 120Hz。 |
| `CFBundleDisplayName` = AstrBot 烛版 | 桌面图标下显示的名字。 |

plist 里没有任何注释——`Info.plist` 是严格 XML 属性列表，注释虽在语法上允许，但 `xcodebuild` 处理时可能被裁掉，解释一律写在本节。

## 五、最低系统版本为什么是 iOS 16.0

SwiftUI 侧使用了 `NavigationStack` 与 `ToolbarItem(placement: .topBarLeading / .topBarTrailing)`，两者都是 iOS 16 才有的 API（`OnboardingView.swift:13/66`、`PanelView.swift:13/25/29`、`SettingsView.swift:15/114`）。把部署目标定在 15.0 会出现 "is only available in iOS 16.0 or newer" 的硬编译错误。因此：

- `ios/project.yml` 的 `options.deploymentTarget.iOS` 与 target 级 `deploymentTarget` 均为 `"16.0"`；
- `project.pbxproj` 的 `IPHONEOS_DEPLOYMENT_TARGET = 16.0`（项目级 Debug 与 Release 各一处）。

顺带说明：`UI/RootView.swift:43-44` 的 `onChange(of:)` 用的是单参数闭包形式，属于 iOS 15 的旧签名。在 iOS 16 下它能编译，只会给一条弃用警告，不影响产物。

## 六、CI 流水线

`.github/workflows/ios-build.yml`，触发条件：改动 `ios/**`、`ui/assets/qqbot/**` 或该 workflow 本身时自动跑，也支持在 Actions 页面手动 `Run workflow`。

在 `macos-14` 上依次执行：

1. `actions/checkout@v4` + `actions/setup-python@v5`（Python 3.12，与本地一致）；
2. `pip install pillow`；
3. `python ios/tools/sync_web.py` —— 同步 H5；
4. `python ios/tools/gen_appicon.py` —— 生成 1024 图标（Assets.xcassets 需要它在）；
5. `python ios/tools/gen_xcodeproj.py` —— 生成工程；
6. `python ios/tools/gen_xcodeproj.py --check` —— 结构自校验；
7. `xcodebuild -project ios/AstrBotIOS.xcodeproj -scheme AstrBotIOS -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`；
8. 上传 `AstrBotIOS.app` 为 artifact（`if-no-files-found: error`，防止假成功）。

只做模拟器构建的原因：真机包需要开发者证书，而证书不该进仓库。模拟器构建已经足以验证"工程能被 Xcode 解析、17 个 Swift 文件能通过编译、资源能进 bundle"这三件最要紧的事。真机安装请在 Mac 上手动签名。

## 七、为什么主路径不用 xcodegen

`project.yml` 是给 Mac 用户提供的便利，但仓库的主路径是 `tools/gen_xcodeproj.py`，理由：

1. **开发机是 Windows**。xcodegen 只能跑在 macOS 上；本项目需要在 Windows 上就产出工程文件并在 CI 之前自查结构，纯 Python 脚本没有平台依赖。
2. **可复现**。UUID 由 `sha1("<角色>|<相对路径>")[:24].upper()` 决定，同一份源码每次生成的 UUID 完全相同，`git diff` 是空的；xcodegen 的 UUID 生成策略同样稳定，但多引入一个黑盒依赖。
3. **可自校验**。`--check` 能脱离 Xcode 验证括号配平、UUID 定义完整性、Sources 覆盖度、INFOPLIST_FILE 路径。xcodegen 产出的工程没有等价的自检手段。
4. **不引入构建工具链**。CI 里 `xcodegen` 需要额外 `brew install`，失败点更多。

代价是：`gen_xcodeproj.py` 只覆盖本项目需要的对象类型（PBXBuildFile / PBXFileReference / PBXGroup / PBXNativeTarget / PBXProject / PBXResourcesBuildPhase / PBXSourcesBuildPhase / PBXFrameworksBuildPhase / XCBuildConfiguration / XCConfigurationList），不支持 CocoaPods、Swift Package、多 target。后续要加这些，应改用 xcodegen 或 HandyJSON 之外的正规生成器。

## 八、诚实说明：本机没有真正编译过

生成这些文件的机器是 **Windows，没有 macOS、没有 Xcode、没有 xcodebuild**。因此：

- 没有任何一条 `xcodebuild` 命令在本机跑过，不存在"编译通过"的证据；
- 本机的验证手段只有两种：
  1. `gen_xcodeproj.py --check` 的结构自校验（9 项，见上）；
  2. 用 Python 标准库 `plistlib` 解析 `Info.plist`，确认它是合法 XML plist 且键值符合预期；
- 真正的编译验证发生在 `.github/workflows/ios-build.yml` 第一次运行时。

### 已知风险点（按可能性排序）

1. **Swift 编译错误**（中等）。17 个 .swift 从未被 Apple 编译器处理过。已知 `RootView.swift:43-44` 的单参数 `onChange` 会报弃用警告；若 Xcode 15 之后把它升级为错误，需要改源码（本脚手架不碰 .swift）。
2. **并发隔离警告**（中等）。工程设了 `SWIFT_STRICT_CONCURRENCY = minimal` 与 Swift 5 语言模式；若未来切到 Swift 6 模式，`@MainActor` + `nonisolated` 的组合可能报错。
3. **folder reference 路径**（低）。`web` 用 `sourceTree = "<group>"`、相对 Resources group 的 `AstrBotIOS/Resources`；`shared` 用 `sourceTree = SOURCE_ROOT`、相对 `ios/`。若把 .xcodeproj 移动到别处，`shared` 会失效。
4. **图标质量**（已知瑕疵）。源图 `ui/assets/qqbot/appicon.png` 只有 120x120，放大到 1024 后偏软。App Store 审核要求 1024 图标不带 alpha 通道，脚本已输出 RGB，但仍建议后续换成原生 1024 素材。
5. **签名**（低，仅真机）。CI 用 `CODE_SIGNING_ALLOWED=NO`，真机包必须在 Xcode 里选 Team 后重签。
6. **Xcode 版本**（低）。pbxproj 的 `objectVersion = 56`、`compatibilityVersion = "Xcode 14.0"`。Xcode 13 及更早打不开（也编译不了 iOS 16 部署目标），Xcode 14 及以上正常。

## 九、资源同步脚本的两个约束

- `sync_web.py` 删除 `Resources/web` 前会断言目标路径位于 `ios/AstrBotIOS/Resources` 之下，防止误删；
- `index.html` 近 480 KB，脚本只比对字节数并读首行断言 doctype，不整文件读入内存比对。

## 十、改源码之后

改完 `.swift` 不需要重新生成工程——工程里登记的是文件路径，文件增删才需要重跑 `gen_xcodeproj.py`（它靠扫盘发现 .swift）。改了 `ui/assets/qqbot` 里的 H5，则必须重跑 `sync_web.py`，否则 App 里还是旧的 `index.html`。
