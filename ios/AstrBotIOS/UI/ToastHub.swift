import SwiftUI

/// 应用内 HUD。Android 版是 `Toast.makeText`，iOS 没有这个 API，
/// 所以自己画一条：不阻塞、1.8 秒自动消失、叠在 WebView 之上（不抢焦点）。
@MainActor
final class ToastHub: ObservableObject {
    static let shared = ToastHub()

    struct Item: Identifiable, Equatable {
        let id = UUID()
        let text: String
    }

    @Published private(set) var current: Item?
    private var hideTask: Task<Void, Never>?

    func show(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        // 桥里的 toast 文案可能很长（比如 exec 的输出），截一下，别糊满屏幕
        let shown = t.count > 120 ? String(t.prefix(120)) + "…" : t
        let item = Item(text: shown)
        current = item
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard !Task.isCancelled, let self else { return }
            if self.current?.id == item.id { self.current = nil }
        }
    }

    /// 桥调用都在后台队列上，统一用这个入口，省得每处都写 Task { @MainActor in }
    nonisolated static func post(_ text: String) {
        Task { @MainActor in ToastHub.shared.show(text) }
    }
}

struct ToastOverlay: View {
    @ObservedObject private var hub = ToastHub.shared

    var body: some View {
        VStack {
            Spacer()
            if let item = hub.current {
                Text(item.text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                            .fill(Theme.ink.opacity(0.92))
                    )
                    .padding(.horizontal, 40)
                    .padding(.bottom, 96)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.18), value: hub.current)
        .allowsHitTesting(false)
    }
}
