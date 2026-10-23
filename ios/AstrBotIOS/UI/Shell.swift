import SwiftUI

/// 一个要弹出来的远端页面（NapCat 面板 / AstrBot 控制台）
struct PanelRequest: Identifiable {
    let id = UUID()
    let url: URL
    let title: String
}

/// 桥调用发生在回环服务的后台队列上，不能直接改 SwiftUI 状态，
/// 所以统一投递到这里，由界面层观察。
@MainActor
final class Shell: ObservableObject {
    static let shared = Shell()

    @Published var panel: PanelRequest?

    private init() {}

    nonisolated static func openPanel(_ url: URL, title: String) {
        Task { @MainActor in Shell.shared.panel = PanelRequest(url: url, title: title) }
    }
}
