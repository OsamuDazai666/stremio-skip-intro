#!/bin/bash
# Skip Intro for Stremio - Linux installer (official Stremio Flatpak, com.stremio.Stremio)
#
#   curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/install-linux.sh | bash
#
# What it does (no sudo needed):
#   1. Downloads the plugin and a small helper library to Stremio's own Flatpak data folder:
#      ~/.var/app/com.stremio.Stremio/data/stremio-skip-intro
#   2. Tells Flatpak to load the helper into Stremio (a per-user "flatpak override"). The helper
#      adds the plugin to Stremio's page. Open Stremio as usual afterwards.
#   3. Adds a systemd user timer that fetches plugin updates every 6 hours.
# The Stremio app itself is not modified.
# Uninstall: curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-linux.sh | bash

set -euo pipefail

APP_ID="com.stremio.Stremio"
REPO_RAW="https://raw.githubusercontent.com/OsamuDazai666/stremio-skip-intro/main"
SHA256_x86_64="__SHA256_X86_64__"
SHA256_aarch64="__SHA256_AARCH64__"
DIR="$HOME/.var/app/$APP_ID/data/stremio-skip-intro"

say() { printf '  %s\n' "$1"; }
fail() { printf '  \033[31m%s\033[0m\n' "$1"; exit 1; }

printf '\n  \033[35mSkip Intro for Stremio (Linux)\033[0m\n  ------------------------------\n'

[ "$(uname -s)" = "Linux" ] || fail "This installer is for Linux."
command -v flatpak >/dev/null || fail "Flatpak is not installed. Stremio for Linux comes as a Flatpak: https://flathub.org/apps/com.stremio.Stremio"
if ! flatpak info "$APP_ID" >/dev/null 2>&1; then
  say "Stremio (Flatpak) was not found. Install it first:"
  say "  flatpak install flathub $APP_ID"
  say "Then run this installer again."
  exit 1
fi

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) WANT_SHA="$SHA256_x86_64" ;;
  aarch64|arm64) ARCH="aarch64"; WANT_SHA="$SHA256_aarch64" ;;
  *) fail "This processor ($ARCH) is not supported yet." ;;
esac
command -v curl >/dev/null || fail "curl is needed."
command -v sha256sum >/dev/null || fail "sha256sum is needed."

say "Downloading the plugin..."
mkdir -p "$DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$REPO_RAW/skip-intro.plugin.js" -o "$TMP/skip-intro.plugin.js"
curl -fsSL "$REPO_RAW/linux/libskipintro-$ARCH.so" -o "$TMP/libskipintro.so"
grep -q '@name Skip Intro' "$TMP/skip-intro.plugin.js" || fail "The plugin download looks wrong. Try again later."
GOT="$(sha256sum "$TMP/libskipintro.so" | awk '{print $1}')"
[ "$GOT" = "$WANT_SHA" ] || fail "The helper library failed its checksum. Try again later."
mv -f "$TMP/skip-intro.plugin.js" "$DIR/skip-intro.plugin.js"
mv -f "$TMP/libskipintro.so" "$DIR/libskipintro.so"
chmod 644 "$DIR/libskipintro.so" "$DIR/skip-intro.plugin.js"

say "Connecting it to Stremio..."
flatpak override --user --env=LD_PRELOAD="$DIR/libskipintro.so" "$APP_ID"

# Plugin updates (the helper only reads the file; this keeps it fresh).
cat > "$DIR/update.sh" <<UPDATE
#!/bin/sh
# Fetches the latest Skip Intro plugin for Stremio. Run by a systemd user timer.
TMPF="$DIR/.plugin.download"
if curl -fsSL --max-time 20 "$REPO_RAW/skip-intro.plugin.js" -o "\$TMPF" \\
   && [ "\$(wc -c < "\$TMPF")" -gt 10000 ] && grep -q '@name Skip Intro' "\$TMPF"; then
  mv -f "\$TMPF" "$DIR/skip-intro.plugin.js"
else
  rm -f "\$TMPF"
fi
UPDATE
chmod +x "$DIR/update.sh"

if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_DIR/stremio-skip-intro-update.service" <<UNIT
[Unit]
Description=Update the Skip Intro plugin for Stremio

[Service]
Type=oneshot
ExecStart=$DIR/update.sh
UNIT
  cat > "$UNIT_DIR/stremio-skip-intro-update.timer" <<UNIT
[Unit]
Description=Update the Skip Intro plugin for Stremio every 6 hours

[Timer]
OnBootSec=5min
OnUnitActiveSec=6h
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now stremio-skip-intro-update.timer >/dev/null 2>&1 || true
else
  say "(No systemd user session: plugin updates will need this installer to be run again.)"
fi

printf '\n  \033[32mInstalled!\033[0m Open Stremio as usual and play an episode.\n'
if flatpak ps --columns=application 2>/dev/null | grep -qx "$APP_ID"; then
  say "Stremio is open right now: close it and open it again to load Skip Intro."
fi
say "Uninstall any time: curl -fsSL https://osamudazai666.github.io/stremio-skip-intro/uninstall-linux.sh | bash"
echo
