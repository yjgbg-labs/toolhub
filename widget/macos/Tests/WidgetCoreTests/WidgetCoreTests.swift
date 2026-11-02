import Foundation
import XCTest
@testable import WidgetCore

final class WidgetCoreTests: XCTestCase {
    private func events(_ text: String) throws -> [String] {
        var parser = SSEParser()
        return try Array(text.utf8).compactMap { try parser.feed($0).map { String(decoding: $0, as: UTF8.self) } }
    }

    func testSSEByteBoundariesUnicodeBOMAndLineEndings() throws {
        let text = "\u{FEFF}: hello\r\ndata: 中文🙂\r\ndata: second\r\n\r\nevent: ignored\rdata: next\r\rdata:\n\n"
        XCTAssertEqual(try events(text), ["中文🙂\nsecond", "next", ""])
        XCTAssertEqual(try events("data: unfinished\n"), [])
        XCTAssertEqual(try events(": heartbeat\n\nid: 42\n\n"), [])
        XCTAssertEqual(try events("data:  preserve space\n\n"), [" preserve space"])
    }

    func testParserBoundsBothSingleLinesAndMultilineEvents() throws {
        var line = SSEParser(limit: 32)
        XCTAssertThrowsError(try Array(("data: " + String(repeating: "x", count: 33)).utf8).forEach { _ = try line.feed($0) })
        var event = SSEParser(limit: 32)
        XCTAssertThrowsError(try Array(String(repeating: "data: abcdef\n", count: 10).utf8).forEach { _ = try event.feed($0) })
        var invalid = SSEParser()
        _ = try invalid.feed(255)
        XCTAssertThrowsError(try invalid.feed(10))
    }

    func testSnapshotIgnoresLegacyServerPosition() throws {
        let json = #"{"revision":"9007199254740993","serverTime":"2026-10-31T00:00:00Z","items":[{"key":"lamp","label":"状态","value":null,"color":"yellow","order":0,"updatedAt":"now"}],"settings":{"x":-20,"y":30,"width":440,"topMost":true,"opacity":0.96,"fontSize":14,"rowHeight":40}}"#
        let snapshot = try JSONDecoder().decode(WidgetSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snapshot.revision, "9007199254740993")
        XCTAssertNil(snapshot.items[0].value)
        XCTAssertEqual(snapshot.items[0].color, .yellow)
        let moved = json.replacingOccurrences(of: "\"x\":-20,\"y\":30", with: "\"x\":1600,\"y\":900")
        XCTAssertEqual(try JSONDecoder().decode(WidgetSnapshot.self, from: Data(moved.utf8)).settings, snapshot.settings)
        let roundTrip = try JSONDecoder().decode(WidgetSettings.self, from: JSONEncoder().encode(snapshot.settings))
        XCTAssertEqual(roundTrip, snapshot.settings)
    }

    func testLocalPositionSurvivesRestartAndStaysIndependent() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let first = root.appendingPathComponent("first/position.json")
        let second = root.appendingPathComponent("second/position.json")
        XCTAssertNil(try WindowPosition.read(from: first))
        try WindowPosition(x: -1200, y: -80).write(to: first)
        try WindowPosition(x: 400, y: 200).write(to: second)
        try WindowPosition(x: -900, y: 120).write(to: first)
        XCTAssertEqual(try WindowPosition.read(from: first), WindowPosition(x: -900, y: 120))
        XCTAssertEqual(try WindowPosition.read(from: second), WindowPosition(x: 400, y: 200))
        for invalid in ["{", "{}", #"{"x":20}"#, #"{"x":null,"y":20}"#, #"{"x":99999999,"y":0}"#] {
            try Data(invalid.utf8).write(to: first)
            XCTAssertThrowsError(try WindowPosition.read(from: first))
        }
    }

    func testRetryResetsAfterReceivingSnapshot() {
        var retry = ReconnectDelay()
        XCTAssertEqual((0..<8).map { _ in retry.next() }, [1, 2, 4, 8, 16, 30, 30, 30])
        retry.reset()
        XCTAssertEqual(retry.next(), 1)
    }

    func testConnectionURLValidation() throws {
        XCTAssertThrowsError(try WidgetAPI(baseURL: "file:///tmp/state"))
        XCTAssertThrowsError(try WidgetAPI(baseURL: "http://localhost:18080?token=x"))
        XCTAssertNoThrow(try WidgetAPI(baseURL: "http://localhost:18080"))
    }
}
