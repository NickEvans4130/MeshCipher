#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$ROOT_DIR"

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

echo "Building and running MeshCipher Desktop..."
./gradlew :desktopApp:run --no-daemon "$@"
