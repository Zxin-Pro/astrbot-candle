import Foundation

/// 返回体的三种形态——决定了远端失败时给前端回什么才不会被误读
enum BridgeKind {
    /// JSON 字符串，前端 JSON.parse
    case json
    /// 纯文本消息，前端当月/字符串比对（如「已提交…」「已保存」）
    case text
    /// 前端不看返回值
    case empty
}

enum BridgeTarget {
    /// 本机原生实现（通知、剪贴板、头像、背景、相册…）
    case local
    /// 转发到宿主侧代理
    case remote
    /// iOS 上无意义，附带一句原因
    case unsupported(String)
}

struct BridgeSpec {
    let target: BridgeTarget
    let kind: BridgeKind
    /// 远端调用是否走长超时（起进程、等进程结束）
    let slow: Bool
}

/// 90 个桥方法的分派表。前端只有 `typeof Android.x === "function"` 这一种能力探测，
/// 所以「挂不挂」就等于「支不支持」——表里的 unsupported 项直接不挂。
final class BridgeRouter {
    static let shared = BridgeRouter()

    /// 没配宿主时也挂的只读接口：否则连「尚未连接宿主」都无法显示
    static let alwaysOn: Set<String> = [
        "state", "todayStats", "riskInfo", "netInfo", "envVers", "astrInfo",
        "instancesInfo", "bootInfo", "bundleInfo", "deployInfo", "reinstallInfo"
    ]

    private static let notConnected = "还没有连接宿主。请点右上角齿轮填上宿主的地址和令牌。"

    let specs: [String: BridgeSpec]

    private init() {
        func L(_ k: BridgeKind) -> BridgeSpec { BridgeSpec(target: .local, kind: k, slow: false) }
        func R(_ k: BridgeKind, _ slow: Bool = false) -> BridgeSpec {
            BridgeSpec(target: .remote, kind: k, slow: slow)
        }
        func X(_ why: String) -> BridgeSpec {
            BridgeSpec(target: .unsupported(why), kind: .text, slow: false)
        }

        specs = [
            // ── 远端转发：宿主沙盒才有的能力 ──
            "state":             R(.json),
            "todayStats":        R(.json),
            "netInfo":           R(.json),
            "envVers":           R(.json),
            "astrInfo":          R(.json),
            "instancesInfo":     R(.json),
            "bootInfo":          R(.json),
            "bundleInfo":        R(.json),
            "deployInfo":        R(.json),
            "reinstallInfo":     R(.json),
            "riskInfo":          R(.json),
            "riskClear":         R(.json),
            "qqDetect":          R(.json),
            "qr":                R(.json),
            "qrShare":           R(.json),
            "astrSave":          R(.json, true),
            "astrSavePoll":      R(.json),
            "astrModelsStart":   R(.empty),
            "astrModelsPoll":    R(.json),
            "checkUpdateStart":  R(.empty),
            "checkUpdatePoll":   R(.json),
            "siteVerStart":      R(.empty),
            "siteVerPoll":       R(.json),
            "proxyGet":          R(.text),
            "proxySet":          R(.text),
            "toggleBot":         R(.json, true),
            "removeBot":         R(.text),
            "loadBots":          R(.json),
            "saveBots":          R(.text),
            "emergencyStop":     R(.json),
            "emergencyResume":   R(.json),
            "backupInfo":        R(.json),
            "backupPick":        R(.text),
            "backupStart":       R(.json, true),
            "backupStartScope":  R(.json, true),
            "backupPoll":        R(.json),
            "restorePick":       R(.text),
            "restorePickScope":  R(.text),
            "restoreApply":      R(.json, true),
            "restorePoll":       R(.json),
            "storageStats":      R(.json),
            "storageInfo":       R(.json),
            "storageRefresh":    R(.json),
            "storageRefreshStart": R(.empty),
            "storageClean":      R(.json, true),
            "doctorStart":       R(.empty),
            "doctorText":        R(.text, true),
            "action":            R(.text, true),
            "exec":              R(.text, true),
            "readLog":           R(.text),
            "clearLog":          R(.text),
            "rmLog":             R(.text),
            "uploadLog":         R(.text, true),

            // ── 本机原生：设备 / 相册 / 剪贴板 / 本地统计 ──
            "toast":             L(.empty),
            "copy":              L(.empty),
            "openUrl":           L(.text),
            "openQqGroup":       L(.text),
            "openPanel":         L(.empty),
            "disclaimerSeen":    L(.text),
            "disclaimerAck":     L(.json),
            "notiAsk":           L(.empty),
            "notiState":         L(.json),
            "powerInfo":         L(.json),
            "powerState":        L(.json),
            "powerGuide":        L(.json),
            "powerAck":          L(.json),
            "pickBg":            L(.empty),
            "bgImage":           L(.json),
            "clearBg":           L(.text),
            "avatarInfo":        L(.json),
            "avatarPath":        L(.json),
            "statsOn":           L(.json),
            "statsState":        L(.json),
            "statsSet":          L(.json),
            "statsId":           L(.json),
            "statsReset":        L(.json),
            // statsInfo 故意不登记：它不是 Java 侧的方法，而页面的 brCall 候选链
            // ["statsState","statsId","statsInfo"] 里 statsState 排在第一位且已经给全字段，
            // 挂上去只会让「方法表 == Android 侧」这条自检失效。
            // （StatsBridge 仍然认得这个名字，哪天要挂回来只加一行。）
            "statsEvents":       L(.json),
            "crashReportOn":     L(.json),
            "crashReportState":  L(.json),
            "crashUploadSet":    L(.json),
            "crashUploadNow":    L(.json),
            "crashInfo":         L(.json),
            "crashBoard":        L(.json),
            "crashClear":        L(.json),
            "ann":               L(.json),
            "announcement":      L(.json),
            "annRefresh":        L(.json),
            "annRefreshStart":   L(.empty),

            // ── iOS 上无意义 ──
            "avatarIconPin":     X("更换应用图标（iOS 版用系统自带的图标方案）"),
            "openTerminal":      X("内置终端（宿主上的 AstrBot 请用 SSH 或网页终端操作）")
        ]
    }

    /// /__caps 返回的方法名列表：垫片只挂这些，前端探测自然降级
    func availableMethods(hasHost: Bool) -> [String] {
        specs.keys.filter { name in
            switch specs[name]!.target {
            case .unsupported: return false
            case .local: return true
            case .remote: return hasHost || BridgeRouter.alwaysOn.contains(name)
            }
        }.sorted()
    }

    func handle(_ name: String, _ args: [Any]) -> String {
        guard let spec = specs[name] else {
            return BR.js(["ok": false, "err": "未知的桥方法：\(name)"])
        }
        switch spec.target {
        case .unsupported(let why):
            return BR.fail("iOS 版不支持" + why)
        case .local:
            return LocalBridge.invoke(name, args)
        case .remote:
            guard Prefs.shared.hasHost else { return fallback(spec.kind) }
            if let s = RemoteClient.shared.call(name, args: args, slow: spec.slow) {
                return name == "state" ? BridgeRouter.patchState(s) : s
            }
            switch spec.kind {
            // error 是页面认的键：只有它能触发「读取状态失败」那条分支，否则页面会拿
            // 一堆缺字段的 JSON 硬渲染（出现 http://undefined:undefined 这种东西）
            case .json:  return BR.js(["ok": false, "err": "连不上宿主：\(Prefs.shared.host)", "error": "连不上宿主：\(Prefs.shared.host)"])
            case .text:  return "失败：连不上宿主 \(Prefs.shared.host)，请检查网络与代理是否在运行"
            case .empty: return ""
            }
        }
    }

    /// 宿主管不到的两件事由本机补进 state：
    /// appVersion 是「这个控制台 App 的版本号」（页脚 v-x.y.z），宿主回空就一直显示「-」；
    /// notiGranted 是 iOS 本机的通知授权（页面据此决定要不要弹「开启通知权限」那条）。
    private static func patchState(_ raw: String) -> String {
        guard var obj = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: Any] else {
            return raw
        }
        if (obj["appVersion"] as? String ?? "").isEmpty {
            obj["appVersion"] = BR.appVersion
        }
        LocalBridge.kickNotiRefresh()
        obj["notiGranted"] = KV.flag("noti.granted")
        guard let d = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: d, encoding: .utf8) else { return raw }
        return s
    }

    private func fallback(_ kind: BridgeKind) -> String {
        switch kind {
        case .json:  return BR.js(["ok": false, "err": BridgeRouter.notConnected, "error": BridgeRouter.notConnected])
        case .text:  return "失败：" + BridgeRouter.notConnected
        case .empty: return ""
        }
    }
}
