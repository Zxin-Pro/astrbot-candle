import UIKit

/// 从后台队列里弹 UIKit 界面的统一入口。
/// 桥调用发生在回环服务的后台线程上，UIKit 一定要回主线程，所以都收在这里。
enum UIHost {
    static var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
    }

    static var root: UIViewController? {
        var vc = keyWindow?.rootViewController
        while let p = vc?.presentedViewController { vc = p }
        return vc
    }

    /// 返回「这次真的递上去了」。
    /// WebKit 的 JS 对话框回调必须被调用一次，否则整页 JS 会永久挂起；
    /// 调用方拿到 false 就该立刻把 completionHandler 放掉，而不是干等。
    @discardableResult
    static func present(_ vc: UIViewController) -> Bool {
        var done = false
        let work = {
            guard let top = root else { return }
            // 已经压着一个弹窗时再 present 会被系统静默丢掉，这里直接认失败
            if top is UIAlertController { return }
            // 转场中或已有弹层时 UIKit 会静默丢弃这次 present，回调却不会来
            if top.presentedViewController != nil { return }
            top.present(vc, animated: true)
            done = true
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.sync(execute: work)
        }
        return done
    }

    static func alert(_ title: String, _ message: String, ok: String = "好") {
        DispatchQueue.main.async {
            guard let top = root else { return }
            let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: ok, style: .default))
            top.present(a, animated: true)
        }
    }

    /// 打开系统「设置」里的本应用页（iOS 不允许直达某个开关）。
    /// 这里不能再拿 canOpenURL 当门禁：`app-settings:` 不在 LSApplicationQueriesSchemes 里，
    /// canOpenURL 会一路 false，于是「既说打不开、又真的没跳」。open(_:) 本身不需要白名单，
    /// 真失败会在 completionHandler 里回 false，那时再提示。
    @discardableResult
    static func openAppSettings() -> Bool {
        guard let u = URL(string: UIApplication.openSettingsURLString) else { return false }
        var done = false
        let work = {
            UIApplication.shared.open(u, options: [:]) { ok in
                if !ok { ToastHub.post("打不开系统设置，请手动进「设置 › AstrBot 烛版」") }
            }
            done = true
        }
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.sync(execute: work)
        }
        return done
    }
}

/// 磁盘上的小存储：背景图、头像缓存。
enum Store {
    static var dir: URL {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("astrios", isDirectory: true)
        if !fm.fileExists(atPath: base.path) {
            try? fm.createDirectory(at: base, withIntermediateDirectories: true)
        }
        return base
    }

    static var bgFile: URL { dir.appendingPathComponent("bg.jpg") }

    static func avatarFile(_ uin: String) -> URL {
        dir.appendingPathComponent("avatar-\(uin.filter { $0.isNumber }).png")
    }

    static func readBase64(_ url: URL) -> String? {
        guard let d = try? Data(contentsOf: url), !d.isEmpty else { return nil }
        return d.base64EncodedString()
    }

    static func dataURL(_ url: URL, mime: String) -> String? {
        guard let b = readBase64(url) else { return nil }
        return "data:\(mime);base64,\(b)"
    }
}
