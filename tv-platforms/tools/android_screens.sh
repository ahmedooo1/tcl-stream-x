#!/usr/bin/env bash
# Android TV screenshots of the unchanged Android app, for the visual comparison with the TV
# editions (docs/SCREENSHOTS.md). Runs the existing UiTourTest only (remote keys, French then
# Arabic) on the emulator started by reactivecircus/android-emulator-runner. Read-only for the
# Android project: it installs the APKs that Gradle built and pulls the screenshots.
set -u
PKG=fr.ahmad.tclstreamx
OUT=android-shots
mkdir -p "$OUT"
adb wait-for-device
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell settings put global stay_on_while_plugged_in 7 || true
adb shell input keyevent KEYCODE_WAKEUP || true
# Same TV geometry as tools/ci_emulator_tests.sh: 1280x640 px at 213 dpi, about 960x480 dp.
adb shell wm size 640x1280 || true
adb shell wm density 213 || true
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 1 || true
sleep 4
adb shell am instrument -w -r -e class "$PKG.UiTourTest" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/UiTourTest.txt" 2>&1
grep -q "^OK (" "$OUT/UiTourTest.txt" && echo "::notice::UiTourTest passed" || echo "::warning::UiTourTest did not pass: screenshots may be partial"
python3 - "$OUT" <<'PY'
import base64, os, sys
out = sys.argv[1]; n = 0
f = os.path.join(out, "UiTourTest.txt")
for line in open(f, errors="replace"):
    if line.startswith("INSTRUMENTATION_STATUS: cireport:"):
        name, _, data = line[len("INSTRUMENTATION_STATUS: cireport:"):].strip().partition("=")
        if "/" in name or name.startswith(".") or not name.endswith(".png"):
            continue
        open(os.path.join(out, name), "wb").write(base64.b64decode(data)); n += 1
lines = [l if not l.startswith("INSTRUMENTATION_STATUS: cireport:") else l.split("=", 1)[0] + "=<decoded>\n" for l in open(f, errors="replace")]
open(f, "w").writelines(lines)
print(f"::notice::{n} screenshots")
PY
ls -la "$OUT"
exit 0
