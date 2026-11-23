#!/system/bin/sh
# Run as root on the robot (tools/install-robot.sh or install-robot.ps1 does it after installing Toque).
# 1. Allows Toque in Magisk, without the on-screen prompt: that prompt closes by itself after
#    10 s and Magisk then refuses Toque (the robot has no way to reach Magisk's settings).
# 2. Installs a boot script that allows it again at every start. Each time it starts, Magisk 31
#    removes the permissions of the apps it believes uninstalled, and on Android 6 that is every
#    app: it looks for them in /data/user, where "0" is a symlink to /data/data that its scan
#    skips. Without the script the prompt comes back at every restart.
# 3. Makes sure Magisk's busybox is in /data/adb/magisk: Toque needs it for the serial port.
U=$(stat -c %u /data/data/app.toque 2>/dev/null)
[ -n "$U" ] || { echo "Toque is not installed"; exit 1; }
if magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)"; then
  echo "Toque allowed in Magisk"
fi
# Magisk runs /data/adb/service.d/*.sh at every start, after that clean-up and before Android
# starts Toque. The script does nothing once Toque is uninstalled.
S=/data/adb/service.d/toque-su.sh
mkdir -p /data/adb/service.d
{
  echo '#!/system/bin/sh'
  echo '# Written by Toque (tools/robot/setup-root.sh): allows Toque in Magisk at every start.'
  echo 'U=$(stat -c %u /data/data/app.toque 2>/dev/null)'
  echo '[ -n "$U" ] && magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" >/dev/null 2>&1'
} > "$S" && chmod 755 "$S" && echo "Boot script installed: Toque stays allowed after a restart"
if [ ! -x /data/adb/magisk/busybox ]; then
  for b in /data/app/com.topjohnwu.magisk*/lib/arm*/libbusybox.so; do
    [ -f "$b" ] || continue
    mkdir -p /data/adb/magisk
    cp "$b" /data/adb/magisk/busybox && chmod 755 /data/adb/magisk/busybox
    chcon u:object_r:magisk_file:s0 /data/adb/magisk/busybox 2>/dev/null
    echo "Magisk busybox installed"
    break
  done
fi
[ -x /data/adb/magisk/busybox ] || echo "WARNING: no busybox in /data/adb/magisk (install the Magisk app: adb install Magisk-vXX.apk)"
