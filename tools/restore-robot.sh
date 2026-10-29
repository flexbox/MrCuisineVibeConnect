#!/usr/bin/env bash
# Gives control back to the stock Monsieur Cuisine app and removes Toque. macOS port of restore-robot.ps1.
# Usage: tools/restore-robot.sh [--ip 192.168.1.x] [--uninstall]
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd -P)
IP=""
UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --ip) IP=${2:?--ip needs an address}; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    *) echo "Usage: tools/restore-robot.sh [--ip 192.168.1.x] [--uninstall]"; exit 1 ;;
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

[ -n "$IP" ] && "$ADB" connect "$IP:5555"
adb_ push "$REPO/tools/robot/restore.sh" /data/local/tmp/toque-restore.sh > /dev/null
adb_ shell "su -c 'sh /data/local/tmp/toque-restore.sh'"
[ "$UNINSTALL" = 1 ] && adb_ uninstall app.toque
adb_ reboot
echo "Robot restarted on the stock app."
