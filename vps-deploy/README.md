# 官网「AstrBot」板块 → 下载页

把 [烛心.xyz](https://xn--15t839a.xyz) 站上那个 **AstrBot** 板块，从「人格分享栏」改成 **AstrBot烛版 的下载页**，
并且让它**每天自动从 GitHub Releases 刷新**（有新版本、换了校验值，页面自己就跟着变，不用手动改）。

---

## 一、只动一个文件

整站是「内容与渲染分离」的：HTML 只负责壳，渲染逻辑在 `assets/core.js` + `assets/astrbot.<hash>.js`，
而**内容**全部来自 `data/astrbot.json`。`astrbot.js` 顶部的注释也写明了这个 JSON 的结构就是唯一契约。

所以本次改造**只改 `data/astrbot.json` 一个文件**，不动 JS / CSS / HTML：

| 好处 | 说明 |
| --- | --- |
| 不用改缓存指纹 | 不动 JS 就不必去改 HTML 里的 `?v=` 参数 |
| 风险最低 | 出问题把备份换回去就完事 |
| 爬虫简单 | 只生成一个 JSON，不用碰前端 |

数据格式（照抄渲染器要求，字段名只有这 5 个）：

```json
{
  "title": "...", "subtitle": "...", "intro": "...", "updated": "...",
  "tabs": [
    { "key": "download", "label": "下载", "icon": "⬇️", "desc": "...",
      "cards": [ { "icon": "📦", "title": "...", "desc": "...", "link": "https://...", "text": "" } ] }
  ]
}
```

硬约束（`astrbot.js` 会校验，超了就不渲染）：`key` 必须匹配 `^[A-Za-z_][A-Za-z0-9_]{0,63}$`；
最多 **12** 个子栏、每栏最多 **200** 张卡。爬虫写文件前会自己先校验一遍，不合格就**拒绝写入**、保留上一次的好数据。

卡片行为由字段决定：

- 只给 `link` → 整张卡可点，右下角显示「🔗 打开链接」；
- 只给 `text` → 变成**可复制卡**，带「⬇️ 下载 .txt」「🔎 展开全文」和「📋 点击复制文案」；
- 两个都给 → 既可点又可复制（本项目的更新日志栏就是这么做的）。

---

## 二、四个子栏（其它子栏原样保留）

爬虫固定重写下面这四栏；**原有的其它子栏（例如早先分享栏留下的人格）会原样保留**
（`KEEP_EXTRA_TABS = True`），所以线上那一栏实际是 5 个子栏：下载 / 这是什么 / 怎么用 / 更新日志 / 人格。

| 子栏 | key | 内容 |
| --- | --- | --- |
| ⬇️ 下载 | `download` | 最新版 APK（**站内 `downloads/` 直接下载，不用跳 GitHub**；同时也给 GitHub 直链与 `ghfast.top` 国内加速）、所有历史版本、**SHA-256 校验值**（做成可复制卡） |
| 🤖 这是什么 | `what` | 这个应用是什么、不需要服务器、装完能做什么 |
| 📱 怎么用 | `howto` | 下载 → 等自动部署 → 扫码登录 → 开始用 |
| 📝 更新日志 | `changelog` | 最近 6 个 release 的正文（截 1200 字） |

校验值不用下载 25 MB 的安装包也能拿到：GitHub 的 release asset 对象自带 `digest` 字段
（形如 `sha256:113f91e1...`），爬虫直接取 `sha256:` 后面那一段。

---

## 三、文件清单

| 文件 | 作用 |
| --- | --- |
| `astrbot_sync.py` | 每日爬虫。纯 Python3 标准库，无第三方依赖 |
| `data/astrbot.json` | 本次改造后的成品内容（已按线上真实数据生成好，可直接铺） |
| `install.sh` | 一键安装：探测站点目录 → 备份 → 铺文件 → 建 systemd timer → 立刻同步一次 |
| **`deploy-astrbot-page.sh`** | **自包含一键部署**：把上面这些文件全部内嵌在这一个脚本里，服务器上单文件就能跑，不用先传目录 |
| `astrbot-sync.service` | systemd oneshot 服务（`install.sh` 会把真实站点路径填进去） |
| `astrbot-sync.timer` | 每天 04:17 触发一次，带 `Persistent=true` 和随机延迟 |
| `rel110-body.md` | v1.1.0 的 release 正文（修掉过一次乱码，留档） |

爬虫支持 `--dry-run`（只打印不写文件），排错时很好用。

---

## 四、怎么部署

### 方式 A：一行命令（推荐，不用先下载任何东西）

在服务器上直接执行：

```bash
curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/Zxin-Pro/astrbot-candle/main/vps-deploy/deploy-astrbot-page.sh | sudo bash
```

`deploy-astrbot-page.sh` 是**自包含**的 —— 爬虫、页面内容、systemd 单元全都内嵌在脚本里，
所以不用先把目录传上去。（`ghfast.top` 是国内加速前缀；服务器能直连 GitHub 就把 `https://ghfast.top/` 去掉。）

它会把站点目录、备份文件名、每一步的结果都打印出来，装完立刻联网同步一次。

### 方式 B：把目录传上去再跑

把整个 `vps-deploy/` 目录传上去，然后：

```bash
sudo bash install.sh
```

`install.sh` 会自己找到站点目录（判据是同一个目录下同时有 `assets/core.js` 和 `data/astrbot.json`）。
如果探测失败，就手工指定：

```bash
sudo bash install.sh --site-dir=/www/wwwroot/your-site
```

其它开关：

| 参数 | 作用 |
| --- | --- |
| `--no-timer` | 只铺文件，不建定时任务 |
| `--sync-dir=PATH` | 改同步脚本的安装位置（默认 `/opt/astrbot-sync`） |
| `--revert` | 还原成备份的那一份，一键回滚 |

没有 systemd 的老机器，`install.sh` 会直接把该加的 cron 行打出来。

---

## 五、每天自动刷新

先说清楚现在跑的是哪一套 —— 仓库里有两代方案，**推荐用新的那套（方式 A）**：

| | 新（`deploy-astrbot-page.sh`） | 旧（`install.sh` + `astrbot_sync.py`） |
| --- | --- | --- |
| 定时器 | `zhuxin-astrbot-dl.timer`，`OnUnitActiveSec=1d`（开机 3 分钟后先跑一次，之后每天一次） | `astrbot-sync.timer`，每天 **04:17** |
| 装到哪 | `<站点>/scripts/astrbot_download_sync.py` | `/opt/astrbot-sync/astrbot_sync.py` |
| 断网兜底 | 自带一份离线内容，先顶上，联网成功后再换成最新 | 铺静态版本，等定时任务再试 |

新方案的重启/补跑行为：`OnBootSec=3min` + `OnUnitActiveSec=1d` + `Persistent=true`
（服务器关机错过了，开机就会补跑一次），不再额外加随机延迟。

随手看看/手动跑一次：

```bash
systemctl list-timers zhuxin-astrbot-dl.timer --no-pager   # 下次什么时候跑
systemctl start zhuxin-astrbot-dl.service                  # 立刻跑一次
journalctl -u zhuxin-astrbot-dl.service -n 30 --no-pager   # 看这次跑了什么
```

旧方案的对应命令是 `systemctl list-timers astrbot-sync.timer` 与
`systemctl start astrbot-sync.service`（它带着 `ASTRBOT_JSON=<站点>/data/astrbot.json` 执行爬虫）。

这跟站上已有的几个定时任务（`data/status.json` 服务器监控、`data/quota.json` AI 中转站额度、
`data/points.json` 积分）是**同一个套路**，架构上不引入新东西。

几个细节：

- **原子写**：先写 `astrbot.json.tmp` 再 `os.replace`，访客不会读到写了一半的 JSON；
- **失败不动线上文件**：抓不到 GitHub、或生成的内容没通过结构校验，脚本 `exit 1` 直接退出，线上保留上一次的好数据；
- **自动备份**：动手前先把原文件存成备份 —— 新方案是 `data/astrbot.json.bak-deploy.<时间戳>`
  （脚本自带 `--revert` 一键还原），旧方案是 `data/astrbot.json.bak.<时间戳>`（`install.sh --revert`）；
- 换新版本**不用改任何配置**，爬虫每次都重新读最新的 release，站内 APK 只留最近 4 个版本。

---

## 六、验证清单

部署完打开 `https://烛心.xyz/#/astrbot`（或站点的 AstrBot 子栏），确认：

1. 标题是「**AstrBot烛版**」、副标题「手机上一键跑起 AstrBot + NapCat」；
2. 有四个子栏：下载 / 这是什么 / 怎么用 / 更新日志（外加原样保留的人格栏，共 5 个）；
3. 「下载」栏第一张卡的链接指向站内的 `downloads/AstrBot-<版本号>.apk`（当前仓库快照里是 `downloads/AstrBot-1.1.26.apk`），且版本号与线上最新一致；
4. 「下载」栏最后一张「SHA-256 校验值」卡点一下能复制出 64 位十六进制串；
5. 「更新日志」栏第一张卡是当前最新版，正文是中文、**没有乱码**。

> 踩过的坑：早先建 v1.1.0 release 时，正文里一行中文是在 PowerShell 脚本里内联写的，
> 而 PS 5.1 按 ANSI 读无 BOM 的 `.ps1`，于是那行存成了 `鐑涚増`（「烛版」被按 GBK 解码的乱码）。
> 已用 REST API 修好。**以后凡是往线上写中文，都必须先把文本用 UTF-8 落盘、再按 UTF-8 字节提交，不能写进 .ps1 字面量。**
