#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.cb.monitor"
DEVICE_SERIAL="${1:-${ANDROID_SERIAL:-}}"

resolve_device_serial() {
  if [[ -n "$DEVICE_SERIAL" ]]; then
    echo "$DEVICE_SERIAL"
    return
  fi
  local detected
  detected="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
  if [[ -z "$detected" ]]; then
    echo "No online Android device found. Pass serial as first argument." >&2
    exit 1
  fi
  echo "$detected"
}

resolve_zipalign() {
  if command -v zipalign >/dev/null 2>&1; then
    command -v zipalign
    return
  fi

  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk_root" ]]; then
    local candidate
    candidate="$(ls -1 "$sdk_root"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -n 1 || true)"
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  fi

  local mac_candidate
  mac_candidate="$(ls -1 "$HOME"/Library/Android/sdk/build-tools/*/zipalign 2>/dev/null | sort -V | tail -n 1 || true)"
  if [[ -n "$mac_candidate" && -x "$mac_candidate" ]]; then
    echo "$mac_candidate"
    return
  fi

  echo "zipalign not found. Install Android build-tools and retry." >&2
  exit 1
}

ensure_java17() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    return
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(
      /usr/libexec/java_home -v 17 2>/dev/null || true
    )"
  fi

  if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "JAVA_HOME is not set and Java 17 was not auto-detected." >&2
    exit 1
  fi
}

DEVICE_SERIAL="$(resolve_device_serial)"
ZIPALIGN_BIN="$(resolve_zipalign)"
ensure_java17

echo "==> Device: $DEVICE_SERIAL"
echo "==> JAVA_HOME: $JAVA_HOME"
echo "==> Building debug APK"
(cd "$ROOT_DIR" && ./gradlew :app:assembleDebug)

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi

echo "==> Verifying 16KB page alignment"
"$ZIPALIGN_BIN" -c -P 16 -v 4 "$APK_PATH" >/tmp/cb-monitor-zipalign.log
echo "16KB alignment OK"

echo "==> Installing APK"
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"

echo "==> Installed package info"
adb -s "$DEVICE_SERIAL" shell dumpsys package "$PACKAGE_NAME" | rg "versionName=|versionCode="

echo "Done."
