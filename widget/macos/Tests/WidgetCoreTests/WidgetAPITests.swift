import Foundation
import XCTest
@testable import WidgetCore

private final class StubProtocol: URLProtocol {
    static var handler: ((StubProtocol) -> Void)?
    static var stopped: (() -> Void)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { Self.handler?(self) }
    override func stopLoading() { Self.stopped?() }
    func reply(status: Int = 200, type: String, data: Data, finish: Bool = true) {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": type])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        if finish { client?.urlProtocolDidFinishLoading(self) }
    }
}

final class WidgetAPITests: XCTestCase {
    private func api() throws -> WidgetAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return try WidgetAPI(baseURL: "http://widget.test:18080", session: URLSession(configuration: config))
    }
    override func tearDown() { StubProtocol.handler = nil; StubProtocol.stopped = nil }

    func testWriteSendsCompleteSettingsToOrdinaryJSONEndpoint() async throws {
        let received = expectation(description: "POST settings")
        var settings = WidgetSettings()
        settings.x = -40
        settings.y = 90
        settings.topMost = false
        let expected = settings
        StubProtocol.handler = { stub in
            XCTAssertEqual(stub.request.url?.path, "/widget/config/write")
            XCTAssertEqual(stub.request.httpMethod, "POST")
            XCTAssertEqual(stub.request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            var data = stub.request.httpBody ?? Data()
            if let stream = stub.request.httpBodyStream {
                stream.open()
                defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    data.append(contentsOf: buffer.prefix(count))
                }
            }
            XCTAssertEqual(try? JSONDecoder().decode(WidgetSettings.self, from: data), expected)
            stub.reply(type: "application/json", data: data)
            received.fulfill()
        }
        let client = try api()
        try await client.write(settings)
        await fulfillment(of: [received], timeout: 3)
    }

    func testStreamingDeliversBeforeEOFAndCancellationClosesRequest() async throws {
        let received = expectation(description: "snapshot before EOF")
        let stopped = expectation(description: "stream cancelled")
        let json = #"{"revision":"42","serverTime":"now","items":[],"settings":{"width":440,"topMost":true,"opacity":0.96,"fontSize":14,"rowHeight":40}}"#
        StubProtocol.handler = { stub in
            XCTAssertEqual(stub.request.url?.path, "/widget/subscribe")
            XCTAssertEqual(stub.request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
            stub.reply(type: "text/event-stream; charset=utf-8", data: Data("data: \(json)\n\n".utf8), finish: false)
        }
        StubProtocol.stopped = { stopped.fulfill() }
        let client = try api()
        let task = Task {
            try await client.subscribe { snapshot in
                XCTAssertEqual(snapshot.revision, "42")
                received.fulfill()
            }
        }
        await fulfillment(of: [received], timeout: 3)
        task.cancel()
        _ = await task.result
        await fulfillment(of: [stopped], timeout: 3)
    }

    func testSubscriptionRejectsHTTPAndMediaTypeErrors() async throws {
        let client = try api()
        for (status, type) in [(503, "text/event-stream"), (200, "text/html")] {
            StubProtocol.handler = { $0.reply(status: status, type: type, data: Data("unavailable".utf8)) }
            do {
                try await client.subscribe { _ in XCTFail("Invalid response reached UI") }
                XCTFail("Expected subscription failure")
            } catch WidgetAPI.Failure.response(let code) { XCTAssertEqual(code, 503) }
              catch WidgetAPI.Failure.notEventStream { XCTAssertEqual(type, "text/html") }
        }
    }
}
