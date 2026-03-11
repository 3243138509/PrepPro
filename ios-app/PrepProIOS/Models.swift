import Foundation

struct ClientMessage: Encodable {
    let type: String
    let requestId: String?
    let password: String?
    let quality: Int?
    let displayId: Int?

    static func auth(password: String) -> ClientMessage {
        ClientMessage(type: "AUTH", requestId: nil, password: password, quality: nil, displayId: nil)
    }

    static func capture(requestId: String, quality: Int = 75, displayId: Int = 1) -> ClientMessage {
        ClientMessage(type: "CAPTURE", requestId: requestId, password: nil, quality: quality, displayId: displayId)
    }
}

struct ServerMessage: Decodable {
    let type: String
    let requestId: String?
    let code: String?
    let message: String?

    let format: String?
    let width: Int?
    let height: Int?
    let imageBase64: String?
}
