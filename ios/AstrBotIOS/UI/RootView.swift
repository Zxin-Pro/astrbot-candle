import SwiftUI

struct RootView: View {
    @ObservedObject var env: AppEnv
    @ObservedObject private var shell = Shell.shared

    /// 同一时刻只会有一个弹层。分开挂三个 .sheet 到同一个节点上，
    /// SwiftUI 会互相顶掉（先弹的那个被后弹的静默取消），所以合成一个枚举。
    private enum Sheet: Identifiable {
        case panel(PanelRequest)
        case settings
        case onboarding

        var id: String {
            switch self {
            case .panel(let p): return "panel-" + p.id.uuidString
            case .settings: return "settings"
            case .onboarding: return "onboarding"
            }
        }
    }

    @State private var sheet: Sheet?

    var body: some View {
        ZStack(alignment: .top) {
            Theme.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                StatusStrip(env: env)
                Divider().overlay(Theme.line)
                if env.loopReady {
                    ConsoleView(
                        base: env.loopBase,
                        methods: BridgeRouter.shared.availableMethods(hasHost: env.hasHost),
                        reloadKey: env.reloadKey
                    )
                    .ignoresSafeArea(.container, edges: .bottom)
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("正在启动本机控制台服务…")
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.ink2)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }

            ToastOverlay()
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .panel(let p):
                PanelView(url: p.url, title: p.title)
            case .settings:
                SettingsView(env: env)
            case .onboarding:
                OnboardingView(env: env)
            }
        }
        .onChange(of: env.hasHost) { _ in env.refreshCaps() }
        .onChange(of: env.host) { _ in env.refreshCaps() }
        // 三个来源（桥投递的面板、状态栏齿轮、首次启动引导）都汇到 pull()
        // PanelRequest 没有 Equatable：拿 id 当观测键，行为一样
        .onChange(of: shell.panel?.id) { _ in pull() }
        .onChange(of: env.showSettings) { _ in pull() }
        .onChange(of: env.showOnboarding) { _ in pull() }
        // 上一个弹层关掉后再拉一次：pull() 在 sheet 非空时会原样保留来源标记
        .onChange(of: sheet?.id) { _ in pull() }
        .onAppear { pull() }
    }

    /// 把任何一处「想弹东西」的意图收进唯一的 sheet，
    /// 并且立刻把来源标记清掉——这样下次再点同一个按钮还能再弹。
    @MainActor
    private func pull() {
        guard sheet == nil else { return }
        if let p = shell.panel {
            shell.panel = nil
            sheet = .panel(p)
            return
        }
        if env.showSettings {
            env.showSettings = false
            sheet = .settings
            return
        }
        if env.showOnboarding {
            env.showOnboarding = false
            sheet = .onboarding
        }
    }
}

/// 顶栏：只放 iOS 特有的三件事——宿主连没连上、改设置、重载页面。
/// 页面自己的头部不重复做这些。
private struct StatusStrip: View {
    @ObservedObject var env: AppEnv

    private var dotColor: Color {
        if !env.hasHost { return Theme.ink2.opacity(0.45) }
        switch env.hostOnline {
        case .some(true): return Theme.primary
        case .some(false): return Theme.warm
        case .none: return Theme.ink2.opacity(0.45)
        }
    }

    private var label: String {
        if !env.hasHost { return "还没有连接宿主" }
        switch env.hostOnline {
        case .some(true): return env.hostNote.isEmpty ? "宿主在线" : env.hostNote
        case .some(false): return "宿主连不上"
        case .none: return "未测试"
        }
    }

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(dotColor)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.ink2)
                .lineLimit(1)
            Spacer(minLength: 8)
            Button {
                env.reloadKey += 1
            } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 15, weight: .medium))
            }
            .foregroundStyle(Theme.ink2)
            .accessibilityLabel("重新载入控制台")
            Button {
                env.showSettings = true
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 15, weight: .medium))
            }
            .foregroundStyle(Theme.primary)
            .accessibilityLabel("打开宿主连接设置")
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
        .background(.white)
    }
}
