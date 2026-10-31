import AppKit
import WidgetCore

final class WidgetCanvas: NSView {
    var items: [WidgetItem] = []
    var settings = WidgetSettings()
    var connected = false
    var drag: ((NSEvent) -> Void)?
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func mouseDown(with event: NSEvent) { drag?(event) }

    static let panel = NSColor(srgbRed: 13/255, green: 17/255, blue: 20/255, alpha: 1)
    private let labelColor = NSColor(srgbRed: 170/255, green: 181/255, blue: 187/255, alpha: 1)
    private let offColor = NSColor(srgbRed: 38/255, green: 45/255, blue: 49/255, alpha: 1)

    override func draw(_ dirtyRect: NSRect) {
        Self.panel.setFill()
        bounds.fill()
        let font = NSFont.monospacedSystemFont(ofSize: CGFloat(settings.fontSize), weight: .regular)
        let rowHeight = CGFloat(settings.rowHeight)
        if items.isEmpty {
            text(connected ? "暂无状态" : "连接中…", rect: NSRect(x: 16, y: 12, width: bounds.width - 32, height: rowHeight),
                 font: font, color: labelColor)
        }
        let labelWidth = min(bounds.width * 0.55, items.map {
            ($0.label as NSString).size(withAttributes: [.font: font]).width
        }.max() ?? 0)
        for (index, item) in items.enumerated() {
            let y = 12 + CGFloat(index) * rowHeight
            let left = NSRect(x: 16, y: y, width: labelWidth, height: rowHeight)
            text(item.label, rect: left, font: font, color: labelColor)
            if let value = item.value, !value.isEmpty {
                text(value, rect: NSRect(x: left.maxX + 16, y: y, width: max(0, bounds.width - left.maxX - 32), height: rowHeight),
                     font: font, color: tone(item.color), alignment: .right)
            } else {
                for (lamp, color) in [WidgetColor.red, .yellow, .green].enumerated() {
                    (item.color == color ? tone(color) : offColor).setFill()
                    NSBezierPath(ovalIn: NSRect(x: bounds.width - 16 - 42 + CGFloat(lamp) * 16,
                                               y: y + (rowHeight - 10)/2, width: 10, height: 10)).fill()
                }
            }
        }
        if !connected {
            NSColor.systemOrange.setFill()
            NSBezierPath(ovalIn: NSRect(x: bounds.width - 10, y: 4, width: 4, height: 4)).fill()
        }
    }

    private func text(_ value: String, rect: NSRect, font: NSFont, color: NSColor, alignment: NSTextAlignment = .left) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byTruncatingTail
        let height = font.ascender - font.descender + font.leading
        (value as NSString).draw(in: NSRect(x: rect.minX, y: rect.midY - height/2, width: rect.width, height: height + 2),
                                withAttributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph])
    }

    private func tone(_ color: WidgetColor) -> NSColor {
        switch color {
        case .red: return NSColor(srgbRed: 1, green: 102/255, blue: 92/255, alpha: 1)
        case .yellow: return NSColor(srgbRed: 232/255, green: 183/255, blue: 93/255, alpha: 1)
        case .green: return NSColor(srgbRed: 88/255, green: 214/255, blue: 169/255, alpha: 1)
        }
    }
}

final class WidgetPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}
