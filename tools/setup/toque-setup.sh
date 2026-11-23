#!/usr/bin/env bash
# Toque setup for the Monsieur Cuisine Connect (SKMC 1200), step by step, from a Mac or a Linux PC.
# Written to be driven by an AI agent (see AGENTS.md) or by hand. Every step is safe to re-run.
#
#   tools/setup/toque-setup.sh status
#
# Steps, in order: status, prepare, backup, flash-twrp, root, flash-root, android, install, wifi.
# SKMC 1100 A1 only: one more step, own-twrp, right after backup (see AGENTS.md). It builds, on
# this computer, the guide's TWRP around the robot's own kernel (port of Wuki60 v0.7.1, issue #10).
# 'unroot' puts the original boot and recovery back. Lines starting with "HUMAN:" are actions only a
# person can do (unscrewing, cables, power switch, a macOS prompt, a tap on the robot's screen).
# A step that needs one stops and says so; run it again once the person is done.
#
# macOS port of toque-setup.ps1. SP Flash Tool and its Windows driver are replaced by mtkclient
# (github.com/bkerler/mtkclient, no driver needed), used only where the robot's MediaTek download
# mode is required: 'backup' and 'flash-twrp'. 'flash-root' and 'unroot' write from TWRP instead.
# Every write is read back and compared with its image. mtkclient runs through the macOS serial
# driver, with the fixes in tools/setup/mtkclient-macos-serial.patch ('prepare' applies them and
# the download-mode steps refuse to run without them).
#
# Linux: same steps. mtkclient goes through libusb by default (its usual way on Linux; --serial uses
# /dev/ttyACM* and the same fixes instead), and 'prepare' asks for the udev rule in
# tools/setup/51-toque-robot.rules, which gives the logged-in user access to the robot's USB port.
# Download mode from Linux has not been tried on a real robot yet.
#
# Options: --work DIR (default: a "Toque" folder next to this repository if there is one, else
# ~/Toque), --apk FILE, --ip ROBOT_IP, --wait-seconds N (adb, default
# 180), --mtk-timeout N (download mode, default 1800), --libusb / --serial (how mtkclient talks to
# the robot: libusb, or the system's serial driver; default --serial on macOS, --libusb on Linux),
# --download-mode ('unroot' through download mode instead of TWRP), --no-udev-check (Linux: USB
# access is set up another way than with tools/setup/51-toque-robot.rules), --guide-twrp
# ('flash-twrp' writes the guide's TWRP although its kernel differs from the robot's: SKMC 1200).
set -u
set -o pipefail

STEPS="status prepare backup own-twrp flash-twrp root flash-root android install wifi unroot"
STEP=""
WORK=""
APK=""
IP=""
WAIT_SECONDS=180
MTK_TIMEOUT=1800
MTK_SERIAL=""  # 1: serial driver, 0: libusb. Default set below, by system.
UDEV_CHECK=1
DOWNLOAD_MODE=0
GUIDE_TWRP=0

usage() {
  echo "Usage: tools/setup/toque-setup.sh <step> [--work DIR] [--apk FILE] [--ip ROBOT_IP]"
  echo "       [--wait-seconds N] [--mtk-timeout N] [--libusb | --serial] [--download-mode] [--no-udev-check]"
  echo "       [--guide-twrp]"
  echo "Steps: $STEPS"
}
opt_value() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "Option $1 needs a value." >&2; usage >&2; exit 1; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --step) opt_value "$@"; STEP=$2; shift 2 ;;
    --work) opt_value "$@"; WORK=$2; shift 2 ;;
    --apk) opt_value "$@"; APK=$2; shift 2 ;;
    --ip) opt_value "$@"; IP=$2; shift 2 ;;
    --wait-seconds) opt_value "$@"; WAIT_SECONDS=$2; shift 2 ;;
    --mtk-timeout) opt_value "$@"; MTK_TIMEOUT=$2; shift 2 ;;
    --serial) MTK_SERIAL=1; shift ;;
    --libusb) MTK_SERIAL=0; shift ;;
    --download-mode) DOWNLOAD_MODE=1; shift ;;
    --no-udev-check) UDEV_CHECK=0; shift ;;
    --guide-twrp) GUIDE_TWRP=1; shift ;;
    -h | --help) usage; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
    *)
      [ -z "$STEP" ] || { echo "Unexpected argument: $1" >&2; usage >&2; exit 1; }
      STEP=$1; shift ;;
  esac
done
STEP=${STEP:-status}
case " $STEPS " in *" $STEP "*) ;; *) echo "Unknown step: $STEP" >&2; usage >&2; exit 1 ;; esac
case "$WAIT_SECONDS$MTK_TIMEOUT" in *[!0-9]*) echo "--wait-seconds and --mtk-timeout take a number of seconds." >&2; exit 1 ;; esac

OS=$(uname -s)
case "$OS" in
  Darwin) HOST="Mac" ;;
  Linux) HOST="computer" ;;
  *) echo "This script runs on macOS and Linux (on Windows: tools/setup/toque-setup.ps1)." >&2; exit 1 ;;
esac
# macOS: the serial driver is the only way that worked on a real robot (libusb: handshake fails).
# Linux: libusb is mtkclient's usual way.
if [ -z "$MTK_SERIAL" ]; then
  if [ "$OS" = Darwin ]; then MTK_SERIAL=1; else MTK_SERIAL=0; fi
fi

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
if [ -z "$WORK" ]; then
  # A "Toque" folder next to the repository (both kept in one parent folder), else ~/Toque.
  if [ -d "$(dirname "$REPO")/Toque" ]; then WORK="$(dirname "$REPO")/Toque"; else WORK="$HOME/Toque"; fi
fi
if [ -n "$APK" ] && [ -f "$APK" ]; then APK="$(cd "$(dirname "$APK")" && pwd -P)/$(basename "$APK")"; fi
DOWNLOADS="$WORK/downloads"
BACKUP="$WORK/backup"
HACK="$WORK/hack"
STATE_FILE="$WORK/state.txt"
LOG="$WORK/setup-log.txt"
MTK_SRC="$WORK/mtkclient/src"
MTK_VENV="$WORK/mtkclient/venv"
MTK_RUN="$WORK/mtkclient/run"
MTK_PATCH="$REPO/tools/setup/mtkclient-macos-serial.patch"
UDEV_RULE="$REPO/tools/setup/51-toque-robot.rules"
UDEV_INSTALLED="/etc/udev/rules.d/51-toque-robot.rules"
BOOTIMG_TOOL="$REPO/tools/setup/bootimg_tool.py"
OWN_TWRP="$WORK/twrp-own-kernel.img"

# Known-good files (SHA-256). The scatter and TWRP come from github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack.
MAGISK_URL="https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk"
MAGISK_SHA="2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8"
HACK_ZIP_URL="https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/archive/refs/heads/master.zip"
TWRP_SHA="fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e"
TWRP_SIZE=11452416
SCATTER_SHA="13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364"
TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
[ "$OS" = Linux ] && TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
RELEASES_API="https://api.github.com/repos/Wuki60/MrCuisineVibeConnect/releases/latest"
# mtkclient: a tag, checked against its commit (a moved tag is refused).
MTK_URL="https://github.com/bkerler/mtkclient"
MTK_TAG="v2.1.4.1"
MTK_COMMIT="5a863eece86fcaa97cb8325cf747e0aae3c307e4"
PARTITION_SIZE=16777216      # boot and recovery: 16 MiB each
BOOT_OFFSET=0x1d20000        # in EMMC_USER, from the scatter file ('prepare' checks it)
RECOVERY_OFFSET=0x2d20000
BYNAME="/dev/block/platform/mtk-msdc.0/11120000.msdc0/by-name"

# ---------------------------------------------------------------- helpers

say() {
  printf '%s\n' "$*"
  { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; } 2>/dev/null || true
}
human() { say "HUMAN: $*"; }
ok() { say "OK: $*"; }
fail() { say "STOP: $*"; exit 2; }
need_human() { human "$*"; say "Run this step again when it is done."; exit 3; }
now() { date '+%Y-%m-%dT%H:%M:%S'; }

state_has() { [ -f "$STATE_FILE" ] && grep -q "^$1=" "$STATE_FILE"; }
state_get() { [ -f "$STATE_FILE" ] && sed -n "s/^$1=//p" "$STATE_FILE" | tail -n 1; }
state_set() {
  { [ -f "$STATE_FILE" ] && grep -v "^$1=" "$STATE_FILE"; printf '%s=%s\n' "$1" "$2"; } > "$STATE_FILE.tmp"
  mv "$STATE_FILE.tmp" "$STATE_FILE"
}

if [ "$OS" = Darwin ]; then
  sha() { shasum -a 256 "$1" | awk '{print $1}'; }
  fsize() { stat -f %z "$1"; }
else
  sha() { sha256sum "$1" | awk '{print $1}'; }
  fsize() { stat -c %s "$1"; }
fi
hex() { printf '0x%x' "$1"; }

fetch() {
  say "Downloading $1"
  if ! curl -fsSL --retry 3 --proto '=https' -A toque-setup -o "$2.part" "$1"; then
    rm -f "$2.part"
    fail "Download failed: $1"
  fi
  mv "$2.part" "$2"
}

# adb: the one 'prepare' downloads, else the Android SDK's, else the PATH.
ADB=""
find_adb() {
  local c
  for c in "$WORK/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -x "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  command -v adb
}
resolve_adb() {
  [ -n "$ADB" ] && return 0
  ADB=$(find_adb) || fail "adb not found: run the 'prepare' step first."
  if [ -n "$IP" ]; then "$ADB" connect "$IP:5555" > /dev/null 2>&1; fi
}
# Runs adb (on the robot at --ip when given); output without carriage returns.
adb_run() {
  if [ -n "$IP" ]; then "$ADB" -s "$IP:5555" "$@" 2>&1 | tr -d '\r'
  else "$ADB" "$@" 2>&1 | tr -d '\r'; fi
}

# State of the robot as adb sees it: "recovery" (TWRP), "device" (Android), "unauthorized", or "".
adb_state() {
  "$ADB" devices 2>/dev/null | tr -d '\r' |
    awk -F '\t' -v ip="$IP" 'NF >= 2 && (ip == "" || index($1, ip) == 1) { print $2; exit }'
}
wait_adb_state() { # wait_adb_state <seconds> <state>...
  local deadline st w
  deadline=$(($(date +%s) + $1)); shift
  while [ "$(date +%s)" -lt "$deadline" ]; do
    st=$(adb_state)
    for w in "$@"; do [ "$st" = "$w" ] && { printf '%s' "$st"; return 0; }; done
    sleep 3
  done
  st=$(adb_state)
  case "$st" in "no permissions"*)
    fail "adb sees the robot but may not open its USB port (Linux). Install the udev rule: $(udev_command) Then unplug and replug the cable and run '$STEP' again." ;;
  esac
  printf '%s' "$st"
}

check_boot_image() { # check_boot_image <file> <name>
  [ -f "$1" ] || fail "$2 missing: $1"
  local len
  len=$(fsize "$1")
  [ "$len" = "$PARTITION_SIZE" ] || fail "$2 has the wrong size ($len bytes, expected $PARTITION_SIZE)."
  [ "$(LC_ALL=C head -c 8 "$1")" = "ANDROID!" ] || fail "$2 is not an Android boot image (no ANDROID! header): do not continue."
}
check_twrp() {
  [ -f "$HACK/recovery.img" ] && [ "$(sha "$HACK/recovery.img")" = "$TWRP_SHA" ] ||
    fail "TWRP ($HACK/recovery.img) missing or checksum mismatch: run 'prepare'."
}
# The TWRP image the flash steps write, in $TWRP_IMG: the one built by 'own-twrp' (SKMC 1100 A1),
# else the guide's. (Not through $(...): fail must stop the script, not a subshell.)
TWRP_IMG=""
select_twrp() {
  local want
  want=$(state_get twrp-own-kernel-sha256)
  if [ -n "$want" ]; then
    [ -f "$OWN_TWRP" ] || fail "twrp-own-kernel.img is missing: run 'own-twrp' again."
    [ "$(sha "$OWN_TWRP")" = "$want" ] || fail "twrp-own-kernel.img does not match the checksum recorded by 'own-twrp': run 'own-twrp' again."
    TWRP_IMG=$OWN_TWRP
  else
    check_twrp
    TWRP_IMG="$HACK/recovery.img"
  fi
}
# Offline helper (tools/setup/bootimg_tool.py) with the system Python, isolated mode.
bootimg_tool() {
  local py
  py=$(find_python) || fail "Python 3.10 or newer is needed. $(pkg_hint python)"
  "$py" -I "$BOOTIMG_TOOL" "$@"
}

# boot and recovery offsets and sizes in the scatter file must be the ones this script writes to.
scatter_field() { # scatter_field <partition> <field>
  awk -v p="$1" -v f="$2:" '{ sub(/\r$/, "") } $1 == "partition_name:" { cur = $2 } cur == p && $1 == f { print $2; exit }' \
    "$HACK/l706_dfbh_v_695scatter.txt"
}
check_layout() {
  local part off
  [ -f "$HACK/l706_dfbh_v_695scatter.txt" ] && [ "$(sha "$HACK/l706_dfbh_v_695scatter.txt")" = "$SCATTER_SHA" ] ||
    fail "Scatter file missing or checksum mismatch: run 'prepare'. This guide is only for the SKMC 1200."
  for part in boot recovery; do
    off=$BOOT_OFFSET; [ "$part" = recovery ] && off=$RECOVERY_OFFSET
    [ "$(scatter_field $part region)" = EMMC_USER ] &&
      [ $(($(scatter_field $part linear_start_addr))) -eq $((off)) ] &&
      [ $(($(scatter_field $part partition_size))) -eq "$PARTITION_SIZE" ] ||
      fail "The scatter file does not place '$part' at $off ($PARTITION_SIZE bytes, EMMC_USER): do not flash."
  done
}

find_python() { # Python 3.10 or newer, for mtkclient
  local c
  for c in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
    command -v "$c" > /dev/null 2>&1 || continue
    if "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' > /dev/null 2>&1; then
      command -v "$c"; return 0
    fi
  done
  return 1
}
find_libusb() { # folder holding libusb 1.0 (macOS: Homebrew's libusb-1.0.dylib; Linux: libusb-1.0.so.0)
  local d
  if [ "$OS" = Linux ]; then
    for d in /usr/lib /usr/lib64 /usr/lib/"$(uname -m)"-linux-gnu /lib/"$(uname -m)"-linux-gnu /usr/local/lib; do
      [ -e "$d/libusb-1.0.so.0" ] && { printf '%s\n' "$d"; return 0; }
    done
    d=$(ldconfig -p 2> /dev/null | sed -n 's|.*=> \(/.*\)/libusb-1\.0\.so\.0$|\1|p' | head -n 1)
    [ -n "$d" ] && { printf '%s\n' "$d"; return 0; }
    return 1
  fi
  for d in "${HOMEBREW_PREFIX:-/opt/homebrew}/lib" /opt/homebrew/lib /usr/local/lib /opt/homebrew/opt/libusb/lib \
    /usr/local/opt/libusb/lib /opt/local/lib; do
    [ -f "$d/libusb-1.0.dylib" ] && { printf '%s\n' "$d"; return 0; }
  done
  return 1
}
mtk_pinned() { [ "$(git -C "$MTK_SRC" rev-parse HEAD 2> /dev/null)" = "$MTK_COMMIT" ]; }
# The pinned commit with exactly the macOS serial fixes: the patch is applied (it reverses
# cleanly) and no other tracked file differs.
mtk_patched() {
  git -C "$MTK_SRC" apply --reverse --check "$MTK_PATCH" 2> /dev/null &&
    [ "$(git -C "$MTK_SRC" diff --name-only | sort)" = "$(git -C "$MTK_SRC" apply --numstat "$MTK_PATCH" | awk '{print $3}' | sort)" ]
}
# Linux: the udev rule that lets the logged-in user open the robot's USB port.
udev_ok() { [ "$OS" != Linux ] || [ "$UDEV_CHECK" = 0 ] || cmp -s "$UDEV_RULE" "$UDEV_INSTALLED"; }
udev_command() {
  printf "sudo install -m 644 '%s' /etc/udev/rules.d/ && sudo udevadm control --reload-rules." "$UDEV_RULE"
}
mtk_require() {
  mtk_ready || fail "mtkclient missing or without its macOS serial fixes: run 'prepare'."
  udev_ok || fail "The udev rule for the robot's USB port is not installed (or differs from this repository's). The person runs: $(udev_command) Then run '$STEP' again."
}
mtk_ready() {
  [ -x "$MTK_VENV/bin/python" ] && [ -f "$MTK_SRC/mtk.py" ] && mtk_pinned && mtk_patched
}
# mtkclient's own code looks for "libusb-1.0.so" on macOS; pyusb then finds Homebrew's libusb
# through DYLD_FALLBACK_LIBRARY_PATH. 'exec' so that the caller's $! is mtkclient itself.
mtk_exec() {
  local lib
  lib=$(find_libusb) || lib=""
  cd "$MTK_RUN" || exit 1
  [ "$OS" = Darwin ] &&
    export DYLD_FALLBACK_LIBRARY_PATH="${lib:+$lib:}${DYLD_FALLBACK_LIBRARY_PATH:-$HOME/lib:/usr/local/lib:/lib:/usr/lib}"
  exec "$MTK_VENV/bin/python" -s "$MTK_SRC/mtk.py" "$@"
}

install_mtkclient() { # needs $PY, git and libusb
  [ -f "$MTK_PATCH" ] || fail "$MTK_PATCH missing: get the whole repository again."
  if ! { [ -f "$MTK_SRC/mtk.py" ] && mtk_pinned && mtk_patched; }; then
    # A clean clone of the pinned commit only needs the patch; anything else is downloaded again.
    if ! { [ -f "$MTK_SRC/mtk.py" ] && mtk_pinned &&
      [ -z "$(git -C "$MTK_SRC" status --porcelain --untracked-files=no 2> /dev/null)" ]; }; then
      rm -rf "$MTK_SRC"
      say "Downloading mtkclient $MTK_TAG"
      git -c advice.detachedHead=false clone -q --depth 1 --branch "$MTK_TAG" "$MTK_URL" "$MTK_SRC" ||
        fail "Could not download mtkclient from $MTK_URL"
      mtk_pinned || fail "mtkclient $MTK_TAG is not commit $MTK_COMMIT: do not use it."
    fi
    if ! git -C "$MTK_SRC" apply "$MTK_PATCH" || ! mtk_patched; then
      fail "Could not apply $(basename "$MTK_PATCH") to mtkclient: do not use download mode."
    fi
    ok "mtkclient: macOS serial fixes applied ($(basename "$MTK_PATCH"))"
  fi
  if ! "$MTK_VENV/bin/python" -s -c 'import usb, serial, Cryptodome, colorama' > /dev/null 2>&1; then
    rm -rf "$MTK_VENV"
    "$PY" -m venv "$MTK_VENV" || fail "Could not create a Python environment with $PY (Debian/Ubuntu: install python3-venv)."
    "$MTK_VENV/bin/python" -m pip install -q --disable-pip-version-check --require-hashes \
      --only-binary=:all: --no-deps -r "$REPO/tools/setup/mtkclient-requirements.txt" ||
      fail "Could not install mtkclient's Python packages (tools/setup/mtkclient-requirements.txt)."
  fi
  mkdir -p "$MTK_RUN"
  (mtk_exec --help) > "$WORK/mtkclient/help.txt" 2>&1 ||
    fail "mtkclient does not start: see $WORK/mtkclient/help.txt"
  ok "mtkclient $MTK_TAG ($MTK_COMMIT) + macOS serial fixes"
}

# One mtkclient session: waits for the robot in download mode, then runs <commands> (separated by
# ';', relative file names in $MTK_RUN). Output in $WORK/mtk-<label>-out.txt.
MTK_OUT=""
run_mtk_session() { # run_mtk_session <label> <commands>
  local pid elapsed=0 tailpid
  MTK_OUT="$WORK/mtk-$1-out.txt"
  mkdir -p "$MTK_RUN"
  : > "$MTK_OUT"
  say "Running mtkclient ($1): $2"
  if [ "$MTK_SERIAL" = 1 ]; then
    say "(Through the serial driver, mtkclient prints 'Handshake failed, retrying...' about every 10 seconds while it waits for the robot: that is normal.)"
    (mtk_exec --serialport DETECT multi "$2") >> "$MTK_OUT" 2>&1 &
  else
    (mtk_exec multi "$2") >> "$MTK_OUT" 2>&1 &
  fi
  pid=$!
  tail -f "$MTK_OUT" &
  tailpid=$!
  trap 'kill "$pid" "$tailpid" 2> /dev/null; exit 130' INT TERM
  while kill -0 "$pid" 2> /dev/null; do
    if [ "$elapsed" -ge "$MTK_TIMEOUT" ]; then
      kill "$pid" 2> /dev/null
      say "mtkclient stopped after $MTK_TIMEOUT seconds."
      break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  wait "$pid" 2> /dev/null
  sleep 1
  kill "$tailpid" 2> /dev/null
  wait "$tailpid" 2> /dev/null
  trap - INT TERM
  say "mtkclient finished (output: $MTK_OUT)"
}

usb_hint() {
  local extra=""
  [ "$OS" = Darwin ] && extra=" If macOS asks to allow the accessory to connect, click Allow, then switch the robot off and on again."
  human "Robot switched OFF with its rear switch, USB A-A cable in the right-hand port marked 'Android USB' and in the $HOST (a $HOST with only USB-C ports: through a USB-C to USB-A adapter or hub; a USB-C to USB-A cable does not work). Switch the robot ON as soon as mtkclient says it is waiting for the device (it waits up to $((MTK_TIMEOUT / 60)) minutes).$extra"
}

# Writes images in download mode and reads each one back in the same session.
mtk_write_verified() { # mtk_write_verified <label> <partition> <image> [<partition> <image>]
  local label=$1 cmds="" part img off len
  shift
  mkdir -p "$MTK_RUN"
  rm -f "$MTK_RUN"/toque-*.img
  while [ $# -ge 2 ]; do
    part=$1; img=$2; shift 2
    off=$BOOT_OFFSET; [ "$part" = recovery ] && off=$RECOVERY_OFFSET
    len=$(fsize "$img")
    [ "$len" -gt 0 ] && [ "$len" -le "$PARTITION_SIZE" ] && [ $((len % 512)) -eq 0 ] ||
      fail "$img ($len bytes) cannot be written to $part."
    cp "$img" "$MTK_RUN/toque-$part.img"
    cmds="$cmds${cmds:+;}wo $off $(hex "$len") toque-$part.img --parttype user"
    cmds="$cmds;ro $off $(hex "$len") toque-$part-check.img --parttype user"
  done
  usb_hint
  run_mtk_session "$label" "$cmds"
}
mtk_check_written() { # mtk_check_written <partition> <image>
  local check="$MTK_RUN/toque-$1-check.img"
  [ -f "$check" ] && [ "$(sha "$check")" = "$(sha "$2")" ] ||
    fail "$1 could not be verified after writing $(basename "$2") (see $MTK_OUT). The robot's preloader is untouched, so download mode still works: switch the robot OFF, then run '$STEP' again."
  rm -f "$check" "$MTK_RUN/toque-$1.img"
  ok "$1 = $(basename "$2") (read back and verified)"
}

# Writes images from TWRP with tools/robot/twrp-flash.sh, which checks each partition first.
twrp_write() { # twrp_write <partition> <image> <allowed> [<partition> <image> <allowed>]...
  local bb="$WORK/busybox-armeabi-v7a" args="" out
  [ "$(sha "$DOWNLOADS/Magisk-v31.0.apk")" = "$MAGISK_SHA" ] || fail "Magisk-v31.0.apk missing or modified: run 'prepare'."
  unzip -p "$DOWNLOADS/Magisk-v31.0.apk" lib/armeabi-v7a/libbusybox.so > "$bb" && [ -s "$bb" ] ||
    fail "Could not extract busybox from Magisk-v31.0.apk."
  adb_run shell "rm -rf /tmp/toque-flash; mkdir -p /tmp/toque-flash" > /dev/null
  say "$(adb_run push "$bb" /tmp/toque-flash/busybox)"
  say "$(adb_run push "$REPO/tools/robot/twrp-flash.sh" /tmp/toque-flash/twrp-flash.sh)"
  adb_run shell "chmod 755 /tmp/toque-flash/busybox" > /dev/null
  while [ $# -ge 3 ]; do
    say "$(adb_run push "$2" "/tmp/toque-flash/$1.img")"
    args="$args $1 /tmp/toque-flash/$1.img $(sha "$2") $3"
    shift 3
  done
  human "Do not touch the robot until this step says OK."
  out=$(adb_run shell "sh /tmp/toque-flash/twrp-flash.sh$args")
  say "$out"
  case "$out" in
    *"== Done"*) adb_run shell "rm -rf /tmp/toque-flash" > /dev/null ;;
    *"== 2."*) fail "Writing from TWRP failed (output above). Do NOT switch the robot off: TWRP is still running, run '$STEP' again." ;;
    *"== 1."*ERROR*) fail "twrp-flash.sh refused to write (output above): nothing was written." ;;
    *) fail "twrp-flash.sh did not run to the end (output above). Do NOT switch the robot off: check that adb still sees TWRP, then run '$STEP' again." ;;
  esac
}

# "size:sha256" of the possible contents of a partition (what twrp-flash.sh accepts).
content_of() { printf '%s:%s' "$(fsize "$1")" "$(sha "$1")"; }
twrp_content() { printf '%s:%s' "$TWRP_SIZE" "$TWRP_SHA"; }
# What 'flash-twrp' may have left in boot: the guide's TWRP and, if built, the own-kernel one.
any_twrp_content() {
  local c
  c=$(twrp_content)
  [ -f "$OWN_TWRP" ] && c="$c,$(content_of "$OWN_TWRP")"
  printf '%s' "$c"
}

# How to install a missing tool, by system.
pkg_hint() { # pkg_hint python|libusb|git
  if [ "$OS" = Darwin ]; then
    case "$1" in
      python) echo "Install it with Homebrew: brew install python" ;;
      libusb) echo "Install it with Homebrew: brew install libusb" ;;
      git) echo "Install Apple's command line tools: xcode-select --install" ;;
    esac
  else
    case "$1" in
      python) echo "Arch: sudo pacman -S python; Debian/Ubuntu: sudo apt install python3 python3-venv; Fedora: sudo dnf install python3" ;;
      libusb) echo "Arch: sudo pacman -S libusb; Debian/Ubuntu: sudo apt install libusb-1.0-0; Fedora: sudo dnf install libusb1" ;;
      git) echo "Arch: sudo pacman -S git; Debian/Ubuntu: sudo apt install git; Fedora: sudo dnf install git" ;;
    esac
  fi
}

# "<tag> <asset name> <url>" of the Toque APK in a GitHub release (JSON file); nothing if there is none.
release_apk() {
  local rel=$1 tag count i name
  if [ "$OS" = Darwin ]; then
    tag=$(plutil -extract tag_name raw "$rel" 2> /dev/null)
    count=$(plutil -extract assets raw "$rel" 2> /dev/null) || count=0
    i=0
    while [ "$i" -lt "$count" ]; do
      name=$(plutil -extract "assets.$i.name" raw "$rel" 2> /dev/null)
      case "$name" in Toque-*.apk)
        printf '%s %s %s\n' "$tag" "$name" "$(plutil -extract "assets.$i.browser_download_url" raw "$rel" 2> /dev/null)"
        return ;;
      esac
      i=$((i + 1))
    done
  else
    python3 -I -c '
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get("assets", []):
    if a["name"].startswith("Toque-") and a["name"].endswith(".apk"):
        print(rel["tag_name"], a["name"], a["browser_download_url"])
        break
' "$rel" 2> /dev/null
  fi
}

# ---------------------------------------------------------------- steps

step_status() {
  local done_steps="" o py lib
  say "Work folder: $WORK"
  [ -f "$STATE_FILE" ] && done_steps=$(sed -n 's/^done-\([^=]*\)=.*/\1/p' "$STATE_FILE" | paste -sd ',' - | sed 's/,/, /g')
  say "Done steps: $done_steps"
  py=$(find_python) || py="missing ($(pkg_hint python))"
  say "Python 3.10+: $py"
  lib=$(find_libusb) || lib="missing ($(pkg_hint libusb))"
  say "libusb: $lib"
  if [ "$OS" = Linux ] && [ "$UDEV_CHECK" = 1 ]; then
    if udev_ok; then say "udev rule: $UDEV_INSTALLED"; else say "udev rule: not installed ($(udev_command))"; fi
  fi
  if mtk_ready; then say "mtkclient: $MTK_TAG + macOS serial fixes, through $([ "$MTK_SERIAL" = 1 ] && echo "the serial driver" || echo libusb)"; else say "mtkclient: not installed or not patched ('prepare')"; fi
  if ADB=$(find_adb); then say "adb state: $(adb_state)"; else ADB=""; say "adb: missing ('prepare')"; fi
  if state_has twrp-own-kernel-sha256; then say "TWRP for the flash steps: twrp-own-kernel.img (built by 'own-twrp' around this robot's kernel)"
  else say "TWRP for the flash steps: hack/recovery.img (the guide's)"; fi
  for o in prepare backup flash-twrp root flash-root android install wifi; do
    if ! state_has "done-$o"; then
      if [ "$o" = flash-twrp ] && ! state_has done-own-twrp; then
        say "Next step: own-twrp (SKMC 1100 A1), then flash-twrp"
      else
        say "Next step: $o"
      fi
      return
    fi
  done
  say "Everything is done."
}

step_prepare() {
  local missing=0 zip tmp twrp scatter rel name url tag
  mkdir -p "$WORK" "$DOWNLOADS" "$BACKUP"

  # Android platform tools (adb)
  if [ "$OS" = Linux ] && [ "$(uname -m)" != x86_64 ]; then
    # Google only publishes platform tools for x86_64 Linux.
    command -v adb > /dev/null 2>&1 ||
      fail "adb missing. Install it from your distribution (Debian/Ubuntu: sudo apt install adb; Arch: sudo pacman -S android-tools), then run 'prepare' again."
  elif [ ! -x "$WORK/platform-tools/adb" ]; then
    zip="$DOWNLOADS/platform-tools.zip"
    fetch "$TOOLS_URL" "$zip"
    unzip -q -o "$zip" -d "$WORK" || fail "Could not extract $zip"
  fi
  ADB=$(find_adb) || fail "adb missing after download."
  ok "adb: $ADB"

  # Magisk
  if [ ! -f "$DOWNLOADS/Magisk-v31.0.apk" ] || [ "$(sha "$DOWNLOADS/Magisk-v31.0.apk")" != "$MAGISK_SHA" ]; then
    fetch "$MAGISK_URL" "$DOWNLOADS/Magisk-v31.0.apk"
  fi
  [ "$(sha "$DOWNLOADS/Magisk-v31.0.apk")" = "$MAGISK_SHA" ] || fail "Magisk-v31.0.apk checksum mismatch."
  ok "Magisk v31.0"

  # Scatter file and TWRP
  twrp="$HACK/recovery.img"
  scatter="$HACK/l706_dfbh_v_695scatter.txt"
  if [ ! -f "$twrp" ] || [ ! -f "$scatter" ]; then
    zip="$DOWNLOADS/Monsieur-Cuisine-Connect-Hack.zip"
    fetch "$HACK_ZIP_URL" "$zip"
    tmp="$DOWNLOADS/hack-tmp"
    rm -rf "$tmp"
    unzip -q -o "$zip" -d "$tmp" || fail "Could not extract $zip"
    mkdir -p "$HACK"
    cp "$(find "$tmp" -name recovery.img -type f | head -n 1)" "$twrp" || fail "recovery.img not found in $zip"
    cp "$(find "$tmp" -name l706_dfbh_v_695scatter.txt -type f | head -n 1)" "$scatter" || fail "Scatter file not found in $zip"
    rm -rf "$tmp"
  fi
  [ "$(sha "$twrp")" = "$TWRP_SHA" ] || fail "TWRP (recovery.img) checksum mismatch: do not flash it."
  [ "$(sha "$scatter")" = "$SCATTER_SHA" ] || fail "Scatter file checksum mismatch: this guide is only for the SKMC 1200."
  check_layout
  ok "TWRP and scatter file (boot at $BOOT_OFFSET, recovery at $RECOVERY_OFFSET)"

  # Toque APK (latest release)
  if [ -z "$APK" ]; then
    rel="$DOWNLOADS/latest-release.json"
    if curl -fsSL --proto '=https' -A toque-setup -o "$rel" "$RELEASES_API"; then
      tag="" name="" url=""
      read -r tag name url < <(release_apk "$rel") || true
      case "$name" in */* | "") url="" ;; esac
      if [ -n "$url" ]; then
        [ -f "$DOWNLOADS/$name" ] || fetch "$url" "$DOWNLOADS/$name"
        ok "Toque $tag: $DOWNLOADS/$name"
      else
        say "No Toque APK in the latest release: build it (see README) or pass --apk later."
      fi
    else
      say "GitHub releases unreachable: build the APK (see README) or pass --apk later."
    fi
  fi

  # mtkclient, for the robot's download mode (replaces SP Flash Tool; no driver on macOS or Linux).
  PY=$(find_python) || { missing=1; say "MISSING: Python 3.10 or newer. $(pkg_hint python)"; }
  find_libusb > /dev/null || { missing=1; say "MISSING: libusb. $(pkg_hint libusb)"; }
  if ! { command -v git && git --version; } > /dev/null 2>&1; then
    missing=1; say "MISSING: git. $(pkg_hint git)"
  fi
  udev_ok || { missing=1; human "MISSING: the udev rule that gives access to the robot's USB port (it asks for your password): $(udev_command)"; }
  if [ "$missing" = 1 ]; then say "Install the missing tools, then run 'prepare' again."; exit 3; fi
  install_mtkclient
  state_set done-prepare "$(now)"
  ok "prepare done. Next: backup"
}

step_backup() {
  local boot="$BACKUP/boot-original.img" rec="$BACKUP/recovery-original.img"
  if [ ! -f "$boot" ] || [ ! -f "$rec" ]; then
    mtk_require
    check_layout
    human "Switch the robot OFF with its rear switch (it can stay plugged into the mains). Unscrew the maintenance cover under the robot (small Torx). Plug the USB A-A cable into the RIGHT-HAND port marked 'Android USB' (never the other one: it can destroy the board) and into the $HOST."
    usb_hint
    rm -f "$MTK_RUN/boot-original.img" "$MTK_RUN/recovery-original.img" "$MTK_RUN"/gpt-*.bin
    # Read only. The first MiB of EMMC_USER (partition table) twice, then boot and recovery; the
    # partition table is checked on this computer before the backups are accepted.
    run_mtk_session backup "ro 0x0 0x100000 gpt-a.bin --parttype user;ro 0x0 0x100000 gpt-b.bin --parttype user;ro $BOOT_OFFSET $(hex $PARTITION_SIZE) boot-original.img --parttype user;ro $RECOVERY_OFFSET $(hex $PARTITION_SIZE) recovery-original.img --parttype user"
    [ -f "$MTK_RUN/boot-original.img" ] && [ -f "$MTK_RUN/recovery-original.img" ] ||
      fail "Readback produced no files (see $MTK_OUT). Nothing was written to the robot. Switch the robot OFF (after a failed session it stays in download mode, black screen), check the cable and port, then run 'backup' again (see AGENTS.md, 'When mtkclient does not reach the robot')."
    [ -f "$MTK_RUN/gpt-a.bin" ] && [ -f "$MTK_RUN/gpt-b.bin" ] ||
      fail "The partition table was not read (see $MTK_OUT). Nothing was written to the robot. Switch the robot OFF and run 'backup' again."
    mkdir -p "$BACKUP"
    bootimg_tool gpt "$MTK_RUN/gpt-a.bin" "$MTK_RUN/gpt-b.bin" "$BOOT_OFFSET" "$RECOVERY_OFFSET" "$PARTITION_SIZE" | tee "$BACKUP/partition-table.txt"
    grep -q '^OK: GPT valid' "$BACKUP/partition-table.txt" ||
      fail "The partition table does not match the offsets this script uses (above, also in $BACKUP/partition-table.txt). Nothing was written to the robot. Do not flash: report it."
    mv "$MTK_RUN/gpt-a.bin" "$BACKUP/emmc-user-first-mib.bin"
    rm -f "$MTK_RUN/gpt-b.bin"
    # Checked where mtkclient wrote them: a bad readback never lands in the backup folder.
    check_boot_image "$MTK_RUN/boot-original.img" "the boot readback"
    check_boot_image "$MTK_RUN/recovery-original.img" "the recovery readback"
    mkdir -p "$BACKUP"
    mv "$MTK_RUN/boot-original.img" "$boot"
    mv "$MTK_RUN/recovery-original.img" "$rec"
  fi
  check_boot_image "$boot" "boot-original.img"
  check_boot_image "$rec" "recovery-original.img"
  [ "$(sha "$boot")" != "$(sha "$rec")" ] || fail "boot and recovery backups are identical: the readback went wrong."
  chmod a-w "$boot" "$rec"
  state_set boot-original-sha256 "$(sha "$boot")"
  state_set recovery-original-sha256 "$(sha "$rec")"
  state_set done-backup "$(now)"
  ok "Backups verified (16 MiB Android boot images). Keep $BACKUP safe: it is the only way back."
  human "Copy the folder $BACKUP to a second place (USB stick, cloud)."
  say "Robot kernel: $(bootimg_tool kernel-version "$boot")"
  ok "backup done. Next: own-twrp (SKMC 1100 A1), or flash-twrp (SKMC 1200)"
}

# SKMC 1100 A1: its kernel (2022) is newer than the one inside the guide's TWRP (2018). Builds, on
# this computer, the guide's TWRP (header and ramdisk, unchanged) around the robot's own kernel and
# device tree, taken from the boot backup. Nothing is sent to the robot; flash-twrp and flash-root
# then write this image instead of hack/recovery.img. Port of Step-OwnTwrp (Wuki60 v0.7.1).
step_own_twrp() {
  local boot="$BACKUP/boot-original.img" code len
  state_has done-backup || fail "Run 'backup' first: the image is built from the robot's own boot backup."
  check_boot_image "$boot" "boot-original.img"
  [ "$(sha "$boot")" = "$(state_get boot-original-sha256)" ] ||
    fail "boot-original.img no longer matches the checksum recorded by 'backup': do not continue."
  check_twrp
  rm -f "$OWN_TWRP.tmp"
  bootimg_tool own-twrp "$boot" "$HACK/recovery.img" "$OWN_TWRP.tmp"
  code=$?
  if [ "$code" = 10 ]; then
    rm -f "$OWN_TWRP.tmp"
    ok "The guide's TWRP already carries this robot's kernel: nothing to build. Next: flash-twrp"
    return
  fi
  [ "$code" = 0 ] && [ -f "$OWN_TWRP.tmp" ] || { rm -f "$OWN_TWRP.tmp"; fail "own-twrp did not build an image (above): nothing to flash."; }
  len=$(fsize "$OWN_TWRP.tmp")
  [ "$len" -le "$PARTITION_SIZE" ] && [ $((len % 512)) -eq 0 ] ||
    { rm -f "$OWN_TWRP.tmp"; fail "The built image ($len bytes) does not fit the partition."; }
  mv "$OWN_TWRP.tmp" "$OWN_TWRP"
  chmod a-w "$OWN_TWRP"
  state_set twrp-own-kernel-sha256 "$(sha "$OWN_TWRP")"
  state_set done-own-twrp "$(now)"
  ok "TWRP with this robot's own kernel: $OWN_TWRP ($(fsize "$OWN_TWRP") bytes). 'flash-twrp' and 'flash-root' will write it instead of hack/recovery.img."
  ok "own-twrp done. Next: flash-twrp"
}

step_flash_twrp() {
  local code
  state_has done-backup || fail "Run 'backup' first: never flash without the backups."
  select_twrp
  if [ "$TWRP_IMG" != "$OWN_TWRP" ] && [ "$GUIDE_TWRP" = 0 ]; then
    # The guide's TWRP carries a 2018 kernel. On an SKMC 1100 A1 (2022 kernel) build 'own-twrp' first.
    bootimg_tool same-kernel "$BACKUP/boot-original.img" "$HACK/recovery.img"
    code=$?
    [ "$code" = 10 ] ||
      fail "The robot's kernel differs from the one inside the guide's TWRP (above). SKMC 1100 A1: run 'own-twrp' first. SKMC 1200 (procedure tested with the guide's TWRP): run 'flash-twrp --guide-twrp'. Nothing was written."
  fi
  mtk_require
  check_layout
  say "TWRP image: $TWRP_IMG ($(fsize "$TWRP_IMG") bytes, SHA-256 $(sha "$TWRP_IMG"))"
  mtk_write_verified flash-twrp boot "$TWRP_IMG"
  mtk_check_written boot "$TWRP_IMG"
  state_set done-flash-twrp "$(now)"
  human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: TWRP (a blue/black recovery screen) appears. If it asks 'Keep System Read only?', tap 'Keep Read Only'. Then plug the cable back in. (Switched on with the cable in, the robot stops on a battery screen.)"
  ok "flash-twrp done. Next: root (with TWRP on the robot's screen)"
}

step_root() {
  local boot="$BACKUP/boot-original.img" st out patched="$WORK/boot-magisk.img"
  resolve_adb
  st=$(wait_adb_state "$WAIT_SECONDS" recovery)
  [ "$st" = recovery ] || need_human "adb does not see TWRP (state: '$st'). The robot must show TWRP and the cable must be plugged in AFTER it started."
  check_boot_image "$boot" "boot-original.img"
  say "$(adb_run shell "rm -rf /tmp/magisk /tmp/orig /tmp/twrp-root.sh")"
  say "$(adb_run push "$DOWNLOADS/Magisk-v31.0.apk" /tmp/magisk.apk)"
  say "$(adb_run push "$boot" /tmp/boot.img)"
  [ -f "$HOME/.android/adbkey.pub" ] && say "$(adb_run push "$HOME/.android/adbkey.pub" /tmp/adbkey.pub)"
  say "$(adb_run push "$REPO/tools/robot/twrp-root.sh" /tmp/twrp-root.sh)"
  out=$(adb_run shell "sh /tmp/twrp-root.sh")
  say "$out"
  case "$out" in *"== Done"*) ;; *) fail "twrp-root.sh did not finish: nothing was flashed. Read the output above." ;; esac
  rm -f "$patched"
  say "$(adb_run pull /tmp/magisk/new-boot.img "$patched")"
  check_boot_image "$patched" "boot-magisk.img"
  mkdir -p "$WORK/system-originals"
  say "$(adb_run pull /tmp/orig "$WORK/system-originals")"
  state_set boot-magisk-sha256 "$(sha "$patched")"
  state_set done-root "$(now)"
  ok "Magisk boot image ready: $patched. Next: flash-root (TWRP still on the robot's screen)"
}

step_flash_root() {
  local patched="$WORK/boot-magisk.img" rec="$BACKUP/recovery-original.img" st
  state_has done-root || fail "Run 'root' first."
  check_boot_image "$patched" "boot-magisk.img"
  [ "$(sha "$patched")" = "$(state_get boot-magisk-sha256)" ] || fail "boot-magisk.img changed since 'root': run 'root' again."
  check_boot_image "$rec" "recovery-original.img"
  select_twrp
  resolve_adb
  st=$(wait_adb_state "$WAIT_SECONDS" recovery)
  [ "$st" = recovery ] || need_human "adb does not see TWRP (state: '$st'). The robot must show TWRP and the cable must be plugged in AFTER it started."
  # TWRP into recovery first, then the Magisk boot. Before: recovery is stock (or already TWRP),
  # boot is TWRP (or already the Magisk boot); anything else and nothing is written.
  twrp_write \
    recovery "$TWRP_IMG" "$(content_of "$rec"),$(any_twrp_content)" \
    boot "$patched" "$(any_twrp_content),$(content_of "$patched")"
  state_set done-flash-root "$(now)"
  human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: the stock Monsieur Cuisine app starts. Then plug the cable back in."
  ok "flash-root done. Next: android"
}

step_android() {
  local st magisk_v pkgs id expected flashed pick
  resolve_adb
  st=$(wait_adb_state "$WAIT_SECONDS" device unauthorized)
  [ "$st" = unauthorized ] && need_human "The robot asks to allow USB debugging: tick 'Always allow' and tap OK on its screen."
  [ "$st" = device ] || need_human "adb does not see Android (state: '$st'). The stock app must be on screen and the cable plugged in after start-up."
  magisk_v=$(adb_run shell "magisk -v")
  case "$magisk_v" in *MAGISK*) ;; *) fail "Magisk is not running: the boot partition is not the patched one. Check the flash-root step." ;; esac
  ok "Magisk $magisk_v"
  pkgs=$(adb_run shell "pm list packages com.topjohnwu.magisk")
  case "$pkgs" in *com.topjohnwu.magisk*) ;; *) say "$(adb_run install -r "$DOWNLOADS/Magisk-v31.0.apk")" ;; esac
  id=$(adb_run shell "su -c 'id -u'")
  [ "$id" = 0 ] || need_human "On the robot's screen, Magisk asks for superuser access for 'Shell': tap GRANT (Autoriser) within 10 seconds. Then run 'android' again."
  expected=$(state_get boot-magisk-sha256)
  if [ -n "$expected" ]; then
    # Magisk's busybox is only copied to /data/adb/magisk by 'install' (setup-root.sh): until then,
    # use the copy inside the Magisk app installed just above.
    # shellcheck disable=SC2016 # $b and $(...) are expanded by the robot's shell, not here
    pick='b=/data/adb/magisk/busybox; [ -x $b ] || b=$(ls /data/app/com.topjohnwu.magisk*/lib/arm*/libbusybox.so 2>/dev/null | head -n 1)'
    flashed=$(adb_run shell "su -c '$pick; \$b head -c $PARTITION_SIZE $BYNAME/boot | \$b sha256sum'")
    case "$flashed" in *"$expected"*) ok "boot partition = boot-magisk.img" ;; *) say "WARNING: the boot partition differs from boot-magisk.img ($flashed)" ;; esac
  fi
  state_set done-android "$(now)"
  ok "Root works (uid 0). Next: install"
}

step_install() {
  local apk=$APK ver f
  if [ -z "$apk" ]; then
    for f in "$DOWNLOADS"/Toque-*.apk; do
      [ -f "$f" ] && { [ -z "$apk" ] || [ "$f" -nt "$apk" ]; } && apk=$f
    done
  fi
  [ -n "$apk" ] && [ -f "$apk" ] || fail "No Toque APK: run 'prepare' (downloads the latest release) or pass --apk <file>."
  resolve_adb
  if [ -n "$IP" ]; then ADB="$ADB" "$REPO/tools/install-robot.sh" --apk "$apk" --ip "$IP"
  else ADB="$ADB" "$REPO/tools/install-robot.sh" --apk "$apk"; fi
  ver=$(adb_run shell "dumpsys package app.toque | grep versionName")
  case "$ver" in *versionName*) ;; *) fail "Toque is not installed." ;; esac
  state_set done-install "$(now)"
  ok "Toque installed: $(printf '%s' "$ver" | tr -s ' ' | sed 's/^ //')"
  human "On the robot, EMPTY bowl, lid closed, hand near the main switch: Toque > Settings > Machine > Real machine > Apply. The badge at the top right must show 'Ready' ('Prete' in French). Then try Manual cooking: 10 s, speed 2."
  ok "install done. Next: wifi (optional)"
}

step_wifi() {
  local st robot_ip check
  resolve_adb
  st=$(adb_state)
  [ "$st" = device ] || need_human "Connect the robot with the USB cable (Android started), then run 'wifi' again."
  robot_ip=$(adb_run shell "ip -f inet addr show wlan0" | sed -n 's/.*inet \([0-9][0-9.]*\).*/\1/p' | head -n 1)
  [ -n "$robot_ip" ] || need_human "The robot is not on Wi-Fi. In Toque, tap the clock at the top right 5 times quickly to open the Android settings, join the home Wi-Fi, come back with the 'Back to Toque' button."
  say "$(adb_run shell "su -c 'setprop persist.adb.tcp.port 5555'")"
  say "$(adb_run tcpip 5555)"
  sleep 5
  say "$("$ADB" connect "$robot_ip:5555" 2>&1)"
  check=$("$ADB" -s "$robot_ip:5555" shell "su -c 'id -u'" 2>&1 | tr -d '\r')
  [ "$check" = 0 ] || fail "Wi-Fi adb does not answer as root yet: unplug and replug the cable, then run 'wifi' again."
  state_set robot-ip "$robot_ip"
  state_set done-wifi "$(now)"
  ok "ADB over Wi-Fi: $robot_ip:5555 (kept after restarts). Home network only."
  human "The cable is no longer needed: screw the maintenance cover back on."
}

# Puts the original boot and recovery back (no more root, no more TWRP). From TWRP by default;
# with --download-mode through mtkclient, which works even when nothing boots any more.
step_unroot() {
  local boot="$BACKUP/boot-original.img" rec="$BACKUP/recovery-original.img" st boot_ok patched="$WORK/boot-magisk.img"
  check_boot_image "$boot" "boot-original.img"
  check_boot_image "$rec" "recovery-original.img"
  if [ -n "$(state_get boot-original-sha256)" ]; then
    [ "$(sha "$boot")" = "$(state_get boot-original-sha256)" ] && [ "$(sha "$rec")" = "$(state_get recovery-original-sha256)" ] ||
      fail "The backups differ from the ones verified by 'backup': restore them from your second copy."
  fi
  if [ "$DOWNLOAD_MODE" = 1 ]; then
    mtk_require
    check_layout
    mtk_write_verified unroot boot "$boot" recovery "$rec"
    mtk_check_written boot "$boot"
    mtk_check_written recovery "$rec"
  else
    resolve_adb
    st=$(adb_state)
    if [ "$st" = device ]; then
      case "$(adb_run shell "pm list packages app.toque")" in
        *app.toque*) fail "Toque is still installed: run tools/restore-robot.sh --uninstall first (it gives the motor back to the stock app, which needs root)." ;;
      esac
      say "Restarting the robot into TWRP (recovery)."
      adb_run reboot recovery > /dev/null
      sleep 10
    fi
    st=$(wait_adb_state "$WAIT_SECONDS" recovery)
    [ "$st" = recovery ] || need_human "adb does not see TWRP (state: '$st'). If the robot shows a battery screen: switch it off, unplug the cable, switch it on, plug the cable in. If nothing boots, use 'unroot --download-mode'."
    boot_ok="$(content_of "$boot"),$(any_twrp_content)"
    [ -f "$patched" ] && boot_ok="$boot_ok,$(content_of "$patched")"
    twrp_write \
      boot "$boot" "$boot_ok" \
      recovery "$rec" "$(content_of "$rec"),$(any_twrp_content)"
  fi
  state_set done-unroot "$(now)"
  human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: the stock Monsieur Cuisine app starts, without root and without TWRP."
  ok "unroot done: original boot and recovery are back."
}

# Sourcing ("source toque-setup.sh") only loads the functions, for tests.
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0; fi
mkdir -p "$WORK"
case "$STEP" in
  status) step_status ;;
  prepare) step_prepare ;;
  backup) step_backup ;;
  own-twrp) step_own_twrp ;;
  flash-twrp) step_flash_twrp ;;
  root) step_root ;;
  flash-root) step_flash_root ;;
  android) step_android ;;
  install) step_install ;;
  wifi) step_wifi ;;
  unroot) step_unroot ;;
esac
