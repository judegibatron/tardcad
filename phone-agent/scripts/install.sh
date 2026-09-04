#!/usr/bin/env bash
# Build Phone Agent, install it over USB and grant every permission through adb.
# Nothing here needs root: adb's shell user may grant permissions, flip app-ops and secure settings.
#
#   scripts/install.sh                      build, install, grant, launch
#   scripts/install.sh --uninstall a.b.c    remove an old app first (package name)
#   scripts/install.sh --no-build           reuse app/build/outputs/apk/debug/app-debug.apk
#
# Needs: JDK 17+, Android SDK with platform 35 (Android Studio installs it), adb on PATH,
# USB debugging enabled on the phone.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=io.github.judegibatron.phoneagent
ACC="$PKG/$PKG.access.AgentAccessibilityService"
LISTENER="$PKG/$PKG.access.AgentNotificationListener"
BUILD=1
UNINSTALL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --uninstall) UNINSTALL="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools and add it to PATH."; exit 1; }
adb start-server >/dev/null
DEVICE=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' | head -1)
if [ -z "$DEVICE" ]; then
  echo "No phone in 'device' state. Plug it in, unlock it and accept the USB debugging prompt."
  adb devices
  exit 1
fi
echo "Using device $DEVICE"

if [ -n "$UNINSTALL" ]; then
  echo "Uninstalling $UNINSTALL"
  adb uninstall "$UNINSTALL" || echo "(not installed)"
fi

if [ "$BUILD" = 1 ]; then
  ./gradlew assembleDebug
fi

# -g grants every runtime permission at install time.
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

grant() {
  if adb shell "$@" >/dev/null 2>&1; then echo "ok   $*"; else echo "skip $*"; fi
}
for p in RECORD_AUDIO SEND_SMS READ_CONTACTS CALL_PHONE POST_NOTIFICATIONS BLUETOOTH_CONNECT WRITE_SECURE_SETTINGS; do
  grant pm grant "$PKG" "android.permission.$p"
done
grant appops set "$PKG" SYSTEM_ALERT_WINDOW allow
grant appops set "$PKG" WRITE_SETTINGS allow
grant cmd notification allow_listener "$LISTENER"
grant cmd notification allow_dnd "$PKG"
grant dumpsys deviceidle whitelist "+$PKG"

CURRENT=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CURRENT" in
  *"$ACC"*) echo "ok   accessibility service already enabled" ;;
  null|"") adb shell settings put secure enabled_accessibility_services "$ACC" ;;
  *) adb shell settings put secure enabled_accessibility_services "$CURRENT:$ACC" ;;
esac
adb shell settings put secure accessibility_enabled 1

adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo
echo "Done. Phone Agent is open on the phone."
echo "Next: paste the Claude API key, tap 'Request' next to Root and approve the Magisk/KernelSU prompt."
echo "Watch it live with:  adb logcat -s PhoneAgent"
