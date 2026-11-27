#!/bin/bash
# Developer helper (run on the Mac): evaluate JavaScript in Stremio's page.
# Needs Stremio started with SKIPINTRO_DEV=1 (see macos/dev-launch.sh).
# Usage: dev-eval.sh 'return JSON.stringify(window.SkipIntro.status())'
#   (the code runs as an async function body: use `return` and `await`)
D="$HOME/Library/Application Support/StremioSkipIntro"
rm -f "$D/eval.out"
printf '%s' "$1" > "$D/eval.js"
for _ in $(seq 1 60); do
  if [ -f "$D/eval.out" ]; then cat "$D/eval.out"; echo; exit 0; fi
  sleep 0.25
done
echo "timeout: is Stremio running with SKIPINTRO_DEV=1?" >&2
exit 1
