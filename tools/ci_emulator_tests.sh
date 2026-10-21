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
for cls in ImportOnDeviceTest PlaybackOnDeviceTest UiTourTest; do
  adb shell am instrument -w -r -e class "$PKG.$cls" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/$cls.txt" 2>&1
  if ! grep -q "^OK (" "$OUT/$cls.txt"; then
    status=1
    echo "::error::$cls failed: $(grep -m3 -E 'Error|Exception|FAILURES|shortMsg' "$OUT/$cls.txt" | tr '\n' ' ' | cut -c1-400)"
  else
    echo "::notice::$cls passed"
  fi
done
adb exec-out run-as "$PKG" sh -c 'cd files/ci && tar -cf - .' > "$OUT/device-files.tar" || true
tar -xf "$OUT/device-files.tar" -C "$OUT" || true
rm -f "$OUT/device-files.tar"
adb logcat -d -v time > "$OUT/logcat.txt" || true
ls -la "$OUT"
echo "$status" > "$OUT/instrumented-status.txt"
exit 0
