#!/usr/bin/env bash
# The on-device tests, run on the emulator .github/workflows/emulator-tests.yml starts (Droidtop/tracker#735):
# the x86_64 debug build and its test APK from one android-build run are installed, the rig tests that make sense on
# a stock emulator are run with `am instrument`, and droidtop is started once to see that it stays up.
#
#   scripts/emulator-tests.sh <dir holding app-x86_64-debug.apk and app-debug-androidTest.apk, at any depth> <results dir>
#
# Fails when the contained-files test fails or droidtop does not start. PluginStartRigTest is run and reported, and
# does not fail the run: it holds a start-time budget that the BlueStacks rig itself does not yet meet, and a CI
# emulator's speed is not the rig's. PythonPluginStartRigTest is run and reported the same way: it times each step of a
# contained python plugin's start (the runtime's download on a clean emulator included), and every "rig start:" line a
# test prints is copied into the summary. PrivPackagesRigTest is not run: it needs the installed Shizuku plugin and a
# console's privileged package service, which a stock emulator has neither of.
set -u

apks="${1:?usage: $0 <apk dir> <results dir>}"
out="${2:?usage: $0 <apk dir> <results dir>}"
app_id="dev.droidtop.app"
runner="$app_id.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$out"
failed=0
summary="$out/summary.md"
: > "$summary"

say() { echo "$*" | tee -a "$summary"; }

# Tests known to fail, each with the issue that carries the finding (Droidtop/tracker#741, from the first run of these
# tests anywhere, which found the contained plugin folder failing in an isolated process on stock Android 13 and 14).
# A known test that fails is reported and does not fail the run. One that PASSES fails the run, so that the entry is
# removed as the issue is fixed and cannot go stale; any other failure is a failure.
KNOWN_FAILING=(
  "dev.droidtop.app.ContainedFilesRigTest#theOwnFolderBehavesForPathsListingChmodUtimesPrefsAndSqlite|Droidtop/tracker#741"
)

# The "class#method" of every test am instrument says failed, one per line ("1) method(class)").
failing_tests() { sed -n 's/^[0-9][0-9]*) \([^(]*\)(\(.*\))$/\2#\1/p' "$1"; }

# One instrumentation class: its output kept; 0 when every test passed or failed as known, 1 otherwise.
instrument() {
  local class="$1" log="$out/instrument-${1##*.}.txt" entry known issue t status=0
  adb shell am instrument -w -e class "$class" "$runner" > "$log" 2>&1
  local ran=0 failing
  if grep -q '^OK (' "$log" || grep -q 'Tests run:' "$log"; then ran=1; fi
  failing="$(failing_tests "$log")"
  if [ "$ran" = 1 ] && [ -z "$failing" ] && ! grep -q 'FAILURES!!!' "$log"; then
    say "- $class: $(grep '^OK (' "$log" | head -1)"
  elif [ "$ran" = 1 ] && [ -n "$failing" ]; then
    local unexpected=""
    while IFS= read -r t; do
      [ -z "$t" ] && continue
      issue=""
      for entry in "${KNOWN_FAILING[@]}"; do
        [ "${entry%%|*}" = "$t" ] && issue="${entry##*|}"
      done
      if [ -n "$issue" ]; then say "- $t: fails as known ($issue)"; else unexpected="$unexpected $t"; fi
    done <<< "$failing"
    if [ -n "$unexpected" ]; then
      say "- $class: FAILED:$unexpected"
      status=1
    fi
  else
    say "- $class: FAILED ($(grep -m1 -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Error' "$log" || echo 'no result line'))"
    status=1
  fi
  # A test that is known to fail and passed: take its entry out of KNOWN_FAILING.
  for entry in "${KNOWN_FAILING[@]}"; do
    known="${entry%%|*}"
    if [ "${known%%#*}" = "$class" ] && [ "$ran" = 1 ] && ! grep -qxF "$known" <<< "$failing"; then
      say "- $known PASSED but is listed as known failing (${entry##*|}): remove it from KNOWN_FAILING in scripts/emulator-tests.sh"
      status=1
    fi
  done
  # The numbers a rig test measured (Droidtop/tracker#848), in the summary: the uploaded results are not the only place.
  grep '^rig start: ' "$log" | tr -d '\r' | while IFS= read -r line; do say "  - ${line#rig start: }"; done
  if [ "$status" != 0 ] || [ -n "$failing" ]; then
    # What failed and where, in the log and the summary: the full output is only in the uploaded results.
    { echo '```'; sed -n '/^There w\|^Error in\|INSTRUMENTATION_RESULT: shortMsg\|^java\.\|^junit\.\|^Caused by/,$p' "$log" | head -45; echo '```'; } | tee -a "$summary"
  fi
  return "$status"
}

say "## Emulator tests"

# The artifacts keep the folders the build wrote them in, so the two APKs are found by name.
app_apk="$(find "$apks" -name app-x86_64-debug.apk | head -1)"
test_apk="$(find "$apks" -name app-debug-androidTest.apk | head -1)"
if [ -z "$app_apk" ] || [ -z "$test_apk" ]; then
  say "- the build's APKs are not all there: app-x86_64-debug.apk '${app_apk}', app-debug-androidTest.apk '${test_apk}'"
  exit 1
fi

for apk in "$app_apk" "$test_apk"; do
  if ! adb install -r -t "$apk" > "$out/install-$(basename "$apk").txt" 2>&1; then
    say "- install of $(basename "$apk") FAILED: $(tail -1 "$out/install-$(basename "$apk").txt")"
    adb logcat -d > "$out/logcat.txt" 2>&1
    exit 1
  fi
done
say "- installed $(basename "$app_apk") and the test APK"

adb logcat -c

instrument dev.droidtop.app.ContainedFilesRigTest || failed=1
instrument dev.droidtop.app.PluginStartRigTest || say "  (reported only: this test's start-time budget is the rig's, not an emulator's)"
instrument dev.droidtop.app.PythonPluginStartRigTest || say "  (reported only: it times the python start path, which the console runs, not this emulator)"

# The smoke test: start droidtop the way a launcher would and see that its process is still there, with no crash of
# its own in the log, after the time a first start takes.
adb shell monkey -p "$app_id" -c android.intent.category.LAUNCHER 1 > "$out/monkey.txt" 2>&1
sleep 30
pid="$(adb shell pidof "$app_id" | tr -d '\r')"
adb logcat -d > "$out/logcat.txt" 2>&1
if [ "$failed" -ne 0 ]; then
  # The lines about droidtop's own sandbox and files, in the log and the summary: the whole logcat is only in the results.
  { echo 'Relevant logcat lines:'; echo '```'; grep -E "droidtop|Sandbox|Contained|isolated|SecurityException|avc: denied" "$out/logcat.txt" | grep -v "^--------" | tail -40; echo '```'; } | tee -a "$summary"
fi
if [ -z "$pid" ]; then
  say "- start: droidtop is not running 30 s after it was started"
  failed=1
elif grep -A3 'FATAL EXCEPTION' "$out/logcat.txt" | grep -q "Process: $app_id"; then
  say "- start: droidtop crashed (a FATAL EXCEPTION in its process, see logcat.txt)"
  failed=1
else
  say "- start: droidtop is running (pid $pid) 30 s after it was started, with no crash of its own in the log"
fi

exit "$failed"
