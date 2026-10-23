import SwiftUI
import UIKit
import WebKit

/// 原版控制台。页面一点都不改：由 App 内回环服务原样喂出来，
/// 再用 documentStart 注入的垫片补上 window.Android。
struct ConsoleView: UIViewRepresentable {
    /// 例如 http://127.0.0.1:17870
    let base: String
    /// 当前可用的桥方法名（决定了 window.Android 上挂哪些函数）
    let methods: [String]
    /// 变了就重建脚本并重新载入
    let reloadKey: Int

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []

        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.navigationDelegate = context.coordinator
        wv.uiDelegate = context.coordinator
        wv.allowsBackForwardNavigationGestures = false
        wv.scrollView.contentInsetAdjustmentBehavior = .never
        wv.scrollView.keyboardDismissMode = .interactive
        wv.isOpaque = false
        wv.backgroundColor = .clear
        context.coordinator.apply(to: wv, base: base, methods: methods)
        context.coordinator.appliedKey = reloadKey
        return wv
    }

    func updateUIView(_ wv: WKWebView, context: Context) {
        guard context.coordinator.appliedKey != reloadKey else { return }
        context.coordinator.appliedKey = reloadKey
        context.coordinator.apply(to: wv, base: base, methods: methods)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var appliedKey = -1

        func apply(to wv: WKWebView, base: String, methods: [String]) {
            let ucc = wv.configuration.userContentController
            ucc.removeAllUserScripts()
            let js = WebInject.script(base: base, methods: methods)
            ucc.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true))
            if let u = URL(string: base + "/") {
                wv.load(URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData))
            }
        }

        // MARK: 只让回环地址在主窗口里导航，其余一律交出去

        private func isLoopback(_ url: URL) -> Bool {
            guard url.scheme?.lowercased() == "http" else { return false }
            guard let h = url.host else { return false }
            return h == "127.0.0.1" || h == "localhost"
        }

        func webView(_ webView: WKWebView,
                     decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else {
                decisionHandler(.allow)
                return
            }
            // 主文档就是回环服务本身，放行
            if isLoopback(url) && action.targetFrame?.isMainFrame == true {
                decisionHandler(.allow)
                return
            }
            // 新窗口 / 外链：用内置面板打开，页面不跳走
            if action.targetFrame == nil || !isLoopback(url) {
                decisionHandler(.cancel)
                openExternally(url)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView,
                     createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = navigationAction.request.url { openExternally(url) }
            return nil
        }

        private func openExternally(_ url: URL) {
            if let sc = url.scheme?.lowercased(), sc == "http" || sc == "https" {
                Shell.openPanel(url, title: url.host ?? "网页")
            } else {
                DispatchQueue.main.async { UIApplication.shared.open(url) }
            }
        }

        func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
            let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "好", style: .default) { _ in completionHandler() })
            // 弹不出来也要把回调放掉：JS 的 alert 会一直等这个回调，漏一次整页卡死
            if !UIHost.present(a) { completionHandler() }
        }
    }
}

/// 注入的脚本：先写好桥的地址与方法表，再贴垫片本体。
/// 垫片源码只有一份（ios/shared/AndroidShim.js），构建时拷进 App 包。
enum WebInject {
    static func script(base: String, methods: [String]) -> String {
        let list = methods.map { "\"" + esc($0) + "\"" }.joined(separator: ",")
        let head = "window.__ASTRIOS__ = { base: \"" + esc(base) + "\", methods: [" + list + "] };"
        return head + "\n" + (shimSource() ?? embeddedShim)
    }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
    }

    private static func shimSource() -> String? {
        guard let root = Bundle.main.resourceURL else { return nil }
        let u = root.appendingPathComponent("shared/AndroidShim.js")
        return try? String(contentsOf: u, encoding: .utf8)
    }

    /// 万一包里的文件漏了，用这份等价的内联版兜底，保证桥一定能通
    private static let embeddedShim = """
    (function () {
      var C = window.__ASTRIOS__ || { base: "", methods: [] };
      var A = {};
      function call(name, args) {
        try {
          var xhr = new XMLHttpRequest();
          xhr.open("POST", C.base + "/__bridge", false);
          xhr.setRequestHeader("Content-Type", "text/plain;charset=UTF-8");
          xhr.send(JSON.stringify({ m: name, a: args || [] }));
          if (xhr.status !== 200) return "";
          return String(xhr.responseText || "");
        } catch (e) { return ""; }
      }
      for (var i = 0; i < C.methods.length; i++) {
        (function (n) {
          A[n] = function () { return call(n, Array.prototype.slice.call(arguments)); };
        })(C.methods[i]);
      }
      window.Android = A;
      window.__ASTRIOS_BRIDGE__ = call;
    })();
    """
}
