#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"

test -s "$APK"
unzip -tq "$APK"
"$BUILD_TOOLS/apksigner" verify --verbose "$APK"
"$BUILD_TOOLS/zipalign" -c -v 4 "$APK"
if unzip -Z1 "$APK" | grep -q '^lib/\(armeabi-v7a\|x86\|x86_64\)/'; then
  echo "Unexpected non-arm64 native library in APK" >&2
  exit 1
fi
sha256sum "$APK"
