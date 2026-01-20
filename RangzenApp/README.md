# Rangzen Revival - Android App

A modern Android implementation of the Rangzen delay-tolerant mesh networking system for anonymous, censorship-resistant communication.

## Overview

Rangzen enables peer-to-peer message exchange without internet connectivity using:
- **Bluetooth Low Energy (BLE)** for peer discovery (~10-30m range)
- **WiFi Direct** for high-bandwidth message exchange
- **PSI-Ca Protocol** for privacy-preserving social trust scoring

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- JDK 17
- Minimum deployment target: Android 8.0 (API 26)
- Physical devices with BLE and WiFi Direct support for full testing

## Building

1. Open the `RangzenApp` folder in Android Studio
2. Wait for Gradle sync to complete
3. Build > Make Project (or Ctrl+F9)

## Running

### Emulator (limited testing)
- UI flows and message storage work
- BLE/WiFi Direct requires physical devices

### Physical Devices (full testing)
1. Enable USB debugging on device
2. Run > Select device > Run 'app'
3. Grant all permissions when prompted

## Testing Checklist

### Emulator
- [ ] App launches without crash
- [ ] All navigation tabs work
- [ ] Compose message saves to database
- [ ] Feed displays messages
- [ ] QR code generation works
- [ ] Settings toggle states persist

### Physical Devices (need 2+)
- [ ] BLE peer discovery finds nearby devices
- [ ] WiFi Direct connection establishes
- [ ] Message exchange between devices
- [ ] Trust scoring based on mutual friends
- [ ] Background service stays alive

## Architecture

```
org.denovogroup.rangzen/
├── backend/
│   ├── ble/
│   │   ├── BleScanner.kt      # Modern BLE scanning
│   │   └── BleAdvertiser.kt   # BLE advertising
│   ├── wifi/
│   │   └── WifiDirectManager.kt  # WiFi P2P
│   ├── Crypto.java            # PSI-Ca implementation (preserved from original)
│   ├── Exchange.kt            # Message exchange protocol
│   ├── MessageStore.kt        # Message persistence
│   ├── FriendStore.kt         # Friend/trust management
│   └── RangzenService.kt      # Foreground service
├── objects/
│   └── RangzenMessage.java    # Message data class
└── ui/
    ├── MainActivity.kt
    ├── FeedFragment.kt
    ├── ComposeFragment.kt
    ├── FriendsFragment.kt
    └── SettingsFragment.kt
```

## Key Improvements from Original Murmur

1. **Modern Android APIs** (SDK 34)
   - BluetoothLeScanner instead of deprecated startLeScan()
   - Foreground Service with notification channels
   - Modern permission handling

2. **Kotlin + Coroutines**
   - Cleaner async code
   - StateFlow for reactive UI updates

3. **Preserved Security**
   - Original PSI-Ca crypto code intact
   - SpongyCastle provider unchanged

## License

BSD-3-Clause - See original Murmur license

## De Novo Group

EIN: 26-3544198
CA Trust: CT0157263
