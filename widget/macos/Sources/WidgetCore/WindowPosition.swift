import Foundation

public struct WindowPosition: Codable, Equatable, Sendable {
    public let x: Int
    public let y: Int

    public init(x: Int, y: Int) { self.x = x; self.y = y }

    public static func read(from url: URL) throws -> WindowPosition? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let position = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        guard (-65536...65536).contains(position.x), (-65536...65536).contains(position.y) else {
            throw CocoaError(.coderInvalidValue)
        }
        return position
    }

    public func write(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(self).write(to: url, options: .atomic)
    }
}
