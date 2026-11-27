#!/bin/bash
# Builds libskipintro.so for x86_64 and aarch64.
# Needs gcc, plus gcc-aarch64-linux-gnu for the ARM build (Debian/Ubuntu package names).
# Built on an older distro (CI uses Ubuntu 22.04) so it runs with any newer glibc,
# including the GNOME Flatpak runtime Stremio uses.
set -euo pipefail
cd "$(dirname "$0")"

CFLAGS="-shared -fPIC -O2 -Wall -Wextra -fvisibility=default -Wl,--no-undefined -Wl,-z,defs"
gcc $CFLAGS -o libskipintro-x86_64.so skipintro.c -ldl
if command -v aarch64-linux-gnu-gcc >/dev/null; then
  aarch64-linux-gnu-gcc $CFLAGS -o libskipintro-aarch64.so skipintro.c -ldl
else
  echo "aarch64-linux-gnu-gcc not found, skipping the ARM build" >&2
fi
strip --strip-unneeded libskipintro-x86_64.so
[ -f libskipintro-aarch64.so ] && aarch64-linux-gnu-strip --strip-unneeded libskipintro-aarch64.so
sha256sum libskipintro-*.so
