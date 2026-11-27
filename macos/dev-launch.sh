#!/bin/bash
# Developer helper (run on the Mac): restart Stremio with the injector in developer mode
# (debug log + eval channel + Safari Web Inspector).
D="$HOME/Library/Application Support/StremioSkipIntro"
pgrep -f '/Applications/Stremio.app/Contents/MacOS/Stremio' >/dev/null && { osascript -e 'quit app "Stremio"'; sleep 3; }
open -a /Applications/Stremio.app --env DYLD_INSERT_LIBRARIES="$D/SkipIntroInject.dylib" --env SKIPINTRO_DEV=1
