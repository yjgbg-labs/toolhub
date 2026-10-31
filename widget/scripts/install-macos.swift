import Foundation

let fm = FileManager.default
let home = fm.homeDirectoryForCurrentUser
let label = "lab.yjgbg.groot-widget"
let domain = "gui/\(getuid())"
let app = home.appendingPathComponent("Applications/GrootWidget.app")
let plist = home.appendingPathComponent("Library/LaunchAgents/\(label).plist")
let support = home.appendingPathComponent("Library/Application Support/GrootWidget")
let config = support.appendingPathComponent("connection.json")
let logs = home.appendingPathComponent("Library/Logs/GrootWidget")
let source = URL(fileURLWithPath: "dist/GrootWidget.app", isDirectory: true)

@discardableResult
func run(_ executable: String, _ arguments: [String], required: Bool = true, quiet: Bool = false) throws -> Int32 {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    if quiet { process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice }
    try process.run()
    process.waitUntilExit()
    if required && process.terminationStatus != 0 {
        throw NSError(domain: "WidgetInstall", code: Int(process.terminationStatus),
                      userInfo: [NSLocalizedDescriptionKey: "Failed: \(executable) \(arguments)"])
    }
    return process.terminationStatus
}

guard CommandLine.arguments.count == 3, let url = URL(string: CommandLine.arguments[1]),
      ["http", "https"].contains(url.scheme), url.host != nil else {
    fatalError("Expected install-macos.swift BASE_URL DIAGNOSTICS(0|1)")
}
try run("/bin/launchctl", ["print", domain], quiet: true)
try run("/usr/bin/codesign", ["--verify", "--strict", source.path])
if fm.fileExists(atPath: app.path) {
    let info = try PropertyListSerialization.propertyList(from: Data(contentsOf: app.appendingPathComponent("Contents/Info.plist")), format: nil) as? [String: Any]
    guard info?["CFBundleIdentifier"] as? String == label else { fatalError("Unrecognized application at \(app.path)") }
}
for directory in [app.deletingLastPathComponent(), plist.deletingLastPathComponent(), support, logs] {
    try fm.createDirectory(at: directory, withIntermediateDirectories: true)
}
let stage = app.deletingLastPathComponent().appendingPathComponent(".GrootWidget-\(UUID().uuidString).app")
let backup = app.deletingLastPathComponent().appendingPathComponent(".GrootWidget-backup-\(UUID().uuidString).app")
try fm.copyItem(at: source, to: stage)
let oldPlist = try? Data(contentsOf: plist)
let oldConfig = try? Data(contentsOf: config)
if let oldPlist {
    let old = try PropertyListSerialization.propertyList(from: oldPlist, format: nil) as? [String: Any]
    guard old?["Label"] as? String == label else { fatalError("Unrecognized launch agent") }
}
if try run("/bin/launchctl", ["print", "\(domain)/\(label)"], required: false, quiet: true) == 0 {
    try run("/bin/launchctl", ["bootout", "\(domain)/\(label)"])
}
let existed = fm.fileExists(atPath: app.path)
if existed { try fm.moveItem(at: app, to: backup) }
do {
    try fm.moveItem(at: stage, to: app)
    try JSONSerialization.data(withJSONObject: ["baseURL": url.absoluteString], options: [.prettyPrinted, .sortedKeys])
        .write(to: config, options: .atomic)
    var agent: [String: Any] = [
        "Label": label,
        "ProgramArguments": [app.appendingPathComponent("Contents/MacOS/GrootWidget").path, "--config", config.path],
        "RunAtLoad": true,
        "KeepAlive": ["SuccessfulExit": false],
        "ThrottleInterval": 10,
        "LimitLoadToSessionType": "Aqua",
        "StandardOutPath": logs.appendingPathComponent("app.log").path,
        "StandardErrorPath": logs.appendingPathComponent("app.log").path
    ]
    if CommandLine.arguments[2] == "1" { agent["EnvironmentVariables"] = ["WIDGET_DIAGNOSTICS_DIR": logs.path] }
    try PropertyListSerialization.data(fromPropertyList: agent, format: .xml, options: 0).write(to: plist, options: .atomic)
    try fm.setAttributes([.posixPermissions: 0o644], ofItemAtPath: plist.path)
    try run("/bin/launchctl", ["bootstrap", domain, plist.path])
    try run("/bin/launchctl", ["kickstart", "\(domain)/\(label)"])
    try run("/bin/launchctl", ["print", "\(domain)/\(label)"])
    if existed { try fm.removeItem(at: backup) }
    print("Installed \(app.path); server=\(url); logs=\(logs.path)")
} catch {
    _ = try? run("/bin/launchctl", ["bootout", "\(domain)/\(label)"], required: false)
    try? fm.removeItem(at: app)
    if existed { try? fm.moveItem(at: backup, to: app) }
    if let oldConfig { try? oldConfig.write(to: config, options: .atomic) }
    if let oldPlist {
        try? oldPlist.write(to: plist, options: .atomic)
        _ = try? run("/bin/launchctl", ["bootstrap", domain, plist.path])
    } else { try? fm.removeItem(at: plist) }
    throw error
}
