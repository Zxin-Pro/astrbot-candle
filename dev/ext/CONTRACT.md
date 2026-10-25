# CE 扩展模块开发契约（1.3.3-beta.4）

> 给并行开发的子代理看的唯一规范。**读完这一份就够了**，不要再通读 1.1 万行的 index.html。

## 0. 你在做什么

`Zxin-Pro/astrbot-candle` 是一个「AstrBot + NapCat 一键跑在安卓手机上」的 App。
它的控制台界面是**一个单文件** `ui/assets/qqbot/index.html`（约 1.1 万行，529 KB）。

本次版本要**新增一批实用功能**。为了能多人并行，所有新功能都做成**独立模块**，
挂在一个新写的扩展内核 `CE` 上；每个模块一个 `.js` 文件，**不要修改 index.html**。

## 1. 文件与目录

```
/var/minis/workspace/astrbot-candle/
├── dev/ext/00-core.js        ← 扩展内核（已写好，只读，不要改）
├── dev/ext/NN-<id>.js        ← 你要写的模块（NN 是分配给你的序号）
├── dev/ext/CONTRACT.md       ← 本文件
├── ui/assets/qqbot/index.html ← 主界面（不要改）
└── build/opt/qqbot/extras.sh  ← 宿主侧数据源脚本（已写好，提供 sysinfo/ports/procs/netdiag/env/clean）
```

**你的输出只有一个文件**：`dev/ext/NN-<id>.js`。
不要改别的任何文件，不要 git 提交，不要碰 index.html。

## 2. 模块契约

你的文件是一个 **IIFE**，调用 `CE.reg({...})` 注册自己：

```js
(function () {
  "use strict";
  CE.reg({
    id: "sysmon",              // 全局唯一，和文件名里的 id 一致
    tab: "home",               // home | bots | logs | more | settings
    order: 30,                 // 同 tab 内的插入顺序，小的在前；已占用见 §7
    title: "资源监控",

    // 返回这张卡的 HTML 字符串（必须是一个或多个 .card 元素）。
    // 返回 "" 表示这次不显示（不会报错）。
    html: function () {
      return '<div class="card"><h2>资源监控</h2><div id="ceSysBody">…</div></div>';
    },

    // 首次插入 DOM 后调用一次（可选）
    mount: function (root) { … },

    // 每次切到本页 + 每 8 秒调用一次（页面不可见时不会调）。用来刷数据。
    tick: function (root) { … },

    // 点击处理：任何带 data-ce-act 的元素被点时调用（可选）
    on: function (act, arg, root, el) { … }
  });
})();
```

### 事件写法

不要写 `onclick`，也不要自己 addEventListener。用属性 + `on()`：

```html
<button class="small" data-ce-act="refresh">刷新</button>
<button class="small primary" data-ce-act="clean" data-ce-arg="cache">清理缓存</button>
```

```js
on: function (act, arg, root, el) {
  if (act === "refresh") doRefresh(root);
  if (act === "clean") doClean(arg, root, el);
}
```

### 取节点

**只在 root 里查**，不要用 document.getElementById（模块可能被重建）：

```js
var box = root.querySelector("#ceSysBody");
```

## 3. CE 提供的能力

| 接口 | 说明 |
| --- | --- |
| `CE.has("方法名")` | 桥里有没有这个方法（真机老版本可能没有） |
| `CE.call("方法名", 参数…)` | 同步调桥，异常时返回 `""` |
| `CE.exec(cmd, cb)` | 执行宿主 shell（`$PREFIX` 可用，cwd=HOME，同步 60s 超时）。**异步包装**：先让出一帧再执行，`cb(stdoutString, ok)` |
| `CE.tool("sysinfo")` | 返回 `bash "$PREFIX/opt/qqbot/extras.sh" sysinfo 2>/dev/null` 这条命令串 |
| `CE.toolJson("sysinfo", cb)` | 跑 extras.sh 子命令并把最后一行 JSON 解析成对象回调（失败给 `null`） |
| `CE.toast(msg)` | 轻提示 |
| `CE.copy(text)` | 复制到剪贴板（同步返回 bool） |
| `CE.esc(s)` | HTML 转义，**所有插进 innerHTML 的动态文本必须过一遍** |
| `CE.kb(n)` | KB → "12.3 MB" |
| `CE.dur(sec)` | 秒 → "3 小时 12 分" |
| `CE.clock(ts)` | 时间戳（秒或毫秒）→ "14:05" |
| `CE.pct(a,b)` | 百分比整数（0-100，已 clamp） |
| `CE.json(raw, def)` | 安全 JSON.parse，失败给 def |
| `CE.ls(k)` / `CE.ls(k,v)` | 读写扩展自己的 localStorage（自动加 `ce_` 前缀） |

**重要：`CE.exec` 是同步阻塞的。** 不能在一次 tick 里连跑 5 条命令，也不能跑超过 3 秒的命令
（部署、apt、pip 一律不要碰）。慢操作拆成"按钮触发 + 结果写进面板"。

## 4. 可用的原生桥（window.Android）

全部只在 `CE.has(x)` 为真时才能调。**返回值都是字符串**，JSON 的要 `CE.json()` 解析。

| 方法 | 返回 |
| --- | --- |
| `state()` | `{ok, deployDone, supervisorAlive, astrbotPort, astrbotPortAuto, astrbotUp, napcatOff, astrbotOff, lanIp, appVersion, bots:[{id,label,uin,napcatPort,onebotPort,up,active,…}], active, doneCount, total, steps:{…}, guardOn, guardWake, guardScreen, notiGranted, logFiles:[…], rc, …}` |
| `todayStats()` | `{ok, day, onlineSec, msgs, errors, rxBytes, txBytes, warm}` |
| `instancesInfo()` | `{ok, instances:{ "<id>":{started, lastOffline, uin, nick, alive} }}` |
| `storageStats()` | `{ok, warm, total, dirs:[{key,name,path,bytes}], top:[{path,bytes}]}` |
| `netInfo()` | `{ok, online, type, lastSwitch, recoveries}` |
| `crashBoard()` | 崩溃看板 JSON（结构自行 `CE.json` 探测，字段缺失要容错） |
| `riskInfo()` | `{ok, risk:bool, …}` |
| `powerState()` | `{ok, manufacturer, hint, shown, …}` |
| `readLog(name, lines)` | 日志文本（name 见 `state().logFiles`） |
| `exec(cmd)` | 同 `CE.exec` 的底层（别直接用，用 `CE.exec`） |
| `toast(msg)` / `copy(text)` | 提示 / 剪贴板 |
| `openUrl(u)` / `openPanel(u)` / `openTerminal()` | 打开外链 / 内嵌网页面板 / 终端 |
| `uploadLog()` | 上传错误日志（慢，55s） |
| `doctorStart()` / `doctorText()` | 异步自检：start 后轮询 text |
| `backupInfo()` / `backupStart()` / `backupPoll()` | 备份 |
| `astrInfo()` | AstrBot 模型与人格配置 JSON |
| `svc("napcat"|"astrbot", "on"|"off")` | 组件开关（前端通过 `Android.action()` 发，见下） |
| `action(json)` | **主控动作**：`{"act":"…","arg":"…"}`，前端所有 data-act 最终都走它 |

`action` 常用 act（可在 index.html 里搜 `data-act="` 确认）：`restartnow`、`stopnow`、
`doctor`、`uploadlog`、`stgclean`、`reinstall`、`settings`、`showlogs`、`backhome`、`openpanel`。

## 5. extras.sh 子命令（宿主侧数据源，已实现）

| 命令 | 返回 JSON |
| --- | --- |
| `sysinfo` | `{ok, cores, load1, load5, load15, procs, memTotal, memFree, memAvail, diskTotal, diskUsed, diskFree, ts}`（内存/磁盘单位 **KB**） |
| `ports` | `{ok, astrbotPort, napcatPort, onebotPort, listening, list:[{port, who, open}]}` |
| `procs` | `{ok, list:[{name, rssKB}]}`（沙盒相关进程内存 TOP6） |
| `netdiag` | `{ok, items:[{name, ok, detail}], ts}` |
| `env` | `{ok, appVersion, arch, rootfs, napcat, astrbot, uptime}` |
| `clean <logs\|cache\|pycache\|apt\|all>` | `{ok, target, freedKB, beforeKB, afterKB}` |

**沙盒里没有 jq**，脚本已经手拼好 JSON，你只负责展示。

## 6. 样式约定（必须遵守，否则深浅色会崩）

主样式表已经定义了整套 CSS 变量，**只准用变量，不准写字面颜色**：

- 卡片：`<div class="card">`，标题 `<h2>`，说明 `<div class="hint">`
- 按钮：`<button class="small">` / `<button class="small primary">`；成组用 `<div class="btns">`
- 状态点：`<span class="dot ok|bad|warn|mute">`
- 输入：`<div class="field"><label>…</label><input …></div>`
- 分段控件：`<div class="seg"><button aria-pressed="true">…</button></div>`
- 空态：`<div class="empty">…</div>`
- 列表行：`<div class="lkrow">…</div>`（图标+标题+按钮）
- 扩展专用类（内核已注入 CSS）：
  `.ce-grid`（两列格子）、`.ce-cell`（格子，内含 `b` 值 + `span` 标签）、
  `.ce-row`（一行，`.ce-rt` 文字区 + `.ce-rv` 数值）、`.ce-dot ok|bad|warn`、
  `.ce-bar > i`（进度条，`i` 可加 `.warn/.bad`）、`.ce-tags > .ce-tag`、
  `.ce-kv`（键值行，`<b>` 是值）、`.ce-pre`（等宽结果面板）、`.ce-hint`、
  `.ce-empty`、`.ce-load`（`<i></i> 加载中`）、`.ce-svg`（图表）
- 颜色强调：`.ce-ok` `.ce-bad` `.ce-warn`

**禁止**：外部字体/图片/CDN 引用、`position:fixed` 大遮罩、修改 `body`/`html` 全局样式、
`document.write`、`eval`、`new Function`。

## 7. 模块分配（order 已定，不要重复占位）

| 文件 | id | tab | order |
| --- | --- | --- | --- |
| 10-sysmon.js | sysmon | home | 30 |
| 11-linkmap.js | linkmap | home | 32 |
| 12-trend.js | trend | home | 34 |
| 13-checklist.js | checklist | home | 36 |
| 14-brief.js | brief | home | 38 |
| 15-netdiag.js | netdiag | more | 20 |
| 16-portmap.js | portmap | more | 22 |
| 17-cmdbox.js | cmdbox | more | 24 |
| 18-cleanup.js | cleanup | more | 26 |
| 19-logtools.js | logtools | logs | 10 |
| 20-instpro.js | instpro | bots | 10 |
| 21-uiscale.js | uiscale | settings | 10 |
| 22-search.js | search | settings | 12 |

## 8. 代码规范

1. **ES5 only**：`var`、`function`，不用箭头函数 / 模板字符串 / `let`/`const` / `??` / 可选链。
   应用内 WebView 可能是 Android 7 的旧内核。
2. 整个文件包在 `(function(){ "use strict"; … })();` 里，不留全局变量。
3. 中文注释，解释「为什么这么做」，不要写「// 设置变量」这种废话。
4. 所有动态文本进 innerHTML 前必须 `CE.esc()`。
5. 任何可能失败的调用都要 try/catch；拿不到数据就渲染空态，不要抛异常（会打断内核 tick）。
6. 不做网络请求（`fetch`/`XHR` 一律禁止），需要联网信息让 `extras.sh` 去取。
7. 单个模块的文件不超过 **450 行**，超过就说明该拆了。

## 9. 自检

写完后必须跑：

```sh
node --check /var/minis/workspace/astrbot-candle/dev/ext/NN-<id>.js
```

`node` 在沙箱里可用（`/usr/local/bin/node` 或 `node`）。语法不过就是不合格。

再自查一遍：
- [ ] 文件里没有 `document.getElementById`
- [ ] 没有 `innerHTML =` 拼接未转义的变量
- [ ] 没有修改 `document.body`/`document.documentElement`
- [ ] `html()` 返回的字符串里至少有一个 `.card`
- [ ] 所有 `CE.has(...)` 为假时都不会崩

## 10. 交付

最后回一段 3-6 行的中文说明：模块做了什么、用了哪些桥/extras 子命令、
有没有降级分支。不要贴大段代码。
