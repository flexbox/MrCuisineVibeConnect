#!/usr/bin/env bash
# Hardware-free regression tests for Magisk policy verification and the Mac installer.
# Uses only Bash 3.2 and standard macOS/Linux command-line tools.
set -eu

REPO=$(cd "$(dirname "$0")/../../.." && pwd -P)
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/toque-root-test.XXXXXX")
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/bin"
: > "$TEST_DIR/test.apk"

cat > "$TEST_DIR/bin/stat" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$MOCK_UID"
MOCK

cat > "$TEST_DIR/bin/magisk" <<'MOCK'
#!/usr/bin/env bash
if [ "$1" = -V ]; then
  printf '%s\n' "$MOCK_MAGISK_VERSION"
  exit 0
fi
printf '%s\n' "$2" >> "$CASE_DIR/sql.log"
case "$2" in
  REPLACE*) exit "$MOCK_SQL_WRITE_STATUS" ;;
  SELECT*)
    [ "$MOCK_SQL_READ_STATUS" = 0 ] || exit "$MOCK_SQL_READ_STATUS"
    printf '%s\n' "$MOCK_POLICY_ROW"
    ;;
  *) echo "Unexpected SQL: $2" >&2; exit 90 ;;
esac
MOCK

cat > "$TEST_DIR/bin/adb" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CASE_DIR/adb.log"
if [ "$1" = -s ]; then shift 2; fi
case "$1" in
  devices|connect|install) exit 0 ;;
  push)
    case "$3" in
      /data/local/tmp/toque-restore.sh) stage=restore-push ;;
      /data/local/tmp/toque-setup-root.sh) stage=setup-push ;;
      *) exit 91 ;;
    esac
    ;;
  shell)
    shift
    case "$*" in
      "su -c 'id -u'") printf '0\n'; exit 0 ;;
      "su -c 'sh /data/local/tmp/toque-setup-root.sh"*) stage=setup ;;
      'am start'*) stage=start ;;
      'dumpsys package app.toque | grep versionName')
        # An old installed APK must not hide a failed new installation.
        printf 'versionName=0.5.1\n'; exit 0 ;;
      *) echo "Unexpected mock adb shell command: $*" >&2; exit 92 ;;
    esac
    ;;
  *) echo "Unexpected mock adb command: $*" >&2; exit 93 ;;
esac
[ "$MOCK_ADB_FAIL_AT" != "$stage" ] || exit 7
case "$stage:$MOCK_REMOTE_REPLY" in
  setup:remote-error) printf 'Root setup failed\nTOQUE_ROOT_SETUP_STATUS=7\n' ;;
  setup:missing-status) printf 'Toque allowed in Magisk\n' ;;
  setup:su-denied) printf 'su: Permission denied\n' ;;
  setup:malformed-status) printf 'TOQUE_ROOT_SETUP_STATUS=0 extra\n' ;;
  setup:status-not-last) printf 'TOQUE_ROOT_SETUP_STATUS=0\nunexpected trailing output\n' ;;
  setup:crlf) printf 'Toque allowed in Magisk\r\nTOQUE_ROOT_SETUP_STATUS=0\r\n' ;;
  setup:*) printf 'Toque allowed in Magisk\nTOQUE_ROOT_SETUP_STATUS=0\n' ;;
  start:remote-start-error) printf 'Status: ok\nTOQUE_START_STATUS=7\n' ;;
  start:activity-error) printf 'Error: Activity class does not exist\nTOQUE_START_STATUS=0\n' ;;
  start:missing-start-status) printf 'Status: ok\n' ;;
  start:timeout) printf 'Status: timeout\nTOQUE_START_STATUS=0\n' ;;
  start:crlf) printf 'Status: ok\r\nTOQUE_START_STATUS=0\r\n' ;;
  start:*) printf 'Status: ok\nTOQUE_START_STATUS=0\n' ;;
esac
# Deliberately return host success for remote failures, as legacy Android 6 adbd does.
exit 0
MOCK

# Source the robot script with its existing busybox simulated, so no /data paths are accessed.
cat > "$TEST_DIR/run-robot.sh" <<'MOCK'
#!/usr/bin/env bash
function [ {
  case "$*" in
    '-x /data/adb/magisk/busybox') return 0 ;;
    '! -x /data/adb/magisk/busybox') return 1 ;;
    *) builtin [ "$@" ;;
  esac
}
. "$1"
MOCK
chmod +x "$TEST_DIR/bin/"* "$TEST_DIR/run-robot.sh"
export PATH="$TEST_DIR/bin:$PATH"

fail() { echo "FAIL: $*" >&2; exit 1; }
has() { grep -Fq -- "$2" "$1" || fail "Missing '$2' in $1"; }
lacks() { if grep -Fq -- "$2" "$1"; then fail "Unexpected '$2' in $1"; fi; }
new_case() {
  CASE_DIR="$TEST_DIR/$1"
  mkdir -p "$CASE_DIR"
  MOCK_UID=10047
  # Keep the legacy boot workaround inactive in these installer regressions.
  MOCK_MAGISK_VERSION=31001
  MOCK_SQL_WRITE_STATUS=0
  MOCK_SQL_READ_STATUS=0
  MOCK_POLICY_ROW='uid=10047|policy=2|until=0'
  MOCK_ADB_FAIL_AT=none
  MOCK_REMOTE_REPLY=success
  export CASE_DIR MOCK_UID MOCK_MAGISK_VERSION MOCK_SQL_WRITE_STATUS MOCK_SQL_READ_STATUS MOCK_POLICY_ROW MOCK_ADB_FAIL_AT MOCK_REMOTE_REPLY
}
run() {
  if "$@" > "$CASE_DIR/output" 2>&1; then RESULT=0; else RESULT=$?; fi
}
assert_failed() { [ "$RESULT" != 0 ] || fail "Expected failure in $CASE_DIR"; }
assert_passed() { [ "$RESULT" = 0 ] || { cat "$CASE_DIR/output"; fail "Expected success in $CASE_DIR"; }; }
robot() { run bash "$TEST_DIR/run-robot.sh" "$REPO/tools/robot/setup-root.sh"; }

new_case sql-write-failure
MOCK_SQL_WRITE_STATUS=7
robot
assert_failed
has "$CASE_DIR/output" 'Could not save'
lacks "$CASE_DIR/output" 'Toque allowed'
lacks "$CASE_DIR/sql.log" SELECT

new_case sql-read-failure
MOCK_SQL_READ_STATUS=7
robot
assert_failed
has "$CASE_DIR/output" 'Could not read'
lacks "$CASE_DIR/output" 'Toque allowed'

for row in 'uid=10047|policy=1|until=0' 'uid=10048|policy=2|until=0' 'uid=10047|policy=2|until=123' ''; do
  new_case "policy-mismatch-${row:-empty}"
  MOCK_POLICY_ROW=$row
  robot
  assert_failed
  has "$CASE_DIR/output" 'did not confirm a permanent allow policy'
  lacks "$CASE_DIR/output" 'Toque allowed'
done

new_case invalid-uid
MOCK_UID=invalid
robot
assert_failed
[ ! -e "$CASE_DIR/sql.log" ] || fail 'Invalid UID reached SQL'

new_case verified-policy
robot
assert_passed
has "$CASE_DIR/sql.log" 'SELECT uid,policy,until FROM policies WHERE uid=10047;'
has "$CASE_DIR/output" 'UID 10047, permanent policy verified'
echo 'PASS: Magisk write/read failures, mismatches, UID validation and verified success'

for stage in restore-push setup-push setup start; do
  new_case "installer-$stage"
  MOCK_ADB_FAIL_AT=$stage
  run env ADB="$TEST_DIR/bin/adb" bash "$REPO/tools/install-robot.sh" --apk "$TEST_DIR/test.apk"
  assert_failed
  lacks "$CASE_DIR/output" 'Toque is running'
  if [ "$stage" != start ]; then lacks "$CASE_DIR/adb.log" 'am start'; fi
done
echo 'PASS: installer propagates both push failures, root setup failure and app start failure'

for reply in remote-error missing-status su-denied malformed-status status-not-last remote-start-error activity-error missing-start-status timeout; do
  new_case "legacy-$reply"
  MOCK_REMOTE_REPLY=$reply
  run env ADB="$TEST_DIR/bin/adb" bash "$REPO/tools/install-robot.sh" --apk "$TEST_DIR/test.apk"
  assert_failed
  lacks "$CASE_DIR/output" 'Toque is running'
done
new_case legacy-crlf-success
MOCK_REMOTE_REPLY=crlf
run env ADB="$TEST_DIR/bin/adb" bash "$REPO/tools/install-robot.sh" --apk "$TEST_DIR/test.apk"
assert_passed
has "$CASE_DIR/output" 'Toque is running'
echo 'PASS: legacy adb host success cannot hide remote errors, missing status, su denial or Activity Manager errors; CRLF succeeds'

for mode in usb wifi; do
  new_case "setup-failure-$mode"
  MOCK_ADB_FAIL_AT=setup
  mkdir -p "$CASE_DIR/platform-tools"
  ln -s "$TEST_DIR/bin/adb" "$CASE_DIR/platform-tools/adb"
  if [ "$mode" = wifi ]; then set -- --ip 192.0.2.1; else set --; fi
  run bash "$REPO/tools/setup/toque-setup.sh" install --work "$CASE_DIR" --apk "$TEST_DIR/test.apk" "$@"
  [ "$RESULT" = 2 ] || fail "Expected setup STOP exit 2, got $RESULT"
  has "$CASE_DIR/output" 'STOP: Toque installation failed'
  lacks "$CASE_DIR/output" 'OK: Toque installed'
  [ ! -e "$CASE_DIR/state.txt" ] || fail 'Failed install wrote completed state'
  lacks "$CASE_DIR/adb.log" dumpsys

  new_case "setup-success-$mode"
  mkdir -p "$CASE_DIR/platform-tools"
  ln -s "$TEST_DIR/bin/adb" "$CASE_DIR/platform-tools/adb"
  run bash "$REPO/tools/setup/toque-setup.sh" install --work "$CASE_DIR" --apk "$TEST_DIR/test.apk" "$@"
  assert_passed
  has "$CASE_DIR/output" 'OK: Toque installed'
  has "$CASE_DIR/state.txt" done-install=
  if [ "$mode" = wifi ]; then has "$CASE_DIR/adb.log" '-s 192.0.2.1:5555'; fi
done
echo 'PASS: setup rejects failed installs despite an existing APK and accepts success over USB/Wi-Fi'
