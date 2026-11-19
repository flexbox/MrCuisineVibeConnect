#!/system/bin/sh
# Run as root on the robot (tools/install-robot.sh or install-robot.ps1 does it after installing Toque).
# 1. Allows Toque in Magisk and verifies its saved, permanent allow policy, without an on-screen
#    prompt (the robot has no way to reach Magisk's settings).
# 2. Makes sure Magisk's busybox is in /data/adb/magisk: Toque needs it for the serial port.
# 3. On Magisk 31.0's legacy user-0 layout only, restores Toque's policy after boot pruning.
U=$(stat -c %u /data/data/app.toque 2>/dev/null)
[ -n "$U" ] || { echo "Toque is not installed"; exit 1; }
case "$U" in *[!0-9]*) echo "Cannot determine Toque's numeric UID" >&2; exit 1 ;; esac
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" ||
  { echo "Could not save Toque's Magisk policy" >&2; exit 1; }
P=$(magisk --sqlite "SELECT uid,policy,until FROM policies WHERE uid=$U;") ||
  { echo "Could not read Toque's Magisk policy" >&2; exit 1; }
[ "$P" = "uid=$U|policy=2|until=0" ] ||
  { echo "Magisk did not confirm a permanent allow policy for Toque (UID $U)" >&2; exit 1; }
echo "Toque allowed in Magisk (UID $U, permanent policy verified)"
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

install_legacy_boot_grant() {
  [ "$(magisk -V 2>/dev/null)" = 31000 ] || return 0
  case "$(readlink /data/user/0 2>/dev/null)" in /data/data|/data/data/) ;; *) return 0 ;; esac
  B=/data/adb/magisk/busybox
  [ -x "$B" ] || { echo "Legacy boot grant requires Magisk busybox" >&2; return 1; }
  [ "$("$B" awk -F= '$1 == "ro.build.version.sdk" { print $2 }' /system/build.prop 2>/dev/null)" = 23 ] || return 0
  D=/data/adb/post-fs-data.d
  H=$D/toque-root-grant.sh
  [ ! -L "$D" ] || { echo "Refusing symlinked Magisk boot directory" >&2; return 1; }
  if [ ! -e "$D" ]; then
    "$B" mkdir -m 700 "$D" || return 1
  fi
  [ -d "$D" ] && [ "$("$B" stat -c %u "$D")" = 0 ] ||
    { echo "Unsafe Magisk boot directory" >&2; return 1; }
  T=$("$B" mktemp "$D/.toque-root-grant.XXXXXX") || return 1
  # Creating the temporary file in the target directory preserves its supported SELinux context.
  # Magisk 31.0 labels /data/adb adb_data_file; magisk_file is not required on legacy devices.
  cat > "$T" <<'TOQUE_ROOT_GRANT_HOOK'
#!/system/bin/sh
# Toque-owned workaround for Magisk 31.0 pruning app grants when user/0 is a symlink.
# Runs once after Magisk's boot prune. Framework/zygote restarts require an upstream fix.
B=/data/adb/magisk/busybox
[ -x "$B" ] || exit 0
[ "$(magisk -V 2>/dev/null)" = 31000 ] || exit 0
# Read a file directly: never contact Android's property service during post-fs-data.
[ "$("$B" awk -F= '$1 == "ro.build.version.sdk" { print $2 }' /system/build.prop 2>/dev/null)" = 23 ] || exit 0
case "$("$B" readlink /data/user/0 2>/dev/null)" in /data/data|/data/data/) ;; *) exit 0 ;; esac
[ "$("$B" id -u)" = 0 ] || exit 1

umask 077
L=/data/adb/toque-root-grant.log
[ ! -L "$L" ] || exit 1
if [ -e "$L" ]; then
  [ -f "$L" ] && [ "$("$B" stat -c %u "$L")" = 0 ] || exit 1
fi
: > "$L" || exit 1
"$B" chmod 600 "$L" || exit 1
BOOT=$("$B" cat /proc/sys/kernel/random/boot_id 2>/dev/null)
[ -n "$BOOT" ] || BOOT=unknown
BEFORE=not-read
AFTER=not-read
finish() {
  {
    printf 'boot_id=%.64s\n' "$BOOT"
    printf 'before=%.200s\n' "$BEFORE"
    printf 'result=%s\n' "$1"
    printf 'after=%.200s\n' "$AFTER"
  } > "$L"
  exit "$2"
}

[ -d /data/data/app.toque ] && [ ! -L /data/data/app.toque ] || finish missing-app-directory 1
U=$("$B" stat -c %u /data/data/app.toque 2>/dev/null) || finish uid-read-failed 1
case "$U" in 1[0-9][0-9][0-9][0-9]) ;; *) finish invalid-app-uid 1 ;; esac
PUID=$("$B" awk '$1 == "app.toque" { count++; uid = $2 }
  END { if (count == 1 && uid ~ /^[0-9]+$/) print uid; else exit 1 }' /data/system/packages.list) ||
  finish package-uid-missing-or-ambiguous 1
[ "$PUID" = "$U" ] || finish package-uid-mismatch 1

Q="SELECT uid,policy,until,logging,notification FROM policies WHERE uid=$U;"
EXPECTED="uid=$U|policy=2|until=0|logging=0|notification=0"
BEFORE=$("$B" timeout 3 magisk --sqlite "$Q" 2>/dev/null) || finish read-before-failed 1
[ -n "$BEFORE" ] || BEFORE=missing
"$B" timeout 3 magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($U,2,0,0,0)" >/dev/null 2>&1 ||
  finish grant-failed 1
AFTER=$("$B" timeout 3 magisk --sqlite "$Q" 2>/dev/null) || finish read-after-failed 1
[ -n "$AFTER" ] || AFTER=missing
# Some Magisk CLI SQL errors still return exit 0, so only this exact row confirms success.
[ "$AFTER" = "$EXPECTED" ] || finish policy-verification-failed 1
finish granted 0
TOQUE_ROOT_GRANT_HOOK
  if [ "$?" != 0 ]; then "$B" rm -f "$T"; return 1; fi
  if [ -e "$H" ] || [ -L "$H" ]; then
    if [ -L "$H" ] || [ ! -f "$H" ] || ! "$B" cmp -s "$T" "$H"; then
      "$B" rm -f "$T"
      echo "Refusing to replace an unexpected Toque boot hook: $H" >&2
      return 1
    fi
  fi
  if ! "$B" chown 0:0 "$T" || ! "$B" chmod 700 "$T" ||
     [ "$("$B" stat -c %u:%g:%a "$T")" != 0:0:700 ] || ! "$B" sh -n "$T"; then
    "$B" rm -f "$T"
    echo "Could not validate Toque's private boot hook" >&2
    return 1
  fi
  "$B" mv -f "$T" "$H" || { "$B" rm -f "$T"; return 1; }
  [ ! -L "$H" ] && [ "$("$B" stat -c %u:%g:%a "$H")" = 0:0:700 ] ||
    { echo "Could not verify installed Toque boot hook" >&2; return 1; }
  echo "Toque legacy boot grant installed (Magisk 31.0, user-0 symlink only)"
}

install_legacy_boot_grant
