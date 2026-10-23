import Foundation
import UIKit
import UserNotifications
import PhotosUI

/// 设备侧实现：通知、剪贴板、相册、头像、背景、外链、省电提示。
/// 宿主沙盒相关的一律由 BridgeRouter 转发远端，不在这里。
enum LocalBridge {
    static func invoke(_ name: String, _ a: [Any]) -> String {
        switch name {
        case "toast":
            ToastHub.post(BR.str(a, 0))
            return ""
        case "copy":
            let t = BR.str(a, 0)
            guard !t.isEmpty else { return "" }
            onMainSync { UIPasteboard.general.string = t }
            ToastHub.post("已复制")
            return ""
        case "openUrl":
            return openUrl(BR.str(a, 0))
        case "openQqGroup":
            return openQqGroup(BR.str(a, 0))
        case "openPanel":
            return openPanel(BR.str(a, 0))
        case "disclaimerSeen":
            return Prefs.shared.disclaimerAck ? "1" : "0"
        case "disclaimerAck":
            Prefs.shared.disclaimerAck = true
            return BR.ok()
        case "notiAsk":
            askNoti()
            return ""
        case "notiState":
            return notiState()
        case "powerInfo", "powerState":
            return powerState()
        case "powerGuide":
            let jumped = onMainSync { UIHost.openAppSettings() }
            return BR.js(["ok": true, "jumped": jumped,
                          "msg": jumped ? "已打开系统设置，通知与后台权限都在那里" : "打不开系统设置，请手动进「设置 › AstrBot 烛版」"])
        case "powerAck":
            Prefs.shared.powerAck = true
            return BR.ok()
        case "pickBg":
            BgPickerDelegate.shared.present()
            return ""
        case "bgImage":
            return bgImage()
        case "clearBg":
            try? FileManager.default.removeItem(at: Store.bgFile)
            ToastHub.post("背景图已移除")
            return "ok"
        case "avatarInfo":
            return avatarInfo()
        case "avatarPath":
            return avatarPath(BR.str(a, 0))
        default:
            if name.hasPrefix("stats") || name.hasPrefix("crash") || name.hasPrefix("ann") {
                return StatsBridge.invoke(name, a)
            }
            return ""
        }
    }

    // MARK: - 外链

    private static func openUrl(_ raw: String) -> String {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: s), let sc = u.scheme?.lowercased(), sc == "http" || sc == "https" else {
            return "只能打开 http / https 链接"
        }
        onMainSync { UIApplication.shared.open(u) }
        return "ok"
    }

    /// QQ 群：走 mqqapi 卡片链接，没装 QQ 就回 fail（前端会退化成「复制群号」）
    private static func openQqGroup(_ raw: String) -> String {
        let g = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard g.range(of: "^[0-9]{5,12}$", options: .regularExpression) != nil else { return "fail" }
        guard let u = URL(string: "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=\(g)&card_type=group&source=qrcode") else {
            return "fail"
        }
        let opened = onMainSync { () -> Bool in
            guard UIApplication.shared.canOpenURL(u) else { return false }
            UIApplication.shared.open(u)
            return true
        }
        return opened ? "ok" : "fail"
    }

    private static func openPanel(_ raw: String) -> String {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: s), let sc = u.scheme?.lowercased(), sc == "http" || sc == "https" else {
            ToastHub.post("面板地址无效")
            return ""
        }
        let low = s.lowercased()
        let title = (low.contains("napcat") || low.contains(":6099")) ? "NapCat 面板" : "AstrBot 控制台"
        Shell.openPanel(u, title: title)
        return ""
    }

    // MARK: - 通知

    private static func askNoti() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            KV.setFlag("noti.granted", granted)
            KV.setFlag("noti.asked", true)
            if granted {
                ToastHub.post("通知已允许")
            } else {
                ToastHub.post("通知未允许，可以到系统设置里再开")
            }
        }
    }

    private static func notiState() -> String {
        kickNotiRefresh()
        let ok = KV.flag("noti.granted")
        // granted/asked 是主字段（CONTRACT.md 早期版本），on/enabled 是页面认的那套——两套都发
        return BR.js([
            "ok": true,
            "granted": ok,
            "asked": KV.flag("noti.asked"),
            "on": ok,
            "enabled": ok
        ])
    }

    /// 把系统的真实授权状态刷进缓存。异步，不用等；下次 state() 拿到的就是新的。
    static func kickNotiRefresh() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let ok = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            KV.setFlag("noti.granted", ok)
            if s.authorizationStatus != .notDetermined {
                KV.setFlag("noti.asked", true)
            }
        }
    }

    // MARK: - 省电

    /// Android 版靠指引用户加白名单；iOS 没有这个概念，所以 shown 恒为 true 让顶栏横幅不出现，
    /// 只在「更多」页留一句说明。字段名沿用 Android 契约，前端不用改。
    private static func powerState() -> String {
        let low = ProcessInfo.processInfo.isLowPowerModeEnabled
        let tip = "iOS 由系统统一管理后台，不需要手动加白；若收不到消息，先看「设置 › 通知」和宿主是否在线。"
        return BR.js([
            "ok": true,
            "manufacturer": "Apple",
            "model": UIDevice.current.model,
            "hint": tip,
            "shown": true,
            "lowPower": low,
            "ack": Prefs.shared.powerAck
        ])
    }

    // MARK: - 背景图

    /// 缩到长边 1600 再转 JPEG 0.82——和 Android 侧 AstrCfg 的处理一致，
    /// 免得把几 MB 的原图塞进 localStorage 撑爆配额
    static func ingestBg(_ img: UIImage) {
        let long = max(img.size.width, img.size.height)
        let scale = long > 1600 ? 1600 / long : 1
        let size = CGSize(width: max(1, img.size.width * scale), height: max(1, img.size.height * scale))
        let out: UIImage? = onMainSync {
            let fmt = UIGraphicsImageRendererFormat.default()
            fmt.scale = 1
            fmt.opaque = true
            return UIGraphicsImageRenderer(size: size, format: fmt).image { _ in
                img.draw(in: CGRect(origin: .zero, size: size))
            }
        }
        guard let out, let data = out.jpegData(compressionQuality: 0.82) else {
            ToastHub.post("这张图处理失败，换一张试试")
            return
        }
        do {
            try data.write(to: Store.bgFile, options: .atomic)
            ToastHub.post("背景图已更新")
        } catch {
            ToastHub.post("背景图保存失败：\(error.localizedDescription)")
        }
    }

    private static func bgImage() -> String {
        guard let d = Store.dataURL(Store.bgFile, mime: "image/jpeg") else {
            return BR.fail("没有自定义背景")
        }
        return BR.js(["ok": true, "data": d])
    }

    // MARK: - 头像

    private static let avQueue = DispatchQueue(label: "astrios.avatar")
    private static var avInflight = Set<String>()

    /// 前端用法：r.ok && r.uin && r.data，data 直接当 img.src，所以必须是 data URL
    private static func avatarInfo() -> String {
        let uin = currentUin()
        guard !uin.isEmpty else { return BR.fail("还没有取到 QQ 号") }
        if let d = Store.dataURL(Store.avatarFile(uin), mime: "image/png") {
            return BR.js(["ok": true, "uin": uin, "data": d])
        }
        kickAvatar(uin)
        return BR.fail("头像正在下载")
    }

    private static func avatarPath(_ raw: String) -> String {
        let uin = raw.isEmpty ? currentUin() : raw
        guard !uin.isEmpty else { return BR.fail("还没有取到 QQ 号") }
        let name = "avatar-\(uin.filter { $0.isNumber }).png"
        let file = Store.dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: file.path) {
            return BR.js(["ok": true, "path": imageURL(name), "cached": true])
        }
        kickAvatar(uin)
        return BR.js(["ok": false, "path": "", "cached": false, "err": "头像正在下载"])
    }

    private static func currentUin() -> String {
        let u = Prefs.shared.cachedUin
        return u.isEmpty ? KV.str("uin") : u
    }

    /// 头像走回环服务的 /__img/：页面是 http://127.0.0.1:x 起源，
    /// 直接给 file:// 会被 WKWebView 拦掉（Android 版没这个问题，WebView 允许 file 互访）
    static func imageURL(_ name: String) -> String {
        let p = LoopbackServer.shared.port
        if p > 0 { return "http://127.0.0.1:\(p)/__img/\(name)" }
        return "file://" + Store.dir.appendingPathComponent(name).path
    }

    private static func kickAvatar(_ uin: String) {
        guard let url = URL(string: "https://q1.qlogo.cn/g?b=qq&nk=\(uin)&s=100") else { return }
        let queued: Bool = avQueue.sync {
            if avInflight.contains(uin) { return false }
            avInflight.insert(uin)
            return true
        }
        guard queued else { return }
        var req = URLRequest(url: url)
        req.timeoutInterval = 12
        URLSession.shared.dataTask(with: req) { data, _, _ in
            defer { _ = avQueue.sync { avInflight.remove(uin) } }
            guard let data, !data.isEmpty, let img = UIImage(data: data) else { return }
            let path = Store.avatarFile(uin)
            if let png = img.pngData() {
                try? png.write(to: path, options: .atomic)
            } else {
                try? data.write(to: path, options: .atomic)
            }
        }.resume()
    }
}

/// 相册选背景。PHPicker 不需要相册写权限，只有用户选中的那张会给我们。
/// 不用 SwiftUI 的 sheet：PHPicker 自己会 dismiss，走 UIKit 直接呈现更省事。
private final class BgPickerDelegate: NSObject, PHPickerViewControllerDelegate {
    static let shared = BgPickerDelegate()

    func present() {
        onMainSync {
            var cfg = PHPickerConfiguration(photoLibrary: .shared())
            cfg.filter = .images
            cfg.selectionLimit = 1
            cfg.preferredAssetRepresentationMode = .current
            let vc = PHPickerViewController(configuration: cfg)
            vc.delegate = BgPickerDelegate.shared
            UIHost.present(vc)
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let p = results.first?.itemProvider, p.canLoadObject(ofClass: UIImage.self) else { return }
        p.loadObject(ofClass: UIImage.self) { obj, _ in
            guard let img = obj as? UIImage else { return }
            LocalBridge.ingestBg(img)
        }
    }
}
