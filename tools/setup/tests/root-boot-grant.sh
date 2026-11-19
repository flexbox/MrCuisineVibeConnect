#!/usr/bin/env bash
# Exercise the embedded Android boot hook in a private temporary filesystem.
# No Android device, root privileges, or real /data paths are used.
set -eu

REPO=$(cd "$(dirname "$0")/../../.." && pwd -P)
TEST_DIR=$(mktemp -d /tmp/toque-boot-test.XXXXXX)
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/bin"
REAL_AWK=$(command -v awk)
export REAL_AWK

fail() { echo "FAIL: $*" >&2; exit 1; }
has() { grep -Fq -- "$2" "$1" || fail "Missing '$2' in $1"; }
lacks() { if grep -Fq -- "$2" "$1"; then fail "Unexpected '$2' in $1"; fi; }

awk "/<<'TOQUE_ROOT_GRANT_HOOK'/ {emit=1; next} emit && /^TOQUE_ROOT_GRANT_HOOK$/ {exit} emit {print}" \
  "$REPO/tools/robot/setup-root.sh" > "$TEST_DIR/hook-source.sh"
[ -s "$TEST_DIR/hook-source.sh" ] || fail 'Embedded boot hook not found'

cat > "$TEST_DIR/bin/magisk" <<'MOCK'
#!/usr/bin/env bash
case "$1" in
  -V) printf '%s\n' "$MOCK_MAGISK_VERSION"; exit 0 ;;
  --sqlite) ;;
  *) echo "Unexpected Magisk arguments: $*" >&2; exit 90 ;;
esac
sql=$(printf '%s' "$2" | tr -d '[:space:]')
printf '%s\n' "$sql" >> "$CASE_DIR/sql.log"
case "$sql" in
  SELECT*)
    count=0
    [ ! -f "$CASE_DIR/read.count" ] || read -r count < "$CASE_DIR/read.count"
    count=$((count + 1))
    printf '%s\n' "$count" > "$CASE_DIR/read.count"
    [ "$MOCK_READ_FAIL_AT" != "$count" ] || exit 7
    uid=${sql##*WHEREuid=}
    uid=${uid%;}
    row=$(grep "^$uid|" "$CASE_DIR/policies" || :)
    [ -n "$row" ] || exit 0
    old_ifs=$IFS
    IFS='|' read -r row_uid policy until logging notification <<ROW
$row
ROW
    IFS=$old_ifs
    if [ "$count" -gt 1 ] && [ "$MOCK_READBACK_MISMATCH" = 1 ]; then policy=1; fi
    case "$sql" in
      SELECTuid,policy,untilFROM*) printf 'uid=%s|policy=%s|until=%s\n' "$row_uid" "$policy" "$until" ;;
      *) printf 'uid=%s|policy=%s|until=%s|logging=%s|notification=%s\n' \
        "$row_uid" "$policy" "$until" "$logging" "$notification" ;;
    esac
    ;;
  REPLACE*)
    [ "$MOCK_WRITE_STATUS" = 0 ] || exit "$MOCK_WRITE_STATUS"
    uid=$(printf '%s' "$sql" | sed -n 's/.*VALUES(\([0-9][0-9]*\),2,0,0,0).*/\1/p')
    [ -n "$uid" ] || { echo 'Unexpected policy write' >&2; exit 91; }
    { grep -v "^$uid|" "$CASE_DIR/policies" || :; printf '%s|2|0|0|0\n' "$uid"; } > "$CASE_DIR/policies.next"
    mv "$CASE_DIR/policies.next" "$CASE_DIR/policies"
    ;;
  *) echo "Unexpected SQL: $sql" >&2; exit 92 ;;
esac
MOCK

cat > "$TEST_DIR/bin/readlink" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$MOCK_USER0_TARGET"
MOCK

cat > "$TEST_DIR/bin/busybox" <<'MOCK'
#!/usr/bin/env bash
applet=$1
shift
case "$applet" in
  stat)
    [ "$1" = -c ] || { echo "Unexpected stat: $*" >&2; exit 93; }
    case "$2:$3" in
      %u:%g:%a:*) printf '0:0:700\n' ;;
      %u:*/data/data/app.toque) printf '%s\n' "$MOCK_UID" ;;
      %u:*) printf '0\n' ;;
      *) echo "Unexpected stat: $*" >&2; exit 93 ;;
    esac
    ;;
  id) [ "$1" = -u ] || exit 93; printf '0\n' ;;
  chown) exit 0 ;;
  readlink) exec readlink "$@" ;;
  timeout)
    [ "$1" = 3 ] || { echo 'SQL timeout must remain bounded' >&2; exit 94; }
    shift
    printf '%s\n' "$*" >> "$CASE_DIR/timeouts.log"
    exec "$@"
    ;;
  awk) exec "$REAL_AWK" "$@" ;;
  *) exec "$applet" "$@" ;;
esac
MOCK
cat > "$TEST_DIR/bin/stat" <<'MOCK'
#!/usr/bin/env bash
exec "$CASE_DIR/data/adb/magisk/busybox" stat "$@"
MOCK
chmod +x "$TEST_DIR/bin/"*
export PATH="$TEST_DIR/bin:$PATH"

new_case() {
  CASE_DIR="$TEST_DIR/$1"
  mkdir -p "$CASE_DIR/data/adb/magisk" "$CASE_DIR/data/data/app.toque" \
    "$CASE_DIR/data/system" "$CASE_DIR/proc/sys/kernel/random" "$CASE_DIR/system"
  cp "$TEST_DIR/bin/busybox" "$CASE_DIR/data/adb/magisk/busybox"
  printf 'test-boot-id\n' > "$CASE_DIR/proc/sys/kernel/random/boot_id"
  printf 'ro.build.version.sdk=23\n' > "$CASE_DIR/system/build.prop"
  printf 'app.toque 10047 0 /data/data/app.toque default none\nother.app 10048 0 /data/data/other.app default none\n' > "$CASE_DIR/data/system/packages.list"
  printf '10047|1|0|1|1\n10048|1|0|1|1\n' > "$CASE_DIR/policies"
  sed -e "s|/data/|$CASE_DIR/data/|g" \
    -e "s|/proc/sys/kernel/random/boot_id|$CASE_DIR/proc/sys/kernel/random/boot_id|g" \
    -e "s|/system/build.prop|$CASE_DIR/system/build.prop|g" \
    "$TEST_DIR/hook-source.sh" > "$CASE_DIR/hook.sh"
  MOCK_MAGISK_VERSION=31000
  MOCK_USER0_TARGET="$CASE_DIR/data/data"
  MOCK_UID=10047
  MOCK_READ_FAIL_AT=none
  MOCK_READBACK_MISMATCH=0
  MOCK_WRITE_STATUS=0
  export CASE_DIR MOCK_MAGISK_VERSION MOCK_USER0_TARGET MOCK_UID \
    MOCK_READ_FAIL_AT MOCK_READBACK_MISMATCH MOCK_WRITE_STATUS
}
run_hook() {
  if bash "$CASE_DIR/hook.sh" > "$CASE_DIR/output" 2>&1; then RESULT=0; else RESULT=$?; fi
}
assert_passed() { [ "$RESULT" = 0 ] || { cat "$CASE_DIR/output"; fail "Expected success in $CASE_DIR"; }; }
assert_failed() { [ "$RESULT" != 0 ] || fail "Expected failure in $CASE_DIR"; }
assert_no_write() {
  if [ -e "$CASE_DIR/sql.log" ]; then lacks "$CASE_DIR/sql.log" REPLACE; fi
  has "$CASE_DIR/policies" '10047|1|0|1|1'
  has "$CASE_DIR/policies" '10048|1|0|1|1'
}

for version in 27000 31001 32000; do
  new_case "other-magisk-$version"
  MOCK_MAGISK_VERSION=$version
  run_hook
  assert_passed
  [ ! -e "$CASE_DIR/sql.log" ] || fail 'Unrelated Magisk version reached SQL'
done
new_case nonlegacy-layout
MOCK_USER0_TARGET=
run_hook
assert_passed
[ ! -e "$CASE_DIR/sql.log" ] || fail 'Nonlegacy layout reached SQL'
echo 'PASS: other Magisk versions and nonlegacy layouts are untouched'

for sdk in 22 24 invalid missing; do
  new_case "other-sdk-$sdk"
  if [ "$sdk" = missing ]; then
    rm "$CASE_DIR/system/build.prop"
  else
    printf 'ro.build.version.sdk=%s\n' "$sdk" > "$CASE_DIR/system/build.prop"
  fi
  run_hook
  assert_passed
  [ ! -e "$CASE_DIR/sql.log" ] || fail 'Unknown or unrelated Android version reached SQL'
done
echo 'PASS: only a positively identified Android 6 system enables the workaround'

for uid in '' invalid 0 2000 9999 20000 100000; do
  new_case "invalid-uid-${uid:-empty}"
  MOCK_UID=$uid
  run_hook
  assert_failed
  assert_no_write
done
package_case=0
for record in 'app.toque 10048 0' 'app.toque.fake 10047 0' ''; do
  package_case=$((package_case + 1))
  new_case "package-mismatch-$package_case"
  printf '%s\n' "$record" > "$CASE_DIR/data/system/packages.list"
  run_hook
  assert_failed
  assert_no_write
done
new_case duplicate-package
printf 'app.toque 10047 0\napp.toque 10047 0\n' > "$CASE_DIR/data/system/packages.list"
run_hook
assert_failed
assert_no_write
echo 'PASS: invalid UIDs and missing, mismatched or duplicate package identities cannot grant root'

for suffix in no-slash trailing-slash; do
  new_case "grant-success-$suffix"
  if [ "$suffix" = trailing-slash ]; then MOCK_USER0_TARGET="$CASE_DIR/data/data/"; fi
  run_hook
  assert_passed
  has "$CASE_DIR/policies" '10047|2|0|0|0'
  has "$CASE_DIR/policies" '10048|1|0|1|1'
  [ "$(wc -l < "$CASE_DIR/policies" | tr -d ' ')" = 2 ] || fail 'Unexpected policy row changed'
  [ "$(wc -l < "$CASE_DIR/timeouts.log" | tr -d ' ')" = 3 ] || fail 'Every SQL call must use the timeout'
  has "$CASE_DIR/data/adb/toque-root-grant.log" test-boot-id
  has "$CASE_DIR/data/adb/toque-root-grant.log" 'uid=10047|policy=1|until=0|logging=1|notification=1'
  has "$CASE_DIR/data/adb/toque-root-grant.log" 'uid=10047|policy=2|until=0|logging=0|notification=0'
done
echo 'PASS: the exact Toque UID is permanently granted and other policies stay unchanged'

new_case success-missing-policy
printf '10048|1|0|1|1\n' > "$CASE_DIR/policies"
run_hook
assert_passed
has "$CASE_DIR/policies" '10047|2|0|0|0'
has "$CASE_DIR/policies" '10048|1|0|1|1'
[ "$(wc -l < "$CASE_DIR/policies" | tr -d ' ')" = 2 ] || fail 'Restoring the missing policy changed another row'
has "$CASE_DIR/data/adb/toque-root-grant.log" 'before=missing'
has "$CASE_DIR/data/adb/toque-root-grant.log" 'result=granted'
echo 'PASS: a policy removed by boot pruning is restored without changing other policies'

for failure in read-before write read-after readback-mismatch; do
  new_case "sql-$failure"
  case "$failure" in
    read-before) MOCK_READ_FAIL_AT=1 ;;
    write) MOCK_WRITE_STATUS=7 ;;
    read-after) MOCK_READ_FAIL_AT=2 ;;
    readback-mismatch) MOCK_READBACK_MISMATCH=1 ;;
  esac
  run_hook
  assert_failed
  has "$CASE_DIR/policies" '10048|1|0|1|1'
  [ "$failure" != read-before ] || assert_no_write
done
echo 'PASS: SQL failures and a mismatched successful readback fail closed'

run_installer() {
  sed -e "s|/data/|$CASE_DIR/data/|g" \
    -e "s|/proc/sys/kernel/random/boot_id|$CASE_DIR/proc/sys/kernel/random/boot_id|g" \
    -e "s|/system/build.prop|$CASE_DIR/system/build.prop|g" \
    "$REPO/tools/robot/setup-root.sh" > "$CASE_DIR/setup-root.sh"
  if bash "$CASE_DIR/setup-root.sh" > "$CASE_DIR/output" 2>&1; then RESULT=0; else RESULT=$?; fi
}
new_case installer-unexpected-existing-hook
mkdir -p "$CASE_DIR/data/adb/post-fs-data.d"
printf '# Existing administrator hook; do not replace.\n' > "$CASE_DIR/data/adb/post-fs-data.d/toque-root-grant.sh"
cp "$CASE_DIR/data/adb/post-fs-data.d/toque-root-grant.sh" "$CASE_DIR/original-hook"
run_installer
assert_failed
has "$CASE_DIR/output" 'Refusing to replace an unexpected Toque boot hook'
cmp -s "$CASE_DIR/original-hook" "$CASE_DIR/data/adb/post-fs-data.d/toque-root-grant.sh" || fail 'Existing hook was modified'
[ "$(find "$CASE_DIR/data/adb/post-fs-data.d" -type f | wc -l | tr -d ' ')" = 1 ] || fail 'Failed install left a temporary hook'

new_case installer-new-hook
run_installer
assert_passed
cmp -s "$CASE_DIR/hook.sh" "$CASE_DIR/data/adb/post-fs-data.d/toque-root-grant.sh" || fail 'Installed hook differs from the reviewed body'
has "$CASE_DIR/output" 'Toque legacy boot grant installed'
# An identical existing hook is safe for an idempotent setup rerun.
run_installer
assert_passed
cmp -s "$CASE_DIR/hook.sh" "$CASE_DIR/data/adb/post-fs-data.d/toque-root-grant.sh" || fail 'Idempotent install changed the hook'
echo 'PASS: boot-hook installation is idempotent and preserves unexpected existing files'
