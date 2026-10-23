# AstrBot iOS 代码审查（17 个 Swift 文件 + AndroidShim.js）

审查范围：E:/DSH/qqbot-sandbox/ios/AstrBotIOS 下 App、Bridge、Net、UI 共 17 个 .swift，外加
Resources/Info.plist、Resources/shared/AndroidShim.js（与 ios/shared 同一份）、AstrBotIOS.xcodeproj/project.pbxproj。
本次没有修改任何 .swift 文件，也没有动 ios/tools 与 ios/PROJECT.md。

## 0. 审查快照（很重要，先看这段）

审查期间这些文件被改过，所以先说清我审的是哪一版：

| 文件 | 最后修改时间 | 我实际读的版本 |
| --- | --- | --- |
| Bridge/Prefs.swift | 2026-10-23 18:24:28 | 当前版（已含 hasHost） |
| Bridge/StatsBridge.swift | 2026-10-23 18:27:12 | 当前版（已含 statsOn / statsEvents / statsInfo） |
| Bridge/LocalBridge.swift、UI/Shell.swift | 18:09:58 | 当前版 |
| Net/LoopbackServer.swift | 18:10:39 | 当前版 |
| App/AstrBotApp.swift、UI/ConsoleView、RootView、SettingsView、OnboardingView | 18:13:02 | 当前版 |
| Bridge/BR.swift、BridgeRouter.swift | 18:05:53 / 18:06:19 | 当前版 |
| Net/RemoteClient.swift、UI/UIHost.swift | 18:02:48 | 当前版 |
| UI/ToastHub、PanelView、PhotoPicker | 17:59:45 | 当前版 |

我第一次读的时候，Prefs.swift 里还没有 hasHost，StatsBridge 也还没有 statsOn/statsEvents/statsInfo；
18:24 和 18:27 这两处被补上了。所以下面不会再出现那两条，本文所有结论都以 18:34 的磁盘状态为准。

## 1. 总评（一句话）

当前这一版我找不到任何「有把握说一定编译失败」的写法：硬错误风险只剩 1 处无法在 Windows 上确认的
Foundation 扩展（Substring 上有没有 trimmingCharacters）；真正会让用户看到白屏/卡死的，是 4 个运行时缺陷
（alert 回调不返回、负 Content-Length 越界、启动期主线程同步绑定、连接没有失败回调），
以及 1 个工程结构隐患（同一个视图上挂了 3 个 .sheet）。

结论：**这一版可以送 xcodebuild 试一次；我预期它会「要么直接过，要么只在 LoopbackServer.swift:154-155 报两条错」。**

## 2. 一定编译不过（必错）

本快照下：**没有**。

历史上确实有过一条，正好是这类审查最该抓到的：

- Bridge/BridgeRouter.swift:173
  - 当时写法：guard Prefs.shared.hasHost else { return fallback(spec.kind) }
  - 为什么错：Prefs（Bridge/Prefs.swift:6-77）当时只有 host/port/token/onboarded/disclaimerAck/powerAck/stats*/ann*/crashReport/cachedUin，
    没有 hasHost；hasHost 只在 AppEnv 上（Prefs.swift:107）。预期诊断：Value of type 'Prefs' has no member 'hasHost'。
  - 现状：Prefs.swift:27 已经有了 var hasHost: Bool { !host.isEmpty }，这条已消失，**不要再按旧报告去改 BridgeRouter**。

## 3. 可能编译不过 / 仅告警

### 3.1 Substring.trimmingCharacters（最高风险，按 70% 概率是硬错误）

- 位置：Net/LoopbackServer.swift:154、Net/LoopbackServer.swift:155
- 代码：
      let k = line[line.startIndex..<idx].trimmingCharacters(in: .whitespaces).lowercased()
      let v = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
- 问题：line 是 String，切片的下标表达式结果类型是 Substring。Foundation 给 trimmingCharacters(in:) 的扩展
  在不同 SDK 上挂在 String 还是 StringProtocol 上并不稳定；若落在 String 上，这两行就是硬错误
  「Value of type 'Substring' has no member 'trimmingCharacters'」，一个文件里两条。
- 判断依据：同一行里的 lowercased() 属于标准库 StringProtocol，肯定有；trimmingCharacters 属于 Foundation overlay，
  是这一行里唯一可疑的成员。
- 改法（顺手把 4.2 的越界也一起修掉，直接整段替换 152-157 行）：
      for line in lines.dropFirst() {
          guard let idx = line.firstIndex(of: ":") else { continue }
          let k = String(line[line.startIndex..<idx]).trimmingCharacters(in: .whitespaces).lowercased()
          let v = String(line[line.index(after: idx)...]).trimmingCharacters(in: .whitespaces)
          if k == "content-length" {
              contentLength = min(max(Int(v) ?? 0, 0), 8 * 1024 * 1024)
          }
      }

### 3.2 只 import SwiftUI 却直接写 UIKit 类型

- 位置：UI/SettingsView.swift:143（keyboard: UIKeyboardType）、UI/OnboardingView.swift:92（同）、
  UI/OnboardingView.swift:35-37 与 SettingsView.swift:20-22 的 .URL / .numberPad 推断也依赖它；
  UI/PanelView.swift:31、UI/ConsoleView.swift:96/102（UIApplication、UIAlertController）
- 问题：这些文件只 import SwiftUI / WebKit，没有 import UIKit。
- 为什么一般没事：SwiftUI 与 WebKit 的 Swift 接口本身就 import 了 UIKit（Clang 模块），实际工程里只 import SwiftUI
  却写 UIApplication.shared 的代码非常常见，能过。
- 但：这是编译器的传递可见性，不是语言保证；如果报 cannot find type 'UIKeyboardType' in scope / cannot find 'UIApplication' in scope，
  就是这里。零成本保险：
      在 UI/SettingsView.swift、UI/OnboardingView.swift 顶部加 import UIKit
      （UI/PanelView.swift、UI/ConsoleView.swift 同理）

### 3.3 @MainActor 隔离：minimal 模式下多为告警，Swift 6 一律变错误

工程设了 SWIFT_STRICT_CONCURRENCY = minimal + Swift 5，所以下面这些位置大概率只是告警；
但其中几条的写法在 Xcode 16 / Swift 6 语言模式下会直接失败，现在改掉成本最低。

1) UI/SettingsView.swift:184-190
   DispatchQueue.main.async 的闭包是 @Sendable，不是 main-actor 隔离的，里面却在改
   testing / result（本 View 的 @State）以及 env.hostOnline / env.hostNote / ToastHub.shared.show（全 @MainActor）。
   典型诊断：Main actor-isolated property 'hostOnline' can not be mutated from a non-isolated context。
   改法（把 main.async 换成 MainActor 任务，顺带不再捕获 self 的可变状态）：
      RemoteClient.shared.ping(host: env.host, port: env.port, token: env.token) { ok, note in
          Task { @MainActor in
              testing = false
              result = (ok, note)
              env.hostOnline = ok
              env.hostNote = note
              ToastHub.shared.show(note)
          }
      }

2) UI/PanelView.swift:67、72、77
   Coordinator 是 NSObject，不在主 actor 上，却直接写 progress.wrappedValue；Binding.wrappedValue 在新 SDK 里是 @MainActor 的。
   72/77 行是同步调用（WebKit 回调本身在主线程，但要靠 @preconcurrency 降级才不报错）。
   改法：didFinish / didFail 里改成
      Task { @MainActor in progress.wrappedValue = 1 }
   67 行本来就包在 DispatchQueue.main.async 里，改成同样的 Task 形式即可。

3) UI/UIHost.swift:7、20、28、39
   enum 上的 static 计算属性/函数都是 nonisolated，却在里面调 UIApplication.shared、present、open。
   这些是 UIKit（@preconcurrency）的 API，所以现在多半是告警；但它同时意味着 **没有任何编译期保证这些只会在主线程执行**，
   见 4.1 的实际后果。建议保留「统一在 DispatchQueue.main.async 里用」的写法，不要改成 @MainActor（那样 LocalBridge 后台调用会直接报错）。

4) Bridge/LocalBridge.swift:17、72、83（onMainSync 闭包里用 UIPasteboard / UIApplication.shared）；
   Bridge/LocalBridge.swift:106（requestAuthorization 回调里调 KV/ToastHub，这两个是线程安全的非隔离入口，没问题）。
   17/72/83 属于同一类「UIKit 在非隔离闭包里被使用」，告警级别，不必动。

### 3.4 异型字典字面量告警

- Net/LoopbackServer.swift:207
      let data = (try? JSONSerialization.data(withJSONObject: ["methods": list, "port": port])) ?? Data("{}".utf8)
- 问题：[String: Any] 没有显式写出来，值类型一为 [String]、一为 Int，编译器会推断成 [String: Any] 并给一条
  「heterogeneous collection literal could only be inferred to '[String : Any]'」告警。能编过。
- 改法：JSONSerialization.data(withJSONObject: ["methods": list, "port": port] as [String: Any])

### 3.5 onChange 单参闭包（只在新 SDK 告警）

- UI/RootView.swift:43、UI/RootView.swift:44 用的是 onChange(of:) { _ in ... } 老签名。
- iOS 17 SDK 会报「onChange(of:perform:) was deprecated in iOS 17」，只是告警；Xcode 14/15 安静。

### 3.6 死代码与空操作（不影响编译，建议清掉）

- UI/PhotoPicker.swift 整个文件没有被任何地方引用（底色选择走的是 LocalBridge.swift:249 的 BgPickerDelegate），
  两份 PHPicker 逻辑重复，迟早会改漏一份。
- Bridge/Prefs.swift:121 的 if c.path.isEmpty { c.path = "" } 是空操作。
- Bridge/Prefs.swift:113 remoteBaseURL 全仓无引用（RemoteClient.swift:24 自己又拼了一遍 base）。
- Bridge/BR.swift:14 BR.int、Bridge/BR.swift:51 KV.remove 无调用点。
- Bridge/Prefs.swift:99 AppEnv.caps 只写不读（RootView.swift:17 是现算的）。

## 4. 运行时会真的崩 / 会真的卡死

### 4.1 严重：alert() 的 completionHandler 可能永远不调用，页面直接卡死

- 位置：UI/ConsoleView.swift:100-105 + UI/UIHost.swift:19-25
- 触发路径：页面 JS 调 alert() → WKUIDelegate 的 runJavaScriptAlertPanelWithMessage 建 UIAlertController →
  UIHost.present：DispatchQueue.main.async 里 guard let top = root else { return }；if top is UIAlertController { return }。
- 为什么是硬伤：WebKit 要求这个 completionHandler 必须且只能调用一次；一旦 present 提前 return，
  JS 侧的 alert() 永不返回，整个页面的脚本线程停在那里（同步 XHR 方案下更明显，因为脚本本来就在主线程阻塞式调用桥）。
  常见触发：连点时第二次 alert、SettingsView sheet 正开着、root 为空（启动瞬间）。
- 改法（不改 UIHost 的通用语义，只在 ConsoleView 这处兜底 + 允许叠在现有 alert 上）：
      func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                   initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
          let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
          a.addAction(UIAlertAction(title: "好", style: .default) { _ in completionHandler() })
          DispatchQueue.main.async {
              guard let top = UIHost.root else { completionHandler(); return }
              top.present(a, animated: true)
          }
      }
- 顺带建议：UIHost.alert（UIHost.swift:27-35）也有同样的 if top is UIAlertController 早退问题，只是它没有回调，不致命。

### 4.2 严重：负的 Content-Length 会让进程崩在 Data 下标上

- 位置：Net/LoopbackServer.swift:156 与 Net/LoopbackServer.swift:162
- 链路：contentLength = Int(v) ?? 0 不校验正负；第 161 行 guard available >= contentLength 对负数恒真；
  第 162 行 buf.index(bodyStart, offsetBy: contentLength) 传负数 → Fatal error: Index out of bounds（进程直接崩，不是 throw）。
- 影响面：只能从 127.0.0.1 打进来（回环），所以是「本机其它程序/被注入的页面脚本」级别的风险，但确实是崩。
- 改法：见 3.1 的整段替换（min(max(...,0), 8MB)）。

### 4.3 严重：启动期在主线程同步等监听端口，最坏 46 秒

- 位置：Net/LoopbackServer.swift:31-42（for i in 0...span，每个端口 sem.wait 最多 1.5 秒）+
  App/AstrBotApp.swift:12（.onAppear(perform: boot)）+ App/AstrBotApp.swift:18（LoopbackServer.shared.start()）
- 为什么出事：start() 是在 main 上同步跑的，端口被占/被墙时每个都要等满 1.5s，31 个端口 = 46.5s。
  iOS 的启动看门狗大约 20s 就 SIGKILL（0x8badf00d），表现是「点开就闪退、日志里没有崩溃栈」。
- 改法（把启动挪到后台，回主线程只做状态赋值）：
      private func boot() {
          if !env.onboarded { env.showOnboarding = true }
          Task.detached(priority: .userInitiated) {
              let started = LoopbackServer.shared.start()
              let caps = BridgeRouter.shared.availableMethods(hasHost: Prefs.shared.hasHost)
              await MainActor.run {
                  if let p = started {
                      env.loopPort = p
                      env.loopReady = true
                      LoopbackServer.shared.setCaps(caps)
                  } else {
                      env.loopReady = false
                  }
              }
          }
      }
- 补充：bindOnce 里的 1.5 秒超时对本地回环来说太长，顺手降到 0.3 秒也能立刻把最坏耗时压到 9 秒以内。

### 4.4 中等：连接没有 stateUpdateHandler，连不上时不会失败只会挂住

- 位置：Net/LoopbackServer.swift:108-111（accept 里只 start + receive，没有 conn.stateUpdateHandler；
  全仓 grep 该 API 为 0 命中）
- 后果：连接进入 .failed / .waiting（权限、端口异常）时 receive 回调不会来，连接既不回包也不关闭；
  页面侧是同步 XHR，表现是整页卡住直到 WebKit 超时。
- 改法：
      private func accept(_ conn: NWConnection) {
          conn.stateUpdateHandler = { st in
              switch st {
              case .failed, .cancelled, .waiting:
                  conn.cancel()
              default:
                  break
              }
          }
          conn.start(queue: queue)
          receive(conn, ConnState())
      }
- 相关：Net/LoopbackServer.swift:196-199 的 queue.async 里用 self?.sendText，若 self 已释放则连接永远不关，建议改成
  guard let self else { conn.cancel(); return }。

### 4.5 中等：同一个视图上挂了 3 个 .sheet

- 位置：UI/RootView.swift:34（item: $shell.panel）、UI/RootView.swift:37（isPresented: $env.showSettings）、
  UI/RootView.swift:40（isPresented: $env.showOnboarding）
- 问题：iOS 16 上同一个节点挂多个 sheet，同时为真或前后毫秒级切换时，后挂的会顶掉先挂的，表现为「点了链接没反应」
  或「弹出一个就再也弹不出第二个」。boot() 里 showOnboarding = true 与 shell.panel 是两条独立通道，确实可能撞。
- 改法：用一个 enum 驱动唯一 sheet：
      enum SheetRoute: Identifiable {
          case panel(PanelRequest), settings, onboarding
          var id: String { switch self { case .panel(let p): return p.id.uuidString; case .settings: return "s"; case .onboarding: return "o" } }
      }
      然后 body 末尾只保留 .sheet(item: $route) { r in switch r { ... } }

### 4.6 轻微：PanelView 的进度条可能永远停在半路

- 位置：UI/PanelView.swift:71-78 只实现了 didFinish 与 didFail，没有 didFailProvisionalNavigation。
- 后果：首个请求就失败（DNS 错、连接被拒）时走的是 provisional 失败，progress 永远 < 1，顶部那条线一直挂着。
- 改法：加一个同名委托方法，把 progress.wrappedValue 置 1（或直接把 if progress < 1 的条件改成 progress > 0 && progress < 1 也无济于事，建议补方法）。

### 4.7 轻微：powerGuide 的 jumped 恒为 true

- 位置：Bridge/LocalBridge.swift:37-41 依赖 UIHost.openAppSettings()（UIHost.swift:37-40），后者只是 dispatch 到主线程就 return true。
- 后果：用户其实没跳成功（比如 URL 打不开）时前端仍显示「已跳出」。要真值就得把 open 的 completion 接回来。

## 5. 契约与实现不一致（不崩，但前端会走错分支）

实测方法：把 index.html 里所有 Android.xxx 与 brCall([...]) / brCall1("...") 的方法名抓出来，和 BridgeRouter.specs 的键做差集。

1) 页面在用、路由表没有：
   - appVer（index.html:10464，页面已 try/catch 兜底，且注释里写了「桥上没有 Android.appVer()」，属于已知缺口）
   - backupState（走 brCall 字符串调用，会拿到 {"ok":false,"err":"未知的桥方法：backupState"}，前端只能当失败处理）
   - statsInfo（同上；但 StatsBridge.swift:12 已经处理 statsInfo，只是 BridgeRouter 没登记，白白丢掉一条能用的方法）
   - 建议：BridgeRouter.swift:86 附近补 ""statsInfo": L(.json)"、补 backupState 与 appVer 的登记（appVer 可直接用 Bundle.main 的 CFBundleShortVersionString 做个本地实现）。
2) notiState 字段名不一致：Bridge/LocalBridge.swift:123-127 返回 ok/granted/asked，
   ios/CONTRACT.md:67 要求 ok/on/enabled。页面目前没调 notiState，所以暂时无感；一旦接上，开关会读不到值。
3) 公告恒空：StatsBridge.swift:81-90 的 annFeed() 读 Prefs.annCache，但**全仓没有任何代码写 annCache**
   （grep 只有 Prefs 的 getter/setter 和这里的读取），而 CONTRACT.md:78 要求去 https://xn--15t839a.xyz/api/announce 拉取并 5 分钟节流。
   结果：ann / announcement / annRefresh / annRefreshStart 永远返回 {"ok":true,"items":[],"at":0}。
4) crashBoard 缺字段：StatsBridge.swift:31 少了 CONTRACT 要的 on（当前崩溃上报开关值）。
5) .json 方法返回空串的隐患：StatsBridge.swift:50-51 的 default 返回 ""。现在登记的 json 方法都已实现，
   但只要以后新增一个忘记实现，前端 JSON.parse("") 会抛 SyntaxError（页面并非每处都包了 try）。
   建议 default 里按 name 习惯返回 {"ok":false,"err":"..."}。

## 6. Info.plist 与工程配置（逐项核对过，基本齐全）

| 需求 | 现状 | 结论 |
| --- | --- | --- |
| WKWebView 载 http://127.0.0.1 | Info.plist:51-57 有 NSAppTransportSecurity.NSAllowsArbitraryLoads + NSAllowsLocalNetworking | 够用（上架要在审核说明里解释 NSAllowsArbitraryLoads） |
| canOpenURL(mqqapi://...) | Info.plist:27-33 LSApplicationQueriesSchemes = mqqapi, mqq, weixin, wx | 够用 |
| 连局域网宿主 | Info.plist:58-59 NSLocalNetworkUsageDescription | 够用 |
| PHPicker | Info.plist:60-61 NSPhotoLibraryUsageDescription（PHPicker 其实不需要，留着无害） | 够用 |
| 启动图 / 全屏 / 加密申报 | Info.plist:34-35、47-48、62-63 | 齐全 |
| 资源目录结构 | project.pbxproj 里 web 与 shared 都是 lastKnownFileType = folder，shared 用 SOURCE_ROOT 指向 ios/shared | 与 LoopbackServer.swift:22、ConsoleView.swift:126-127 的取路径方式一致 |

需要注意的两点（都不影响编译）：
- 没有 UIApplicationSceneManifest：SwiftUI App 生命周期不需要，正常。
- CFBundleDevelopmentRegion = zh_CN：标准写法是 zh-Hans，只影响本地化匹配。
- 工程 objectVersion = 56 / compatibilityVersion Xcode 14，必须 Xcode 14 及以上才打得开（也就意味着 if let self 这种 Swift 5.7 简写是安全的）。

## 7. AndroidShim.js 与 ConsoleView 内联 embeddedShim 是否等价

逐行比对 ios/shared/AndroidShim.js（53 行）与 UI/ConsoleView.swift:132-154 的 embeddedShim：

| 维度 | AndroidShim.js | 内联 embeddedShim | 是否等价 |
| --- | --- | --- | --- |
| 严格模式 | "use strict" | 无 | 等价（没有依赖 sloppy 模式的写法） |
| base 兜底 | C.base 为空时用 http://127.0.0.1:17870 | C.base 直接拼（默认 ""，即同源相对路径） | 不等价，但两者都能打到回环服务 |
| methods 兜底 | Array.isArray 检查 | 直接读 C.methods.length | 若 __ASTRIOS__ 缺 methods 字段，内联版会抛异常导致 IIFE 中断、window.Android 不存在 |
| 参数规整 | undefined 压成 "" | 原样（JSON 序列化后变 null） | 语义等价：服务端 BR.str→""、BR.int→0、BR.bool→false |
| 异常处理 | open / send 分开两段 try | 一段 try | 等价 |
| 返回规整 | typeof responseText === "string" | String(responseText || "") | 等价 |
| window.Android | 同 | 同 | 等价 |
| window.__ASTRIOS_BRIDGE__ | { base, methods, call } 对象 | call 函数本身 | 不等价 |

结论：功能上等价（页面只认 window.Android），目前没有可观察差异；index.html 里对 __ASTRIOS_BRIDGE__ 的引用是 0 次。
但两处差异是坑：调试脚本若按对象用法读 __ASTRIOS_BRIDGE__.base 会 undefined；内联版少了 methods 兜底。
建议把内联版对齐成文件版末尾两行：

      window.Android = A;
      window.__ASTRIOS_BRIDGE__ = { base: C.base || "", methods: (C.methods || []).slice(), call: call };

另外：注入用 atDocumentStart + forMainFrameOnly: true（ConsoleView.swift:49）是对的；index.html 首屏就会同步调
Android.state()，所以只要 shim 注入失败（例如 bundle 里漏了 shared/AndroidShim.js 且内联版也抛异常），
页面第一行 JSON.parse 就会挂。这也是第 9 节要上 Mac 亲自看一眼的原因。

## 8. 已逐条核查、确认没问题（不要按网上说法乱改）

1) PHPickerConfiguration 的初始化器：LocalBridge.swift:254-257、PhotoPicker.swift:12-15 用的都是
   PHPickerConfiguration(photoLibrary: .shared())，filter / selectionLimit / preferredAssetRepresentationMode 作为属性赋值，
   **这是唯一正确的写法**；不要改成 PHPickerConfiguration(filter:...)。
2) WKAudiovisualMediaTypes：ConsoleView.swift:17 的 cfg.mediaTypesRequiringUserActionForPlayback = [] 合法（OptionSet 的数组字面量）。
3) Network 框架：LoopbackServer.swift:66-87 里 NWParameters.tcp、requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port:)、
   allowLocalEndpointReuse、NWListener(using:)、newConnectionHandler、stateUpdateHandler(.ready/.failed/.cancelled) 签名全部正确。
4) RemoteClient 的同步等待不会死锁：RemoteClient.swift:48-61 的 URLSession 回调跑在自建串行队列，不依赖主线程；
   主线程上没有人在等这个信号量，所以 sem.wait 不会锁死界面。**不要把它改掉**。
   （代价是 slow 方法最多占住回环并发队列的一个线程 65 秒，属于设计取舍。）
5) @MainActor 类型的 static let shared 在后台首次访问不会崩：swift_once 保证线程安全，Swift 5 只在编译期做隔离检查；
   而且 AppEnv/Shell/ToastHub 的 shared 实际只在主线程或 nonisolated static 入口里被访问。
6) RootView.swift:14-29 的 if / else 两分支是不同视图类型，这在 ViewBuilder（body）里完全合法，**不需要 Group**。
7) ConsoleView.swift:132-154 的多行字符串缩进合法（结束三引号 4 空格，所有内容行 >= 4 空格）。
8) 跨文件符号：Prefs.hasHost 补齐后，Store.avatarFile/bgFile/dir/dataURL、BR.str/int/bool/js/ok/fail、KV.str/set/num/setNum/flag/setFlag/remove、
   UIHost.present/alert/openAppSettings/root/keyWindow、Theme 9 个常量、Shell.openPanel/shared、ToastHub.post/shared、
   LoopbackServer.shared.start/setCaps/port、BridgeRouter.shared.availableMethods/handle、RemoteClient.shared.call/ping、
   LocalBridge.invoke/ingestBg/imageURL、StatsBridge.invoke、WebInject.script，以及 env. 的 15 个成员，全部对得上。
9) onMainSync（BR.swift:55-58）先判 Thread.isMainThread，主线程调用不会 self-deadlock。
10) bundle 路径：web 与 shared 两个 folder reference 会原样拷进 App 包，Bundle.main.resourceURL + "/web" 与 "/shared/AndroidShim.js" 成立。

## 9. 无法在 Windows 上验证、必须上 Mac 才知道的三件事

1) Substring 上到底有没有 trimmingCharacters(in:)（决定这一版是 0 错还是 2 错）。
   Xcode 打开直接 Build，看 LoopbackServer.swift:154-155 有没有 "Value of type 'Substring' has no member"。
   这也顺手验证 Foundation overlay 在你们 SDK 上的挂载点。

2) NWParameters.requiredLocalEndpoint 在 listener 上到底是不是「把监听钉在 127.0.0.1」。
   Apple 文档只保证它作用于「连接」，listener 上属于未明说行为。要在真机上看两件事：
   模拟器/真机里 App 起来后 lsof / 从同一 Wi-Fi 的另一台机器 curl http://<手机IP>:17870/__caps 能不能通。
   如果能通，说明它没被钉住，控制台就暴露在局域网了（这是个安全问题，不是编译问题）。

3) WKWebView 里边注入 + 同步 XHR 的时序是否真能跑通。
   具体要看：页面首次 Android.state() 调用时 window.Android 是否已就绪（atDocumentStart 在主文档脚本前的时机）、
   同步 XHR 在 WebKit 里是否被允许打到 127.0.0.1、以及首次弹权限/首帧时 alert 是否正常返回。
   这三点只能真机 + Safari Web Inspector 看，Windows 上无法模拟。
   （另外：这一版在你们 Xcode 上跑完后，把 3.3 那几条 @MainActor 诊断的实际等级记一下，
   如果全是 warning，就说明当下还能用 Swift 5 混过去；一旦切 Swift 6 就要按 3.3 改。）

---

附：本次审查没有修改任何 .swift、ios/tools、ios/PROJECT.md；本文件之外没有新增文件。
