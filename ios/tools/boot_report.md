# iOS 版前端验收报告（boot_check 自动生成）

> 本文件由 `ios/tools/boot_check.py` 每次运行时重写，内容是那一次真跑的结果，不是手写。

- 生成时间：2026-10-23 18:50:39 +0800
- 仓库：`E:\DSH\qqbot-sandbox`
- 页面：`ui/assets/qqbot/index.html`（480 KB）
- 垫片：`ios/shared/AndroidShim.js`（2.0 KB）
- mock 服务：`http://127.0.0.1:59195/`
- Python：3.12.10
- Chrome：`C:\Program Files\Google\Chrome\Application\chrome.exe`
- 无头参数：`--headless=new --disable-gpu --no-first-run --no-default-browser-check --user-data-dir=<临时目录> --virtual-time-budget=9000`

## 结论：**全部 PASS**（15/15）

## 断言明细

| # | 断言 | 结果 | 实测 |
| --- | --- | --- | --- |
| 1 | 四个页签都在（主页 / 机器人 / 日志 / 更多） | PASS | 导航文本：主页   机器人   日志   更多 |
| 2 | header 副标题不是「正在读取状态…」 | PASS | hdrSub = 2 个 NapCat 实例  ·  当前：bot1 |
| 3 | 页面里没有「读取状态失败」 | PASS | 命中 0 处 |
| 4 | window.Android 方法数 >= 40 | PASS | 实测 91 个方法 |
| 5 | 状态卡片里的版本号文本出现了（state.appVersion=0.1.0） | PASS | #v-appver = 0.1.0，落款 #pfv-home = v0.1.0 |
| 6 | 主页状态卡显示「一切正常」 | PASS | h3v = 一切正常 |
| 7 | NapCat / AstrBot 都显示为在线 | PASS | 状态详情：NapCat 在线 · AstrBot 在线 · OneBot 已连接；副标题：NapCat 与 AstrBot 都在线，消息通道已接通 |
| 8 | 今日概览有真实数字（在线 / 消息 / 异常 / 流量） | PASS | 在线 7h39m · 消息 1842 · 异常 3 · 流量 21.5 MB |
| 9 | 部署进度 100%（部署台写着「部署已完成」） | PASS | deployHint = 部署已完成，可以开始扫码登录 QQ 了。；#deployBar style = width: 100%; |
| 10 | <title> 抓到了 | PASS | title = AstrBot烛版 |
| 11 | 430 宽下没有横向溢出 | PASS | scrollWidth 430 / innerWidth 430，溢出 0 px |
| 12 | 页面没有未捕获的 JS 报错 | PASS | 0 条 |
| 13 | 390 宽下没有横向溢出 | PASS | scrollWidth 390 / innerWidth 390，溢出 0 px |
| 14 | 截图 430x932 生成且不是空白页 | PASS | E:\DSH\qqbot-sandbox\ios\tools\shots\home-430x932.png，430x932，204.1 KB |
| 15 | 截图 390x844 生成且不是空白页 | PASS | E:\DSH\qqbot-sandbox\ios\tools\shots\home-390x844.png，390x844，178.1 KB |

## 截图

| 文件 | 尺寸 | 大小 |
| --- | --- | --- |
| `ios/tools/shots/home-430x932.png` | 430x932 | 204.1 KB |
| `ios/tools/shots/home-390x844.png` | 390x844 | 178.1 KB |

## 控制台报错

- 页面内探针：没有未捕获的 JS 报错（window.onerror / unhandledrejection / console.error 都没触发）
- chrome_debug.log 没生成（Chrome 版本差异），控制台报错以页面内探针为准

## 复跑

```
python ios\tools\boot_check.py
```
