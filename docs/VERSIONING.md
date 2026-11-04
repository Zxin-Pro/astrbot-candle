# 版本号与升级规则（永久单调递增）

本文是 versionCode 的唯一权威说明。**任何改动都必须保证：新包的 `versionCode` 严格大于已发布到用户手里的任何版本。**

## 1. 版本名（versionName）

来源：仓库根目录的 `VERSION` 文件，一行，形如：

| 形式 | 例子 | 含义 |
| --- | --- | --- |
| `MAJOR.MINOR.PATCH` | `1.3.6` | 正式版 |
| `MAJOR.MINOR.PATCH-beta.N` | `1.3.6-beta.2` | 测试版，N 只能是 1..8 |

`build-ui.ps1` 只做校验和换算，不会改写 `VERSION`。

## 2. versionCode（自 1.3.5-beta.7 起）

```
base       = MAJOR*10000 + MINOR*100 + PATCH      // 历史映射：1.3.5 -> 10305
预发布     = base*10 + N                          // N = beta 序号（1..8）
正式版     = base*10 + 9
```

由此得到三条性质：

1. 同一个 base 内，**预发布 < 正式版**（`103057 < 103059`），所以「先装 beta 再装正式版」是原地升级。
2. 预发布之间按序号递增（beta.6 `103056` → beta.7 `103057`）。
3. 换算进 10 倍空间，因此 **1.3.5-beta.7 的 code 是 `103057`**，天生高于历史上用旧公式算出的 `10305`（1.3.5-beta.5/beta.6）与 `10306`（已撤回但装到过设备的 1.3.6-beta.1）。

旧公式 `MAJOR*10000+MINOR*100+PATCH` 不再使用，只用于查历史表。

### 2.1 为什么旧公式必须废掉

旧公式让后缀完全不参与计算：

- `1.3.5-beta.5` / `1.3.5-beta.6` / `1.3.5` 全是 `10305`，同一个测试版序列里 code 一直不涨；
- 一次「版本名往前跳、又退回来」的事故（`1.3.6-beta.1` = `10306` 撤回后发 `1.3.5-beta.6` = `10305`）导致发出去的包 **code 比设备上的更小**，Android 直接拒绝覆盖安装（`INSTALL_FAILED_VERSION_DOWNGRADE`），只能卸载重装 —— 这正是本规则要永久消灭的情况。

## 3. 单调下限：`versionCode.floor`

仓库根目录的 `versionCode.floor` 记录「世界上已经存在过的最高版本」：

```
code=103057         # 已发布到用户手里的最大 versionCode（含已撤回但装到过设备的）
name=1.3.5-beta.7   # 最后一次正式对外发布的 versionName
```

上面这段就是仓库里 `versionCode.floor` 的**当前实际内容**（1.3.5-beta.7 发布后已同步）。下次构建必须是 `> 103057` 的 code、且版本名不低于 `1.3.5-beta.7`，比如 1.3.5-beta.8（`103058`）、1.3.5 正式版（`103059`）、1.3.6-beta.1（`103061`）。

`build-ui.ps1` 在写 `apktool.yml` 之前强制校验两条：

1. 新 `versionCode` **严格大于** `code`；
2. 新版本名的数字段**不低于** `name`（挡住「1.3.6 撤回后发 1.3.5」这类名次回退）。

任一不满足就直接 `exit 1`，除非显式加 `-AllowVersionCodeReuse`（只给「同一版本内部重打一份」用，比如只换签名或只改文档）：

```powershell
powershell -ExecutionPolicy Bypass -File build-ui.ps1
powershell -ExecutionPolicy Bypass -File build-ui.ps1 -AllowVersionCodeReuse   # 内部重打
```

> `versionCode.floor` 是**保护网**，不是历史记录。发版后必须手动把它改成新包的两个值，否则下次构建会被自己的保护网拦住（这是故意的）。

## 4. 历史对照表（真实构建产物）

| versionName | versionCode | 说明 |
| --- | --- | --- |
| 1.3.5（正式版） | 10305 | 旧公式 |
| 1.3.5-beta.5 / beta.6 | 10305 | 旧公式，与正式版同码 |
| 1.3.6-beta.1 | 10306 | 旧公式；**已撤回**（Release 与 tag 已删），但装到过设备上，必须被新版本越过 |
| 1.3.5-beta.7 | **103057** | 新公式首个版本 |
| 1.3.6（正式版，计划） | 103069 | 新公式：`10306*10+9` |

## 5. 已装 10306 测试包的设备怎么升

- **不需要卸载，不需要清数据。** 1.3.5-beta.7 的 `103057 > 10306`，直接覆盖安装。
- 覆盖安装要求签名一致：所有 Release 包都由同一份本机 keystore 签名（v2 + v3），签名材料不入库。
- 若用户装的是更早的、用旧 keystore 签过的包，Android 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，此时才需要卸载；这与 versionCode 无关，文档里单独说明。

## 6. 发版检查单（每次发版照做）

1. 决定版本号，写进 `VERSION`（后缀只能是 `-beta.N`，N=1..8）。
2. 跑 `build-ui.ps1`，确认输出 `VERSION GUARD ok: last published <old> -> <new / VC>`。
3. 跑 `build-apk.ps1 -Arch arm64` 与 `build-apk.ps1 -Update`（两个包共用同一份 `_apkwork`，code 天然一致）。
4. 用 `aapt2 dump badging`（或 `dumpsys package`）核对 **全量包与增量包的 versionCode 完全相同**，且等于第 2 步打印的值。
5. `apksigner verify` 核对两个包签名一致（v2 + v3）。
6. 更新 `versionCode.floor`：`code=<新 VC>`、`name=<新版本名>`。
7. 同步 README / 使用说明 / docs/BUILD.md / CHANGELOG 里的版本号与体积、SHA-256。
8. 提交并打 tag `v<版本名>`，Release 同时附全量包与增量包。

## 7. 常见误区

- **不要为了区分 beta 去手工改 code**：beta 与随后的正式版靠公式自然区分（+N vs +9）。
- **不要在发版后回退 `VERSION`**：guard 会拦住；确实要回退就先把 `versionCode.floor` 调低并说明原因（等于承认线上下载的包作废）。
- **不要用「卸载重装」当默认升级方案**：这是版本号治理失当的症状，不是解法。
