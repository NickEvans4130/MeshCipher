#!/usr/bin/env bash
# MeshCipher Desktop — messaging functionality test script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

echo "=== MeshCipher Desktop — Messaging Test ==="
echo

echo "Prerequisites:"
echo "  [ ] Android app installed on phone"
echo "  [ ] Phone and desktop can reach relay.meshcipher.com"
echo "  [ ] relay.conf written to ~/.config/meshcipher/relay.conf"
echo "      (relayUrl=https://relay.meshcipher.com)"
echo "      (authToken=<your JWT token>)"
echo "  [ ] Optional: TOR installed  → sudo dnf install tor"
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
echo "--- 2. Key storage ---"
if command -v secret-tool &>/dev/null; then
    pass "secret-tool (libsecret) is available"
else
    skip "secret-tool not installed — wrap key stored in plaintext file"
fi

CONFIG_DIR="$HOME/.config/meshcipher"
if [[ -f "$CONFIG_DIR/identity.pub" ]]; then
    pass "Identity public key exists"
else
    skip "No identity key yet — will be generated on first run"
fi

if [[ -f "$CONFIG_DIR/wrap.key" ]]; then
    fail "Plaintext wrap.key exists — migrate to libsecret by running the app"
elif secret-tool lookup application meshcipher key-type wrap &>/dev/null 2>&1; then
    pass "Wrap key stored in libsecret keyring"
else
    skip "No wrap key yet — generated on first run"
fi

echo
echo "--- 3. Relay configuration ---"
if [[ -f "$CONFIG_DIR/relay.conf" ]]; then
    pass "relay.conf exists"
    if grep -q "relayUrl=" "$CONFIG_DIR/relay.conf"; then
        pass "relayUrl configured"
    else
        fail "relayUrl missing from relay.conf"
    fi
    if grep -q "authToken=" "$CONFIG_DIR/relay.conf"; then
        pass "authToken configured"
    else
        fail "authToken missing from relay.conf"
    fi
else
    skip "relay.conf not found — messaging will be local-only"
fi

echo
echo "--- 4. TOR availability ---"
if command -v tor &>/dev/null; then
    pass "TOR is installed"
else
    skip "TOR not installed (optional) — install: sudo dnf install tor"
fi

echo
echo "--- 5. Manual device link test ---"
echo "  Start the desktop app:  ./desktopApp/run.sh"
echo "  The app should display a QR code on the 'Link' screen."
echo "  On your phone: Settings → Linked Devices → Link New Device → scan QR."
if ask "Did the device link successfully?"; then
    pass "Device linking works"
else
    fail "Device linking failed — check logs"
fi

echo
echo "--- 6. Manual send message test (desktop → phone) ---"
echo "  In the desktop app, open a chat and send a message."
if ask "Did the message appear on the phone?"; then
    pass "Send message (desktop → phone) works"
else
    fail "Send message failed — check relay.conf and relay server logs"
fi

echo
echo "--- 7. Manual receive message test (phone → desktop) ---"
echo "  Send a reply from the phone."
if ask "Did the message appear on the desktop in real-time?"; then
    pass "Receive message (phone → desktop) works"
else
    fail "Receive message failed — check WebSocket connection"
fi

if command -v tor &>/dev/null; then
    echo
    echo "--- 8. TOR mode test ---"
    echo "  Enable TOR mode in app settings, then send a message."
    if ask "Did messaging still work with TOR enabled?"; then
        pass "TOR relay mode works"
    else
        fail "TOR mode failed — check if TOR bootstrapped (see app logs)"
    fi
else
    skip "TOR mode test (TOR not installed)"
fi

echo
echo "=== Summary ==="
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo "  SKIP: $SKIP"
echo

if [[ $FAIL -eq 0 ]]; then
    echo "All tests passed — desktop messaging is functional."
    exit 0
else
    echo "$FAIL test(s) failed."
    exit 1
fi
