import SwiftUI
import UIKit

/// 第一次打开：说清楚 iOS 版是「远程控制台」，不是把 AstrBot 跑在手机上。
struct OnboardingView: View {
    @ObservedObject var env: AppEnv
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var portText = "17880"
    @State private var token = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("AstrBot iOS 版")
                            .font(.system(size: 26, weight: .semibold))
                            .foregroundStyle(Theme.ink)
                        Text("iOS 不允许在手机上开 Linux 沙盒，所以这一版是控制台：界面上所有按钮、日志、"
                             + "机器人管理都还在，只是真正跑 AstrBot 的那台机器要在别处。")
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.ink2)
                            .lineSpacing(5)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        bullet("1", "在跑 AstrBot 的机器上启动 ios/remote-agent/astrbot_bridge_agent.py")
                        bullet("2", "把它的地址、端口（和令牌，如果在用）填到下面")
                        bullet("3", "同一局域网直接填内网 IP；跨网络请先建 SSH 隧道")
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        row("地址", $host, "192.168.1.8", keyboard: .URL)
                        row("端口", $portText, "17880", keyboard: .numberPad)
                        row("令牌", $token, "可留空", keyboard: .default)
                    }

                    Button {
                        env.save(host: host, port: Int(portText) ?? 17880, token: token)
                        env.setOnboarded()
                        dismiss()
                    } label: {
                        Text("开始使用")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous).fill(Theme.primary))
                            .foregroundStyle(.white)
                    }

                    Button("先看看界面（不连接）") {
                        env.setOnboarded()
                        dismiss()
                    }
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.ink2)
                    .frame(maxWidth: .infinity)
                }
                .padding(24)
            }
            .background(Theme.bg.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("跳过") {
                        env.setOnboarded()
                        dismiss()
                    }
                    .foregroundStyle(Theme.ink2)
                }
            }
        }
    }

    private func bullet(_ n: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(n)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Theme.primary.opacity(0.85)))
            Text(text)
                .font(.system(size: 14))
                .foregroundStyle(Theme.ink2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func row(_ title: String, _ text: Binding<String>, _ ph: String,
                     keyboard: UIKeyboardType) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.ink)
                .frame(width: 48, alignment: .leading)
            TextField(ph, text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 15))
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 12)
                .frame(height: 44)
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(.white))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.line, lineWidth: 1))
                .accessibilityLabel(title)
        }
    }
}
