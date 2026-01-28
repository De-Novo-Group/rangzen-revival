#!/bin/bash
# Deploy latest release APK to all connected ADB devices
#
# IMPORTANT: This script installs RELEASE builds from GitHub, not debug builds.
# This ensures ADB phones can receive future OTA updates (same signing key).
#
# Usage: ./scripts/deploy-adb.sh [version]
#   version: optional, e.g. "0.2.64". If omitted, fetches latest release.

set -e

REPO="De-Novo-Group/rangzen-revival"
PACKAGE="org.denovogroup.rangzen"
APK_DIR="/tmp/murmur-deploy"

# Get version (from arg or latest release)
if [ -n "$1" ]; then
    VERSION="$1"
    TAG="v$VERSION"
else
    echo "Fetching latest release..."
    TAG=$(gh release view --repo "$REPO" --json tagName -q '.tagName')
    VERSION="${TAG#v}"
fi

echo "Deploying version: $VERSION (tag: $TAG)"

# Create temp directory
mkdir -p "$APK_DIR"
APK_PATH="$APK_DIR/murmur-$VERSION.apk"

# Download APK if not already present
if [ ! -f "$APK_PATH" ]; then
    echo "Downloading APK from GitHub release..."
    gh release download "$TAG" \
        --repo "$REPO" \
        --pattern "murmur-$VERSION.apk" \
        --dir "$APK_DIR" \
        --clobber
fi

# Verify download
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: Failed to download APK"
    exit 1
fi

echo "APK ready: $APK_PATH"
echo ""

# Get connected devices
DEVICES=$(adb devices | grep -v "List" | grep "device$" | cut -f1)

if [ -z "$DEVICES" ]; then
    echo "ERROR: No ADB devices connected"
    exit 1
fi

echo "Found devices:"
echo "$DEVICES"
echo ""

# Install on each device
for DEVICE in $DEVICES; do
    echo "=== $DEVICE ==="

    # Uninstall first (to handle signature mismatches)
    echo "  Uninstalling existing app..."
    adb -s "$DEVICE" uninstall "$PACKAGE" 2>/dev/null || echo "  (not installed)"

    # Install release APK
    echo "  Installing release APK..."
    adb -s "$DEVICE" install "$APK_PATH"

    # Grant runtime permissions
    echo "  Granting permissions..."
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_SCAN 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    # Verify version
    INSTALLED=$(adb -s "$DEVICE" shell dumpsys package "$PACKAGE" | grep versionName | head -1 | tr -d ' ')
    echo "  Installed: $INSTALLED"

    # Launch app
    echo "  Launching app..."
    adb -s "$DEVICE" shell am start -n "$PACKAGE/.ui.MainActivity"

    echo ""
done

echo "Done! All devices updated to release $VERSION"
