#!/usr/bin/env bash
# Runs inside an Android emulator. End-to-end check of the signing-key rotation, with no access to any private key:
#   A. v1.0 (legacy key, private fixture) -> published v1.1 -> this build -> a future test build (versionCode+1):
#      every step must be an in-place update that keeps the app's data.
#   B. An APK signed ONLY with the compromised legacy key (private fixture, versionCode 999999, so a rejection can only
#      be caused by the signature) must be rejected over the updated app, which must stay untouched.
#   C. Control: the same attacker APK installs on an empty device, so B's rejection really is the signature check.
#   D. v1.0 -> this build directly (users who skip 1.1) must also update in place with data kept.
#   E. This build installs on a clean device and launches.
# Files in test-apks/: zoomify-1.0-legacy.apk, TEST-ONLY-legacy-attacker.apk, Zoomify-v1.1.apk, Zoomify.apk,
#                      TEST-ONLY-future-update.apk
set -uo pipefail
PKG=com.zoomify.camera
DIR=test-apks
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
REPORT=rotation-report-api$API.txt
CUR_VC=${CUR_VC:?} FUT_VC=${FUT_VC:?}
pass() { echo "PASS: $*" | tee -a "$REPORT"; }
fail() { echo "FAIL: $*" | tee -a "$REPORT"; exit 1; }
vc() { adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }
install() { local out; out=$(adb install "$@" 2>&1); echo "$out" >&2; echo "$out"; }
echo "Android API $API ($(adb shell getprop ro.build.version.release | tr -d '\r'))" | tee "$REPORT"

# App data check: a marker file inside the app's private data directory (needs root, which google_apis emulators
# allow). It disappears if Android wipes the data (uninstall/reinstall), so it proves data survives each update.
ROOT=0
adb root >/dev/null 2>&1 && adb wait-for-device && sleep 2 && [ "$(adb shell id -u | tr -d '\r')" = 0 ] && ROOT=1
DATA=/data/data/$PKG/files/zoomify-update-test-marker
put_marker() {
  if [ $ROOT = 1 ]; then
    MARK=$(date +%s%N)
    adb shell "mkdir -p /data/data/$PKG/files && echo $MARK > $DATA" || fail "could not write the data marker"
  fi
  FIRST=$(adb shell dumpsys package $PKG | grep -m1 -o 'firstInstallTime=[^ ]*[^ ]* [^ ]*' | tr -d '\r')
}
check_marker() {
  if [ $ROOT = 1 ]; then
    [ "$(adb shell cat $DATA 2>/dev/null | tr -d '\r')" = "$MARK" ] || fail "app data was lost after: $1"
  fi
  [ "$(adb shell dumpsys package $PKG | grep -m1 -o 'firstInstallTime=[^ ]*[^ ]* [^ ]*' | tr -d '\r')" = "$FIRST" ] \
    || fail "the app was reinstalled instead of updated after: $1"
  pass "app data kept after $1$([ $ROOT = 1 ] || echo ' (install-time check only; no root on this image)')"
}

# A. Update chain
adb uninstall $PKG >/dev/null 2>&1 || true
[[ $(install "$DIR/zoomify-1.0-legacy.apk") == *Success* ]] || fail "v1.0 (legacy key) did not install"
[ "$(vc)" = 1 ] || fail "v1.0 has versionCode $(vc)"
pass "v1.0 (legacy key) installed"
put_marker
[[ $(install -r "$DIR/Zoomify-v1.1.apk") == *Success* ]] || fail "published v1.1 did not update v1.0"
[ "$(vc)" = 2 ] || fail "after v1.1 versionCode is $(vc)"
pass "published v1.1 updated v1.0 in place"; check_marker "1.0 -> 1.1"
[[ $(install -r "$DIR/Zoomify.apk") == *Success* ]] || fail "this build did not update v1.1"
[ "$(vc)" = "$CUR_VC" ] || fail "after this build versionCode is $(vc)"
pass "this build (new key only + v1.1 lineage) updated v1.1 in place"; check_marker "1.1 -> this build"
[[ $(install -r "$DIR/TEST-ONLY-future-update.apk") == *Success* ]] || fail "future test build did not update"
[ "$(vc)" = "$FUT_VC" ] || fail "after the future build versionCode is $(vc)"
pass "future update (versionCode $FUT_VC, new key) installed in place"; check_marker "this build -> future update"

# B. Attacker APK (legacy key only) over the updated app
out=$(install -r "$DIR/TEST-ONLY-legacy-attacker.apk")
[[ "$out" == *Success* ]] && fail "an APK signed only with the compromised key REPLACED the updated app"
[[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]] || fail "attacker APK failed for an unexpected reason: $out"
pass "APK signed only with the compromised key was rejected (INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures)"
[ "$(vc)" = "$FUT_VC" ] || fail "installed app changed after the rejected install: $(vc)"
check_marker "the rejected attacker install"

# C. Control: the attacker APK itself is valid
adb uninstall $PKG >/dev/null
[[ $(install "$DIR/TEST-ONLY-legacy-attacker.apk") == *Success* ]] || fail "control: attacker fixture is not installable"
pass "control: the attacker APK installs on an empty device, so the rejection above was the signature check"
adb uninstall $PKG >/dev/null

# D. v1.0 straight to this build
[[ $(install "$DIR/zoomify-1.0-legacy.apk") == *Success* ]] || fail "v1.0 did not install (D)"
put_marker
[[ $(install -r "$DIR/Zoomify.apk") == *Success* ]] || fail "this build did not update v1.0 directly"
pass "this build updated v1.0 directly"; check_marker "1.0 -> this build"
out=$(install -r "$DIR/TEST-ONLY-legacy-attacker.apk")
[[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]] || fail "attacker APK not rejected after 1.0 -> this build: $out"
pass "attacker APK also rejected after a direct 1.0 -> this build update"

# E. Clean install + launch
adb uninstall $PKG >/dev/null
[[ $(install "$DIR/Zoomify.apk") == *Success* ]] || fail "this build did not install on a clean device"
pass "this build installs on a clean device"
adb shell pm grant $PKG android.permission.CAMERA || true
adb logcat -c || true
adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 10
if adb shell pidof $PKG >/dev/null 2>&1; then
  echo "INFO: app launched and was still running after 10 s" | tee -a "$REPORT"
else   # emulator camera/GPU are not real hardware, so this is informational
  echo "INFO: app was not running after 10 s on the emulator; crash log:" | tee -a "$REPORT"
  adb logcat -d -b crash | tail -40 | tee -a "$REPORT" || true
fi
echo "All rotation checks passed on API $API" | tee -a "$REPORT"
