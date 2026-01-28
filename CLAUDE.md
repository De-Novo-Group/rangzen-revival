# Murmur/Rangzen Android App - Agent Instructions

## Deploying to ADB Test Phones

**IMPORTANT**: Always use RELEASE builds from GitHub, NOT debug builds.

Debug and release APKs have different signing keys. If you install a debug build,
the device cannot receive OTA updates (signature mismatch). This causes repeated
"package conflicts with existing package" errors.

### To deploy to ADB phones:

```bash
./scripts/deploy-adb.sh
```

This script:
1. Downloads the latest release APK from GitHub (properly signed)
2. Uninstalls any existing version (handles signature mismatches)
3. Installs the release APK on all connected devices
4. Launches the app

To deploy a specific version:
```bash
./scripts/deploy-adb.sh 0.2.64
```

### DO NOT:
- Run `./gradlew assembleDebug` and install via ADB for test devices
- This creates signature mismatches that break OTA updates

### When to use debug builds:
- Only for local development/debugging on your personal device
- When you need to attach a debugger or see debug logs

## Local Debug Testing (Development Only)

When actively debugging/developing features locally, use the debug deployment script:

```bash
./scripts/deploy-debug-local.sh --build
```

This script:
1. Builds debug APK (with --build flag, or if APK doesn't exist)
2. Uninstalls existing app on ALL connected devices
3. Installs debug APK with ALL permissions pre-granted
4. Launches the app
5. Shows peer count status after 5 seconds

**CRITICAL**: This is for LOCAL TESTING ONLY. These devices will NOT receive OTA updates
until you reinstall a release build. Use this when:
- Iterating on bug fixes locally
- Testing code changes before committing
- Debugging with logcat (Timber logs visible in debug builds)

**DO NOT** waste time manually granting permissions - the script handles everything.

## OTA Updates

OTA updates are delivered via the telemetry server to devices with QA mode enabled.
Release workflow:
1. Push a tag `v*` (e.g., `v0.2.65`)
2. GitHub Actions builds, signs, and creates a release
3. Private repo deploys APK to OTA server
4. Devices with QA mode enabled receive the update

## Building

- Debug: `./gradlew assembleDebug` (local dev only)
- Release: Done via GitHub Actions (requires signing keys in secrets)

## GitHub Repo

- Public: De-Novo-Group/rangzen-revival
- Private (OTA server): De-Novo-Group/murmur-telemetry-server

## Current Debugging Focus: BLE Message Exchange

**Problem**: BLE GATT exchanges are timing out before all messages transfer.
Messages propagate very slowly via BLE (one every ~30 seconds) and often incomplete.

**Key observations**:
- This is a **protocol issue**, NOT a bandwidth problem (messages are <10 characters)
- WiFi Aware exchanges work perfectly (sent=10, received=10)
- BLE exchanges timeout with partial results (e.g., received=3 out of 10)
- The issue is in **our code** (RangzenService.kt exchange logic), not hardware

**Test setup**:
- Samsung (BT + WiFi Direct + LAN) - gets messages from network
- Pixel 5 (BT + WiFi Aware) - should receive from Samsung via BLE
- Pixel 4a (WiFi Aware only) - receives from Pixel 5 via WiFi Aware
- Motorola (v0.2.94 from GitHub) - additional BLE peer on network

**Where to look**:
- `RangzenService.kt`: BLE exchange loop, GATT client/server logic
- `BleExchangeController.kt`: BLE GATT connection handling
- Exchange timeout configuration in `config.json` (exchangeSessionTimeoutMs: 30000)
- Backoff timing: base 10s, max 320s

**Logs to watch**:
```
adb logcat -s RangzenService:* BleExchangeController:* BleGattClient:* BleGattServer:*
```

**Fixed bugs** (already resolved):
- Asymmetric initiator selection: publicId (64 chars) vs prefix (8 chars) comparison
