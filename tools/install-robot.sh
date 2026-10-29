#!/usr/bin/env bash
# Installs Toque on the Monsieur Cuisine Connect (rooted, ADB enabled). macOS port of install-robot.ps1.
# Usage: tools/install-robot.sh [--apk path.apk] [--ip 192.168.1.x]
# adb: $ADB if set, else the Android SDK's, else the one on the PATH.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd -P)
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
IP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=${2:?--apk needs a file}; shift 2 ;;
    --ip) IP=${2:?--ip needs an address}; shift 2 ;;
    *) echo "Usage: tools/install-robot.sh [--apk path.apk] [--ip 192.168.1.x]"; exit 1 ;;
  esac
done
ADB=${ADB:-}
if [ -z "$ADB" ]; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
    [ -x "$c" ] && { ADB=$c; break; }
  done
fi
ADB=${ADB:-adb}
adb_() { if [ -n "$IP" ]; then "$ADB" -s "$IP:5555" "$@"; else "$ADB" "$@"; fi; }

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK (./gradlew assembleDebug, or download it from GitHub Releases)"
  exit 1
fi
[ -n "$IP" ] && "$ADB" connect "$IP:5555"
"$ADB" devices

root=$(adb_ shell "su -c 'id -u'" 2> /dev/null | tr -d '\r[:space:]')
if [ "$root" != 0 ]; then echo "No root access: install Magisk first (see docs/INSTALLATION.md)."; exit 1; fi

echo "Installing Toque..."
adb_ install -r "$APK" || { echo "Installation failed."; exit 1; }
adb_ push "$REPO/tools/robot/restore.sh" /data/local/tmp/toque-restore.sh > /dev/null
adb_ push "$REPO/tools/robot/setup-root.sh" /data/local/tmp/toque-setup-root.sh > /dev/null
adb_ shell "su -c 'sh /data/local/tmp/toque-setup-root.sh'"
adb_ shell am start -n app.toque/.ui.MainActivity > /dev/null
echo ""
echo "Toque is running. On the robot:"
echo "  1. Settings > Machine > Real machine > Apply (first test: EMPTY bowl)."
echo "  2. Toque now opens by itself when the robot starts (the robot has no Home button)."
echo "  3. Settings > Lidl recipes > Sync."
echo "To undo everything: tools/restore-robot.sh"
