import Foundation
import Network

/// App 内的回环 HTTP 服务：把原版 index.html 原样喂给 WKWebView，
/// 同时充当 window.Android 的后端（POST /__bridge）。
///
/// 为什么不用 WKURLSchemeHandler：自定义 scheme 的页面没有正常的同源和
/// 同步 XHR 行为，而原版页面 200 多处调用都是同步取值，必须走真 HTTP。
final class LoopbackServer {
    static let shared = LoopbackServer()

    private let queue = DispatchQueue(label: "astrios.loopback", qos: .userInitiated, attributes: .concurrent)
    private let lock = NSLock()
    private var listener: NWListener?
    private var caps: [String] = []

    /// 0 表示没起来
    private(set) var port: Int = 0

    /// 页面与静态资源都从 App 包里取（构建时由 tools/sync_web.py 从 ui/assets/qqbot 同步过来）
    var webRoot: URL {
        (Bundle.main.resourceURL ?? Bundle.main.bundleURL).appendingPathComponent("web", isDirectory: true)
    }

    private init() {}

    // MARK: - 启停

    /// 依次尝试 17870…17899，第一个能绑上的就用。只绑回环，不暴露到局域网。
    @discardableResult
    func start(preferred: Int = 17870, span: Int = 30) -> Int? {
        stop()
        for i in 0...span {
            let p = preferred + i
            guard let nwPort = NWEndpoint.Port(rawValue: UInt16(p)) else { continue }
            if bindOnce(nwPort) {
                port = p
                return p
            }
        }
        return nil
    }

    func stop() {
        lock.lock()
        let l = listener
        listener = nil
        port = 0
        lock.unlock()
        l?.cancel()
    }

    func setCaps(_ methods: [String]) {
        lock.lock()
        caps = methods
        lock.unlock()
    }

    private final class Flag {
        var ready = false
    }

    private func bindOnce(_ nwPort: NWEndpoint.Port) -> Bool {
        // requiredLocalEndpoint 把监听地址钉在 127.0.0.1：
        // 否则 NWListener 默认吃 0.0.0.0，同一 Wi-Fi 下的设备就能访问到这个控制台
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: nwPort)
        params.allowLocalEndpointReuse = true
        guard let l = try? NWListener(using: params) else { return false }

        let flag = Flag()
        let sem = DispatchSemaphore(value: 0)
        l.stateUpdateHandler = { st in
            switch st {
            case .ready:
                flag.ready = true
                sem.signal()
            case .failed, .cancelled:
                sem.signal()
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] conn in
            self?.accept(conn)
        }
        l.start(queue: queue)
        // 单次尝试等 1.2 秒：太短会把「有点慢但没被占」的端口误判成占用，
        // 全长扫描最坏 31×1.2 秒，但 start() 已经在后台队列里跑，不会撞启动看门狗
        if sem.wait(timeout: .now() + 1.2) == .timedOut {
            l.cancel()
            return false
        }
        guard flag.ready else {
            l.cancel()
            return false
        }
        lock.lock()
        listener = l
        lock.unlock()
        return true
    }

    // MARK: - 连接与请求解析

    private final class ConnState {
        var buf = Data()
    }

    private func accept(_ conn: NWConnection) {
        // 连接进 .waiting / .failed 时 receive 不会回调，页面那边的同步 XHR 就会一直挂着，
        // 所以必须自己盯着状态：失败就取消，让浏览器立刻拿到错误。
        conn.stateUpdateHandler = { st in
            switch st {
            case .failed, .cancelled:
                conn.cancel()
            default:
                break
            }
        }
        conn.start(queue: queue)
        receive(conn, ConnState())
    }

    private func receive(_ conn: NWConnection, _ st: ConnState) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { conn.cancel(); return }
            if let data, !data.isEmpty { st.buf.append(data) }
            if error != nil {
                conn.cancel()
                return
            }
            if let req = Self.parse(st.buf) {
                self.route(req, conn)
                return
            }
            if isComplete || st.buf.count > (4 * 1024 * 1024) {
                conn.cancel()
                return
            }
            self.receive(conn, st)
        }
    }

    /// 请求体上限。桥的入参是几个字符串，8MB 已经远超实际需要，超了就别读了。
    static let maxBody = 8 * 1024 * 1024

    private struct Request {
        let method: String
        let path: String
        let body: Data
    }

    /// 攒够一个完整请求就返回；不够返回 nil 继续收
    private static func parse(_ buf: Data) -> Request? {
        guard let headEnd = Self.findHeaderEnd(buf) else { return nil }
        let headData = buf[buf.startIndex..<headEnd]
        guard let head = String(data: headData, encoding: .utf8) else { return nil }
        let lines = head.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true)
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0]).uppercased()
        var path = String(parts[1])

        var contentLength = 0
        for line in lines.dropFirst() {
            guard let idx = line.firstIndex(of: ":") else { continue }
            // 切片是 Substring，先转 String 再用 Foundation 的方法（避免依赖 Substring 上的扩展）
            let k = String(line[line.startIndex..<idx]).trimmingCharacters(in: .whitespaces).lowercased()
            let v = String(line[line.index(after: idx)...]).trimmingCharacters(in: .whitespaces)
            if k == "content-length" {
                // 负数或离谱的大数一律夹紧：否则下面 offsetBy 会越界，index(offsetBy: -1) 直接崩进程
                let n = Int(v) ?? 0
                contentLength = n < 0 ? 0 : min(n, Self.maxBody)
            }
        }

        let bodyStart = headEnd
        let available = buf.distance(from: bodyStart, to: buf.endIndex)
        guard contentLength >= 0, available >= contentLength else { return nil }
        let body = buf[bodyStart..<buf.index(bodyStart, offsetBy: contentLength)]

        if let q = path.firstIndex(of: "?") { path = String(path[path.startIndex..<q]) }
        return Request(method: method, path: path, body: Data(body))
    }

    private static func findHeaderEnd(_ buf: Data) -> Data.Index? {
        let needle = Data([13, 10, 13, 10])
        guard buf.count >= 4 else { return nil }
        guard let r = buf.range(of: needle) else { return nil }
        return r.upperBound
    }

    // MARK: - 路由

    private func route(_ req: Request, _ conn: NWConnection) {
        let path = req.path

        if path == "/__bridge" {
            guard req.method == "POST" else {
                sendText(conn, status: "405 Method Not Allowed", body: "只接受 POST")
                return
            }
            var m = ""
            var args: [Any] = []
            if let obj = try? JSONSerialization.jsonObject(with: req.body) as? [String: Any] {
                m = (obj["m"] as? String) ?? ""
                args = (obj["a"] as? [Any]) ?? []
            }
            guard !m.isEmpty else {
                sendText(conn, status: "400 Bad Request", body: "{\"ok\":false,\"err\":\"缺少方法名\"}")
                return
            }
            // 远端转发可能阻塞几十秒（exec），一定要离开接收队列
            queue.async { [weak self] in
                let reply = BridgeRouter.shared.handle(m, args)
                self?.sendText(conn, status: "200 OK", body: reply)
            }
            return
        }

        if path == "/__caps" {
            lock.lock()
            let list = caps
            lock.unlock()
            let data = (try? JSONSerialization.data(withJSONObject: ["methods": list, "port": port])) ?? Data("{}".utf8)
            sendRaw(conn, status: "200 OK", type: "application/json; charset=utf-8", body: data)
            return
        }

        if path.hasPrefix("/__img/") {
            let name = String(path.dropFirst("/__img/".count))
            serveFile(conn, from: Store.dir, name: name)
            return
        }

        guard req.method == "GET" || req.method == "HEAD" else {
            sendText(conn, status: "405 Method Not Allowed", body: "只接受 GET")
            return
        }

        var name = path
        if name.hasPrefix("/") { name.removeFirst() }
        if name.isEmpty { name = "index.html" }
        serveFile(conn, from: webRoot, name: name)
    }

    /// 静态文件。做两件事：挡路径穿越、认常见的 mime（页面本身是单文件，主要就是 png）。
    private func serveFile(_ conn: NWConnection, from root: URL, name: String) {
        let decoded = name.removingPercentEncoding ?? name
        guard !decoded.contains(".."), !decoded.hasPrefix("/") else {
            sendText(conn, status: "403 Forbidden", body: "路径不对")
            return
        }
        let target = root.appendingPathComponent(decoded).standardizedFileURL
        let rootStd = root.standardizedFileURL
        guard target.path == rootStd.path || target.path.hasPrefix(rootStd.path + "/") else {
            sendText(conn, status: "403 Forbidden", body: "路径不对")
            return
        }
        var fileURL = target
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDir), !isDir.boolValue else {
            sendText(conn, status: "404 Not Found", body: "没有这个文件：" + decoded)
            return
        }
        if fileURL.pathExtension.isEmpty {
            fileURL = fileURL.appendingPathComponent("index.html")
        }
        guard let data = try? Data(contentsOf: fileURL) else {
            sendText(conn, status: "404 Not Found", body: "读不到文件")
            return
        }
        sendRaw(conn, status: "200 OK", type: Self.mime(fileURL.pathExtension.lowercased()), body: data)
    }

    private static func mime(_ ext: String) -> String {
        switch ext {
        case "html", "htm": return "text/html; charset=utf-8"
        case "js", "mjs":   return "application/javascript; charset=utf-8"
        case "css":         return "text/css; charset=utf-8"
        case "json":        return "application/json; charset=utf-8"
        case "png":         return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp":        return "image/webp"
        case "svg":         return "image/svg+xml"
        case "ico":         return "image/x-icon"
        case "woff2":       return "font/woff2"
        case "ttf":         return "font/ttf"
        case "txt", "log":  return "text/plain; charset=utf-8"
        default:            return "application/octet-stream"
        }
    }

    // MARK: - 发送

    private func sendText(_ conn: NWConnection, status: String, body: String) {
        sendRaw(conn, status: status, type: "text/plain; charset=utf-8", body: Data(body.utf8))
    }

    private func sendRaw(_ conn: NWConnection, status: String, type: String, body: Data) {
        let header = "HTTP/1.1 \(status)\r\n"
            + "Content-Type: \(type)\r\n"
            + "Content-Length: \(body.count)\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n"
            + "\r\n"
        var out = Data(header.utf8)
        out.append(body)
        conn.send(content: out, completion: .contentProcessed { _ in
            conn.cancel()
        })
    }
}
