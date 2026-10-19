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
3. 「下载」栏第一张卡的链接指向站内的 `downloads/AstrBot-<版本号>.apk`（当前仓库快照里是 `downloads/AstrBot-1.1.30.apk`），且版本号与线上最新一致；
4. 「下载」栏最后一张「SHA-256 校验值」卡点一下能复制出 64 位十六进制串；
5. 「更新日志」栏第一张卡是当前最新版，正文是中文、**没有乱码**。

> 踩过的坑：早先建 v1.1.0 release 时，正文里一行中文是在 PowerShell 脚本里内联写的，
> 而 PS 5.1 按 ANSI 读无 BOM 的 `.ps1`，于是那行存成了 `鐑涚増`（「烛版」被按 GBK 解码的乱码）。
> 已用 REST API 修好。**以后凡是往线上写中文，都必须先把文本用 UTF-8 落盘、再按 UTF-8 字节提交，不能写进 .ps1 字面量。**

---

## 七、公告与上报服务（qqbot-atom，8800）

上面那些讲的是**下载页**（纯静态，一个 JSON 就是一页）。站上另外还跑着一个很小的服务
`qqbot-atom`，是给 AstrBot 烛版 App 的 **v1.1.30** 用的：

| 接口 | 用途 |
| --- | --- |
| `GET /api/announce?ver=<客户端版本>` | 应用内公告列表（App 启动时拉一次） |
| `GET /api/app-config.json` | 同一份公告的另一种形状，对应 `docs/接口约定-1.1.30.md` 第 11 节 |
| `POST /api/crash` | 崩溃聚合上报（同类崩溃在客户端先归并成一条，只报 sig + 次数） |
| `POST /api/stats` | 匿名日统计（在线秒数、消息数、错误数、事件计数） |
| `GET /api/atom-health` | 运维用，看收了多少条 |

它**只吐 JSON，站点页面里不塞任何脚本**。监听 `127.0.0.1:8800`，由 nginx 反代到 443 上，
和已有的 `qqbot-errdump`（8799）是同一个套路：单元文件、`X-QQBot-*` 头挡爬虫、只绑回环。

> **客户端契约以 `docs/接口约定-1.1.30.md` 为准**（那份是给 Java 与控制台看的唯一契约）。
> 服务端为了兼容，**同时接受**两套字段名：`module` 与 `component`、`onlineSec/msgs/errors`
> 与 `events` 两种形状都收；`/api/app-config.json` 只是 `/api/announce` 的**扁平别名**，
> 新代码请用 `GET /api/announce?ver=<版本>`。（详见下面「关于『两种形状』」一节。）

架构上和 errdump 保持一致的几点：

- 纯 **python3 标准库**（`http.server` + `socketserver.ThreadingTCPServer`），没有第三方依赖，
  服务器上不装 pip 也能跑；
- 只监听 `127.0.0.1`，外网一律经 nginx；
- `ProtectSystem=strict` + `ReadWritePaths=/opt/qqbot-atom`，服务只能写自己那个目录。

### 数据落在哪

| 路径 | 是什么 | 谁写 |
| --- | --- | --- |
| `/opt/qqbot-atom/server.py` | 服务本体 | `install.sh` 覆盖 |
| `/opt/qqbot-atom/announce.json` | 公告内容 | **人工维护**，服务只读不写 |
| `/opt/qqbot-atom/crash.jsonl` | 崩溃聚合，**一类一行**（按 `sig+ver` 合并） | 服务合并重写 |
| `/opt/qqbot-atom/stats.jsonl` | 日统计，**一台机器一天一行**（按 `id+day+ver` 覆盖） | 服务覆盖重写 |
| `/etc/systemd/system/qqbot-atom.service` | systemd 单元 | `install.sh` 覆盖 |

`announce.json` 和两个 `.jsonl` 都是**增量数据，`install.sh` 永远不碰**——重装只换代码，
公告不会被覆盖、统计不会被清空。

两个 `.jsonl` 每条记录都会自动补两列：`received_at`（服务器收到的 epoch 秒）和
`ip`（访客 IP 的 **sha1 前 12 位**，原始 IP 不落盘）。所以同一台设备多次上报能对上，
但反查不出是谁。

**这两个文件不是「追加」而是「归并」，行数 = 类别数，不是上报次数**：

| 文件 | 归并键 | 重复上报时 |
| --- | --- | --- |
| `crash.jsonl` | `sig` + `ver` | **合并成一行**，`count` 累加，`received_at` 取最近一次，`first` 取最早、`last` 取最晚。同一崩溃不管报多少次都只占一行——看板要的是「这一类崩了几次」，重复行会把统计冲稀 |
| `stats.jsonl` | `id` + `day` + `ver` | **覆盖当天那条**（后到的整条替换先到的）。否则同一台机器一天报十几次，日活和在线时长会算重 |

所以 `/api/atom-health` 的 `crash` / `stats` 读的是**类别数**：
`crash` = 一共有多少种崩溃（sig+ver 组合），`stats` = 一共有多少个「设备×天」。
想看总上报次数就看 `count` 累加值，或者看 journal。

合并是「读-改-写 + `.tmp` 再 `os.replace`」，断电不会留半截文件；写入用一把锁串行化，
并发请求不会互相覆盖。手改文件时写到一半被读到也不会炸——坏行会被跳过。

### 怎么部署

三个文件在 `vps-deploy/atom/` 下（`server.py` / `qqbot-atom.service` / `install.sh`），
传上去跑 `install.sh` 即可：

```bash
# 本机 → 服务器（端口换成你自己的）
scp -P <端口> -r vps-deploy/atom root@<服务器>:/tmp/qqbot-atom-src

# 服务器上
bash /tmp/qqbot-atom-src/install.sh
```

本仓库平时的习惯是用 `out/shh.py` 把本地脚本 base64 之后推到服务器上跑
（`python out/shh.py <本地.sh>`），效果一样，好处是不依赖 scp。

`install.sh` 是**幂等**的，重跑只会把 `server.py` / unit 覆盖成最新、把 nginx 里那段 location
整块替换；nginx 配置没变化时它会直接跳过（不备份、不 reload）。开关：

| 参数 | 作用 |
| --- | --- |
| `--no-nginx` | 只装服务，nginx 自己改（会把这四段 location 打出来） |
| `--prefix=PATH` | 改安装目录（默认 `/opt/qqbot-atom`） |
| `--nginx-conf=PATH` | 改要动的 nginx 配置文件（默认 `/etc/nginx/conf.d/zhuxin-site.conf`） |

它会先 `cp -p` 备份成 `zhuxin-site.conf.bak-atom-<时间戳>` 再改，`nginx -t` 不过就自动还原。
插入位置靠哨兵注释 `# >>> qqbot-atom begin` / `# <<< qqbot-atom end` 标记，
重跑时按哨兵整块替换，所以**不会越插越多**。要改这四段，改仓库里的 `install.sh`，别直接改线上。

### 加一条公告

直接编辑 `/opt/qqbot-atom/announce.json`，**不用重启服务**——每次请求都现读这个文件。
格式：

```json
{
  "items": [
    {
      "id": "v1130-release",
      "title": "烛版 1.1.30 已发布",
      "body": "正文，一两句就够。",
      "level": "info",
      "minVer": "1.1.30",
      "until": ""
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一标识，客户端拿它去重/记「已读」。建议 `v<版本>-<主题>` |
| `title` / `body` | 标题与正文，纯文本 |
| `level` | `info`（普通）或 `warn`（醒目），其它值按 `info` 处理 |
| `minVer` | 最低客户端版本。留空 = 所有版本都能看到；填 `"1.1.30"` = 只有 ≥1.1.30 的才看得到 |
| `until` | 到期日 `YYYY-MM-DD`。留空 = 永不过期；填了就是**到那天为止（含当天）** |

一个最省事的流程：

```bash
cp -p /opt/qqbot-atom/announce.json /opt/qqbot-atom/announce.json.bak
vi /opt/qqbot-atom/announce.json
python3 -m json.tool /opt/qqbot-atom/announce.json > /dev/null && echo "JSON OK"
curl -sS 'http://127.0.0.1:8800/api/announce?ver=1.1.30'
```

JSON 写坏了不会让服务挂，也不会让客户端拿到空数组：读不出来（语法错、编码错、没有 `items` 数组）
就**退回代码里内置的那条 1.1.30 公告**，同时往 stderr / journal 记一行
`announce.json 读取失败，退回内置公告：<原因>`。这是刻意的——公告接口挂了会让 App 每次启动都报错，
代价比"多看到一条旧公告"大得多。**但还是要改完看一眼 `curl` 的输出**，别只看文件。

因为服务对返回内容做了 `ETag`（内容 sha1）和 `Cache-Control: public, max-age=60`，
**改完最多 60 秒内客户端就一定能看到新的**；客户端的 `If-None-Match` 命中时服务回 `304`，省流量。

### 怎么清理

```bash
# 只删某一条公告：编辑 announce.json，把那个对象从数组里去掉
# 全部停掉公告（客户端拿到空数组）：把 items 改成 []
echo '{"items": []}' > /opt/qqbot-atom/announce.json

# 清统计（先备份再清）
cp -p /opt/qqbot-atom/stats.jsonl /root/stats.jsonl.bak-$(date +%F)
: > /opt/qqbot-atom/stats.jsonl

# 清崩溃
cp -p /opt/qqbot-atom/crash.jsonl /root/crash.jsonl.bak-$(date +%F)
: > /opt/qqbot-atom/crash.jsonl
```

用 `: > 文件` 清空而不是 `rm`——文件的属主和权限保持不变，服务不用重启。
清完 `curl -sS https://xn--15t839a.xyz/api/atom-health` 的计数会归零。

顺手看一眼日志：

```bash
journalctl -u qqbot-atom -n 50 --no-pager
systemctl status qqbot-atom --no-pager
```

### nginx 加的那四段

`install.sh` 会往 `zhuxin-site.conf` 的 `server{}` 里插这五段（插在「静态资源缓存 7 天」前面），
写法和上面 `/api/errdump` 那段一模一样，只是端口是 8800、`proxy_pass` 不带尾斜杠
（要保留原始 URI 里的 `?ver=`）：

```nginx
location = /api/announce {
    proxy_pass http://127.0.0.1:8800;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_read_timeout 15s;
}
location = /api/crash {
    client_max_body_size 64k;
    proxy_pass http://127.0.0.1:8800;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_read_timeout 15s;
}
location = /api/stats {
    client_max_body_size 64k;
    proxy_pass http://127.0.0.1:8800;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_read_timeout 15s;
}
location = /api/atom-health {
    proxy_pass http://127.0.0.1:8800;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 15s;
}
location = /api/app-config.json {
    proxy_pass http://127.0.0.1:8800;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 15s;
}
```

用 `location =`（精确匹配）而不是前缀匹配，是为了不让它们落到站点那个 `location /`
的 `try_files` 兜底上——否则 `GET /api/crash` 会返回一页 HTML 而不是 JSON。

### 验证

```bash
curl -sS -i https://xn--15t839a.xyz/api/atom-health
curl -sS    'https://xn--15t839a.xyz/api/announce?ver=1.1.30'
curl -sS -X POST -H 'X-Qqbot-Atom: 1' -H 'Content-Type: application/json' \
  -d '{"id":"test","ver":"1.1.30","sig":"0123456789abcdef","module":"napi2native","signal":11,"count":1,"ts":1792400000}' \
  https://xn--15t839a.xyz/api/crash
tail -1 /opt/qqbot-atom/crash.jsonl
```

两个 `POST` 都要求请求头 `X-Qqbot-Atom: 1`（缺了回 `403`，挡掉随手扫的爬虫，
和 errdump 的 `X-Qqbot-Report: 1` 一个思路）。body 上限 64KB（nginx 和 Python 两侧都卡，
超了是 `413`）。按 IP 限流 **60 秒 20 次**，超了回 `429`；限流表在内存里，重启就清空——这个量级不用落盘。

字段校验是白名单：

| 字段 | 规则 |
| --- | --- |
| `id` | `^[A-Za-z0-9_-]{1,64}$` |
| `ver` | `^[0-9A-Za-z._-]{1,32}$` |
| `sig` | `^[0-9a-fA-F]{8,40}$` |
| `module` | `^[A-Za-z0-9_.:/-]{1,64}$`（`component` 也收，落盘统一叫 `module`） |
| `device` | `^[A-Za-z0-9_.-]{0,16}$`，可选 |
| `signal` | 整数，-128 ~ 255 |
| `count` | 整数，1 ~ 1000000 |
| `ts` | 整数，0 ~ 4102444800（2100 年） |
| `day` | `^\d{4}-\d{2}-\d{2}$` |
| `onlineSec` | 整数，0 ~ 172800（2 天） |
| `msgs` / `errors` | 整数，分别 0 ~ 1e8 / 0 ~ 1e6 |
| `os` / `arch` | `[A-Za-z0-9 ._+-]{0,32}` / `[A-Za-z0-9_.-]{0,16}`，可以不传 |
| `events` | 对象，可选；事件名 `^[a-z][a-z0-9_]{0,31}$`，值 0 ~ 1e8，最多 32 条 |

任何一条不合规回 `400` + `{"ok":false,"error":"<哪个字段>..."}`，
不会出现 500 空 body——所有异常都被 `try/except` 兜住并转成 JSON。

### 关于「两种形状」（重要）

仓库里 `docs/接口约定-1.1.30.md` 在 2026-10-19 16:00 先定了一版跨层契约，它和服务端这次的需求书
在字段名上有出入。**服务端目前两种都收**，谁先落地都不用改对面：

| | 需求书（本服务主形状） | `docs/接口约定-1.1.30.md` |
| --- | --- | --- |
| 公告 | `GET /api/announce?ver=1.1.30` → `{"ok":true,"items":[{id,title,body,level,minVer,until}]}` | `GET /api/app-config.json` → `{"ver":1,"minVer":"1.1.0","ann":{id,text,level,until},"flags":{...}}` |
| 崩溃 | `{"id","ver","sig","module","signal","count","ts"}` | `{"id","ver","sig","component","signal","count","device","first","last"}` |
| 统计 | `{"id","ver","day","onlineSec","msgs","errors","os","arch"}` | `{"id","ver","day","events":{"open":12,...}}` |

具体怎么兼容的：

- `/api/crash`：`module` 和 `component` 都认，**优先 `module`**，落盘统一叫 `module`；
  `device` / `first` / `last` 有就原样存，没有不影响；
  **`ts` 缺省时先用客户端给的 `last`，再没有才用服务器当前时间**
  （直接用 `now` 会把「上个月崩的那次」算成今天）。
  最小可用形状就是 `{"id","ver","sig","module","signal","count"}`，五个字段之外全可省。
- `/api/stats`：`onlineSec` / `msgs` / `errors` 缺省按 `0` 落（不再因为少报一项就整条拒收）；
  额外接受 `events` 映射，事件名限 `^[a-z][a-z0-9_]{0,31}$`、最多 32 条、每项是整数。
- `/api/app-config.json` 和 `/api/announce` **读同一份 `announce.json`**，
  前者只取第一条公告摊平成约定的形状，`until` 会从 `YYYY-MM-DD` 换算成 epoch 秒（空 → `0`）。

**决策（2026-10-19）**：以 `docs/接口约定-1.1.30.md` 为**唯一客户端契约**，
双形兼容保留不删（两种形状客户端都可能用），但**新代码一律用 `GET /api/announce?ver=`**，
`/api/app-config.json` 只作为扁平别名存在，不再往它上面加功能。

两边都保留是有意的：这个版本离客户端上传层落地还有距离，
先用最小代价把路铺通，等 `docs/接口约定-1.1.30.md` 定稿再决定砍掉哪一边。

> 该契约文件第 3 行写着「任何一方想改字段，先改本文件并通知其他层」。
> 如果最终以契约为准，把 `announce.json` 的心智模型保留、把 `/api/announce` 标成兼容层即可；
> 反过来以需求书为准，就把 `/api/app-config.json` 当成旧客户端的兼容口。
