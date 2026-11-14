#!/usr/bin/env bash
# Emulator smoke test: installs Jarvis, walks through the main screens, saves screenshots and fails on a crash.
set -u
APK="$1"; OUT="$2"; PKG=com.jarvis.agent
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$APK" || exit 1
adb logcat -c

shot() { sleep 3; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

# Tap the first on-screen element whose text contains $1 (uses the UI hierarchy dump).
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
root = ET.parse("/tmp/ui.xml").getroot()
for n in root.iter("node"):
    t = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if want in t.lower():
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -z "$xy" ]; then echo "not found: $1"; return 1; fi
  adb shell input tap $xy; echo "tapped $1 at $xy"
}

start() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.ui.MainActivity "$@" >/dev/null; }

# 1. First run: onboarding wizard
adb shell pm clear $PKG >/dev/null
start
shot 01-onboarding-welcome
tap_text "Next" && shot 02-onboarding-ai-key
tap_text "Next" && shot 03-onboarding-permissions
tap_text "Next" && shot 04-onboarding-ready

# 2. Main screens (debug-only extras skip onboarding)
start --ez demo_skip_onboarding true --ei demo_tab 0 --ez demo_urdu false
shot 05-home
start --ez demo_skip_onboarding true --ei demo_tab 1
shot 06-activity
start --ez demo_skip_onboarding true --ei demo_tab 2
shot 07-settings
tap_text "Abilities" && shot 08-abilities
start --ez demo_skip_onboarding true --ei demo_tab 0 --ez demo_urdu true
shot 09-home-roman-urdu
start --ez demo_skip_onboarding true --ez demo_urdu false --es demo_tool MEETING
shot 10-meeting
start --ez demo_skip_onboarding true --es demo_tool TRANSLATE
shot 11-translate
start --ez demo_skip_onboarding true --es demo_tool EXPENSES
shot 12-expenses
start --ez demo_skip_onboarding true --es demo_tool DRIVING
shot 13-driving
start --ez demo_skip_onboarding true --ei demo_tab 2
tap_text "Privacy" >/dev/null 2>&1; adb shell input swipe 160 500 160 150 300; shot 14-settings-privacy

# Fail on any crash of our app
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" -A20 "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "CRASH DETECTED"; grep -E "FATAL EXCEPTION" -A30 "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "No crash."
