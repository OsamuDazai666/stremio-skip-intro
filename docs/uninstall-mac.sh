#!/bin/bash
# Skip Intro for Stremio - macOS uninstaller
#
#   curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-mac.sh | bash
#
# Removes the "Stremio Skip Intro" app and the plugin files, then reopens Stremio without them.

STREMIO="/Applications/Stremio.app"
printf '\n  \033[35mUninstalling Skip Intro for Stremio (macOS)\033[0m\n'

WAS_RUNNING=0
if pgrep -f "$STREMIO/Contents/MacOS/Stremio" >/dev/null; then
  WAS_RUNNING=1
  osascript -e 'quit app "Stremio"' >/dev/null 2>&1
  for _ in $(seq 1 20); do pgrep -f "$STREMIO/Contents/MacOS/Stremio" >/dev/null || break; sleep 0.25; done
fi

rm -rf "/Applications/Stremio Skip Intro.app" "$HOME/Applications/Stremio Skip Intro.app"
rm -rf "$HOME/Library/Application Support/StremioSkipIntro"
printf '  Removed the app and the plugin files.\n'

if [ "$WAS_RUNNING" = 1 ] && [ -d "$STREMIO" ]; then
  open -a "$STREMIO"
  printf '  Stremio reopened without the plugin.\n'
fi
printf '  \033[32mDone.\033[0m\n\n'
