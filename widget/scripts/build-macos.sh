set -eu
xcrun swift build --package-path macos -c release
app=dist/GrootWidget.app
mkdir -p "$app/Contents/MacOS"
install -m 755 macos/.build/release/GrootWidget "$app/Contents/MacOS/GrootWidget"
install -m 644 macos/Info.plist "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
codesign --verify --strict "$app"
tar -czf dist/GrootWidget.tar.gz -C dist GrootWidget.app
