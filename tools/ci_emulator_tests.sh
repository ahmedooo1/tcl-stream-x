#!/usr/bin/env bash
# Runs on the Android TV emulator started by reactivecircus/android-emulator-runner.
# The app stays installed after the tests so the reports can be pulled with run-as.
set -u
PKG=fr.ahmad.tclstreamx
OUT=ci-out
mkdir -p "$OUT"
adb wait-for-device
adb shell getprop ro.build.characteristics > "$OUT/device.txt"
adb shell getprop ro.product.model >> "$OUT/device.txt"
adb shell getprop ro.build.version.sdk >> "$OUT/device.txt"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size > "$OUT/display.txt" 2>&1 || true
adb shell wm density >> "$OUT/display.txt" 2>&1 || true
adb shell settings put global stay_on_while_plugged_in 7 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb logcat -c || true
status=0
run_test() {
  adb shell am instrument -w -r -e class "$PKG.$1" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/$1.txt" 2>&1
  if ! grep -q "^OK (" "$OUT/$1.txt"; then
    status=1
    echo "::error::$1 failed: $(grep -m3 -E 'Error|Exception|FAILURES|shortMsg' "$OUT/$1.txt" | tr '\n' ' ' | cut -c1-400)"
  else
    echo "::notice::$1 passed"
  fi
}
run_test ImportOnDeviceTest
run_test PlaybackOnDeviceTest
# The default AVD panel is 320x640 px and refuses overrides much larger than 2x, so the logical
# geometry is set with sizes it accepts and a density that gives the wanted width in dp.
# Phone (portrait, 392 dp wide like a typical phone): 640x1306 px at 261 dpi.
adb shell wm size 640x1306 || true
adb shell wm density 261 || true
sleep 3
run_test PhoneTourTest
adb shell wm size >> "$OUT/display.txt" 2>&1 || true
# TV (960x540 dp like a 1080p Google TV): 1280x720 px at 213 dpi.
adb shell wm size 1280x720 || true
adb shell wm density 213 || true
sleep 3
run_test UiTourTest
adb shell wm size >> "$OUT/display.txt" 2>&1 || true
adb exec-out run-as "$PKG" sh -c 'cd files/ci && tar -cf - .' > "$OUT/device-files.tar" || true
tar -xf "$OUT/device-files.tar" -C "$OUT" || true
rm -f "$OUT/device-files.tar"
adb logcat -d -v time > "$OUT/logcat.txt" || true
ls -la "$OUT"
echo "$status" > "$OUT/instrumented-status.txt"
exit 0
