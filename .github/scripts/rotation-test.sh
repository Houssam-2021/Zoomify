#!/usr/bin/env bash
# Runs inside an Android emulator. Verifies the signing-key rotation end to end:
#   1. the old v1.0 (legacy key) installs,
#   2. v1.1 (new key + rotation proof) installs over it as an update,
#   3. an APK signed ONLY with the leaked legacy key is rejected over v1.1,
#   4. v1.1 installs on a clean device.
set -uo pipefail
PKG=com.zoomify.camera
DIR=test-apks
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
REPORT=rotation-report-api$API.txt
pass() { echo "PASS: $*" | tee -a "$REPORT"; }
fail() { echo "FAIL: $*" | tee -a "$REPORT"; exit 1; }
echo "Android API $API ($(adb shell getprop ro.build.version.release | tr -d '\r'))" | tee "$REPORT"

adb uninstall "$PKG" >/dev/null 2>&1 || true
out=$(adb install "$DIR/zoomify-1.0-legacy.apk" 2>&1); echo "$out"
[[ "$out" == *Success* ]] || fail "v1.0 (legacy key) did not install: $out"
pass "v1.0 installed (versionCode $(adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*'))"

out=$(adb install -r "$DIR/Zoomify.apk" 2>&1); echo "$out"
[[ "$out" == *Success* ]] || fail "v1.1 did not install over v1.0: $out"
vc=$(adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*')
[[ "$vc" == "versionCode=2" ]] || fail "unexpected version after update: $vc"
pass "v1.1 installed over v1.0 as an update ($vc)"

out=$(adb install -r "$DIR/TEST-ONLY-legacy-signed.apk" 2>&1); echo "$out"
if [[ "$out" == *Success* ]]; then fail "an APK signed only with the leaked key was accepted over v1.1"; fi
[[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* || "$out" == *signatures* ]] || fail "unexpected install error: $out"
pass "APK signed only with the leaked key was rejected over v1.1"
vc=$(adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*')
[[ "$vc" == "versionCode=2" ]] || fail "installed app changed after the rejected install: $vc"
pass "installed app is still the genuine v1.1"

adb uninstall "$PKG" >/dev/null
out=$(adb install "$DIR/Zoomify.apk" 2>&1); echo "$out"
[[ "$out" == *Success* ]] || fail "v1.1 did not install on a clean device: $out"
pass "v1.1 installs on a clean device"

# Informational smoke test (emulator camera/GPU are not real hardware, so this never fails the build).
adb shell pm grant $PKG android.permission.CAMERA || true
adb logcat -c || true
adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 10
if adb shell pidof $PKG >/dev/null 2>&1; then
  echo "INFO: app launched and was still running after 10 s" | tee -a "$REPORT"
else
  echo "INFO: app was not running after 10 s on the emulator; crash log:" | tee -a "$REPORT"
  adb logcat -d -b crash | tail -40 | tee -a "$REPORT" || true
fi
echo "All rotation checks passed on API $API" | tee -a "$REPORT"
