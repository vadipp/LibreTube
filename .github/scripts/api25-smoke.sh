#!/usr/bin/env bash
set -euo pipefail
mkdir -p api25-smoke
package=com.github.libretube.debug
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 25 ]]
mapfile -t apks < <(find dist -maxdepth 1 -name '*-debug-*.apk')
[[ "${#apks[@]}" == 1 ]]
adb install --no-streaming "${apks[0]}"
adb logcat -c
for attempt in 1 2; do
  adb shell am force-stop "$package"
  adb shell am start -W -n "$package/com.github.libretube.ui.activities.MainActivity" | tee "api25-smoke/start-$attempt.txt"
  sleep 8
  adb shell dumpsys activity activities > "api25-smoke/activities-$attempt.txt"
  grep -E 'mResumedActivity.*com.github.libretube.debug/.*(Welcome|Main)Activity' "api25-smoke/activities-$attempt.txt"
  adb shell pidof "$package"
done
adb logcat -d > api25-smoke/logcat.txt
adb exec-out screencap -p > api25-smoke/launch.png
if grep -q "Process: $package," api25-smoke/logcat.txt; then
  echo '::error::The app crashed on API 25; inspect the emulator report.'
  exit 1
fi

mapfile -t test_apks < <(find android-test -name '*.apk')
[[ "${#test_apks[@]}" == 1 ]]
adb install --no-streaming "${test_apks[0]}"
adb shell am instrument -w -r "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee api25-smoke/queue-tests.txt
grep -q 'OK (2 tests)' api25-smoke/queue-tests.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' api25-smoke/queue-tests.txt; then exit 1; fi
