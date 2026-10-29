#!/sbin/sh
# Writes boot/recovery images from TWRP, checking each partition before and after. Run it in TWRP,
# as root (tools/setup/toque-setup.sh does it for the 'flash-root' and 'unroot' steps):
#   sh twrp-flash.sh <partition> <image> <sha256> <allowed> [<partition> <image> <sha256> <allowed>]...
#
# <allowed> lists what the partition may hold before writing, as size:sha256 items separated by
# commas (sha256 of its first <size> bytes). Nothing at all is written if a partition is not 16 MiB,
# holds anything else, or an image differs from its sha256. Partitions are then written in the order
# given and each one is read back (page cache dropped) and compared with its image.
# Uses the busybox in $BB (default: the busybox next to this script).
BB=${BB:-${0%/*}/busybox}
SIZE=${SIZE:-16777216}
die() { echo "ERROR: $*"; exit 1; }

[ -x "$BB" ] || die "busybox missing: $BB"
[ $# -gt 0 ] && [ $(($# % 4)) -eq 0 ] || die "usage: twrp-flash.sh <partition> <image> <sha256> <allowed>..."
# shellcheck disable=SC2012 # same lookup as twrp-root.sh (TWRP may have no find)
B=${BYNAME:-$(ls -d /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name 2>/dev/null | head -n 1)}
[ -d "$B" ] || die "partition folder (by-name) not found"
ARGS="$*"

# sha256 of the first $2 bytes of $1
hash_of() { "$BB" head -c "$2" "$1" | "$BB" sha256sum | "$BB" cut -d ' ' -f 1; }
device_of() { "$BB" readlink -f "$B/$1"; }
drop_caches() {
  sync
  "$BB" blockdev --flushbufs "$1" 2>/dev/null
  [ -w /proc/sys/vm/drop_caches ] && echo 3 > /proc/sys/vm/drop_caches
}

echo "== 1. Checks (nothing is written if one fails)"
# shellcheck disable=SC2086 # ARGS holds fixed paths and hex digests, no spaces
set -- $ARGS
while [ $# -ge 4 ]; do
  dev=$(device_of "$1")
  [ -b "$dev" ] || die "$1: no block device at $B/$1"
  size=$("$BB" blockdev --getsize64 "$dev")
  [ "$size" = "$SIZE" ] || die "$1 ($dev) is $size bytes, expected $SIZE: not the expected partition"
  [ -f "$2" ] || die "$2 missing"
  len=$("$BB" stat -c %s "$2")
  [ "$len" -gt 0 ] && [ "$len" -le "$SIZE" ] || die "$2 is $len bytes: it does not fit in $1"
  [ "$("$BB" sha256sum < "$2" | "$BB" cut -d ' ' -f 1)" = "$3" ] || die "$2 differs from the PC's copy (sha256)"
  if [ "$(hash_of "$dev" "$len")" = "$3" ]; then
    echo "$1 ($dev): already holds $2"
  else
    found=""
    old_ifs=$IFS; IFS=,
    for item in $4; do
      if [ "$(hash_of "$dev" "${item%%:*}")" = "${item#*:}" ]; then found=$item; break; fi
    done
    IFS=$old_ifs
    [ -n "$found" ] || die "$1 ($dev) holds something unexpected: nothing was written"
    echo "$1 ($dev): expected content, will be replaced"
  fi
  shift 4
done

echo "== 2. Writing (do not switch the robot off)"
# shellcheck disable=SC2086
set -- $ARGS
while [ $# -ge 4 ]; do
  dev=$(device_of "$1")
  len=$("$BB" stat -c %s "$2")
  if [ "$(hash_of "$dev" "$len")" != "$3" ]; then
    "$BB" dd if="$2" of="$dev" bs=1048576 conv=fsync 2>&1 || die "$1: write failed. Do NOT switch the robot off: run the step again"
    drop_caches "$dev"
    [ "$(hash_of "$dev" "$len")" = "$3" ] || die "$1: read-back differs after writing. Do NOT switch the robot off: run the step again"
    echo "$1: written and verified"
  else
    echo "$1: unchanged"
  fi
  shift 4
done
sync
echo "== Done"
