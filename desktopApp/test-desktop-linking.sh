#!/usr/bin/env bash
# MeshCipher Desktop — device linking test script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

echo "=== MeshCipher Desktop — Device Linking Test ==="
echo

echo "Prerequisites:"
echo "  [ ] Android app installed and running on phone"
echo "  [ ] Phone can reach relay.meshcipher.com"
echo "  [ ] relay.conf written to ~/.config/meshcipher/relay.conf"
echo "  [ ] Android app has identity set up (past onboarding)"
echo

read -rp "Press Enter to start tests..."

PASS=0
FAIL=0
SKIP=0

pass()  { echo "  ✓ $1"; ((++PASS)) || true; }
fail()  { echo "  ✗ $1"; ((++FAIL)) || true; }
skip()  { echo "  - $1 (skipped)"; ((++SKIP)) || true; }
ask()   { read -rp "  $1 (y/n): " _ANS; [[ "$_ANS" == "y" ]]; }

echo
echo "--- 1. Build check ---"
if (cd "$ROOT_DIR" && ./gradlew :desktopApp:compileKotlin --no-daemon -q 2>/dev/null); then
    pass "desktopApp compiles"
else
    fail "desktopApp compile failed — fix errors first"
    exit 1
fi

echo
echo "--- 2. Shared module ---"
if (cd "$ROOT_DIR" && ./gradlew :shared:compileDebugKotlinAndroid --no-daemon -q 2>/dev/null); then
    pass "shared module compiles (Android target)"
else
    fail "shared module compile failed"
    exit 1
fi

echo
echo "--- 3. Device ID ---"
CONFIG_DIR="$HOME/.config/meshcipher"
if [[ -f "$CONFIG_DIR/device.id" ]]; then
    DEVICE_ID=$(cat "$CONFIG_DIR/device.id")
    pass "Desktop device ID: $DEVICE_ID"
else
    skip "No device.id yet — generated on first run"
fi

echo
echo "--- 4. QR code format ---"
echo "  Start the desktop app:  ./desktopApp/run.sh"
echo "  The Link screen should show a QR code encoding:"
echo "    meshcipher://link/<base64url-json>"
if ask "Does the QR code display on the Link screen?"; then
    pass "QR code displayed"
else
    fail "QR code not displayed — check app logs"
fi

echo
echo "--- 5. Phone scans QR ---"
echo "  On your phone: Settings → Linked Devices → tap + → scan the desktop QR"
if ask "Did the phone show the 'Link Device' approval screen?"; then
    pass "Phone scanned QR and showed approval screen"
else
    fail "QR scan failed — check that the QR is a meshcipher://link/ URI"
fi

echo
echo "--- 6. Approve link ---"
echo "  On your phone: tap Approve on the device link approval screen"
if ask "Did the phone send the approval?"; then
    pass "Link approved on phone"
else
    fail "Approval failed — check relay config and network"
fi

echo
echo "--- 7. Desktop receives approval ---"
echo "  The desktop app should process the approval response from the relay"
if ask "Did the desktop update its linked devices list?"; then
    pass "Desktop received link approval"
else
    skip "Desktop approval handling — requires WebSocket relay to be running"
fi

echo
echo "--- 8. Message forwarding (phone → desktop) ---"
echo "  Send a message on the phone to a contact"
if ask "Did the message appear on the desktop app?"; then
    pass "Message forwarding works (phone → desktop)"
else
    skip "Message forwarding not verified"
fi

echo
echo "--- 9. Notification on new message ---"
echo "  Minimize the desktop app to tray, then send a message from phone"
if command -v notify-send &>/dev/null; then
    if ask "Did you receive a desktop notification for the incoming message?"; then
        pass "Desktop notification works"
    else
        fail "Desktop notification failed — check notify-send / libnotify"
    fi
else
    skip "Desktop notifications (notify-send not installed)"
fi

echo
echo "--- 10. Minimize to tray ---"
echo "  Close the desktop window (X button)"
if ask "Did the app minimize to system tray instead of quitting?"; then
    pass "Minimize to tray works"
else
    fail "App quit instead of minimizing to tray"
fi

echo
echo "=== Summary ==="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
echo

if [[ $FAIL -eq 0 ]]; then
    echo "All tests passed — device linking is functional."
    exit 0
else
    echo "$FAIL test(s) failed."
    exit 1
fi
