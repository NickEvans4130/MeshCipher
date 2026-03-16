#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

echo "=== MeshCipher Desktop — Reinstall ==="

# Kill any running instances
echo "Stopping any running instances..."
pkill -f "MeshCipher" 2>/dev/null || true
sleep 1

# Build
echo "Building..."
cd "$ROOT_DIR"
./gradlew :desktopApp:packageRpm --no-daemon -q

RPM=$(find desktopApp/build/compose/binaries/main/rpm -name "*.rpm" | head -1)
if [[ -z "$RPM" ]]; then
    echo "ERROR: RPM not found after build"
    exit 1
fi

echo "Installing $RPM..."
sudo rpm -Uvh --force "$RPM"

echo
echo "Done. Run with: ./desktopApp/run.sh"
