import Foundation

public enum WidgetColor: String, Codable, Sendable { case red, yellow, green }

public struct WidgetItem: Codable, Equatable, Sendable {
    public let key: String
    public let label: String
    public let value: String?
    public let color: WidgetColor
    public let order: Int
    public let updatedAt: String
}

public struct WidgetSettings: Codable, Equatable, Sendable {
    public var x: Int?
    public var y: Int?
    public var width = 440
    public var topMost = true
    public var opacity = 0.96
    public var fontSize = 14
    public var rowHeight = 40

    public init() {}
}

public struct WidgetSnapshot: Codable, Equatable, Sendable {
    public let revision: String
    public let serverTime: String
    public let items: [WidgetItem]
    public let settings: WidgetSettings
}

public struct ReconnectDelay {
    private var attempt = 0
    public init() {}
    public mutating func reset() { attempt = 0 }
    public mutating func next() -> Double {
        let seconds = min(30, pow(2, Double(attempt)))
        attempt = min(attempt + 1, 5)
        return seconds
    }
}
