#!/usr/bin/env bash
# Runs the on-device tests (app/src/androidTest) on a connected phone without losing the installed
# app's sign-in or downloads. Build first, from this checkout: assembleDebug
# assembleDebugAndroidTest, plus assembleDev for RESTORE_APK. Never use connectedAndroidTest:
# Gradle uninstalls the app afterwards.
#
#   bash tools/device-tests/run.sh [OUT_DIR] [TEST_CLASS[#method]]...
#   RESTORE_APK=app/build/outputs/apk/dev/app-dev.apk bash tools/device-tests/run.sh [OUT_DIR] ...
#
# - reads the app and test packages from the built APKs (aapt2 dump badging), so they follow
#   the build's devApplicationId (app/build.gradle.kts),
# - installs the app with -r (keeps its data) and the test APK,
# - wakes the screen (the phone stays locked: the tests show their host over the lock screen),
# - runs `am instrument` (all tests, or the given classes), printing the results
#   (everything in OUT_DIR/instrument.txt),
# - pulls the screenshots and report.txt into OUT_DIR, then deletes them on the phone,
# - uninstalls the TEST package only and turns the screen off again.
# SERIAL=<adb serial> picks the phone; otherwise the one device in `adb devices` whose Android
# version the APK supports (a device listed twice, over Wi-Fi and by its mDNS name, counts once).
# RESTORE_APK=<path> (optional): the tests need the debug build, which replaces the dev build the
# phone runs (same package). With it, that APK is installed again with -r (its data kept) after the
# test package is uninstalled, even when the tests fail. Its baseline profile goes with it through
# install-multiple when there is one of the same base name: next to it, or where AGP writes it
# (baselineProfiles/0/app-dev.dm beside app-dev.apk); if pm refuses that, it is installed alone. It
# must not have a lower versionCode than the debug APK (build both from the same checkout).
# Ctrl-C (or TERM, or closing the terminal) once the installs start still uninstalls the test
# package and restores before exiting; only a kill -9 or a lost adb connection skips that, and then
# the debug build stays until RESTORE_APK is installed by hand (install -r).
# ADB and AAPT2 override the tools' paths; by default both come from the Android SDK
# ($ANDROID_HOME, else %LOCALAPPDATA%\Android\Sdk), aapt2 from its newest build-tools.
set -uo pipefail
# Git Bash would turn /sdcard/... into a Windows path: device paths go through untouched, and
# local paths are handed to adb.exe in Windows form (win).
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
win() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
if command -v cygpath >/dev/null; then SDK="$(cygpath -u "$SDK")"; fi
ADB="${ADB:-$SDK/platform-tools/adb}"
AAPT2="${AAPT2:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)aapt2}"
OUT="${1:-device-test-output}"
shift || true
here="$(cd "$(dirname "$0")/../.." && pwd)"
APP_APK="$here/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$here/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RESTORE_APK="${RESTORE_APK:-}"
RESTORE_DM="${RESTORE_APK%.apk}.dm"
[ -f "$RESTORE_DM" ] || RESTORE_DM="$(dirname "$RESTORE_APK")/baselineProfiles/0/$(basename "$RESTORE_DM")"

if [ ! -f "$APP_APK" ] || [ ! -f "$TEST_APK" ]; then
  echo "Build first: assembleDebug assembleDebugAndroidTest${RESTORE_APK:+ assembleDev}" >&2; exit 2
fi
# Checked before anything is installed, so a wrong path can't leave the debug build on the phone.
if [ -n "$RESTORE_APK" ] && [ ! -f "$RESTORE_APK" ]; then echo "RESTORE_APK not found: $RESTORE_APK" >&2; exit 2; fi

# An APK's package name (package_of), or one field of aapt2's badging (badging APK minSdkVersion).
badging() { "$AAPT2" dump badging "$(win "$1")" 2>/dev/null | sed -n "s/^$2:'\{0,1\}\([^' ]*\).*/\1/p" | head -1; }
package_of() { "$AAPT2" dump badging "$(win "$1")" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1; }
APP="$(package_of "$APP_APK")"
TEST="$(package_of "$TEST_APK")"
if [ -z "$APP" ] || [ -z "$TEST" ]; then echo "Could not read the packages with $AAPT2 (set AAPT2=<path to aapt2>)" >&2; exit 2; fi
if [ -n "$RESTORE_APK" ] && [ "$(package_of "$RESTORE_APK")" != "$APP" ]; then
  echo "RESTORE_APK is not $APP: build it from this checkout (assembleDev)" >&2; exit 2
fi
echo "App: $APP, tests: $TEST"

min_sdk="$(badging "$APP_APK" minSdkVersion)"
pick_device() {
  local s sdk id seen="" found=""
  for s in $("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
    sdk="$("$ADB" -s "$s" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    [ -n "$sdk" ] && [ "$sdk" -ge "${min_sdk:-0}" ] 2>/dev/null || continue
    id="$("$ADB" -s "$s" shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
    case " $seen " in *" $id "*) continue ;; esac
    seen="$seen $id"
    found="$found $s"
  done
  set -- $found
  if [ $# -eq 1 ]; then echo "$1"; return 0; fi
  if [ $# -eq 0 ]; then echo "No device in adb devices runs Android API ${min_sdk:-?} or newer" >&2
  else echo "Several devices could run the tests:$found. Pick one with SERIAL=<serial>" >&2; fi
  return 1
}
serial="${SERIAL:-$(pick_device)}" || exit 2
adb() { "$ADB" -s "$serial" "$@"; }
echo "Device: $serial"

# Puts RESTORE_APK (and its .dm) back over the debug build; returns non-zero if that failed. A .dm
# pm refuses (a stale profile, a phone that wants it fs-verity signed) only costs the profile.
restore() {
  [ -n "$RESTORE_APK" ] || return 0
  if [ -f "$RESTORE_DM" ]; then
    if adb install-multiple -r "$(win "$RESTORE_APK")" "$(win "$RESTORE_DM")"; then
      echo "Restored $RESTORE_APK with its profile"; return 0
    fi
    echo "install-multiple with $RESTORE_DM failed: installing $RESTORE_APK without it" >&2
  fi
  if adb install -r "$(win "$RESTORE_APK")"; then echo "Restored $RESTORE_APK"; return 0; fi
  echo "RESTORE FAILED: the debug build is still installed; install $RESTORE_APK by hand (install -r)" >&2
  return 1
}

# Interrupted from here on: take the test package off and put RESTORE_APK back before exiting.
interrupted() {
  trap - INT TERM HUP
  echo "Interrupted: uninstalling $TEST${RESTORE_APK:+, restoring $RESTORE_APK}" >&2
  adb uninstall "$TEST" >/dev/null 2>&1
  restore
  adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
  exit "$1"
}
trap 'interrupted 130' INT
trap 'interrupted 143' TERM
trap 'interrupted 129' HUP

adb install -r "$(win "$APP_APK")" || exit 1
if ! adb install -r "$(win "$TEST_APK")"; then restore; exit 1; fi

classes=""
if [ $# -gt 0 ]; then
  classes="-e class $(IFS=,; echo "$*")"
fi
mkdir -p "$OUT"
adb shell input keyevent KEYCODE_WAKEUP
# shellcheck disable=SC2086
adb shell am instrument -w -r $classes "$TEST/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$OUT/instrument.txt" \
  | grep -E '^(INSTRUMENTATION_STATUS: test=|OK \(|FAILURES|Tests run|[0-9]+\) |java\.lang\.AssertionError)' | uniq
status=0
grep -q '^OK (' "$OUT/instrument.txt" || status=1

remote="/sdcard/Android/data/$APP/files/device-tests"
if adb shell "[ -d $remote ]"; then
  adb pull "$remote/." "$(win "$OUT")/" >/dev/null && echo "Pulled into $OUT"
  adb shell rm -rf "$remote"
fi
adb uninstall "$TEST" >/dev/null && echo "Uninstalled $TEST"
restore || status=1
trap - INT TERM HUP
adb shell input keyevent KEYCODE_SLEEP
exit $status
