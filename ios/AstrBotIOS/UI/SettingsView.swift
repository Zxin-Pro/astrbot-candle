import SwiftUI
import UIKit

/// 宿主连接设置。iOS 版是远程控制台，所以这里填的是「跑 AstrBot 的那台机器」。
struct SettingsView: View {
    @ObservedObject var env: AppEnv
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var portText = ""
    @State private var token = ""
    @State private var testing = false
    @State private var result: (Bool, String)? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    card {
                        VStack(alignment: .leading, spacing: 16) {
                            field("宿主地址", hint: "跑 AstrBot 的那台机器的 IP 或域名", text: $host, keyboard: .URL)
                            field("代理端口", hint: "astrbot_bridge_agent.py 启动时用的端口", text: $portText, keyboard: .numberPad)
                            field("访问令牌", hint: "代理启动时如果加了 --token 就填在这里", text: $token, keyboard: .default)
                        }
                    }

                    if let r = result {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: r.0 ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                .foregroundStyle(r.0 ? Theme.primary : Theme.warm)
                            Text(r.1)
                                .font(.system(size: 14))
                                .foregroundStyle(Theme.ink2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal, 4)
                    }

                    VStack(spacing: 12) {
                        Button {
                            saveAndTest()
                        } label: {
                            HStack(spacing: 8) {
                                if testing { ProgressView().tint(.white) }
                                Text(testing ? "正在连接…" : "保存并测试连接")
                                    .font(.system(size: 16, weight: .semibold))
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous).fill(Theme.primary))
                            .foregroundStyle(.white)
                        }
                        .disabled(testing)
                        .accessibilityLabel("保存并测试连接")

                        Button {
                            save()
                            dismiss()
                        } label: {
                            Text("仅保存")
                                .font(.system(size: 16, weight: .medium))
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .background(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous).stroke(Theme.line, lineWidth: 1))
                                .foregroundStyle(Theme.ink)
                        }
                    }

                    card {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("本机回环服务")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Theme.ink)
                            Text(env.loopReady ? "运行中 · 127.0.0.1:\(env.loopPort)" : "没有启动，控制台页面打不开")
                                .font(.system(size: 13))
                                .foregroundStyle(Theme.ink2)
                            Text("宿主侧需要跑 ios/remote-agent/astrbot_bridge_agent.py。它只监听宿主本机，"
                                 + "跨网络请自己套 SSH 隧道或反向代理，不要直接把代理暴露到公网。")
                                .font(.system(size: 12))
                                .foregroundStyle(Theme.ink2.opacity(0.9))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                    card {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("数据")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Theme.ink)
                            Button("清除自定义背景图") {
                                try? FileManager.default.removeItem(at: Store.bgFile)
                                ToastHub.post("背景图已清除")
                            }
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.warm)
                            Button("清除头像缓存") {
                                if let items = try? FileManager.default.contentsOfDirectory(at: Store.dir, includingPropertiesForKeys: nil) {
                                    for f in items where f.lastPathComponent.hasPrefix("avatar-") {
                                        try? FileManager.default.removeItem(at: f)
                                    }
                                }
                                ToastHub.post("头像缓存已清除")
                            }
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.warm)
                        }
                    }
                }
                .padding(20)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("宿主连接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }.foregroundStyle(Theme.primary)
                }
            }
            .onAppear {
                host = env.host
                portText = String(env.port)
                token = env.token
                if let on = env.hostOnline { result = (on, env.hostNote) }
            }
        }
    }

    // MARK: - 小件

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        content()
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: Theme.radiusCard, style: .continuous)
                    .fill(Theme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.radiusCard, style: .continuous)
                            .stroke(Theme.line, lineWidth: 1)
                    )
            )
    }

    private func field(_ title: String, hint: String, text: Binding<String>, keyboard: UIKeyboardType) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.ink)
            TextField("", text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 15))
                .foregroundStyle(Theme.ink)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 12)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Theme.bg)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(Theme.line, lineWidth: 1)
                )
                .accessibilityLabel(title)
            Text(hint)
                .font(.system(size: 12))
                .foregroundStyle(Theme.ink2.opacity(0.85))
        }
    }

    // MARK: - 动作

    private func save() {
        let p = Int(portText.trimmingCharacters(in: .whitespaces)) ?? 17880
        env.save(host: host, port: p, token: token)
    }

    private func saveAndTest() {
        save()
        testing = true
        result = nil
        RemoteClient.shared.ping(host: env.host, port: env.port, token: env.token) { ok, note in
            DispatchQueue.main.async {
                testing = false
                result = (ok, note)
                env.hostOnline = ok
                env.hostNote = note
                ToastHub.shared.show(note)
            }
        }
    }
}
