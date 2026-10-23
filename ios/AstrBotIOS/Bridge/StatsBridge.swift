import Foundation

/// 匿名统计 / 崩溃看板 / 公告缓存。
/// iOS 版没有 Java 侧的 StatsRecorder、CrashBoard、AnnoCache，
/// 这里用 UserDefaults 存最小状态，保证界面上的开关与卡片有正确反应。
enum StatsBridge {
    static func invoke(_ name: String, _ a: [Any]) -> String {
        switch name {
        // MARK: 匿名统计
        // 前端 refreshStats 只取第一个能解析的结果，所以 statsState 要一次给全
        // （enabled + installId），不然「安装标识」那一行会一直显示「尚未生成」
        case "statsState", "statsId", "statsInfo":
            // 字段两套都发：Android 侧 StatsId 同时给 id/on 与 installId/enabled，
            // 前端只认后者，老接口认前者——照抄一份兼容，免得上游哪天换了读法
            return statsJSON(create: false, forceOn: nil)
        case "statsOn", "statsSet":
            let on = BR.bool(a, 0)
            Prefs.shared.statsOn = on
            Prefs.shared.crashReport = on
            return statsJSON(create: on, forceOn: on)
        case "statsReset":
            Prefs.shared.statsId = ""
            return statsJSON(create: true, forceOn: nil)
        case "statsEvents":
            // 原生层不落事件表，只回空数组 + 今日在线秒数（与 Android 侧 StatsId.events 对齐）
            return BR.js(["ok": true, "events": [], "count": 0, "todayOnlineSec": 0])

        // MARK: 崩溃看板
        // 契约：crashBoard 优先，classes 带 first/ver/device，items 精简；iOS 版暂时恒空
        case "crashBoard":
            return BR.js([
                "ok": true,
                "on": Prefs.shared.crashReport,
                "classes": [],
                "items": [],
                "total": 0
            ])
        case "crashInfo":
            return BR.js(["ok": true, "items": [], "count": 0])
        case "crashReportOn":
            Prefs.shared.crashReport = BR.bool(a, 0)
            return BR.js(["ok": true, "enabled": Prefs.shared.crashReport])
        case "crashReportState":
            return BR.js(["ok": true, "enabled": Prefs.shared.crashReport])
        case "crashUploadSet":
            Prefs.shared.crashReport = BR.bool(a, 0)
            return BR.js(["ok": true, "enabled": Prefs.shared.crashReport])
        case "crashUploadNow":
            return BR.js(["ok": true, "queued": false, "msg": "iOS 版没有本地崩溃日志可上报"])
        case "crashClear":
            return BR.js(["ok": true, "cleared": true])

        // MARK: 公告
        case "ann", "announcement", "annRefresh", "annRefreshStart":
            return annFeed()
        default:
            return ""
        }
    }

    private static func statsJSON(create: Bool, forceOn: Bool?) -> String {
        let on = forceOn ?? Prefs.shared.statsOn
        let id = installId(create: create && on)
        return BR.js([
            "ok": true,
            "on": on,
            "enabled": on,
            "id": id,
            "installId": id,
            "atomOn": true,
            "lastSentDay": Prefs.shared.statsLastSentDay,
            "lastErr": "",
            "lastAt": Int(Prefs.shared.annAt)
        ])
    }

    /// 安装标识：只在用户真的开了统计时才生成（默认关闭，关着就不留痕）
    private static func installId(create: Bool) -> String {
        let cur = Prefs.shared.statsId
        if !cur.isEmpty { return cur }
        guard create else { return "" }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        Prefs.shared.statsId = id
        return id
    }

    private static func annFeed() -> String {
        let raw = Prefs.shared.annCache
        var items: [[String: Any]] = []
        if !raw.isEmpty,
           let d = raw.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] {
            items = arr
        }
        return BR.js(["ok": true, "items": items, "at": Int(Prefs.shared.annAt)])
    }
}
