#!/usr/bin/env bash
# The on-device tests, run on the emulator .github/workflows/emulator-tests.yml starts (Droidtop/tracker#735):
# the x86_64 debug build and its test APK from one android-build run are installed, the rig tests that make sense on
# a stock emulator are run with `am instrument`, and droidtop is started once to see that it stays up.
#
#   scripts/emulator-tests.sh <dir holding app-x86_64-debug.apk and app-debug-androidTest.apk, at any depth> <results dir>
#
# Fails when the contained-files test fails or droidtop does not start. PluginStartRigTest is run and reported, and
# does not fail the run: it holds a start-time budget that the BlueStacks rig itself does not yet meet, and a CI
# emulator's speed is not the rig's. PrivPackagesRigTest is not run: it needs the installed Shizuku plugin and a
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

# One instrumentation class: its output kept, and 0 when am instrument says OK.
instrument() {
  local class="$1" log="$out/instrument-${1##*.}.txt"
  adb shell am instrument -w -e class "$class" "$runner" > "$log" 2>&1
  if grep -q '^OK (' "$log" && ! grep -q 'FAILURES!!!' "$log"; then
    say "- $class: $(grep '^OK (' "$log" | head -1)"
    return 0
  fi
  say "- $class: FAILED ($(grep -m1 -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Error' "$log" || echo 'no result line'))"
  # What failed and where, in the log and the summary: the full output is only in the uploaded results.
  { echo '```'; sed -n '/^There w\|^Error in\|INSTRUMENTATION_RESULT: shortMsg\|^java\.\|^junit\.\|^Caused by/,$p' "$log" | head -45; echo '```'; } | tee -a "$summary"
  return 1
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

# The smoke test: start droidtop the way a launcher would and see that its process is still there, with no crash of
# its own in the log, after the time a first start takes.
adb shell monkey -p "$app_id" -c android.intent.category.LAUNCHER 1 > "$out/monkey.txt" 2>&1
sleep 30
pid="$(adb shell pidof "$app_id" | tr -d '\r')"
adb logcat -d > "$out/logcat.txt" 2>&1
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
