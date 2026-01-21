/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Modern BLE Scanner implementation for Android 14+
 * Uses BluetoothLeScanner API with proper permission handling
 */
package org.denovogroup.rangzen.backend.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.denovogroup.rangzen.backend.AppConfig
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * BLE Scanner for discovering nearby Rangzen peers.
 */
class BleScanner(private val context: Context) {

    companion object {
        val RANGZEN_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        // Standard Client Characteristic Configuration Descriptor (CCCD).
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Tag for Android Log fallback in case Timber is filtered.
        private const val LOG_TAG = "BleScanner"
        // Time window before a peer is considered stale.
        private const val PEER_STALE_MS = 30_000L
        // How often we prune stale peers.
        private const val PEER_CLEANUP_INTERVAL_MS = 10_000L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeer>()

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    var onPeerDiscovered: ((DiscoveredPeer) -> Unit)? = null
    // Optional callback when the peer list changes.
    var onPeersUpdated: ((List<DiscoveredPeer>) -> Unit)? = null

    // Handler used to schedule periodic cleanup.
    private val cleanupHandler = Handler(Looper.getMainLooper())
    // Runnable that prunes stale peers.
    private lateinit var cleanupRunnable: Runnable

    init {
        // Initialize the cleanup runnable after fields are available.
        cleanupRunnable = Runnable {
            // Snapshot current time for age calculation.
            val now = System.currentTimeMillis()
            // Track whether we removed any peers.
            var removedAny = false
            // Iterate over current peers to find stale entries.
            val iterator = discoveredPeers.entries.iterator()
            // Loop over all current peer entries.
            while (iterator.hasNext()) {
                // Read the next peer entry.
                val entry = iterator.next()
                // Compute how old this peer is.
                val ageMs = now - entry.value.lastSeen
                // Remove peers older than our threshold.
                if (ageMs > PEER_STALE_MS) {
                    // Mark that we removed something.
                    removedAny = true
                    // Remove the stale peer.
                    iterator.remove()
                }
            }
            // Update the exposed list when removals occurred.
            if (removedAny) {
                // Rebuild the list sorted by RSSI.
                _peers.value = discoveredPeers.values.sortedByDescending { it.rssi }
                // Notify observers of the updated list.
                onPeersUpdated?.invoke(_peers.value)
                // Log the cleanup event.
                Timber.i("Pruned stale peers; remaining=${_peers.value.size}")
            }
            // Schedule the next cleanup pass.
            cleanupHandler.postDelayed(cleanupRunnable, PEER_CLEANUP_INTERVAL_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Log each scan result to trace discovery timing.
            Timber.d("Scan callback result type=$callbackType device=${result.device.address}")
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Map common scan failure codes for quicker diagnosis.
            val errorName = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                else -> "SCAN_FAILED_UNKNOWN"
            }
            // Emit both the numeric code and friendly name.
            Timber.e("BLE scan failed: code=$errorCode name=$errorName")
            _isScanning.value = false
        }
    }

    fun hasPermissions(): Boolean {
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true

        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        
        val locationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        return scanPermission && connectPermission && locationPermission
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) {
            // Log why scanning cannot start for visibility in logs.
            Timber.w("Cannot start scanning, permissions or bluetooth state is invalid")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RANGZEN_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        
        // Start scanning with filter and settings.
        bleScanner?.startScan(filters, settings, scanCallback)
        // Mark scanning state as active.
        _isScanning.value = true
        // Start periodic cleanup of stale peers.
        startPeerCleanup()
        // Log scan start for visibility.
        Timber.i("BLE scanning started")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!_isScanning.value) return
        // Stop the active scan callback.
        bleScanner?.stopScan(scanCallback)
        // Mark scanning state as inactive.
        _isScanning.value = false
        // Stop periodic cleanup when scanning stops.
        stopPeerCleanup()
        // Log scan stop for visibility.
        Timber.i("BLE scanning stopped")
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(peer: DiscoveredPeer, data: ByteArray): ByteArray? = suspendCancellableCoroutine { continuation ->
        var responded = false
        // Track if we already issued a read to avoid duplicates.
        var readTriggered = false
        // Handler for scheduling read fallback on the main thread.
        val handler = Handler(Looper.getMainLooper())
        // Placeholder for the characteristic used in this exchange.
        var exchangeCharacteristic: BluetoothGattCharacteristic? = null
        // Descriptor used for enabling notifications.
        var exchangeDescriptor: BluetoothGattDescriptor? = null
        // Keep the write payload available for later steps.
        val writePayload = data
        // Keep a reference to the active GATT for fallback reads.
        var activeGatt: BluetoothGatt? = null
        // Track whether the CCCD write callback fired.
        var cccdWriteAcknowledged = false
        // Runnable for the read fallback when write callbacks are missing.
        val readFallbackRunnable = Runnable {
            // Only attempt fallback if we haven't responded yet.
            if (!responded && !readTriggered) {
                // Mark that we are triggering a fallback read.
                readTriggered = true
                // Log the fallback attempt for debugging.
                Timber.w("GATT write callback missing; forcing read fallback")
                Log.w(LOG_TAG, "GATT write callback missing; forcing read fallback")
                // Try to read the characteristic if available.
                val characteristic = exchangeCharacteristic
                // Try to read using the active GATT connection if present.
                val readStarted = if (characteristic != null && activeGatt != null) {
                    // readCharacteristic returns true if the read was initiated.
                    activeGatt?.readCharacteristic(characteristic) == true
                } else {
                    // If we are missing GATT or characteristic, report failure.
                    false
                }
                // Log that the fallback read was requested.
                Timber.i("GATT fallback read requested: started=$readStarted")
                Log.i(LOG_TAG, "GATT fallback read requested: started=$readStarted")
            }
        }

        // Track the negotiated MTU to size transport chunks.
        var negotiatedMtu = 23
        // Track the transport header size.
        val transportHeaderSize = TransportProtocol.headerSize()
        // Track the maximum payload size for transport chunks.
        var maxChunkPayloadSize = 1
        // Track request offset for chunked transfer.
        var requestOffset = 0
        // Track response assembly state.
        var responseTotalLength = 0
        var responseReceived = 0
        // Buffer for response accumulation.
        val responseBuffer = ByteArrayOutputStream()
        // Track whether a GATT write is in progress.
        var writeInProgress = false
        // Hold the next payload to write when the current write completes.
        var pendingWrite: ByteArray? = null
        // Track write retry attempts.
        var writeRetryCount = 0
        // Maximum write retries for transient errors.
        val maxWriteRetries = 3
        // Delay between write retries.
        val writeRetryDelayMs = 200L
        // Track whether a request start is already scheduled.
        var requestStartScheduled = false
        // Hold the pending GATT for scheduled request starts.
        var pendingStartGatt: BluetoothGatt? = null

        fun updateChunkSize(mtu: Int) {
            // BLE payload is MTU - 3 bytes for ATT header.
            val attPayloadLimit = mtu - 3
            // Read the configured max attribute length for safety.
            val maxAttributeLength = AppConfig.gattMaxAttributeLength(context)
            // Clamp the payload limit to the max attribute length.
            val payloadLimit = minOf(attPayloadLimit, maxAttributeLength)
            // Deduct transport header to get usable payload space.
            val computed = payloadLimit - transportHeaderSize
            // Ensure we have at least one byte of payload.
            maxChunkPayloadSize = if (computed > 0) computed else 1
        }

        fun resetResponse() {
            // Reset response tracking when starting a new response.
            responseTotalLength = 0
            responseReceived = 0
            responseBuffer.reset()
        }

        fun buildRequestFrame(): ByteArray {
            // Compute remaining bytes to send.
            val remaining = writePayload.size - requestOffset
            // Determine chunk length based on negotiated MTU.
            val chunkSize = minOf(remaining, maxChunkPayloadSize)
            // Slice the payload for this chunk.
            val chunk = writePayload.copyOfRange(requestOffset, requestOffset + chunkSize)
            // Build a DATA frame for this chunk.
            val frame = TransportFrame(
                op = TransportProtocol.OP_DATA,
                totalLength = writePayload.size,
                offset = requestOffset,
                payload = chunk
            )
            // Advance the request offset for the next chunk.
            requestOffset += chunkSize
            // Encode the frame as bytes for GATT write.
            return TransportProtocol.encode(frame)
        }

        fun buildContinueFrame(): ByteArray {
            // Ask for the next response chunk.
            val frame = TransportFrame(
                op = TransportProtocol.OP_CONTINUE,
                totalLength = 0,
                offset = responseReceived,
                payload = ByteArray(0)
            )
            // Encode the frame as bytes for GATT write.
            return TransportProtocol.encode(frame)
        }

        fun writeTransportFrame(gatt: BluetoothGatt, payload: ByteArray) {
            // Queue the write if a previous write is still in progress.
            if (writeInProgress) {
                pendingWrite = payload
                Timber.w("Write in progress, queued transport frame (size=${payload.size})")
                return
            }
            // Ensure characteristic exists.
            val characteristic = exchangeCharacteristic
            if (characteristic == null) {
                Timber.e("Cannot write transport frame; characteristic missing")
                gatt.close()
                if (continuation.isActive && !responded) continuation.resume(null)
                return
            }
            // Ensure write type is explicit.
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Perform the write using the appropriate API.
            val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Attempt the modern API first on Android 13+.
                val status = gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                // If the stack is busy, try the legacy API once before giving up.
                if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    // Log the busy state explicitly.
                    Timber.w("GATT write busy; attempting legacy write fallback")
                    Log.w(LOG_TAG, "GATT write busy; attempting legacy write fallback")
                    // Populate the characteristic value for legacy writes.
                    characteristic.value = payload
                    // Attempt legacy write to clear the busy state.
                    if (gatt.writeCharacteristic(characteristic)) 0
                    else BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                } else if (status != BluetoothStatusCodes.SUCCESS) {
                    // Log the modern API failure for visibility.
                    Timber.w("GATT write returned status=$status; falling back to legacy API")
                    Log.w(LOG_TAG, "GATT write returned status=$status; falling back to legacy API")
                    // Populate the characteristic value for legacy writes.
                    characteristic.value = payload
                    // Attempt the legacy write and map the boolean to status code.
                    if (gatt.writeCharacteristic(characteristic)) 0 else -1
                } else {
                    // Return the success status as-is for consistent handling.
                    status
                }
            } else {
                // Use the legacy API on pre-Android 13 devices.
                characteristic.value = payload
                if (gatt.writeCharacteristic(characteristic)) 0 else -1
            }
            // Log the write result.
            Timber.i("GATT transport write started=$writeResult device=${peer.address}")
            Log.i(LOG_TAG, "GATT transport write started=$writeResult device=${peer.address}")
            // Schedule a fallback read if the write was accepted.
            if (writeResult == 0) {
                // Mark write as in progress once accepted.
                writeInProgress = true
                // Reset retry count on success.
                writeRetryCount = 0
                val delayMs = AppConfig.gattReadFallbackDelayMs(context)
                handler.removeCallbacks(readFallbackRunnable)
                handler.postDelayed(readFallbackRunnable, delayMs)
                Timber.i("Scheduled GATT read fallback after ${delayMs}ms")
            } else if (writeResult == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                // Treat BUSY as a hard failure after legacy fallback.
                Timber.e("GATT write busy after fallback; closing connection")
                Log.e(LOG_TAG, "GATT write busy after fallback; closing connection")
                gatt.close()
                if (continuation.isActive && !responded) continuation.resume(null)
            } else {
                Timber.e("GATT transport write could not be started (code=$writeResult)")
                if (writeRetryCount < maxWriteRetries) {
                    writeRetryCount += 1
                    pendingWrite = payload
                    Timber.w("Scheduling transport write retry #$writeRetryCount")
                    handler.postDelayed({ writeTransportFrame(gatt, payload) }, writeRetryDelayMs)
                } else {
                    Timber.e("Transport write retries exhausted; closing connection")
                    gatt.close()
                    if (continuation.isActive && !responded) continuation.resume(null)
                }
            }
        }

        fun startRequestTransfer(gatt: BluetoothGatt) {
            // Grab the characteristic needed for writes.
            val characteristic = exchangeCharacteristic
            // Fail loudly if the characteristic is missing.
            if (characteristic == null) {
                // Log the missing characteristic for visibility.
                Timber.e("GATT request start aborted: characteristic missing")
                Log.e(LOG_TAG, "GATT request start aborted: characteristic missing")
                // Close the GATT to avoid leaks.
                gatt.close()
                return
            }
            // Clear the scheduled flag now that we are starting.
            requestStartScheduled = false
            // Reset response tracking before first request chunk.
            resetResponse()
            // Reset request offset before first request chunk.
            requestOffset = 0
            // Build the first request frame.
            val payload = buildRequestFrame()
            // Write the transport frame to begin the request transfer.
            writeTransportFrame(gatt, payload)
        }

        // Runnable to begin the request transfer after a small delay.
        val startRequestRunnable = Runnable {
            // Fetch the pending GATT reference.
            val gatt = pendingStartGatt
            // Abort if we no longer have a GATT.
            if (gatt == null) {
                // Log that the runnable fired without a GATT reference.
                Timber.w("Request start runnable fired without active GATT")
                Log.w(LOG_TAG, "Request start runnable fired without active GATT")
                // Reset the scheduled flag when we cannot proceed.
                requestStartScheduled = false
                return@Runnable
            }
            // Do not proceed if we already responded.
            if (responded) {
                // Log that the runnable fired after a response arrived.
                Timber.w("Request start runnable skipped: response already received")
                Log.w(LOG_TAG, "Request start runnable skipped: response already received")
                // Reset the scheduled flag when the exchange is done.
                requestStartScheduled = false
                return@Runnable
            }
            // Log that we are starting the request transfer.
            Timber.i("Request start runnable fired; starting transfer now")
            Log.i(LOG_TAG, "Request start runnable fired; starting transfer now")
            // Begin the request transfer now that the delay elapsed.
            startRequestTransfer(gatt)
        }

        fun scheduleRequestStart(gatt: BluetoothGatt, reason: String) {
            // Avoid scheduling multiple starts for the same exchange.
            if (requestStartScheduled) {
                // Log that a start is already scheduled.
                Timber.i("Request start already scheduled; skipping (reason=$reason)")
                Log.i(LOG_TAG, "Request start already scheduled; skipping (reason=$reason)")
                return
            }
            // Mark that a request start is scheduled.
            requestStartScheduled = true
            // Track the GATT we should use for the scheduled start.
            pendingStartGatt = gatt
            // Clear any prior scheduled start to avoid duplicate runs.
            handler.removeCallbacks(startRequestRunnable)
            // Resolve the initial delay from config for this device.
            val initialDelayMs = AppConfig.initialWriteDelayMs(context)
            // Log the scheduling reason for debugging.
            Timber.i("Scheduling request start: reason=$reason delayMs=$initialDelayMs")
            Log.i(LOG_TAG, "Scheduling request start: reason=$reason delayMs=$initialDelayMs")
            // Post the start after a short delay to avoid GATT busy errors.
            handler.postDelayed(startRequestRunnable, initialDelayMs)
        }

        // Runnable for CCCD write timeouts so the request can still start.
        val cccdFallbackRunnable = Runnable {
            // Only proceed if we are still waiting on CCCD and no response yet.
            if (!responded && !cccdWriteAcknowledged) {
                // Log the missing CCCD callback for debugging.
                Timber.w("GATT CCCD callback missing; proceeding with request transfer")
                Log.w(LOG_TAG, "GATT CCCD callback missing; proceeding with request transfer")
                // Use the active GATT if available.
                val gatt = activeGatt
                // Abort if the GATT is missing.
                if (gatt == null) {
                    // Fail loudly when we cannot proceed.
                    Timber.e("GATT CCCD fallback aborted: missing active GATT")
                    Log.e(LOG_TAG, "GATT CCCD fallback aborted: missing active GATT")
                    return@Runnable
                }
                // Schedule the request transfer without waiting for CCCD callback.
                scheduleRequestStart(gatt, "cccd-fallback")
            }
        }

        fun handleTransportResponse(gatt: BluetoothGatt, response: ByteArray) {
            // Decode the transport frame.
            val frame = TransportProtocol.decode(response)
            if (frame == null) {
                Timber.e("Failed to decode transport frame from ${peer.address}")
                gatt.close()
                if (continuation.isActive && !responded) continuation.resume(null)
                return
            }
            // Log the decoded frame to trace transport flow.
            Timber.i(
                "Transport frame op=${frame.op} total=${frame.totalLength} " +
                    "offset=${frame.offset} payload=${frame.payload.size} from ${peer.address}"
            )
            Log.i(
                LOG_TAG,
                "Transport frame op=${frame.op} total=${frame.totalLength} " +
                    "offset=${frame.offset} payload=${frame.payload.size} from ${peer.address}"
            )
            // Handle ACK frames for request transfer.
            if (frame.op == TransportProtocol.OP_ACK) {
                // Send next request chunk if any remain.
                if (requestOffset < writePayload.size) {
                    val payload = buildRequestFrame()
                    writeTransportFrame(gatt, payload)
                } else {
                    // Request complete; wait for response chunks.
                    Timber.i("Request transfer complete; awaiting response")
                }
                return
            }
            // Handle DATA frames for response transfer.
            if (frame.op == TransportProtocol.OP_DATA) {
                // Initialize response tracking on first chunk.
                if (responseTotalLength == 0) {
                    responseTotalLength = frame.totalLength
                    responseReceived = 0
                }
                // Handle empty responses immediately.
                if (responseTotalLength == 0) {
                    responded = true
                    handler.removeCallbacks(readFallbackRunnable)
                    gatt.close()
                    if (continuation.isActive) continuation.resume(ByteArray(0))
                    return
                }
                // Verify offset matches expected position.
                if (frame.offset != responseReceived) {
                    Timber.e("Response offset mismatch expected=$responseReceived got=${frame.offset}")
                    gatt.close()
                    if (continuation.isActive && !responded) continuation.resume(null)
                    return
                }
                // Append payload to response buffer.
                responseBuffer.write(frame.payload)
                responseReceived += frame.payload.size
                // If response complete, finish the exchange.
                if (responseReceived >= responseTotalLength) {
                    responded = true
                    handler.removeCallbacks(readFallbackRunnable)
                    gatt.close()
                    if (continuation.isActive) continuation.resume(responseBuffer.toByteArray())
                } else {
                    // Request next response chunk.
                    val payload = buildContinueFrame()
                    writeTransportFrame(gatt, payload)
                }
                return
            }
            // Unknown frame type; fail loudly.
            Timber.e("Unknown transport op=${frame.op} from ${peer.address}")
            gatt.close()
            if (continuation.isActive && !responded) continuation.resume(null)
        }
        
        val callback = object : BluetoothGattCallback() {
            var servicesRequested = false

            private fun requestServicesOnce(gatt: BluetoothGatt) {
                if (servicesRequested) return
                servicesRequested = true
                gatt.discoverServices()
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                // Trace connection state transitions and status codes.
                Timber.i("GATT connection state change: status=$status newState=$newState device=${peer.address}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Store active GATT connection for fallback reads.
                    activeGatt = gatt
                    // Initialize chunk size before MTU negotiation.
                    updateChunkSize(negotiatedMtu)
                    val mtuRequested = try {
                        val mtu = AppConfig.bleMtu(context)
                        gatt.requestMtu(mtu)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to request MTU, falling back to service discovery")
                        false
                    }
                    if (!mtuRequested) {
                        requestServicesOnce(gatt)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Cancel any pending fallback when disconnected.
                    handler.removeCallbacks(readFallbackRunnable)
                    // Cancel any scheduled request start when disconnected.
                    handler.removeCallbacks(startRequestRunnable)
                    // Cancel any pending CCCD fallback when disconnected.
                    handler.removeCallbacks(cccdFallbackRunnable)
                    // Clear active GATT reference on disconnect.
                    activeGatt = null
                    // Close and finish if we disconnect early.
                    gatt.close()
                    if (continuation.isActive && !responded) continuation.resume(null)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Timber.i("GATT MTU changed: mtu=$mtu status=$status device=${peer.address}")
                Log.i(LOG_TAG, "GATT MTU changed: mtu=$mtu status=$status device=${peer.address}")
                // Track the negotiated MTU and update chunk size.
                negotiatedMtu = mtu
                updateChunkSize(negotiatedMtu)
                requestServicesOnce(gatt)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Log service discovery status.
                Timber.i("GATT services discovered: status=$status device=${peer.address}")
                // Also log to Android Log to ensure visibility.
                Log.i(LOG_TAG, "GATT services discovered: status=$status device=${peer.address}")
                val service = gatt.getService(RANGZEN_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleAdvertiser.RANGZEN_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    // Store the characteristic for fallback usage.
                    exchangeCharacteristic = characteristic
                    // Fetch the CCCD descriptor for notifications.
                    exchangeDescriptor = characteristic.getDescriptor(CCCD_UUID)
                    // Enable notifications locally so callbacks can fire.
                    val notifySet = gatt.setCharacteristicNotification(characteristic, true)
                    Timber.i("GATT notifications enabled local=$notifySet device=${peer.address}")
                    Log.i(LOG_TAG, "GATT notifications enabled local=$notifySet device=${peer.address}")
                    // If descriptor exists, write CCCD to enable notifications on remote.
                    exchangeDescriptor?.let { descriptor ->
                        // Mark CCCD as pending before the write.
                        cccdWriteAcknowledged = false
                        // Ensure any prior fallback is cleared.
                        handler.removeCallbacks(cccdFallbackRunnable)
                        // Set CCCD value for notifications.
                        val cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val cccdWriteResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Use new writeDescriptor API on Android 13+.
                            gatt.writeDescriptor(descriptor, cccdValue)
                        } else {
                            // Use legacy API for older devices.
                            descriptor.value = cccdValue
                            if (gatt.writeDescriptor(descriptor)) 0 else -1
                        }
                        Timber.i("GATT CCCD write started=$cccdWriteResult device=${peer.address}")
                        Log.i(LOG_TAG, "GATT CCCD write started=$cccdWriteResult device=${peer.address}")
                        // Schedule a fallback to start the request if CCCD callback never arrives.
                        handler.postDelayed(
                            cccdFallbackRunnable,
                            AppConfig.gattReadFallbackDelayMs(context)
                        )
                        // Proceed immediately if the CCCD write could not be started.
                        if (cccdWriteResult != 0) {
                            // Log the write failure for visibility.
                            Timber.w("GATT CCCD write failed to start; proceeding without callback")
                            Log.w(LOG_TAG, "GATT CCCD write failed to start; proceeding without callback")
                            // Schedule the request transfer without waiting for CCCD callback.
                            scheduleRequestStart(gatt, "cccd-write-failed")
                        }
                        // We will start the characteristic write in onDescriptorWrite or fallback.
                        return
                    }
                    // Start the request immediately when CCCD is absent.
                    scheduleRequestStart(gatt, "cccd-missing")
                } else {
                    // Log missing service/characteristic for troubleshooting.
                    Timber.w("GATT service/characteristic not found on device=${peer.address}")
                    // Mirror to Android Log for visibility.
                    Log.w(LOG_TAG, "GATT service/characteristic not found on device=${peer.address}")
                    gatt.close()
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Log write result status.
                Timber.i("GATT characteristic write status=$status uuid=${characteristic.uuid}")
                // Mirror to Android Log for visibility.
                Log.i(LOG_TAG, "GATT characteristic write status=$status uuid=${characteristic.uuid}")
                // Cancel fallback once we receive the write callback.
                handler.removeCallbacks(readFallbackRunnable)
                // Mark write as complete.
                writeInProgress = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Do not issue an immediate read here.
                    // We rely on notifications for responses to avoid reading stale values.
                    // Allow the fallback read to fire only if notifications never arrive.
                    readTriggered = false
                    // Flush any queued write after a successful write completes.
                    pendingWrite?.let { queued ->
                        // Clear the queue before issuing the next write.
                        pendingWrite = null
                        // Start the next write now that the GATT write pipeline is free.
                        writeTransportFrame(gatt, queued)
                    }
                } else {
                    // Close on write failure to avoid dangling connections.
                    gatt.close()
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                // Log descriptor write status.
                Timber.i("GATT descriptor write status=$status uuid=${descriptor.uuid}")
                Log.i(LOG_TAG, "GATT descriptor write status=$status uuid=${descriptor.uuid}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Close on descriptor failure to avoid dangling connections.
                    gatt.close()
                    return
                }
                // Only proceed if this is the CCCD we wrote.
                if (descriptor.uuid == CCCD_UUID) {
                    // Mark CCCD as acknowledged to stop fallback.
                    cccdWriteAcknowledged = true
                    // Cancel any pending CCCD fallback since we got a callback.
                    handler.removeCallbacks(cccdFallbackRunnable)
                    // Schedule the request transfer now that CCCD is enabled.
                    scheduleRequestStart(gatt, "cccd-write-complete")
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                // Log notification receipt for debugging.
                Timber.i("GATT notification received uuid=${characteristic.uuid}")
                Log.i(LOG_TAG, "GATT notification received uuid=${characteristic.uuid}")
                // Cancel any pending fallback since we got a response.
                handler.removeCallbacks(readFallbackRunnable)
                // Handle the transport response using the notification payload.
                handleTransportResponse(gatt, characteristic.value)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Log read result status.
                Timber.i("GATT characteristic read status=$status uuid=${characteristic.uuid}")
                // Mirror to Android Log for visibility.
                Log.i(LOG_TAG, "GATT characteristic read status=$status uuid=${characteristic.uuid}")
                // Cancel any pending fallback once read completes.
                handler.removeCallbacks(readFallbackRunnable)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Handle the transport response using the read payload.
                    handleTransportResponse(gatt, characteristic.value)
                } else {
                    // Close on read failure.
                    gatt.close()
                }
            }
        }
        // Connect to the peer's GATT server.
        val gatt = peer.device.connectGatt(context, false, callback)
        // Ensure GATT is closed if the coroutine is cancelled.
        continuation.invokeOnCancellation {
            // Cancel any pending fallback before closing.
            handler.removeCallbacks(readFallbackRunnable)
            // Cancel any scheduled request start before closing.
            handler.removeCallbacks(startRequestRunnable)
            // Cancel any pending CCCD fallback before closing.
            handler.removeCallbacks(cccdFallbackRunnable)
            // Close the connection to avoid leaks.
            gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(RANGZEN_SERVICE_UUID)) == true) {
            Timber.d("Scan result from device: ${result.device.address}")
            val peer = DiscoveredPeer(
                address = result.device.address,
                name = result.device.name,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis(),
                device = result.device
            )
            if (!discoveredPeers.containsKey(peer.address)) {
                onPeerDiscovered?.invoke(peer)
            }
            // Persist the peer entry in the map.
            discoveredPeers[peer.address] = peer
            // Rebuild the list sorted by RSSI.
            _peers.value = discoveredPeers.values.sortedByDescending { it.rssi }
            // Notify observers whenever the peer list changes.
            onPeersUpdated?.invoke(_peers.value)
        }
    }

    fun clearPeers() {
        // Clear all discovered peers.
        discoveredPeers.clear()
        // Reset the exposed peer list.
        _peers.value = emptyList()
        // Notify observers when peers are cleared.
        onPeersUpdated?.invoke(_peers.value)
    }

    private fun startPeerCleanup() {
        // Ensure no duplicate callbacks are scheduled.
        cleanupHandler.removeCallbacks(cleanupRunnable)
        // Schedule the first cleanup pass.
        cleanupHandler.postDelayed(cleanupRunnable, PEER_CLEANUP_INTERVAL_MS)
    }

    private fun stopPeerCleanup() {
        // Remove any pending cleanup callbacks.
        cleanupHandler.removeCallbacks(cleanupRunnable)
    }
}
data class DiscoveredPeer(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val device: BluetoothDevice
)
