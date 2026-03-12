import SwiftUI
import UIKit

@MainActor
final class AppViewModel: ObservableObject {
    @Published var host: String = "192.168.1.10"
    @Published var portText: String = "9527"
    @Published var password: String = "changeme"

    @Published var statusText: String = "未连接"
    @Published var errorText: String = ""
    @Published var capturedImage: UIImage?

    private let client = TCPClient()

    init() {
        client.onError = { [weak self] err in
            Task { @MainActor in
                self?.errorText = err
                self?.statusText = "连接异常"
            }
        }

        client.onMessage = { [weak self] msg in
            Task { @MainActor in
                self?.handleServerMessage(msg)
            }
        }
    }

    func connect() {
        errorText = ""
        guard let port = UInt16(portText) else {
            errorText = "端口无效"
            return
        }
        statusText = "连接中..."
        client.connect(host: host, port: port) { [weak self] in
            Task { @MainActor in
                self?.statusText = "已连接"
            }
        }
    }

    func disconnect() {
        client.disconnect()
        statusText = "已断开"
    }

    func auth() {
        client.send(.auth(password: password))
    }

    func capture() {
        let reqId = UUID().uuidString
        client.send(.capture(requestId: reqId, quality: 75, displayId: 1))
    }

    private func handleServerMessage(_ msg: ServerMessage) {
        switch msg.type.uppercased() {
        case "AUTH_OK":
            statusText = "鉴权成功"
        case "IMAGE":
            if let b64 = msg.imageBase64,
               let data = Data(base64Encoded: b64),
               let image = UIImage(data: data) {
                capturedImage = image
                statusText = "截图已更新"
            } else {
                errorText = "收到 IMAGE 但图片解析失败"
            }
        case "ERROR":
            let code = msg.code ?? "UNKNOWN"
            let text = msg.message ?? "未知错误"
            errorText = "\(code): \(text)"
        default:
            break
        }
    }
}

struct ContentView: View {
    @StateObject private var vm = AppViewModel()
    @State private var showingQRScanner = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    GroupBox("连接配置") {
                        VStack(spacing: 12) {
                            Button("扫描二维码连接") {
                                showingQRScanner = true
                            }
                            .buttonStyle(.borderedProminent)
                            .frame(maxWidth: .infinity)

                            TextField("Windows IP", text: $vm.host)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .textFieldStyle(.roundedBorder)

                            TextField("端口", text: $vm.portText)
                                .keyboardType(.numberPad)
                                .textFieldStyle(.roundedBorder)

                            SecureField("口令", text: $vm.password)
                                .textFieldStyle(.roundedBorder)

                            HStack(spacing: 10) {
                                Button("连接") { vm.connect() }
                                    .buttonStyle(.borderedProminent)
                                Button("断开") { vm.disconnect() }
                                    .buttonStyle(.bordered)
                                Button("鉴权") { vm.auth() }
                                    .buttonStyle(.bordered)
                            }
                        }
                        .padding(.top, 6)
                    }

                    GroupBox("操作") {
                        VStack(alignment: .leading, spacing: 12) {
                            Button("请求截图") { vm.capture() }
                                .buttonStyle(.borderedProminent)

                            Text("状态: \(vm.statusText)")
                                .font(.subheadline)

                            if !vm.errorText.isEmpty {
                                Text("错误: \(vm.errorText)")
                                    .font(.footnote)
                                    .foregroundStyle(.red)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)
                    }

                    GroupBox("预览") {
                        if let image = vm.capturedImage {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else {
                            Text("暂无截图")
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, minHeight: 180)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("PrepPro iOS")
        }
        .sheet(isPresented: $showingQRScanner) {
            QRScannerView { scanned in
                guard let data = scanned.data(using: .utf8),
                      let info = try? JSONDecoder().decode(QRConnectInfo.self, from: data)
                else { return }
                vm.host = info.ip
                vm.portText = String(info.port)
                if let pw = info.password { vm.password = pw }
                vm.connect()
            }
        }
    }
}
