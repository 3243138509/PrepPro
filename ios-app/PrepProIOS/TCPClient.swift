import Foundation
import Network

final class TCPClient {
    enum TCPError: Error {
        case notConnected
        case invalidFrame
    }

    private let queue = DispatchQueue(label: "preppro.ios.tcp")
    private var connection: NWConnection?

    var onMessage: ((ServerMessage) -> Void)?
    var onError: ((String) -> Void)?

    func connect(host: String, port: UInt16, onReady: @escaping () -> Void) {
        let nwHost = NWEndpoint.Host(host)
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            onError?("Invalid port")
            return
        }

        let conn = NWConnection(host: nwHost, port: nwPort, using: .tcp)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                onReady()
                self?.receiveLoop()
            case .failed(let err):
                self?.onError?("Connection failed: \(err.localizedDescription)")
            case .cancelled:
                self?.onError?("Connection closed")
            default:
                break
            }
        }

        conn.start(queue: queue)
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
    }

    func send(_ message: ClientMessage) {
        guard let connection else {
            onError?("Not connected")
            return
        }

        do {
            let body = try JSONEncoder().encode(message)
            var lenBE = UInt32(body.count).bigEndian
            let head = Data(bytes: &lenBE, count: 4)
            let packet = head + body
            connection.send(content: packet, completion: .contentProcessed { [weak self] sendError in
                if let sendError {
                    self?.onError?("Send failed: \(sendError.localizedDescription)")
                }
            })
        } catch {
            onError?("Encode failed: \(error.localizedDescription)")
        }
    }

    private func receiveLoop() {
        receiveExact(length: 4) { [weak self] header in
            guard let self else { return }
            guard let header, header.count == 4 else {
                self.onError?("Disconnected")
                return
            }

            let len = header.withUnsafeBytes { raw -> UInt32 in
                raw.load(as: UInt32.self).bigEndian
            }
            if len == 0 || len > 20_000_000 {
                self.onError?("Invalid frame length")
                return
            }

            self.receiveExact(length: Int(len)) { body in
                guard let body else {
                    self.onError?("Disconnected")
                    return
                }
                do {
                    let msg = try JSONDecoder().decode(ServerMessage.self, from: body)
                    self.onMessage?(msg)
                    self.receiveLoop()
                } catch {
                    self.onError?("Decode failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func receiveExact(length: Int, completion: @escaping (Data?) -> Void) {
        guard let connection else {
            completion(nil)
            return
        }

        var buffer = Data()

        func step() {
            let remain = length - buffer.count
            connection.receive(minimumIncompleteLength: 1, maximumLength: remain) { data, _, isComplete, err in
                if let err {
                    self.onError?("Receive failed: \(err.localizedDescription)")
                    completion(nil)
                    return
                }
                if let data, !data.isEmpty {
                    buffer.append(data)
                }
                if buffer.count == length {
                    completion(buffer)
                    return
                }
                if isComplete {
                    completion(nil)
                    return
                }
                step()
            }
        }

        step()
    }
}
