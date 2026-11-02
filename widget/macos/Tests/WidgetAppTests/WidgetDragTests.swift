import AppKit
import XCTest
import WidgetCore
@testable import GrootWidget

final class WidgetDragTests: XCTestCase {
    @MainActor
    func testDelayedNativeMoveSurvivesRefreshAndRestart() throws {
        _ = NSApplication.shared
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("position.json")
        let api = try WidgetAPI(baseURL: "http://example.test")
        let app = WidgetApp(api: api, positionURL: url)
        app.isLeftMousePressed = { false }
        defer { app.dragTimer?.invalidate(); app.panel.orderOut(nil) }
        app.layout()
        let initial = app.panel.frame
        app.beginDragTracking()
        XCTAssertNil(try WindowPosition.read(from: url), "Starting an asynchronous drag must not save the old position")

        // Model a WindowServer move whose didMove notification has not reached the app yet.
        app.panel.delegate = nil
        let moved = initial.offsetBy(dx: -120, dy: -80)
        app.panel.setFrame(moved, display: false)
        app.layout()
        XCTAssertEqual(app.panel.frame, moved, "An incoming snapshot must not reset the in-flight drag")
        app.panel.delegate = app
        app.windowDidMove(Notification(name: NSWindow.didMoveNotification, object: app.panel))
        XCTAssertFalse(app.dragging)
        XCTAssertNil(app.dragTimer)
        XCTAssertEqual(app.position?.x, Int(moved.minX))
        XCTAssertEqual(try WindowPosition.read(from: url), app.position)

        app.layout()
        XCTAssertEqual(app.panel.frame, moved, "A later refresh must keep the released position")
        let restarted = WidgetApp(api: api, positionURL: url)
        defer { restarted.panel.orderOut(nil) }
        restarted.layout()
        XCTAssertEqual(restarted.panel.frame, moved, "Restart must restore the final position, not the drag's starting point")
    }

    @MainActor
    func testReleaseWithoutMoveNotificationUsesWindowServerBounds() throws {
        _ = NSApplication.shared
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let app = WidgetApp(api: try WidgetAPI(baseURL: "http://example.test"), positionURL: root.appendingPathComponent("position.json"))
        app.isLeftMousePressed = { false }
        defer { app.dragTimer?.invalidate(); app.panel.orderOut(nil) }
        app.layout()
        app.panel.orderFrontRegardless()
        app.beginDragTracking()
        app.panel.delegate = nil
        let moved = app.panel.frame.offsetBy(dx: -90, dy: -60)
        app.panel.setFrame(moved, display: false)
        app.panel.display()
        // NSWindow changes reach the WindowServer when AppKit flushes the run loop.
        // Exercise the actual release timer, with both native callbacks absent.
        let deadline = Date(timeIntervalSinceNow: 1)
        while app.dragging && Date() < deadline {
            RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
        }
        XCTAssertNotNil(app.serverWindowFrame())
        XCTAssertFalse(app.dragging, "Missing mouse-up/didMove must not leave layout frozen")
        XCTAssertEqual(app.panel.frame, moved)
        XCTAssertEqual(try WindowPosition.read(from: app.positionURL), app.position)

        app.beginDragTracking()
        app.checkDragEnd()
        XCTAssertFalse(app.dragging, "A click without movement must also finish")
        XCTAssertEqual(app.panel.frame, moved)
    }
}
