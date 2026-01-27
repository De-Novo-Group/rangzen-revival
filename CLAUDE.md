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
