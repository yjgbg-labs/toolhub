import AppKit
import WidgetCore

extension WidgetApp {
    func reconnect() {
        streamTask?.cancel()
        let generation = UUID()
        streamGeneration = generation
        streamTask = Task { [weak self] in
            guard let self else { return }
            var retry = ReconnectDelay()
            while !Task.isCancelled {
                connection(false)
                var first = true
                do {
                    try await api.subscribe { snapshot in
                        guard self.streamGeneration == generation else { return }
                        retry.reset()
                        self.receive(snapshot, first: first)
                        first = false
                    }
                } catch {
                    if Task.isCancelled { break }
                    connection(false)
                    let delay = retry.next() * Double.random(in: 0.8...1.2)
                    log("Subscription disconnected: \(error); retry in \(String(format: "%.1f", delay))s")
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }
            }
        }
    }

    func connection(_ connected: Bool) {
        if canvas.connected != connected { log(connected ? "SSE connected" : "SSE offline") }
        canvas.connected = connected
        canvas.toolTip = connected ? "拖动以移动窗口" : "连接已断开，正在重连；保留最后收到的状态"
        statusItem.button?.toolTip = connected ? "Groot Widget · 已连接" : "Groot Widget · 重连中"
        canvas.needsDisplay = true
        if let lastSnapshot { diagnose(lastSnapshot, capture: false) }
    }

    func receive(_ snapshot: WidgetSnapshot, first: Bool) {
        let changed = first || lastSnapshot?.revision != snapshot.revision
        connection(true)
        lastSnapshot = snapshot
        let settingsChanged = settings != snapshot.settings
        if settingsChanged { settings = snapshot.settings }
        if changed || settingsChanged {
            canvas.items = snapshot.items
            if !dragging { layout() }
            log("Applied revision=\(snapshot.revision) items=\(snapshot.items.count) visible=\(panel.isVisible) size=\(Int(panel.frame.width))x\(Int(panel.frame.height))")
        }
        diagnose(snapshot, capture: changed || settingsChanged)
    }
}
