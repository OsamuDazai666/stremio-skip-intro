#!/bin/bash
# Skip Intro for Stremio - macOS installer
#
#   curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/install-mac.sh | bash
#
# What it does (no admin rights needed):
#   1. Downloads the plugin and a small helper library to
#      ~/Library/Application Support/StremioSkipIntro
#   2. Creates a "Stremio Skip Intro" app (in /Applications, or ~/Applications) that opens the
#      official Stremio with the helper loaded. The helper adds the plugin to Stremio's page.
#   3. Opens it.
# The Stremio app itself is not modified.
# Uninstall: curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-mac.sh | bash

set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/OsamuDazai666/stremio-skip-intro/main"
DYLIB_SHA256="a1b799857f798ad2f5eef804c6e736ab60b12da75fcc29325a2a0ea9ebfabbec"
STREMIO="/Applications/Stremio.app"
SUPPORT="$HOME/Library/Application Support/StremioSkipIntro"
APP_NAME="Stremio Skip Intro.app"

say() { printf '  %s\n' "$1"; }
fail() { printf '  \033[31m%s\033[0m\n' "$1"; exit 1; }

printf '\n  \033[35mSkip Intro for Stremio (macOS)\033[0m\n  ------------------------------\n'

[ "$(uname -s)" = "Darwin" ] || fail "This installer is for macOS."
[ -d "$STREMIO" ] || { say "Stremio was not found in /Applications. Install it from https://www.stremio.com/downloads and run this again."; open "https://www.stremio.com/downloads" || true; exit 1; }

BUNDLE_ID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$STREMIO/Contents/Info.plist" 2>/dev/null || echo "")
case "$BUNDLE_ID" in
  com.westbridge.stremio*) ;;
  *) fail "This Stremio build ($BUNDLE_ID) is not supported yet. Please report it on GitHub." ;;
esac
ENTITLEMENTS=$(codesign -d --entitlements - "$STREMIO" 2>/dev/null || true)
case "$ENTITLEMENTS" in
  *allow-dyld-environment-variables*) ;;
  *) fail "This Stremio version doesn't allow add-ons to be loaded. Please report it on GitHub." ;;
esac

say "Downloading the plugin..."
mkdir -p "$SUPPORT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$REPO_RAW/skip-intro.plugin.js" -o "$TMP/skip-intro.plugin.js"
curl -fsSL "$REPO_RAW/macos/SkipIntroInject.dylib" -o "$TMP/SkipIntroInject.dylib"
grep -q '@name Skip Intro' "$TMP/skip-intro.plugin.js" || fail "The plugin download looks wrong. Try again later."
GOT=$(shasum -a 256 "$TMP/SkipIntroInject.dylib" | awk '{print $1}')
[ "$GOT" = "$DYLIB_SHA256" ] || fail "The helper library failed its checksum. Try again later."
mv -f "$TMP/skip-intro.plugin.js" "$SUPPORT/skip-intro.plugin.js"
mv -f "$TMP/SkipIntroInject.dylib" "$SUPPORT/SkipIntroInject.dylib"
xattr -dr com.apple.quarantine "$SUPPORT" 2>/dev/null || true
echo "$REPO_RAW/skip-intro.plugin.js" > "$SUPPORT/update-url.txt"

say "Creating the \"Stremio Skip Intro\" app..."
if [ -w /Applications ]; then APPS="/Applications"; else APPS="$HOME/Applications"; mkdir -p "$APPS"; fi
APP="$APPS/$APP_NAME"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$STREMIO/Contents/Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns" 2>/dev/null || true
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Stremio Skip Intro</string>
  <key>CFBundleDisplayName</key><string>Stremio Skip Intro</string>
  <key>CFBundleIdentifier</key><string>io.github.osamudazai666.stremio-skip-intro</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>launcher</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSUIElement</key><true/>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
</dict>
</plist>
PLIST
cat > "$APP/Contents/MacOS/launcher" <<'LAUNCHER'
#!/bin/bash
# Opens the official Stremio with the Skip Intro helper loaded.
SUPPORT="$HOME/Library/Application Support/StremioSkipIntro"
STREMIO="/Applications/Stremio.app"
PLUGIN="$SUPPORT/skip-intro.plugin.js"

# Fetch a newer plugin (quick, silent; the current one is kept on any problem).
if [ -f "$SUPPORT/update-url.txt" ]; then
  TMPF="$SUPPORT/.plugin.download"
  if curl -fsSL --max-time 6 "$(head -n1 "$SUPPORT/update-url.txt")" -o "$TMPF" 2>/dev/null \
     && [ "$(wc -c < "$TMPF")" -gt 10000 ] && grep -q '@name Skip Intro' "$TMPF"; then
    mv -f "$TMPF" "$PLUGIN"
  else
    rm -f "$TMPF"
  fi
fi

# Stremio only reads the helper at launch: restart it if it is already open.
if pgrep -f "$STREMIO/Contents/MacOS/Stremio" >/dev/null; then
  osascript -e 'quit app "Stremio"' >/dev/null 2>&1
  for _ in $(seq 1 20); do pgrep -f "$STREMIO/Contents/MacOS/Stremio" >/dev/null || break; sleep 0.25; done
fi

if [ -f "$SUPPORT/SkipIntroInject.dylib" ] && [ -f "$PLUGIN" ]; then
  open -a "$STREMIO" --env DYLD_INSERT_LIBRARIES="$SUPPORT/SkipIntroInject.dylib"
else
  open -a "$STREMIO"
fi
LAUNCHER
chmod +x "$APP/Contents/MacOS/launcher"
touch "$APP"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP" >/dev/null 2>&1 || true

say "Opening Stremio with Skip Intro..."
open "$APP"

printf '\n  \033[32mInstalled!\033[0m Open Stremio with "Stremio Skip Intro" (in %s).\n' "$APPS"
say "Tip: drag it to your Dock in place of Stremio."
say "Uninstall any time: curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-mac.sh | bash"
echo
