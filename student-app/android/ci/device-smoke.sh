#!/usr/bin/env bash
# Emulator smoke test: starts the web server on the CI machine, installs the APK, connects the app to the
# server (10.0.2.2 is the host as seen from the emulator), signs in, saves screenshots, fails on a crash.
set -u
APK="$1"; OUT="$2"; PKG=com.assignmentreview.app
HOST_URL=http://127.0.0.1:3000
APP_URL=http://10.0.2.2:3000
mkdir -p "$OUT"

# 1. Web server (demo AI, fresh data)
LOG_REQUESTS=1 DATA_DIR=$(mktemp -d) PORT=3000 PUBLIC_URL=$APP_URL AI_MODE=demo node --no-warnings student-app/src/server.js > "$OUT/server.log" 2>&1 &
SERVER_PID=$!
for i in $(seq 1 30); do curl -sf $HOST_URL/api/health >/dev/null && break; sleep 1; done
curl -sf $HOST_URL/api/health || { echo "server did not start"; cat "$OUT/server.log"; exit 1; }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$APK" || exit 1
adb logcat -c

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
has_text() { dump; grep -qi -- "$1" /tmp/ui.xml; }

# Tap the first on-screen element whose text, description or hint contains $1 (or equals it with exact=1).
tap_text() {
  dump
  cp /tmp/ui.xml "$OUT/ui-$(date +%s%N).xml" 2>/dev/null
  local xy
  xy=$(python3 - "$1" "${2:-0}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1].lower(); exact = sys.argv[2] == "1"
root = ET.parse("/tmp/ui.xml").getroot()
for n in root.iter("node"):
    vals = [(n.get(k) or "").strip().lower() for k in ("text", "content-desc", "hint")]
    if (want in vals) if exact else any(want in v for v in vals):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -z "$xy" ]; then echo "not found: $1"; return 1; fi
  adb shell input tap $xy; echo "tapped $1 at $xy"
}
type_text() { adb shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; }
fail() {
  echo "FAIL: $1"; dump; cp /tmp/ui.xml "$OUT/ui-at-failure.xml"; adb logcat -d > "$OUT/logcat.txt"
  echo "--- on-screen text ---"; python3 -c "import xml.etree.ElementTree as E;[print(repr(n.get('text')),n.get('class'),n.get('bounds')) for n in E.parse('/tmp/ui.xml').getroot().iter('node') if n.get('text') or 'WebView' in (n.get('class') or '')]"
  echo "--- server requests ---"; tail -40 "$OUT/server.log"
  echo "--- app log ---"; grep -E "chromium|WebView|AndroidRuntime|$PKG" "$OUT/logcat.txt" | tail -40
  kill $SERVER_PID; exit 1; }

# 2. First launch: native "Connect to your school" screen
adb shell pm clear $PKG >/dev/null
adb shell am start -W -n $PKG/.MainActivity >/dev/null
shot 01-connect-screen
has_text "Connect to your school" || fail "connect screen not shown"

# 3. A wrong address shows an error, the right one opens the web app
tap_text "app.yourschool.org" || fail "address field not found"
type_text "http://10.0.2.2:3999"
adb shell input keyevent 66
sleep 6
has_text "Could not reach" || fail "unreachable server not reported"
shot 02-connect-error 1
adb shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 30); do adb shell input keyevent 67; done
type_text "$APP_URL"
tap_text "Connect" 1 || fail "connect button not found"
sleep 8
shot 03-web-setup
grep -q "GET /api/setup 200 android-app" "$OUT/server.log" || fail "web app did not load in the WebView"
has_text "Set up your school" || echo "note: WebView text not visible to uiautomator (checked via server log instead)"

# 4. Create the school on the server, then sign in through the app
curl -sf -X POST $HOST_URL/api/setup -H 'content-type: application/json' -H 'x-requested-with: app' \
  -d '{"orgName":"Demo International School","name":"Admin","username":"admin","password":"AdminPass2026","defaultLang":"en"}' >/dev/null || fail "setup via API failed"
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 6
grep -c "GET /api/me 401 android-app" "$OUT/server.log" | grep -qv '^0$' || fail "app did not reopen the saved server"
shot 04-sign-in 1
# The username field has focus on the sign-in page; Tab moves to the password field.
type_text "admin"
adb shell input keyevent 61
type_text "AdminPass2026"
adb shell input keyevent 66
sleep 6
shot 05-two-step-sign-in
grep -q "POST /api/login 200 android-app" "$OUT/server.log" || fail "sign-in through the app failed"
grep -q "GET /api/mfa/setup 200 android-app" "$OUT/server.log" || fail "session cookie not kept after sign-in"

# 5. Back button and app restart keep working
adb shell input keyevent 4
sleep 2
adb shell am start -W -n $PKG/.MainActivity >/dev/null
shot 06-after-restart

kill $SERVER_PID
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A20 "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "CRASH DETECTED"; grep -E "FATAL EXCEPTION" -A30 "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "Device smoke test passed."
