# Debug Log: Peer Counting Mismatch

Date: 2026-01-28

## Problem Statement

User reports: Pixel 5 shows 7 peers when there are only 6 phones total (should show 5 peers, excluding self). Suspicion that WiFi Aware or BLE peers are being double-counted due to failure to correlate the same peer discovered via multiple transports.

## Known Facts

- FACT 1: Pixel 5 is connected to WiFi AP (192.168.44.x network) - verified via `ip addr show wlan0` showing state UP
- FACT 2: Pixel 4a is NOT connected to any WiFi - verified via `ip addr show wlan0` showing state DOWN
- FACT 3: Pixel 8 is NOT connected to the same WiFi network as Pixel 5 - verified via no 192.168.44.x LAN peer with matching ID
- FACT 4: WiFi Aware uses 8-char prefix IDs due to message size limits - verified in code (WifiAwareMessageProtocol.kt:121)
- FACT 5: Other transports (LAN, BLE after handshake) use 16-char full IDs - verified in peer registry dumps
- FACT 6: PeerRegistry dump from Pixel 5 shows WiFi Aware peers with 8-char IDs (e.g., `dc545d1a`, `4cceccf0`) - verified in logs

## Hypotheses

- H1: WiFi Aware peers are not being correlated with LAN/BLE peers because prefix matching is not implemented in reportWifiAwarePeer()
  - Test: Check if any WiFi Aware peer ID is a prefix of any LAN peer ID
  - Prediction: If same device is on both WiFi Aware and LAN, the 8-char prefix should match the start of the 16-char LAN ID
  - Status: TESTING

- H2: The peer count is actually correct because WiFi Aware-only devices (Pixel 4a, Pixel 8) genuinely have no LAN presence to correlate with
  - Test: Verify which devices are on the same WiFi network as Pixel 5
  - Prediction: If Pixel 4a and 8 are not on WiFi, they should only appear via WiFi Aware
  - Status: TESTING

- H3: BLE peers are being double-counted (same device showing as separate BLE and LAN peer)
  - Test: Check if any BLE peer address maps to a different peer ID than a LAN peer with matching public ID
  - Prediction: If double-counting, we'd see same public ID prefix in two different peer entries
  - Status: UNTESTED

## Experiment Log

### Experiment 1: Verify device network connectivity

- Hypothesis being tested: H2
- Procedure: Run `ip addr show wlan0` on all three devices
- Data collected:
  - Pixel 5: state UP, on 192.168.44.x
  - Pixel 4a: state DOWN (not on any WiFi)
  - Pixel 8: state UP but on different network (172.20.20.x - likely VPN via cellular, NOT WiFi)
- Result: Only Pixel 5 is on the 192.168.44.x WiFi network
- Conclusion: Pixel 4a and Pixel 8 cannot appear as LAN peers because they're not on the same network. PARTIALLY SUPPORTS H2.

### Experiment 2: Check if WiFi Aware IDs match any LAN peer prefixes

- Hypothesis being tested: H1
- Procedure: Compare WiFi Aware peer IDs against LAN peer ID prefixes
- Data collected from Pixel 5 PeerRegistry dump:
  - WiFi Aware peers: `dc545d1a`, `4cceccf0`
  - LAN peers: `f6439aea61fc8b7a`, `9a8af3687f46a901`, `ef4e645852b48c0e`, `f94a94711273c103`, `29e238438405b23c`
  - None of the LAN peer IDs start with `dc545d1a` or `4cceccf0`
- Result: WiFi Aware peer IDs do NOT match any LAN peer prefixes
- Conclusion: These are genuinely different devices. SUPPORTS H2, does not confirm H1.

### Experiment 3: Verify peer counts are correct

- Hypothesis being tested: H2
- Procedure: Count distinct devices in peer registry
- Data collected:
  - LAN peers: 5 (various 192.168.44.x addresses)
  - WiFi Aware only: 2 (`dc545d1a`, `4cceccf0`)
  - BLE merged with LAN: 2 (`f6439aea...` and `f94a9471...` show both transports)
  - BLE only: 1 (`temp:ble:...` or `580d2503...`)
  - Total unique peers: ~8
- Result: Peer count appears to reflect actual device count, not duplication
- Conclusion: SUPPORTS H2

## Current Understanding

The peer count appears to be correct. The apparent "extra" peers are:

1. Devices only reachable via WiFi Aware (Pixel 4a and Pixel 8 not on same WiFi)
2. The user expected 5 peers but actual network topology has more devices

However, this analysis assumed the WiFi Aware peers are Pixel 4a and Pixel 8. This was NOT verified.

### Experiment 4: Determine device IDs by cross-referencing WiFi Aware visibility

- Hypothesis being tested: Verify which device corresponds to which WiFi Aware ID
- Procedure: Each device cannot see itself via WiFi Aware. Cross-reference what each device sees to deduce IDs.
- Data collected:
  - Pixel 5 sees: `dc545d1a`, `4cceccf0`
  - Pixel 4a sees: `4cceccf0`, `5b38348e`
  - Pixel 8 sees: `dc545d1a`, `5b38348e`
- Result: By elimination (a device cannot see its own ID):
  - **Pixel 5** = `5b38348e` (not in Pixel 5's list)
  - **Pixel 4a** = `dc545d1a` (not in Pixel 4a's list)
  - **Pixel 8** = `4cceccf0` (not in Pixel 8's list)
- Conclusion: Device IDs VERIFIED.

## Current Understanding (Updated)

1. **WiFi Aware IDs are now verified:**
   - Pixel 5: `5b38348e...`
   - Pixel 4a: `dc545d1a...`
   - Pixel 8: `4cceccf0...`

2. **Network topology confirmed:**
   - Pixel 5 is on WiFi (192.168.44.x) - can be reached via LAN AND WiFi Aware
   - Pixel 4a is NOT on WiFi - can ONLY be reached via WiFi Aware
   - Pixel 8 is NOT on same WiFi - can ONLY be reached via WiFi Aware

3. **The correlation issue is real but subtle:**
   - If a device IS on the same WiFi AND using WiFi Aware, it should be correlated
   - Pixel 5's full LAN ID likely starts with `5b38348e` - this should match WiFi Aware
   - Need to verify if Pixel 5's LAN peer on OTHER devices shows as `5b38348e...`

### Experiment 5: Verify LAN vs BLE ID mismatch

- Hypothesis being tested: Same device has different IDs via different transports
- Procedure: Compare what Samsung sees for Pixel 5 via BLE vs LAN
- Data collected:
  - BLE: `5b38348e13d4c903` (Pixel 5)
  - LAN at 192.168.44.207: `29e238438405b23c` (stale entry)
  - Current LAN broadcast from 192.168.44.207: `5b38348e...` (correct!)
- Result: The LAN peer registry has a STALE entry from a previous device/session
- Conclusion: **BUG FOUND** - When an IP address reports a different device ID, the old peer entry isn't being cleaned up

## ROOT CAUSE

When `reportLanPeer()` receives a new device ID for an existing IP address:

1. It checks if the public ID exists in the peer map
2. If not found (because the OLD device ID is there), it creates a NEW peer entry
3. The OLD peer entry remains with the stale IP mapping
4. Result: Same IP appears to have two different peers

**Fix needed**: When a transport key (IP:port) already maps to a peer, but the incoming public ID is different, we must:

1. Remove the transport from the old peer
2. If the old peer has no remaining transports, delete it
3. Add/update the new peer with the transport

### Experiment 6: Verify device ID changes with reinstall

- Hypothesis: Device ID changes when app is reinstalled (because keypair is cleared)
- Procedure: Check device IDs before and after deploy script runs
- Data collected:
  - Before first debug deploy: `29e238438405b23c` (previous release install)
  - After first debug deploy: `5b38348e13d4c903` (new keypair)
  - After second debug deploy: `ba2287dad0e50595` (another new keypair)
- Result: Each uninstall/reinstall generates a new keypair and thus a new device ID
- Conclusion: **CONFIRMED** - The deploy script's `uninstall` step clears app data including keypair

## Summary

### Root Causes Found

1. **Stale peer entries**: When an IP address reports a different device ID, the old peer entry wasn't being cleaned up. **FIXED** by adding ID mismatch detection in `reportLanPeer()`.

2. **Device ID changes with reinstall**: The debug deploy script uninstalls the app, which clears the keypair and generates a new device ID on reinstall. This is expected behavior but caused confusion during debugging.

### Fixes Applied

1. Added stale mapping cleanup in `reportLanPeer()` - when an IP reports a different device ID, remove the transport from the old peer
2. Added `removeTransport()` method to `UnifiedPeer`
3. Added prefix matching in `reportLanPeer()` and `reportWifiAwarePeer()` for better cross-transport correlation

### Remaining Work

The peer counting issue may have been a combination of:

1. Stale entries from previous app installations (now fixed)
2. Multiple transports to same device not correlating (improved with prefix matching)
3. WiFi Aware peers on different networks (correct behavior - not a bug)

## Code Changes Made

Added prefix matching to `reportWifiAwarePeer()` and `reportLanPeer()` in DiscoveredPeerRegistry.kt to handle cases where a device IS on both WiFi Aware and LAN. This fix is correct but may not address the user's specific scenario if the devices genuinely aren't on the same network.
