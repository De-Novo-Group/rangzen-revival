#!/bin/bash
# deploy-debug-local.sh - Deploy debug APK to all connected ADB devices
# For local testing only - NOT for production/OTA

set -e

PACKAGE="org.denovogroup.rangzen"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

cd "$PROJECT_DIR"

# Build debug APK if requested or if it doesn't exist
if [[ "$1" == "--build" ]] || [[ ! -f "$APK_PATH" ]]; then
    echo "Building debug APK..."
    # Use --no-daemon to avoid hanging, --console=plain for non-TTY compatibility
    ./gradlew assembleDebug --no-daemon --console=plain 2>&1 | tail -20
    echo "Build complete."
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "ERROR: Debug APK not found at $APK_PATH"
    echo "Run with --build to build first"
    exit 1
fi

# Get all connected devices
DEVICES=$(adb devices | grep -v "List" | grep "device$" | cut -f1)

if [[ -z "$DEVICES" ]]; then
    echo "No devices connected!"
    exit 1
fi

echo "Found devices: $(echo $DEVICES | tr '\n' ' ')"
echo ""

for DEVICE in $DEVICES; do
    echo "=== Deploying to $DEVICE ==="

    # Get model name for better output
    MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
    echo "  Device: $MODEL"

    # Force stop any running instance
    adb -s "$DEVICE" shell am force-stop "$PACKAGE" 2>/dev/null || true

    # Uninstall existing (handles signature mismatches)
    echo "  Uninstalling..."
    adb -s "$DEVICE" uninstall "$PACKAGE" 2>/dev/null || true

    # Install debug APK
    echo "  Installing..."
    if ! adb -s "$DEVICE" install -r "$APK_PATH"; then
        echo "  FAILED to install on $DEVICE"
        continue
    fi

    # Grant ALL permissions non-interactively
    echo "  Granting permissions..."
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_SCAN 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.ACCESS_WIFI_STATE 2>/dev/null || true
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.CHANGE_WIFI_STATE 2>/dev/null || true

    # Launch app
    echo "  Launching..."
    adb -s "$DEVICE" shell am start -n "$PACKAGE/.ui.MainActivity" >/dev/null

    echo "  Done: $MODEL"
    echo ""
done

echo "All devices deployed!"
echo ""

# Quick status check
echo "=== Device Summary ==="
for DEVICE in $DEVICES; do
    MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
    echo "✓ $DEVICE ($MODEL)"
done
