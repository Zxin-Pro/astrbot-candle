import Foundation

/// 转发到宿主侧远端代理。**必须同步**：前端是 `JSON.parse(Android.state())` 这种同步取值，
/// 所以这里用信号量等 URLSession 的回调，等不到就超时返回 nil（Router 再翻成「连不上宿主」）。
final class RemoteClient {
    static let shared = RemoteClient()

    /// 读类接口给 2.5 秒：JS 线程被同步 XHR 卡着，宁可报错也不能让界面僵住
    static let readTimeout: TimeInterval = 2.5
    /// 起进程/等进程结束的接口（exec/action/doctor）放宽到 65 秒，和 Android 桥的 60 秒上限对齐
    static let slowTimeout: TimeInterval = 65

    private let session: URLSession

    private init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 3
        cfg.timeoutIntervalForResource = 70
        cfg.waitsForConnectivity = false
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: cfg)
    }

    private var base: URL? {
        var h = Prefs.shared.host.trimmingCharacters(in: .whitespacesAndNewlines)
        if h.isEmpty { return nil }
        if !h.lowercased().hasPrefix("http://") && !h.lowercased().hasPrefix("https://") {
            h = "http://" + h
        }
        guard var c = URLComponents(string: h) else { return nil }
        if c.port == nil { c.port = Prefs.shared.port }
        guard let u = c.url else { return nil }
        return u.appendingPathComponent("api/qqbot/bridge")
    }

    func call(_ name: String, args: [Any], slow: Bool) -> String? {
        guard let url = base else { return nil }
        guard JSONSerialization.isValidJSONObject(["m": name, "a": args]) else { return nil }
        let body = (try? JSONSerialization.data(withJSONObject: ["m": name, "a": args])) ?? Data("{}".utf8)

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let token = Prefs.shared.token
        if !token.isEmpty { req.setValue(token, forHTTPHeaderField: "X-AstrBot-Key") }

        let sem = DispatchSemaphore(value: 0)
        var out: String?
        let task = session.dataTask(with: req) { data, resp, _ in
            defer { sem.signal() }
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return }
            guard let data = data, let s = String(data: data, encoding: .utf8) else { return }
            out = s
        }
        task.resume()
        if sem.wait(timeout: .now() + (slow ? Self.slowTimeout : Self.readTimeout)) == .timedOut {
            task.cancel()
            return nil
        }
        return out
    }

    /// 「测试连接」用：GET /api/qqbot/ping，返回 (ok, 说明)
    func ping(host: String, port: Int, token: String, done: @escaping (Bool, String) -> Void) {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if h.isEmpty { done(false, "先填宿主地址"); return }
        if !h.lowercased().hasPrefix("http://") && !h.lowercased().hasPrefix("https://") { h = "http://" + h }
        guard var c = URLComponents(string: h) else { done(false, "地址格式不对"); return }
        if c.port == nil { c.port = port }
        guard let u = c.url?.appendingPathComponent("api/qqbot/ping") else { done(false, "地址格式不对"); return }
        var req = URLRequest(url: u)
        req.timeoutInterval = 6
        if !token.isEmpty { req.setValue(token, forHTTPHeaderField: "X-AstrBot-Key") }
        session.dataTask(with: req) { data, resp, err in
            if let e = err {
                done(false, "连不上：\(e.localizedDescription)")
                return
            }
            guard let data = data,
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                done(false, "宿主返回的不是 JSON")
                return
            }
            if let ok = obj["ok"] as? Bool, ok {
                let name = (obj["name"] as? String) ?? "宿主"
                let ver = (obj["version"] as? String) ?? ""
                done(true, "\(name) · v\(ver)")
            } else {
                done(false, (obj["err"] as? String) ?? "宿主拒绝了请求")
            }
        }.resume()
    }
}
