// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GrootWidget",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "GrootWidget", targets: ["GrootWidget"])],
    targets: [
        .target(name: "WidgetCore"),
        .executableTarget(name: "GrootWidget", dependencies: ["WidgetCore"]),
        .testTarget(name: "WidgetCoreTests", dependencies: ["WidgetCore"])
    ]
)
