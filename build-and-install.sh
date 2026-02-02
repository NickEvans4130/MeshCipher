#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# -- Java --
# Gradle 8.7 + AGP 8.5 support JDK 21. Avoid JDK 25+ which is unsupported.
if [ -z "${JAVA_HOME:-}" ]; then
    for jdk_dir in /usr/lib/jvm/java-21 /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17 /usr/lib/jvm/java-17-openjdk; do
        if [ -d "$jdk_dir" ]; then
            export JAVA_HOME="$jdk_dir"
            break
        fi
    done
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

# -- Android SDK --
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

if [ ! -d "$ANDROID_HOME" ]; then
    echo "ERROR: Android SDK not found at $ANDROID_HOME"
    echo "Set ANDROID_HOME to your SDK location."
    exit 1
fi

# Write local.properties if missing
if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo "Created local.properties"
fi

# -- Gradle wrapper --
if [ ! -f gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    GRADLE_VERSION="8.7"
    REPO_TAG="v${GRADLE_VERSION}.0"
    BASE_URL="https://raw.githubusercontent.com/gradle/gradle/${REPO_TAG}"

    mkdir -p gradle/wrapper

    echo "Downloading gradle wrapper files (Gradle ${GRADLE_VERSION})..."
    curl -fsSL -o gradle/wrapper/gradle-wrapper.jar "${BASE_URL}/gradle/wrapper/gradle-wrapper.jar"
    curl -fsSL -o gradlew "${BASE_URL}/gradlew"
    chmod +x gradlew

    echo "Gradle wrapper ready."
fi

# -- Check device --
echo "Checking for connected device..."
if ! adb devices | grep -q 'device$'; then
    echo "ERROR: No device found. Connect your Pixel and enable USB debugging."
    echo "Detected devices:"
    adb devices
    exit 1
fi

DEVICE=$(adb devices | grep 'device$' | head -1 | awk '{print $1}')
echo "Found device: $DEVICE"

# -- Build --
echo ""
echo "Building debug APK..."
./gradlew assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK"
    echo "Build may have failed. Check output above."
    exit 1
fi

echo "APK built: $APK"

# -- Install --
echo ""
echo "Installing to $DEVICE..."
adb -s "$DEVICE" install -r "$APK"

# -- Launch --
echo ""
echo "Launching MeshCipher..."
adb -s "$DEVICE" shell am start -n com.meshcipher/.presentation.MainActivity

echo ""
echo "Done."
