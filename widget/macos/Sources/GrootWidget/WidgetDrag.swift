import AppKit
import WidgetCore

extension WidgetApp {
    func drag(_ event: NSEvent) {
        beginDragTracking()
        // AppKit returns immediately; the WindowServer continues the drag asynchronously.
        panel.performDrag(with: event)
    }

    func beginDragTracking() {
        dragging = true
        dragTimer?.invalidate()
        // A native drag may consume mouse-up, including a click/cancel with no didMove.
        let timer = Timer(timeInterval: 0.05, target: self, selector: #selector(checkDragEnd), userInfo: nil, repeats: true)
        dragTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    func windowDidMove(_ notification: Notification) {
        guard dragging, !isLeftMousePressed() else { return }
        completeDrag(at: panel.frame)
    }

    @objc func checkDragEnd() {
        guard dragging, !isLeftMousePressed() else { return }
        // The final didMove notification can still be queued. Read the actual server-side
        // bounds rather than NSWindow.frame, which may still contain the pre-drag position.
        guard let frame = serverWindowFrame() else { return }
        completeDrag(at: frame)
    }

    func serverWindowFrame() -> NSRect? {
        guard let main = NSScreen.screens.first,
              let windows = CGWindowListCopyWindowInfo(.optionIncludingWindow, CGWindowID(panel.windowNumber)) as? [[String: Any]],
              let window = windows.first,
              let bounds = window[kCGWindowBounds as String] as? NSDictionary,
              let frame = CGRect(dictionaryRepresentation: bounds) else { return nil }
        return NSRect(x: frame.minX, y: main.frame.maxY - frame.maxY, width: frame.width, height: frame.height)
    }

    func completeDrag(at frame: NSRect) {
        guard dragging, let main = NSScreen.screens.first else { return }
        position = WindowPosition(x: Int(frame.minX.rounded()), y: Int((main.frame.maxY - frame.maxY).rounded()))
        dragging = false
        dragTimer?.invalidate()
        dragTimer = nil
        layout()
        do {
            try position?.write(to: positionURL)
            log("Local window position saved x=\(position!.x) y=\(position!.y)")
        } catch { log("Local window position save failed: \(error)") }
    }
}
