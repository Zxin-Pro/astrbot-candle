# iOS 版跨层契约（v0.1 草案）

本文件是 **E:\DSH\qqbot-sandbox 的 iOS 端** 与「宿主侧远端代理」之间的协议，照此写，不许各自发明字段。
上游契约见 `docs/接口约定-1.1.30.md`（Android 版，JSON 形状全部沿用，字段名/类型一律不改）。

## 0. 一句话架构

iOS 不能跑 proot 沙盒，所以 iOS 版是**远程控制台**：

```
 WKWebView (index.html 原样，零改动)
        │  同步调用 window.Android.xxx(...)   ← 契约要求同步返回字符串
        ▼
 内嵌回环 HTTP 服务  http://127.0.0.1:<port>   （同源，页面也从这里取）
        │  POST /__bridge  {"m":"state","a":[]}
        ├── 本地设备类 → Swift 原生实现（toast / 相册 / 分享 / 通知 …）
        └── 宿主状态类 → 转发 远端代理  POST http://<host>:<rport>/api/qqbot/bridge
```

为什么必须走回环 HTTP 而不是 `WKScriptMessageHandler`：`index.html` 全部是
`var r = JSON.parse(Android.state())` 这种**同步取值**写法，`postMessage` 是异步的，一改就是 400+ 处调用点。
同源回环 HTTP + 同步 XHR 让前端一个字节都不用改，这也正是本项目最重要的资产（404 KB 单文件控制台）。

## 1. 回环服务（iOS App 内）

- 监听 `127.0.0.1`，首选端口 `17870`，占用则 `17871..17899` 顺延；**只绑回环**，不对局域网暴露。
- `GET /`、`GET /index.html` → `Resources/web/index.html`
- `GET /<其他>` → `Resources/web/<其他>`（`appicon.png` 等），路径穿越一律 400。
- `GET /__caps` → `{"methods":["state","action",...]}`（**本机真正可用的方法名清单**）
- `POST /__bridge` → 见第 2 节
- 响应头：`Content-Type: text/plain; charset=utf-8`（`/__bridge`）、`Cache-Control: no-store`、
  `Connection: close`（一次性连接，不做 keep-alive）。
- 请求体用 `text/plain` 是为了**不触发 CORS 预检**，保持「简单请求」。

## 2. `POST /__bridge` 线格式

请求体（UTF-8 JSON）：

```json
{"m":"action","a":["svc:napcat:on"]}
```

响应体：**就是前端拿到的那一个字符串**，不做任何包装。三种形态与 Android 完全一致：

1. JSON 字符串：`{"ok":true,...}` / `{"ok":false,"err":"中文原因"}`
2. 纯文本：`action` / `exec` / `readLog` / `saveBots` / `proxyGet` 这类，前端直接当消息显示
3. 空串：`void` 方法（toast/copy/pickBg/…）

**前端约定（照抄 index.html 的用法，不许变）**：返回空串 / null / `JSON.parse` 失败 一律按失败处理。

**JSON 类失败必须带 `error` 字段**（而不是只带 `err`）：`index.html` 刷新状态时先 `st = JSON.parse(Android.state())`，
解析**成功**就不会走「读取状态失败」分支，只会读 `st.error`。只回 `{"ok":false,"err":…}` 的话，
页面会拿一份缺字段的对象硬渲染下去（出现 `http://undefined:undefined` 这类地址）。
所以没连宿主 / 连不上宿主时统一回 `{"ok":false,"err":"<原因>","error":"<原因>"}`。

## 3. 方法分派表（90 个方法，三类）

`L` = 本地原生实现；`R` = 转发远端代理（无宿主时返回 `{"ok":false,"err":"还没有连接宿主：去「设置」填地址"}`）；
`X` = iOS 上无意义，直接返回 `{"ok":false,"err":"iOS 版不支持 <原因>"}`；`V` = void 方法（本地，返回空串）。

| 方法 | 返回 | 类 | iOS 做法 |
| --- | --- | --- | --- |
| toast(msg) | void | L | 应用内 HUD（不阻塞） |
| copy(text) | void | L | UIPasteboard + HUD「已复制」 |
| openUrl(url) | text | L | 只用 http/https，成功返回 `ok`，否则返回中文原因；SFSafariViewController |
| openQqGroup(group) | text | L | `mqqapi://card/show_pslcard?src_type=internal&version=1&uin=<g>&card_type=group&source=qrcode`；装了 QQ 才 ok |
| openPanel(url) | void | L | 内嵌 WKWebView 弹层（AstrBot/NapCat WebUI），带关闭按钮 |
| openTerminal() | void | X | iOS 没有可用的本地终端 → HUD 提示 |
| appVer() | text | — | **不登记**：Java 侧也没有这个方法，页面只在 `try{}` 兜底里调它。版本号走 `state.appVersion`（由本机补，见第 4 节） |
| disclaimerSeen() / disclaimerAck() | text / json | L | UserDefaults `qqbot.ios.disclaimer` |
| notiAsk() | void | L | `UNUserNotificationCenter.requestAuthorization` |
| notiState() | json | L | `{"ok":true,"granted":<已授权>,"asked":<问过没>,"on":<同上>,"enabled":<同上>}`（页面其实不调它，只读 state 里的 `notiGranted`，见第 4 节） |
| powerInfo() / powerState() / powerGuide() / powerAck() | json | L | 报 `ProcessInfo.isLowPowerModeEnabled`；guide 打开系统设置 `UIApplication.openSettingsURLString`；ack 记 UserDefaults |
| pickBg() | void | L | PHPickerViewController → 缩到长边 1600 / JPEG 0.82 → `Library/bg.jpg` |
| bgImage() | json | L | `{"ok":true,"data":"data:image/jpeg;base64,..."}`；无图 `{"ok":false,"err":"没有自定义背景"}` |
| clearBg() | json | L | 删 `bg.jpg` → `{"ok":true}` |
| avatarInfo() | json | L | 从远端 `avatarPath`/`state` 拿 uin，再自己下 `https://q1.qlogo.cn/g?b=qq&nk=<uin>&s=100`，返回 `{"ok":true,"uin":"…","data":"data:image/png;base64,…"}`（带 50 条 LRU 磁盘缓存）；拿不到 uin 返回 `{"ok":false,"err":"还没有登录账号"}` |
| avatarPath(uin) | json | L | 同上，返回 `{"ok":true,"path":"file://…","cached":true}` 供 `<img>` 用 |
| avatarIconPin() | json | X | iOS 不能换应用图标（除预置 icon 集）→ 返回 `{"ok":false,"err":"iOS 版不支持更换应用图标"}` |
| statsOn/statsSet/statsId/statsState/statsReset/statsEvents | json | L | 只落 UserDefaults：安装 ID（UUID，首次开启才生成）、开关、最后上报日；**关着不发任何请求** |
| crashReportOn/crashUploadSet/crashReportState/crashUploadNow | json | L | 同上开关；iOS 版不上传崩溃（无 crash-agg），`crashUploadNow` 返回 `{"ok":true,"queued":false}` |
| crashInfo / crashBoard / crashClear | json | L | 永空：`{"ok":true,"on":<开关>,"classes":[],"items":[],"total":0,"kinds":0}` |
| ann / announcement / annRefresh / annRefreshStart | json | L | GET `https://xn--15t839a.xyz/api/announce?ver=<ver>`（头 `X-Qqbot-Atom: 1`），缓存到 UserDefaults，5 分钟节流，读缓存立即返回 |
| state() | json | R | 宿主状态（在线/端口/版本…），见第 4 节 |
| instancesInfo / bootInfo / bundleInfo / deployInfo / reinstallInfo | json | R | |
| envVers / astrInfo / netInfo / todayStats / riskInfo / riskClear | json | R | |
| action(what) | text | R | `svc:napcat:on` / `svc:astrbot:off` / `guard:wake:on` / `linkbg` … 返回一句中文消息 |
| exec(cmd) | text | R | 阻塞，宿主执行命令后回 stdout+stderr |
| readLog(name,lines) / clearLog(name) / rmLog(name) / uploadLog() | text/json | R | |
| saveBots(json) / loadBots() / toggleBot(id) / removeBot(id) / qqDetect() | text/json | R | |
| qr(id) / qrShare(id) | json | R | 宿主侧读二维码文件 → `{"ok":true,"data":"data:image/png;base64,…","mtime":…,"until":…}` |
| astrSave / astrSavePoll / astrModelsStart / astrModelsPoll | json | R | |
| proxyGet / proxySet(v) | text | R | |
| checkUpdateStart / checkUpdatePoll / siteVerStart / siteVerPoll | json | R | |
| backupStart / backupStartScope / backupPoll / backupInfo / backupPick | json | R | |
| restorePick / restorePickScope / restoreApply / restorePoll | json | R | |
| storageStats / storageInfo / storageRefresh / storageRefreshStart / storageClean | json | R | |
| doctorStart / doctorText | void/text | R | |

**`brHas(name)` 语义**：`index.html` 用 `typeof Android.<name> === "function"` 判断能力。
`window.Android` 只挂 `/__caps` 里列出的方法，所以「宿主没配」时远程方法**不挂**，
前端会走「这个安装包还不支持…」的分支，而不是弹一堆报错——这是有意的降级。
但 `state`、`todayStats`、`riskInfo` 这类**始终挂**（返回 ok:false 更好，界面能显示「未连接」）。

## 4. 远端代理协议（宿主侧必须实现）

`POST http://<host>:<rport>/api/qqbot/bridge`

- 头：`Content-Type: application/json; charset=utf-8`、`X-AstrBot-Key: <token>`（未配置 token 时可不带，代理侧可不校验）
- 体：`{"m":"state","a":[...]}`
- 响应：utf-8 JSON 文本，**就是返回给前端的那个字符串**；状态码恒 200（错误也走 `{"ok":false,"err":…}`）
- `GET /api/qqbot/caps` → `{"ok":true,"version":"0.1.0","methods":[...],"host":"linux|windows|android"}`
- `GET /api/qqbot/ping` → `{"ok":true,"ts":<epoch秒>,"name":"<宿主名>"}`
- 客户端超时：读类 2000 ms、`exec`/`action` 65000 ms；**超时按失败返回，不许卡住 JS 线程**

必须实现的**最小集**（其余可返回 `{"ok":false,"err":"宿主未提供 <方法>"}`）：

`state` `todayStats` `netInfo` `envVers` `astrInfo` `instancesInfo` `riskInfo` `riskClear`
`action` `exec` `readLog` `clearLog` `rmLog` `loadBots` `saveBots` `bootInfo` `deployInfo` `bundleInfo`

`state()` 的最小字段（Android 版是超集，前端缺字段有兜底）：

```json
{"ok":true,"appVersion":"0.1.0","hostName":"vps-01","os":"linux","online":true,
 "napcatOn":true,"astrbotOn":true,"guardOn":true,"deployed":true,
 "napcatPort":6099,"astrPort":6185,"astrPortOn":true,
 "botCount":1,"activeBot":"bot1","uin":"10001","nick":"","uptimeSec":7320,
 "diskFree":1, "ts":1760900000}
```

字段只增不改；前端拿不到的字段可以省略，不要塞 `null` 字符串。

**本机补进去的两个字段（宿主不用管）**：iOS 端拿到 `state` 之后会补上 / 覆盖 `appVersion` 与 `notiGranted`。

- `appVersion`：宿主回空（Linux/Windows 上没有 APK 版本号）时填本 App 的 `CFBundleShortVersionString`，
  页面页脚「v-x.y.z」就靠它；宿主自己给了版本号则以宿主为准。
- `notiGranted`：iOS 本机的通知授权（`UNUserNotificationCenter.authorizationStatus` 的缓存）。
  页面的通知横幅只认这个字段：`undefined` 不显示、`false` 显示「开启通知权限」、`true` 收起。
  所以这里每次都要写真实值，否则用户授权完横幅还在。

## 5. 版本与兼容

- iOS 版 `VERSION` 从 `0.1.0` 起，与 Android 的 `1.3.0` **不同轨**（是控制台，不是同一个包）。
- 上游契约 `docs/接口约定-1.1.30.md` 是唯一真源；本文件只是 iOS 侧的分派与线格式补充。
