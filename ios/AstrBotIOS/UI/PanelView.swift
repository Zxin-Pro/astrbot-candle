import SwiftUI
import UIKit
import WebKit

/// 内嵌 WebUI 弹层：点主页的「NapCat 面板 / AstrBot 控制台」时用。
/// Android 版是起一个 Activity 里的 WebView，这里是一个 sheet。
struct PanelView: View {
    let url: URL
    let title: String
    @Environment(\.dismiss) private var dismiss
    @State private var progress: Double = 0

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                PanelWebView(url: url, progress: $progress)
                if progress < 1 {
                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                        .tint(Theme.primary)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(Theme.primary)
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        UIApplication.shared.open(url)
                    } label: {
                        Image(systemName: "safari")
                    }
                    .accessibilityLabel("用 Safari 打开")
                }
            }
        }
    }
}

private struct PanelWebView: UIViewRepresentable {
    let url: URL
    @Binding var progress: Double

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.navigationDelegate = context.coordinator
        wv.allowsBackForwardNavigationGestures = true
        wv.load(URLRequest(url: url))
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(progress: $progress) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let progress: Binding<Double>
        private var obs: NSKeyValueObservation?
        init(progress: Binding<Double>) { self.progress = progress }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            obs = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
                DispatchQueue.main.async { self?.progress.wrappedValue = wv.estimatedProgress }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            progress.wrappedValue = 1
            obs = nil
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            progress.wrappedValue = 1
        }

        /// 连不上（域名解析失败、端口没开）时不会走 didFail，只会走这个：
        /// 不补上进度条就永远停在半路，看起来像页面卡死。
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            progress.wrappedValue = 1
            obs = nil
        }
    }
}
