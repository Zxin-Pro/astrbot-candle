import SwiftUI

@main
struct AstrBotApp: App {
    @StateObject private var env = AppEnv.shared

    var body: some Scene {
        WindowGroup {
            RootView(env: env)
                .environmentObject(Shell.shared)
                .preferredColorScheme(.light)
                .onAppear { boot() }
        }
    }

    /// View 只有 body 带 @MainActor（类型级隔离是 Xcode 16 才有的），
    /// 所以直接写 env.showOnboarding = true 可能报「main actor-isolated property 被非隔离上下文修改」。
    @MainActor
    private func boot() {
        if !env.onboarded {
            env.showOnboarding = true
        }
        if env.loopReady { return }
        // 端口扫描是逐个 bind 试的，最坏能拖十几秒；放在主线程会撞上启动看门狗
        // （表现为点开图标闪退、没有任何崩溃栈），所以整段丢到后台队列。
        DispatchQueue.global(qos: .userInitiated).async {
            let p = LoopbackServer.shared.start()
            // 用 Task { @MainActor } 而不是 DispatchQueue.main.async：
            // 后者的闭包不带主线程隔离，改 @Published 会被判成跨隔离域写
            Task { @MainActor in
                if let p = p {
                    env.loopPort = p
                    env.loopReady = true
                    LoopbackServer.shared.setCaps(BridgeRouter.shared.availableMethods(hasHost: env.hasHost))
                } else {
                    env.loopReady = false
                    ToastHub.post("本机控制台服务没能启动，这个 App 暂时用不了")
                }
            }
        }
    }
}
