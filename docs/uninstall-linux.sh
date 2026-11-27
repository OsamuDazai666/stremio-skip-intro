#!/bin/bash
# Skip Intro for Stremio - Linux uninstaller
#
#   curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-linux.sh | bash

set -uo pipefail

APP_ID="com.stremio.Stremio"
DIR="$HOME/.var/app/$APP_ID/data/stremio-skip-intro"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

printf '\n  \033[35mSkip Intro for Stremio (Linux): uninstall\033[0m\n'

# Remove only our LD_PRELOAD line from Stremio's per-user Flatpak overrides, keeping any others.
# ("flatpak override --unset-env" would instead record an "unset LD_PRELOAD" rule.)
OVERRIDE="${XDG_DATA_HOME:-$HOME/.local/share}/flatpak/overrides/$APP_ID"
if [ -f "$OVERRIDE" ]; then
  sed -i '/^LD_PRELOAD=.*stremio-skip-intro/d' "$OVERRIDE"
  # Drop the file if nothing but empty section headers is left.
  if ! grep -qv '^\s*\(\[.*\]\)\?\s*$' "$OVERRIDE"; then rm -f "$OVERRIDE"; fi
fi
if command -v systemctl >/dev/null; then
  systemctl --user disable --now stremio-skip-intro-update.timer >/dev/null 2>&1 || true
fi
rm -f "$UNIT_DIR/stremio-skip-intro-update.service" "$UNIT_DIR/stremio-skip-intro-update.timer"
command -v systemctl >/dev/null && systemctl --user daemon-reload >/dev/null 2>&1 || true
rm -rf "$DIR"

printf '  \033[32mRemoved.\033[0m Restart Stremio if it is open.\n\n'
