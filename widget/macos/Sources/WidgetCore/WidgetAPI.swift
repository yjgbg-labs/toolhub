import Foundation

public final class WidgetAPI {
    public enum Failure: Error { case invalidURL, response(Int), notEventStream, disconnected }
    private let baseURL: URL
    private let session: URLSession

    public init(baseURL: String, session: URLSession? = nil) throws {
        guard let url = URL(string: baseURL), ["http", "https"].contains(url.scheme),
              url.host != nil, url.query == nil, url.fragment == nil else { throw Failure.invalidURL }
        self.baseURL = url
        if let session { self.session = session }
        else {
            let config = URLSessionConfiguration.ephemeral
            // The server sends a full heartbeat snapshot every 15s; a silent connection must reconnect.
            config.timeoutIntervalForRequest = 45
            config.timeoutIntervalForResource = 7 * 24 * 3600
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: config)
        }
    }

    deinit { session.invalidateAndCancel() }

    public func subscribe(_ receive: (WidgetSnapshot) async throws -> Void) async throws {
        var request = URLRequest(url: endpoint("subscribe"))
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            bytes.task.cancel()
            throw Failure.response((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
        guard http.value(forHTTPHeaderField: "Content-Type")?.lowercased().split(separator: ";").first == "text/event-stream" else {
            bytes.task.cancel()
            throw Failure.notEventStream
        }
        defer { bytes.task.cancel() }
        var parser = SSEParser()
        for try await byte in bytes {
            try Task.checkCancellation()
            if let data = try parser.feed(byte) {
                let snapshot = try JSONDecoder().decode(WidgetSnapshot.self, from: data)
                try await receive(snapshot)
            }
        }
        throw Failure.disconnected
    }

    public func write(_ settings: WidgetSettings) async throws {
        var request = URLRequest(url: endpoint("config/write"))
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(settings)
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw Failure.response((response as? HTTPURLResponse)?.statusCode ?? 0)
        }
    }

    private func endpoint(_ path: String) -> URL { baseURL.appendingPathComponent("widget/" + path) }
}
