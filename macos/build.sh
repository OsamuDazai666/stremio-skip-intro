#!/bin/bash
# Builds SkipIntroInject.dylib (universal: Apple Silicon + Intel). Needs Xcode Command Line Tools.
set -euo pipefail
cd "$(dirname "$0")"
clang -dynamiclib -fobjc-arc -O2 \
  -arch arm64 -arch x86_64 \
  -mmacosx-version-min=12.0 \
  -framework Foundation -framework WebKit \
  -o SkipIntroInject.dylib SkipIntroInject.m
codesign --force --sign - SkipIntroInject.dylib
lipo -info SkipIntroInject.dylib
