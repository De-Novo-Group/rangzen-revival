# Debug Log: Peer Correlation Issue

Date: 2026-01-28

## Problem Statement

Pixel 5 shows more peer entries than expected. With 6 physical phones, Pixel 5 (excluding itself) should show 5 unique peers. Currently showing 7-8 entries, suggesting peer correlation across transports is failing.

## Known Facts

- FACT 1: 6 physical phones with Murmur: Pixel 5, Pixel 4a, Pixel 8, Samsung A16, Moto-en, Moto-farsi
- FACT 2: Pixel 4a device ID prefix is `dc545d1a` (verified from RangzenService log at 18:27:31)
- FACT 3: Pixel 4a wlan0 is DOWN (not connected to WiFi)
- FACT 4: Pixel 8 is not connected to any WiFi network (no SSID credentials)
- FACT 5: Pixel 5 current peer registry shows 7 entries (as of 19:47:58)
- FACT 6: One peer (`f6439aea61fc8b7a`) correctly merges bt-le + wlan transports
- FACT 7: WiFi connected: Samsung, Moto-en, Moto-farsi, Pixel 5 (4 phones)
- FACT 8: BT enabled: Pixel 5, Moto-farsi, Samsung (3 phones)
- FACT 9: BT disabled: Pixel 4a, Pixel 8, Moto-en (3 phones)

## Expected Peer Count from Pixel 5

Pixel 5 should see 5 unique peers (6 phones - self):
1. Pixel 4a - WiFi Aware only (no WiFi, no BT)
2. Pixel 8 - WiFi Aware only (no WiFi, no BT)
3. Samsung - LAN + BLE (should merge)
4. Moto-en - LAN only (BT disabled)
5. Moto-farsi - LAN + BLE (should merge)

Expected transports:
- 3 LAN peers (Samsung, Moto-en, Moto-farsi)
- 2 WiFi Aware peers (Pixel 4a, Pixel 8)
- 2 BLE peers (Samsung, Moto-farsi) - SHOULD merge with LAN

**Actual: 7 entries. Expected: 5. Discrepancy: 2 extra entries.**

## Peer Registry Snapshot (Pixel 5, 19:47:58)

```
peer=580d250330b93dd5 transports=[bt-le(55:F2:AD:D3:02:2C)] [HS]
peer=f94a94711273c103 transports=[wlan(192.168.44.218:41235)] [HS]
peer=f6439aea61fc8b7a transports=[bt-le(43:9C:DC:F7:3F:B2), wlan(192.168.44.223:41235)] [HS]
peer=dc545d1a transports=[wifi-aware(nan_906)] [HS]
peer=4cceccf0 transports=[wifi-aware(nan_904)] [HS]
peer=9a8af3687f46a901 transports=[wlan(192.168.44.208:41235)] [HS]
peer=ef4e645852b48c0e transports=[wlan(192.168.44.216:41235)] [HS]
```

## Device ID Mapping (Incomplete)

| Phone | Device ID (full) | Device ID (prefix) | BT Status | WiFi Status |
|-------|------------------|-------------------|-----------|-------------|
| Pixel 5 | ? | ? | ON | ON (192.168.44.x) |
| Pixel 4a 5G | dc545d1a... | dc545d1a | **OFF** | **OFF** |
| Pixel 8 | ? | 4cceccf0? | **OFF** | **OFF** |
| Samsung A16 | ? | ? | ON | ? |
| Phone 5 | ? | ? | ? | ? |
| Phone 6 | ? | ? | ? | ? |

### Key Finding: BT Status

```
Pixel 4a: bluetooth_on = 0 (OFF)
Pixel 8: bluetooth_on = 0 (OFF)
Pixel 5: bluetooth_on = 1 (ON)
Samsung A16: bluetooth_on = 1 (ON)
```

This explains why WiFi Aware peers (dc545d1a, 4cceccf0) don't appear in BLE scans.
They're not duplicates - they're devices with Bluetooth disabled.

## Hypotheses

### H1: All 7 entries are unique devices

- Test: Identify device ID for all 6 phones, verify each maps to exactly one peer entry
- Prediction: If true, 7 entries means Pixel 5 is discovering a 7th device (not in our 6)
- Status: UNTESTED

### H2: WiFi Aware peers duplicate other transport entries

- Test: Check if `dc545d1a` or `4cceccf0` prefix matches any LAN/BLE peer's full ID
- Prediction: If duplicate exists, the full ID should START WITH the WiFi Aware prefix
- Status: **TESTED - NEGATIVE**
- Finding:
  - BLE peer IDs: `580d2503...`, `f6439aea...` - neither starts with dc545d1a or 4cceccf0
  - LAN peer IDs: `f94a9471...`, `9a8af368...`, `ef4e6458...` - none match
- **Conclusion: WiFi Aware peers are NOT duplicates. They are correctly identified as separate devices (Pixel 4a/8 with BT disabled).**

### H3: BLE peer `580d250330b93dd5` is a duplicate

- Test: Check if 580d2503 prefix matches any WiFi Aware or LAN peer
- Prediction: If duplicate, another entry exists with matching prefix
- Status: **TESTED - NEGATIVE**
- Finding: No LAN peer starts with `580d2503`:
  - f94a9471... ❌
  - 9a8af368... ❌
  - ef4e6458... ❌
- WiFi Aware peers also don't match (tested in Experiment 1)
- **Conclusion: 580d250330b93dd5 is NOT a duplicate of any other peer**

### H4: Pixel 5 is discovering devices outside the 6-phone set

- Test: Count unique physical devices in range
- Prediction: If >6 devices exist, extra peers are legitimate
- Status: UNTESTED

## Experiment Log

### Experiment 1: Check BLE prefix against WiFi Aware peers

- Hypothesis being tested: H3
- Procedure: Compare `580d2503` (BLE peer prefix) against WiFi Aware peer IDs
- Data collected:
  - BLE peer: `580d250330b93dd5` (prefix = `580d2503`)
  - WiFi Aware peers: `dc545d1a`, `4cceccf0`
  - Neither matches `580d2503`
- Result: No match found
- Conclusion: `580d250330b93dd5` is NOT a duplicate of WiFi Aware peers

### Experiment 2: Identify Pixel 8 device ID

- Hypothesis being tested: H1 (need device ID mapping)
- Procedure: Get Pixel 8's device ID from logs
- Data collected: (pending - logs rotated, need fresh data)
- Result: PENDING
- Conclusion: PENDING

### Experiment 3: Verify correlation logic handles BLE-to-LAN matching

- Hypothesis being tested: H3 (code bug causing missed correlation)
- Procedure: Code review of `reportLanPeer()` in DiscoveredPeerRegistry.kt
- Data collected:
  - LAN correlation logic at lines 298-324 does prefix matching
  - Checks: `publicId.startsWith(peerId) || peerId.startsWith(publicId)`
  - This SHOULD match `580d250330b93dd5` (LAN full ID) with `580d2503` (BLE prefix)
- Result: **Code is correct** - correlation would work IF the same device has same ID on both transports
- Conclusion: The correlation logic is sound. If 580d2503... doesn't match any LAN peer, it's because the device either:
  1. Is NOT on WiFi (despite user's claim)
  2. Is a 7th external device
  3. Has a different device ID on different transports (would be a serious bug)

### Experiment 4: Verify device ID source is same for BLE and LAN

- Hypothesis being tested: Different ID sources could cause mismatch
- Procedure: Code review of RangzenService.kt initialization
- Data collected:
  - BLE: `bleAdvertiser.localPublicId = DeviceIdentity.getDeviceId(this)` (line 368)
  - LAN: `lanDiscoveryManager.initialize(deviceId)` where `deviceId = DeviceIdentity.getDeviceId(this)` (line 274)
- Result: **Same source** - both use DeviceIdentity.getDeviceId()
- Conclusion: A device MUST have the same ID on all transports. If 580d2503... doesn't match a LAN peer, that device is NOT on LAN.

## Current Understanding

- 1 peer is correctly merged (f6439aea61fc8b7a has bt-le + wlan) - **this is either Samsung or Moto-farsi**
- 2 WiFi Aware peers (dc545d1a, 4cceccf0) have no LAN presence - **correct, these are Pixel 4a/8 with no WiFi**
- dc545d1a confirmed as Pixel 4a (which has no WiFi connection)
- 4cceccf0 suspected to be Pixel 8 (which has no WiFi connection) - UNVERIFIED
- **H2 and H3 ruled out** - no duplicate peers detected through prefix matching
- **The 7th entry problem remains**: BLE peer `580d250330b93dd5` has no matching LAN peer

## Key Analysis: The 580d2503 Mystery

BLE peer `580d250330b93dd5` should be either Samsung or Moto-farsi (the only phones with BT enabled besides Pixel 5).

- If Samsung → should also appear on LAN with same ID starting with 580d2503
- If Moto-farsi → should also appear on LAN with same ID starting with 580d2503

**But no LAN peer starts with 580d2503!**

Possible explanations:

1. **User information is incorrect**: Either Samsung or Moto-farsi is NOT connected to WiFi
2. **External device**: 580d2503 is a 7th device not in the test set (neighbor's phone?)
3. **ID changed mid-session**: Device ID regenerated after connecting to different transport (unlikely - IDs are persistent)

## Next Steps

1. **Verify WiFi status on all phones** - physically check Samsung and Moto-farsi are on 192.168.44.x
2. **Get Samsung's device ID** - check RangzenService startup log on Samsung
3. **Look for neighboring devices** - are there other Murmur users nearby?

## Open Questions

1. What is Pixel 8's full device ID?
2. What is Samsung A16's device ID?
3. What is Moto-farsi's device ID?
4. ~~Why does BLE peer 580d250330b93dd5 not appear on any other transport?~~ → **Because it's NOT on WiFi or it's an external device**
5. Is there a 7th Murmur device in range?
