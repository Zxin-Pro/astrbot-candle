import Foundation
import SwiftUI

/// 本机偏好。桥里所有「设置类」方法都落到这里，键名与 Android 版
/// `$QQBOT_HOME/state/prefs.json` 的语义一一对应（前缀换成 `ios.`），方便两边对照排查。
final class Prefs {
    static let shared = Prefs()

    private let d = UserDefaults.standard

    // MARK: 宿主连接
    /// 远端代理的主机名/IP，可带 scheme（`192.168.1.5`、`https://box.example.com`）
    var host: String {
        get { d.string(forKey: "ios.host") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "ios.host") }
    }
    var port: Int {
        get { d.object(forKey: "ios.port") as? Int ?? 17880 }
        set { d.set(newValue, forKey: "ios.port") }
    }
    /// 可选访问令牌，对应代理的 `--token`，走 `X-AstrBot-Key` 头
    var token: String {
        get { d.string(forKey: "ios.token") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "ios.token") }
    }
    /// 有没有配过宿主（分派表和界面都问这一句）
    var hasHost: Bool { !host.isEmpty }

    /// 首次引导是否看过（1.0 只弹一次，之后从右下角状态球进设置）
    var onboarded: Bool {
        get { d.bool(forKey: "ios.onboarded") }
        set { d.set(newValue, forKey: "ios.onboarded") }
    }

    // MARK: 免责声明 / 省电提示
    var disclaimerAck: Bool {
        get { d.bool(forKey: "qqbot.ios.disclaimer") }
        set { d.set(newValue, forKey: "qqbot.ios.disclaimer") }
    }
    var powerAck: Bool {
        get { d.bool(forKey: "qqbot.ios.powerack") }
        set { d.set(newValue, forKey: "qqbot.ios.powerack") }
    }

    // MARK: 匿名统计 / 崩溃上报（默认关，关着不发任何请求）
    var statsOn: Bool {
        get { d.bool(forKey: "qqbot.ios.stats.on") }
        set { d.set(newValue, forKey: "qqbot.ios.stats.on") }
    }
    var statsId: String {
        get { d.string(forKey: "qqbot.ios.stats.id") ?? "" }
        set { d.set(newValue, forKey: "qqbot.ios.stats.id") }
    }
    var statsLastSentDay: String {
        get { d.string(forKey: "qqbot.ios.stats.day") ?? "" }
        set { d.set(newValue, forKey: "qqbot.ios.stats.day") }
    }
    var crashReport: Bool {
        get { d.bool(forKey: "qqbot.ios.crash.on") }
        set { d.set(newValue, forKey: "qqbot.ios.crash.on") }
    }

    // MARK: 缓存
    var annCache: String {
        get { d.string(forKey: "qqbot.ios.ann.cache") ?? "" }
        set { d.set(newValue, forKey: "qqbot.ios.ann.cache") }
    }
    var annAt: Double {
        get { d.double(forKey: "qqbot.ios.ann.at") }
        set { d.set(newValue, forKey: "qqbot.ios.ann.at") }
    }
    /// 上次连接的宿主给的 uin，用来取 QQ 头像（宿主没连上时也不至于没头像）
    var cachedUin: String {
        get { d.string(forKey: "qqbot.ios.uin") ?? "" }
        set { d.set(newValue, forKey: "qqbot.ios.uin") }
    }
}

/// 全局运行态：宿主地址、回环端口、连接健康度。
/// Swift 5 语言模式下不强制 Sendable，但这里所有写入都走主线程，避免脏读。
@MainActor
final class AppEnv: ObservableObject {
    static let shared = AppEnv()

    @Published var host: String
    @Published var port: Int
    @Published var token: String
    /// 本次启动选定的回环端口（服务起来之后才有效）
    @Published var loopPort: Int = 0
    @Published var loopReady: Bool = false
    /// 宿主探活结果：nil = 还没测过
    @Published var hostOnline: Bool?
    @Published var hostNote: String = ""
    @Published var showSettings: Bool = false
    @Published var showOnboarding: Bool = false
    /// 每加一就让 WKWebView 重新载入（换了宿主、换了方法表都得重来）
    @Published var reloadKey: Int = 0
    /// 当前挂在 window.Android 上的方法名，回环服务的 /__caps 也从这里取
    @Published var caps: [String] = []

    private init() {
        host = Prefs.shared.host
        port = Prefs.shared.port
        token = Prefs.shared.token
    }

    var hasHost: Bool { !host.isEmpty }

    /// 首次引导是否已过（读 UserDefaults，不占内存态）
    var onboarded: Bool { Prefs.shared.onboarded }

    /// 远端代理的基地址。允许用户只填 IP，也允许填完整 URL（带 scheme 或带路径）。
    var remoteBaseURL: URL? {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if h.isEmpty { return nil }
        if !h.lowercased().hasPrefix("http://") && !h.lowercased().hasPrefix("https://") {
            h = "http://" + h
        }
        guard var c = URLComponents(string: h) else { return nil }
        if c.port == nil { c.port = port }
        if c.path.isEmpty { c.path = "" }
        return c.url
    }

    var loopBase: String { "http://127.0.0.1:\(loopPort)" }

    func save(host: String, port: Int, token: String) {
        self.host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        self.port = port
        self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        Prefs.shared.host = self.host
        Prefs.shared.port = port
        Prefs.shared.token = self.token
        refreshCaps()
    }

    /// 重算可用的桥方法：没连宿主时远端那批不挂，页面自己会走「这个安装包还不支持」的降级分支。
    func refreshCaps() {
        caps = BridgeRouter.shared.availableMethods(hasHost: hasHost)
        if loopReady {
            LoopbackServer.shared.setCaps(caps)
        }
        reloadKey += 1
    }

    func setOnboarded() {
        Prefs.shared.onboarded = true
        showOnboarding = false
    }
}

/// AGENTS.md 里的设计令牌，原生这层用同一套色，免得和网页控制台两种蓝。
enum Theme {
    static let primary = Color(hex: 0x2563EB)
    static let warm = Color(hex: 0xEA580C)
    static let ink = Color(hex: 0x0F172A)
    static let ink2 = Color(hex: 0x475569)
    static let line = Color(hex: 0xE2E8F0)
    static let bg = Color(hex: 0xF7F9FC)
    static let card = Color.white
    static let radius: CGFloat = 12
    static let radiusCard: CGFloat = 16
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}
