# AstrBot iOS 版 · 宿主侧远端代理

iOS 跑不了 proot 沙盒，所以 iOS 版控制台是「远程控制台」：App 里用 WKWebView 载入原样的
`ui/assets/qqbot/index.html`，那些需要宿主真实状态的方法由 App 转发到这里，由真正的机器
（VPS / HomeLab / 开发机）执行。

- 协议真源：`ios/CONTRACT.md` 第 4 节
- 字段形状：`docs/接口约定-1.1.30.md`（Android 版），**只增不改**，前端可以直接复用
- 实现：单文件 `astrbot_bridge_agent.py`，Python 3.8+，**只用标准库**，不装任何第三方包
- 当前版本：`0.1.0`（iOS 与 Android 1.3.0 不同轨）

## 跑起来

```bash
# 最小用法：只监听 127.0.0.1:17880，宿主布局根自动探测
python ios/remote-agent/astrbot_bridge_agent.py

# 推荐：指定布局根 + 令牌（App 的「远端代理」里填同一个令牌）
python ios/remote-agent/astrbot_bridge_agent.py --root /root/.qqbot --token <长随机串>

# 自测：自己起一个临时端口把主要接口打一遍，全部通过打印 SELFTEST OK
python ios/remote-agent/astrbot_bridge_agent.py --selftest
```

起来以后先用浏览器或 curl 确认：

```bash
curl -s http://127.0.0.1:17880/api/qqbot/ping
# {"ok":true,"ts":1760900000,"name":"vps-01","version":"0.1.0"}

curl -s -X POST http://127.0.0.1:17880/api/qqbot/bridge \
  -H "Content-Type: application/json; charset=utf-8" \
  -H "X-AstrBot-Key: <长随机串>" \
  -d '{"m":"state","a":[]}'
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/qqbot/ping` | `{"ok":true,"ts":<epoch秒>,"name":"<宿主名>","version":"0.1.0"}` |
| GET | `/api/qqbot/caps` | `{"ok":true,"version":"0.1.0","host":"linux\|windows\|macos","methods":[...]}` |
| POST | `/api/qqbot/bridge` | 请求体 `{"m":"state","a":[...]}`，响应体就是给前端的那一个字符串 |

细节：

- `bridge` 的状态码**恒为 200**，成败看响应体里的 `ok`（或纯文本里的中文提示）。
- 其它路径一律 404，响应体也是 JSON。
- 请求体上限 1 MB，超了回 `{"ok":false,"err":"请求体太大（上限 1 MB）"}`。
- 所有响应都带 `Content-Type: application/json; charset=utf-8` 和 `Cache-Control: no-store`。
- 配了令牌就校验 `X-AstrBot-Key`，缺/错回 `{"ok":false,"err":"token 不对"}`（比较用 `compare_digest`）。
- 返回纯文本的方法：`action`、`exec`、`readLog`、`clearLog`、`rmLog`、`saveBots`，其余都返回 JSON。

## 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--port` | `17880` | 监听端口 |
| `--bind` | `127.0.0.1` | 监听地址；非回环地址必须显式加 `--lan` |
| `--lan` | 关 | 允许绑定 `0.0.0.0`。开了就是整个网段都能打进来，见「安全说明」 |
| `--token` | 空 | 桥令牌；给了就校验 `X-AstrBot-Key` |
| `--root` | 自动探测 | 宿主上 AstrBot/Termux 布局的根（下面有目录表） |
| `--config` | `agent.json` | 配置文件；默认看脚本同目录，其次当前目录；找不到就全用默认值 |
| `--selftest` | — | 起临时服务自测，成功后打印 `SELFTEST OK`、退出码 0 |

自动探测 `--root` 的顺序：`$QQBOT_HOME` → `~/.qqbot` → `/opt/qqbot` → `/root/.qqbot` →
`%LOCALAPPDATA%\qqbot` → 当前目录下的 `.qqbot`，取第一个「有 `state/` 或 `logs/` 或 `deploy.done`」的；
都没命中就用 `~/.qqbot`。

命令行参数覆盖配置文件里的同名项。

## 配置文件 `agent.json`

照抄 `agent.example.json` 改即可。所有键都能省，省略或给空串 = 用默认值/自动探测。

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 机器名 | 上报给 App 的宿主名（`ping.name`、`state.hostName`） |
| `root` | string | 自动探测 | 布局根，同 `--root` |
| `token` | string | 空 | 桥令牌，同 `--token` |
| `napcatPort` | int | `6099` | NapCat 端口；用来判断 NapCat 在不在线 |
| `astrPort` | int | `6185` | AstrBot 控制台端口（`state/bots.json` 里 `astrbotPort>0` 时以它为准） |
| `logDir` | string | `<root>/logs` | 日志目录，`readLog/clearLog/rmLog` 只认这里面的 `*.log` |
| `stateDir` | string | `<root>/state` | 状态目录（`bots.json`、`boot.json`、`risk.json`、各种 `.on` 标记） |
| `netType` | string | 自动 | 网络类型文案；留空时 Linux 看 `/sys/class/net/*/wireless`，其余给 `wired` |
| `astrbotDir` | string | 自动 | AstrBot 源码目录（找 `__init__.py` 的 `__version__`、`data/cmd_config.json`） |
| `commands` | object | `{}` | 动作名 → 宿主上真正要执行的 shell 命令，见下 |

### `commands`

`action` 只负责「翻译 + 提交」，命令本身必须由你配。键按下面的顺序匹配，命中就用：

1. 完整动作名，例如 `"svc:napcat:on"`
2. `"<前缀>:<名字>"`，例如 `"svc:napcat"`、`"guard:napcat"`
3. `"<前缀>:<on|off>"`，例如 `"svc:on"`、`"guard:off"`
4. `"<前缀>"`，例如 `"svc"`

都没配，回一句纯文本中文：`失败：宿主没有配置 svc:napcat:on 对应的命令（在 agent.json 的 commands 里补一条）`。
前端靠 `indexOf("失败")` 判断失败（`index.html` 的 svc 开关），所以失败文案里一定带「失败」两字。

命令是**异步提交**的：`action` 立刻回 `已提交 svc:napcat:on`，真正执行放到后台子进程，
命令输出追加到 `<logDir>/agent-action.log`。前端靠 `indexOf("已提交")` 判断成功
（`index.html` 的 linkbg）。所以别在命令里写需要交互的东西。

## 宿主布局（`--root` 指向的那一层）

```
<root>/
  deploy.done            # 部署完成的标记
  deploy.pid             # 正在跑部署时写进 PID
  deploy.error           # 部署失败的报错（deployInfo.err 取末 6 行）
  steps/10-termux … 70-launcher   # 部署步骤标记，存在即该步完成
  state/bots.json        # {"active":"bot1","bots":[{...}]}
  state/boot.json        # {"lastBoot":…,"lastStart":…,"attempts":…,"lastErr":"…"}
  state/instances.json   # {"bot1":{"started":…,"lastOffline":…,"uin":"…","nick":"…"}}
  state/risk.json        # 命中风控时写 {"instance","word","line","since","file"}
  state/today.json       # {"day":"2026-10-19","onlineSec","msgs","errors","rxBytes","txBytes"}
  state/guard.on         # 守护开着的标记（guardWake 看 state/wake.on）
  logs/*.log             # 日志（readLog 的默认目录，deploy.log 给 deployInfo 用）
  cache/bundle.tar.gz    # 离线包；没有就是瘦包（bundleInfo 回 slim:true）
```

这些文件**都不需要预先存在**：读不到就给合法空值（`0`、`[]`、`false`、`warm:false`），
不返回 error，免得前端整块消失。只有 `action` / `exec` 这类真的要动宿主的方法才会说失败。

## 已实现的方法

`state`、`todayStats`、`netInfo`、`envVers`、`astrInfo`、`instancesInfo`、`riskInfo`、
`riskClear`、`action`、`exec`、`readLog`、`clearLog`、`rmLog`、`loadBots`、`saveBots`、
`bootInfo`、`deployInfo`、`bundleInfo`。

其余方法名（`toggleBot`、`qr`、`storage` 之类）一律回
`{"ok":false,"err":"宿主未提供 <方法名>"}`，状态码仍然是 200，不会抛异常也不会 500。

几个和其它平台对齐的细节：

- `state` 字段一次给全：契约第 4 节的 `appVersion/hostName/os/online/napcatOn/astrbotOn/guardOn/
  deployed/napcatPort/astrPort/astrPortOn/botCount/activeBot/uin/nick/uptimeSec/diskFree/ts`，
  外加 Android 版同义的 `napcatUp/astrbotUp/napcatOff/astrbotOff/astrbotPort/guardWake/logFiles/root`。
  `logFiles` 的 `mtime` 是 epoch 秒，`diskFree` 是 GB 整数（向下取整）。
- `online` = NapCat 端口可连 **或** AstrBot 端口可连；探端口 socket 超时 200 ms。
- `uptimeSec` 是**本代理自己**的运行时长（不是宿主开机时长），符合契约里的「代理启动时间」口径。
- `appVersion` 读宿主 AstrBot 的 `astrbot/__init__.py` 里的 `__version__`，找不到给空串，5 分钟缓存。
- `readLog` 只认 `<logDir>` 下的 `*.log`：带 `/`、`\`、`..` 的一律回空串，软链接绕出去也拦。
  行数默认 400 行、上限 5000 行，最多读尾部 512 KB。
- `clearLog` 是截断（文件留着给正在写的进程继续用），`rmLog` 是真删，文案分别是
  `已清空 qq-bot1.log（1234 字节）`、`已删除 qq-bot1.log（1234 字节）`。
- `saveBots` 先校验 JSON：不是合法对象、或没有 `bots` 数组，回 `格式不对，没有写盘（保存失败）`，
  一个字节都不写。写盘走「临时文件 + `os.replace`」，不会写一半。
- `exec` 用 `shell=True`、超时 60 秒，返回 stdout+stderr 拼接的纯文本，没输出就回空串。

## 线程安全

`ThreadingHTTPServer`，每个请求一个线程：

- `state()` 结果缓存 2 秒，端口探测结果同样缓存 2 秒 —— 控制台轮询不会每次都去 connect 一遍。
- AstrBot 版本号缓存 5 分钟。
- 执行过 `action` / `saveBots` 会主动让这些缓存失效，下一次刷新立刻重新探测。
- 缓存都在锁里换引用；`exec`、`readLog` 这些不做共享状态。

## 安全说明

- **默认只监听 `127.0.0.1`**。App 通过 SSH 隧道 / 反代连过来时这样最安全。
- 要绑 `0.0.0.0` 必须显式 `--lan`，否则直接拒绝启动（退出码 2）。绑了非回环又没配 token，
  启动时会打印一条警告 —— 那等于把宿主的 shell 敞开给整个网段。
- `--token` 的常量时间比较（`secrets.compare_digest`）。令牌走明文 HTTP，跨公网请套 TLS 或 SSH 隧道。
- `exec` 和 `action` 能在宿主上执行你配置的命令，**能力等于 SSH 登录**。别把它暴露到公网，
  也不要把 token 写进仓库（`agent.json` 已经在 `ios/` 树的本地使用范围，提交前确认）。
- `readLog` / `clearLog` / `rmLog` 只碰 `<logDir>` 下的 `*.log`，路径穿越和软链接逃逸都拒绝。
- 请求体上限 1 MB，避免一个烂请求把内存吃干净。

## Windows 上怎么试

仓库在 Windows 上开发，代理本身跨平台，可以直接在本机验证（不需要 Linux）：

```powershell
cd E:\DSH\qqbot-sandbox

# 1) 自检：不碰任何真实目录，起临时端口打一遍
python ios\remote-agent\astrbot_bridge_agent.py --selftest
# ……若干 [ok] …… 最后一行 SELFTEST OK，退出码 0

# 2) 造一个假的宿主布局，真起一次
$demo = Join-Path $env:TEMP "qqbot-demo"
New-Item -ItemType Directory -Force "$demo\state", "$demo\logs" | Out-Null
python -c "import json,os;p=os.path.join(os.environ['TEMP'],'qqbot-demo');open(os.path.join(p,'state','bots.json'),'w',encoding='utf-8').write(json.dumps({'active':'bot1','bots':[{'id':'bot1','name':'Main','uin':'10001'}]}))"

# 3) 起代理（另开一个窗口，或末尾加 & 让它在后台）
python ios\remote-agent\astrbot_bridge_agent.py --port 17881 --root $demo --token demo-key

# 4) 另开一个窗口打一条
python -c "import json,urllib.request;req=urllib.request.Request('http://127.0.0.1:17881/api/qqbot/bridge',data=json.dumps({'m':'state','a':[]}).encode(),headers={'Content-Type':'application/json; charset=utf-8','X-AstrBot-Key':'demo-key'});print(urllib.request.urlopen(req,timeout=5).read().decode())"
```

Windows 上还有几点要注意：

- 端口被占会当场退出并提示。Windows 默认的 `SO_REUSEADDR` 会让第二个进程也绑定成功、
  却收不到包（看起来「起两次都没报错」），代理在 Windows 上改成了独占绑定。
- `--selftest` 里用 `echo` 测 `exec`，走的是 `cmd.exe` 内建命令，正常情况下能回显。
- `action` 配置的命令也走 `cmd.exe`（`shell=True` 在 Windows 上就是 `cmd /c`），写法按 Windows 来，
  比如 `"restart": "taskkill /F /IM astrbot.exe & start astrbot.exe"`。
- 本机第一次起服务时 Windows 防火墙可能弹窗问是否放行，选「取消」也行 —— 默认只监听回环，不需要放行。

## 排错

| 现象 | 多半是 |
| --- | --- |
| App 里状态全空、`botCount` 为 0 | `--root` 没指对，用 `state.root` 字段看看实际用的是哪个目录 |
| `napcatOn/astrbotOn` 一直 false | 端口没配对（`napcatPort`/`astrPort`），或服务确实没起 |
| `appVersion` 是空串 | 没找到 AstrBot 源码目录，用 `astrbotDir` 直接指定 |
| `action` 回 `失败：宿主没有配置 …` | `agent.json` 的 `commands` 里没有对应键 |
| 起不来，日志说端口被占 | 换个 `--port`，或者先 `netstat -ano \| findstr 17880` 看看是谁 |
| 绑 0.0.0.0 直接退出 | 没加 `--lan`，这是故意的 |
