import Foundation

/// Incremental UTF-8 framing: tolerate arbitrary byte splits, CR/LF/CRLF, comments and a leading BOM.
/// Only a blank line dispatches an event; a disconnected partial event is discarded with this parser.
public struct SSEParser {
    public enum Failure: Error { case eventTooLarge, invalidUTF8 }
    private var line: [UInt8] = []
    private var event: [UInt8] = []
    private var hasData = false
    private var afterCR = false
    private var firstLine = true
    private let limit: Int

    public init(limit: Int = 1024 * 1024) { self.limit = limit }

    public mutating func feed(_ byte: UInt8) throws -> Data? {
        if afterCR {
            afterCR = false
            if byte == 10 { return nil }
        }
        if byte == 13 || byte == 10 {
            afterCR = byte == 13
            return try endLine()
        }
        guard line.count + event.count < limit else { throw Failure.eventTooLarge }
        line.append(byte)
        return nil
    }

    private mutating func endLine() throws -> Data? {
        if firstLine {
            firstLine = false
            if line.starts(with: [239, 187, 191]) { line.removeFirst(3) }
        }
        defer { line.removeAll(keepingCapacity: true) }
        guard String(bytes: line, encoding: .utf8) != nil else { throw Failure.invalidUTF8 }
        if line.isEmpty {
            defer { event.removeAll(keepingCapacity: true); hasData = false }
            return hasData ? Data(event.dropLast()) : nil
        }
        let colon = line.firstIndex(of: 58) ?? line.count
        if line[..<colon].elementsEqual([100, 97, 116, 97]) {
            var start = min(colon + 1, line.count)
            if start < line.count && line[start] == 32 { start += 1 }
            guard event.count + line.count - start + 1 <= limit else { throw Failure.eventTooLarge }
            event.append(contentsOf: line[start...])
            event.append(10)
            hasData = true
        }
        return nil
    }
}
