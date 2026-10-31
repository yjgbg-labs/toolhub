import AppKit
import WidgetCore

@MainActor
final class WidgetApp: NSObject, NSApplicationDelegate {
    let api: WidgetAPI
    let panel = WidgetPanel(contentRect: NSRect(x: 0, y: 0, width: 440,
                                              height: WidgetCanvas.verticalPadding * 2 + CGFloat(WidgetSettings().rowHeight)),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
    let canvas = WidgetCanvas()
    let scroll = NSScrollView()
    var statusItem: NSStatusItem!
    var pinItem: NSMenuItem!
    var streamTask: Task<Void, Never>?
    var streamGeneration = UUID()
    var writeTask: Task<Void, Never>?
    var pendingSettings: WidgetSettings?
    var settings = WidgetSettings()
    var ready = false
    var dragging = false
    var hidden = false
    var lastSnapshot: WidgetSnapshot?

    init(api: WidgetAPI) { self.api = api }

    func applicationDidFinishLaunching(_ notification: Notification) {
        panel.title = "Groot Widget"
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        let container = NSVisualEffectView(frame: panel.contentView!.bounds)
        container.material = .hudWindow
        container.blendingMode = .behindWindow
        // The panel never becomes key; keep its backdrop blurred while other apps have focus.
        container.state = .active
        container.appearance = NSAppearance(named: .darkAqua)
        let cornerRadius: CGFloat = 16
        container.wantsLayer = true
        container.layer?.cornerRadius = cornerRadius
        container.layer?.masksToBounds = true
        // Behind-window blur is composited separately; explicitly mask that backdrop as well.
        let mask = NSImage(size: NSSize(width: cornerRadius * 2 + 1, height: cornerRadius * 2 + 1), flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: cornerRadius, yRadius: cornerRadius).fill()
            return true
        }
        mask.capInsets = NSEdgeInsets(top: cornerRadius, left: cornerRadius, bottom: cornerRadius, right: cornerRadius)
        mask.resizingMode = .stretch
        container.maskImage = mask
        let tint = NSView(frame: container.bounds)
        tint.autoresizingMask = [.width, .height]
        tint.wantsLayer = true
        tint.layer?.backgroundColor = NSColor(srgbRed: 13/255, green: 17/255, blue: 20/255, alpha: 0.4).cgColor
        container.addSubview(tint)
        scroll.frame = container.bounds
        scroll.autoresizingMask = [.width, .height]
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.documentView = canvas
        container.addSubview(scroll)
        panel.contentView = container
        canvas.drag = { [weak self] event in self?.drag(event) }
        setupMenu()
        NotificationCenter.default.addObserver(self, selector: #selector(screenChanged),
                                               name: NSApplication.didChangeScreenParametersNotification, object: nil)
        layout()
        panel.orderFrontRegardless()
        reconnect()
    }

    func applicationWillTerminate(_ notification: Notification) {
        streamTask?.cancel()
        writeTask?.cancel()
    }

    func setupMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        statusItem.button?.image = NSImage(systemSymbolName: "list.bullet.rectangle", accessibilityDescription: "Groot Widget")
        let menu = NSMenu()
        for (title, selector) in [("显示 / 隐藏", #selector(toggleVisible)), ("置顶", #selector(togglePin)),
                                  ("重新连接", #selector(reconnect)), ("退出", #selector(quit))] {
            let item = NSMenuItem(title: title, action: selector, keyEquivalent: "")
            item.target = self
            menu.addItem(item)
            if selector == #selector(togglePin) { pinItem = item }
        }
        statusItem.menu = menu
    }

    @objc func toggleVisible() {
        hidden.toggle()
        if hidden { panel.orderOut(nil) } else { panel.orderFrontRegardless() }
    }
    @objc func togglePin() {
        guard ready else { return }
        settings.topMost.toggle()
        layout()
        saveSettings()
    }
    @objc func quit() { NSApp.terminate(nil) }
    @objc func screenChanged() { if !dragging { layout() } }

    func layout() {
        guard let main = NSScreen.screens.first else { return }
        canvas.settings = settings
        let width = CGFloat(settings.width)
        let contentHeight = WidgetCanvas.verticalPadding * 2 + CGFloat(max(1, canvas.items.count) * settings.rowHeight)
        let x = settings.x.map(CGFloat.init) ?? (main.visibleFrame.maxX - width - 20)
        let top = settings.y.map { main.frame.maxY - CGFloat($0) } ?? (main.visibleFrame.maxY - 20)
        let proposed = NSRect(x: x, y: top - min(contentHeight, main.visibleFrame.height - 40),
                              width: width, height: min(contentHeight, main.visibleFrame.height - 40))
        let screen = NSScreen.screens.max { a, b in
            let left = a.visibleFrame.intersection(proposed), right = b.visibleFrame.intersection(proposed)
            return (left.isNull ? 0 : left.width * left.height) < (right.isNull ? 0 : right.width * right.height)
        } ?? main
        let visible = screen.visibleFrame.insetBy(dx: 8, dy: 8)
        let height = min(contentHeight, visible.height)
        let actualWidth = min(width, visible.width)
        panel.setFrame(NSRect(x: min(max(x, visible.minX), visible.maxX - actualWidth),
                              y: min(max(top - height, visible.minY), visible.maxY - height),
                              width: actualWidth, height: height), display: true)
        panel.invalidateShadow()
        canvas.frame = NSRect(x: 0, y: 0, width: scroll.contentSize.width, height: contentHeight)
        panel.level = settings.topMost ? .floating : .normal
        panel.alphaValue = settings.opacity
        pinItem?.state = settings.topMost ? .on : .off
        canvas.needsDisplay = true
    }

    func drag(_ event: NSEvent) {
        guard ready else { return }
        dragging = true
        panel.performDrag(with: event)
        dragging = false
        guard let main = NSScreen.screens.first else { return }
        settings.x = Int(panel.frame.minX.rounded())
        settings.y = Int((main.frame.maxY - panel.frame.maxY).rounded())
        layout()
        saveSettings()
    }

    func diagnose(_ snapshot: WidgetSnapshot, capture: Bool) {
        guard let directory = ProcessInfo.processInfo.environment["WIDGET_DIAGNOSTICS_DIR"] else { return }
        do {
            let root = URL(fileURLWithPath: directory, isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let status: [String: Any] = ["revision": snapshot.revision, "itemCount": snapshot.items.count,
                "connected": canvas.connected, "visible": panel.isVisible, "topMost": settings.topMost,
                "receivedAt": ISO8601DateFormatter().string(from: Date()),
                "frame": ["x": panel.frame.minX, "y": panel.frame.minY, "width": panel.frame.width, "height": panel.frame.height]]
            try JSONSerialization.data(withJSONObject: status, options: [.sortedKeys]).write(to: root.appendingPathComponent("status.json"), options: .atomic)
            if capture, let view = panel.contentView, let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
                view.displayIfNeeded()
                view.cacheDisplay(in: view.bounds, to: bitmap)
                try bitmap.representation(using: .png, properties: [:])?.write(to: root.appendingPathComponent("widget.png"), options: .atomic)
            }
        } catch { log("Diagnostics failed: \(error)") }
    }
}
