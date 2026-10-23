import Foundation

/// 桥参数的取值工具：JS 传过来的是 Any（String / NSNumber / NSNull）
enum BR {
    static func str(_ a: [Any], _ i: Int) -> String {
        guard i >= 0 && i < a.count else { return "" }
        let v = a[i]
        if v is NSNull { return "" }
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return String(describing: v)
    }

    static func int(_ a: [Any], _ i: Int) -> Int {
        guard i >= 0 && i < a.count else { return 0 }
        if let n = a[i] as? NSNumber { return n.intValue }
        return Int(str(a, i).trimmingCharacters(in: .whitespaces)) ?? 0
    }

    static func bool(_ a: [Any], _ i: Int) -> Bool {
        guard i >= 0 && i < a.count else { return false }
        if let n = a[i] as? NSNumber { return n.boolValue }
        let s = str(a, i).lowercased()
        return s == "1" || s == "true" || s == "on" || s == "yes"
    }

    /// 拼 JSON 字符串——前端一律 JSON.parse，所以必须是合法 JSON
    static func js(_ o: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(o),
              let d = try? JSONSerialization.data(withJSONObject: o, options: []),
              let s = String(data: d, encoding: .utf8)
        else { return "{\"ok\":false,\"err\":\"内部错误：JSON 编码失败\"}" }
        return s
    }

    /// 本 App 自己的版本号（页脚「v-x.y.z」与 state.appVersion 用它）
    static var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0.0"
    }

    static func ok() -> String { js(["ok": true]) }

    static func fail(_ msg: String) -> String { js(["ok": false, "err": msg]) }
}

/// 桥里用到的本机小配置。都在 UserDefaults，键名前缀 qqbot.ios.
enum KV {
    private static let d = UserDefaults.standard

    static func str(_ k: String) -> String { d.string(forKey: "qqbot.ios." + k) ?? "" }
    static func set(_ k: String, _ v: String) { d.set(v, forKey: "qqbot.ios." + k) }
    static func num(_ k: String) -> Int { d.integer(forKey: "qqbot.ios." + k) }
    static func setNum(_ k: String, _ v: Int) { d.set(v, forKey: "qqbot.ios." + k) }
    static func flag(_ k: String) -> Bool { d.bool(forKey: "qqbot.ios." + k) }
    static func setFlag(_ k: String, _ v: Bool) { d.set(v, forKey: "qqbot.ios." + k) }
    static func remove(_ k: String) { d.removeObject(forKey: "qqbot.ios." + k) }
}

/// 保证在main线程同步执行（桥处理器跑在回环服务的并发队列上）
func onMainSync<T>(_ body: () -> T) -> T {
    if Thread.isMainThread { return body() }
    return DispatchQueue.main.sync(execute: body)
}
