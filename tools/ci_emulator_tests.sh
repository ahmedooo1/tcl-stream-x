#!/usr/bin/env bash
# Runs on the Android TV emulator started by reactivecircus/android-emulator-runner.
# The app stays installed after the tests so the reports can be pulled.
set -u
PKG=fr.ahmad.tclstreamx
OUT=ci-out
mkdir -p "$OUT"
adb wait-for-device
adb shell getprop ro.build.characteristics > "$OUT/device.txt"
adb shell getprop ro.product.model >> "$OUT/device.txt"
adb shell getprop ro.build.version.sdk >> "$OUT/device.txt"
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
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
run_test XtreamOnDeviceTest
run_test PlaybackOnDeviceTest
# The default AVD panel is 320x640 px and refuses overrides much larger than 2x, so the logical
# geometry is set with sizes it accepts and a density that gives the wanted width in dp.
# Phone (portrait, 392 dp wide like a typical phone): 640x1306 px at 261 dpi.
adb shell wm size 640x1306 || true
adb shell wm density 261 || true
sleep 3
run_test PhoneTourTest
adb shell wm size >> "$OUT/display.txt" 2>&1 || true
# TV: the panel is portrait (320x640) and refuses overrides wider than about 2x, so a landscape
# 16:9-like screen is made by overriding the natural (portrait) size to 640x1280 and rotating it:
# 1280x640 px at 213 dpi = 961x480 dp, close to a 1080p Google TV (960x540 dp).
adb shell wm size 640x1280 || true
adb shell wm density 213 || true
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 1 || true
sleep 4
adb shell dumpsys window displays 2>/dev/null | grep -m2 -E "cur=|init=" >> "$OUT/display.txt" || true
run_test UiTourTest
adb shell wm size >> "$OUT/display.txt" 2>&1 || true
# The tested build is not debuggable (no run-as): reports live in the app's external files dir.
REPORTS="/sdcard/Android/data/$PKG/files/ci"
adb shell ls -la "$REPORTS" | head -5 || true
adb exec-out sh -c "cd $REPORTS && tar -cf - ." > "$OUT/device-files.tar" || true
tar -xf "$OUT/device-files.tar" -C "$OUT" 2>/dev/null || { mkdir -p "$OUT/pulled" && adb pull "$REPORTS" "$OUT/pulled" && cp -r "$OUT"/pulled/*/. "$OUT/" ; } || true
rm -rf "$OUT/device-files.tar" "$OUT/pulled"
echo "::notice::reports pulled: $(ls "$OUT" | grep -c -E '\.(png|json)$') files"
adb logcat -d -v time > "$OUT/logcat.txt" || true
ls -la "$OUT"
echo "$status" > "$OUT/instrumented-status.txt"
exit 0
