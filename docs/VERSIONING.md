# 版本号与升级规则（测试版与正式版同号）

本文是 versionCode 的唯一权威说明。

规则一句话：**`versionCode = MAJOR*10000 + MINOR*100 + PATCH`，`-beta.N` 后缀只写在 `versionName` 里、不参与换算**——所以测试版与它要转正的正式版同号，验收通过后去掉后缀重打即可原地覆盖。

## 1. 版本名（versionName）

来源：仓库根目录的 `VERSION` 文件，一行，形如：

| 形式 | 例子 | 含义 |
| --- | --- | --- |
| `MAJOR.MINOR.PATCH` | `1.3.6` | 正式版 |
| `MAJOR.MINOR.PATCH-beta.N` | `1.3.6-beta.2` | 测试版，N 只能是 1..8 |

`build-ui.ps1` 只做校验和换算，不会改写 `VERSION`。

## 2. versionCode

```
versionCode = MAJOR*10000 + MINOR*100 + PATCH
1.3.5                -> 10305
1.3.5-beta.7         -> 10305
1.3.5（正式版）       -> 10305
1.3.6 / 1.3.6-beta.1 -> 10306
```

由此得到三条性质：

1. **同号转正**：`1.3.5-beta.8` 与 `1.3.5` 都是 `10305`，正式版去掉后缀重打即可覆盖安装（Android 允许同 versionCode 覆盖，只拒绝更小的）。
2. **同一正式版下的多个 beta 也同号**（`1.3.5-beta.7` / `-beta.8` 都是 `10305`），反复迭代不会把号堆高。
3. **跨正式版才涨号**：`1.3.5`（`10305`）→ `1.3.6`（`10306`）。

### 2.1 历史：一次短暂的 6 位制（已废止）

2026-11-04 曾把规则改成 `base*10 + N`（正式版 `base*10 + 9`），产出 `1.3.5-beta.7 = 103057`。该规则当日废止、回归同号规则，`103057` 是**唯一一个 6 位包**（已发布到 Release）。

后果（必须记住）：

- 装过 `1.3.5-beta.7`（`103057`）的设备，装任何 5 位制新包（`10305` / `10306`）都会被系统按降级拒装（`INSTALL_FAILED_VERSION_DOWNGRADE`），**只能卸载一次**。
- `10305` 及以下的历史版本、以及今后所有 5 位制版本之间，升级链完全正常。
- 因此 `versionCode.floor` 的 `code` 记的是「当前 5 位制下已发出的最高号」（`10305`），而 `103057` 这个特例在文件注释、README、CHANGELOG 里单独写明。

## 3. 下限保护：`versionCode.floor`

仓库根目录的 `versionCode.floor` 记录两件事：

```
code=10305          # 已发布到用户手里的最高 versionCode（当前 5 位制）
name=1.3.5-beta.7   # 最后一次发布的 versionName
```

`build-ui.ps1` 在写 `apktool.yml` 之前强制校验两条：

1. 新 `versionCode` **不得低于** `code`——同号放行（beta 与正式版本来就同号），更低一律 `exit 1`；
2. 新版本名的数字段**不得低于** `name`——拦住「1.3.6 之后又发 1.3.5」这类名次回退。

任一不满足就直接失败，除非显式加 `-AllowVersionCodeReuse`（只给「同一版本内部重打」用，比如只换签名或只改文档）：

```powershell
powershell -ExecutionPolicy Bypass -File build-ui.ps1
powershell -ExecutionPolicy Bypass -File build-ui.ps1 -AllowVersionCodeReuse   # 内部重打
```

> `versionCode.floor` 是**保护网**，不是历史记录。发版后必须手动把它改成新包的两个值，否则下次构建会被自己的保护网拦住（这是故意的）。

## 4. 历史对照表（真实构建产物）

| versionName | versionCode | 说明 |
| --- | --- | --- |
| 1.3.2 … 1.3.4 各版 | 10302 … 10304 | 同号规则 |
| 1.3.5-beta.5 / 1.3.5-beta.6 | 10305 | 同号规则 |
| 1.3.6-beta.1 | 10306 | 同号规则；**已撤回**（Release 与 tag 已删），但装到过设备上 |
| 1.3.5-beta.7 | **103057** | **6 位制唯一产物**（已废止的规则），装过它的设备需卸载一次 |
| 1.3.5 / 1.3.5-beta.8 起 | 10305 | 同号规则 |
| 1.3.6 / 1.3.6-beta.1 起 | 10306 | 同号规则 |

## 5. 装过 103057 / 10306 的设备怎么升

- **装过 `1.3.5-beta.7`（103057）**：装 5 位制新包（10305 / 10306）会被拒 → **卸载一次**再装。这是 6 位制的遗留代价，与签名无关。
- **装过 `1.3.6-beta.1`（10306）**：装 `1.3.5` 系（10305）会被拒 → 卸载一次；装 `1.3.6` 系（10306）则同号覆盖，正常。
- **其它（10305 及以下）**：直接覆盖安装，沙盒、QQ 登录态与配置全部保留。
- 覆盖安装要求签名一致：所有 Release 包都由同一份本机 keystore 签名（v2 + v3），签名材料不入库。

## 6. 发版检查单（每次发版照做）

1. 决定版本名，写进 `VERSION`（后缀只能是 `-beta.N`，N=1..8）。
2. 跑 `build-ui.ps1`，确认输出 `VERSION GUARD ok: last published <old> -> <new / VC>`，并核对换算出的 VC 与手算一致。
3. 跑 `build-apk.ps1 -Arch arm64` 与 `build-apk.ps1 -Update`（两个包共用同一份 `_apkwork`，code 天然一致）。
4. 用 `aapt2 dump badging`（或 `dumpsys package`）核对**全量包与增量包的 versionCode 完全相同**，且等于第 2 步打印的值。
5. `apksigner verify` 核对两个包签名一致（v2 + v3）。
6. 更新 `versionCode.floor`：`code=<新 VC>`、`name=<新版本名>`。
7. 同步 README / 使用说明 / docs/BUILD.md / CHANGELOG 里的版本号与体积、SHA-256。
8. 提交并打 tag `v<版本名>`，Release 同时附全量包与增量包。
9. **转正式版**：`VERSION` 去掉 `-beta.N` 后缀，**versionCode 不变**，重打两个包并发正式 Release，用户同号覆盖升级。

## 7. 常见误区

- **不要给 beta 单独加号段**：一旦 beta 与正式版不同号，转正那一刻就变成「换号」，用户要么装不上要么被要求卸载。
- **不要在发版后回退 `VERSION`**：guard 会拦住；确实要回退就先把 `versionCode.floor` 调低并说明原因（等于承认线上下载的包作废）。
- **不要用「卸载重装」当默认升级方案**：这是版本号治理失当的症状，不是解法。`103057` 那一次是唯一例外，已单独说明。
- **不要拿 `_apkwork` 里的 `apktool.yml` 当版本证据**：它是上一次构建的残留，真值只看 `VERSION` 与打包出来的 APK。
