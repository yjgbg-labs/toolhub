import AppKit
import WidgetCore

func log(_ message: String) {
    let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
    FileHandle.standardError.write(Data(line.utf8))
}

struct ConnectionConfig: Decodable { let baseURL: String }

@main
struct WidgetMain {
    @MainActor static func main() {
        do {
            let args = CommandLine.arguments
            let configPath = args.firstIndex(of: "--config").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
            let defaultPath = FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Application Support/GrootWidget/connection.json").path
            let config = try JSONDecoder().decode(ConnectionConfig.self, from: Data(contentsOf: URL(fileURLWithPath: configPath ?? defaultPath)))
            let api = try WidgetAPI(baseURL: config.baseURL)
            let app = NSApplication.shared
            app.setActivationPolicy(.accessory)
            let delegate = WidgetApp(api: api)
            app.delegate = delegate
            withExtendedLifetime(delegate) { app.run() }
        } catch {
            log("Startup failed: \(error)")
            exit(1)
        }
    }
}
