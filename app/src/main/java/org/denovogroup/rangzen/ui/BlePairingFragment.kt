/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * BLE Pairing Fragment - mutual-code pairing flow for adding friends via BLE.
 *
 * Flow:
 * 1. Show "Your Code" (6 digits) and "Pairing Mode" banner
 * 2. Show Nearby Devices list, user selects one
 * 3. Prompt: "Enter their code" - user types code from other phone
 * 4. "Verifying..." - BLE handshake validates both codes
 * 5. "Friend verified" - ask for optional nickname
 * 6. Success - return to friends list
 */
package org.denovogroup.rangzen.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.ble.BleAdvertiser
import org.denovogroup.rangzen.backend.ble.BlePairingProtocol
import org.denovogroup.rangzen.backend.ble.BleScanner
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import org.denovogroup.rangzen.databinding.FragmentBlePairingBinding
import timber.log.Timber

/**
 * Fragment for BLE-based mutual-code friend pairing.
 */
class BlePairingFragment : Fragment() {

    companion object {
        private const val TAG = "BlePairingFragment"

        fun newInstance(): BlePairingFragment = BlePairingFragment()
    }

    private var _binding: FragmentBlePairingBinding? = null
    private val binding get() = _binding!!

    // State machine
    private enum class State {
        SHOW_CODE,      // Display our pairing code
        NEARBY_DEVICES, // Show list of nearby devices
        ENTER_CODE,     // Enter the other person's code
        VERIFYING,      // BLE handshake in progress
        NICKNAME,       // Ask for optional nickname
        SUCCESS,        // Pairing complete
        ERROR           // Show error with retry
    }

    private var currentState = State.SHOW_CODE

    // BLE components
    private var bleScanner: BleScanner? = null

    // Pairing session
    private var pairingSession: BlePairingProtocol.PairingSession? = null
    private var discoveredPairingPeers = mutableMapOf<String, BlePairingProtocol.PairingPeer>()
    private var selectedPeer: DiscoveredPeer? = null
    private var selectedPairingPeer: BlePairingProtocol.PairingPeer? = null

    // Track peers who have verified us (they entered our code correctly)
    // Maps address -> their code
    private var peersWhoVerifiedUs = mutableMapOf<String, String>()

    // Flag to pause announcements during verification (avoid BLE resource contention)
    @Volatile
    private var verificationInProgress = false

    // Friend store
    private lateinit var friendStore: FriendStore

    // Adapter for nearby devices
    private lateinit var deviceAdapter: NearbyDeviceAdapter

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlePairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        friendStore = FriendStore.getInstance(requireContext())
        bleScanner = BleScanner(requireContext())

        setupListeners()
        setupDeviceList()
        startPairingSession()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
        scope.cancel()
        _binding = null
    }

    // ========================================================================
    // Setup
    // ========================================================================

    private fun setupListeners() {
        // Back button
        binding.btnBack.setOnClickListener {
            handleBack()
        }

        // Show code state -> see nearby devices
        binding.btnSeeNearby.setOnClickListener {
            showState(State.NEARBY_DEVICES)
        }

        // Enter code state
        binding.editCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnVerify.isEnabled = s?.length == 6
            }
        })

        binding.btnVerify.setOnClickListener {
            val code = binding.editCode.text.toString()
            if (code.length == 6) {
                verifyCode(code)
            }
        }

        // Nickname state
        binding.btnSkipNickname.setOnClickListener {
            saveFriend(null)
        }

        binding.btnSaveNickname.setOnClickListener {
            val nickname = binding.editNickname.text.toString().trim()
            saveFriend(nickname.ifEmpty { null })
        }

        // Success state
        binding.btnDone.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Error state
        binding.btnRetry.setOnClickListener {
            startPairingSession()
        }
    }

    private fun setupDeviceList() {
        deviceAdapter = NearbyDeviceAdapter { peer ->
            selectDevice(peer)
        }

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceAdapter
        }
    }

    private fun handleBack() {
        when (currentState) {
            State.SHOW_CODE -> parentFragmentManager.popBackStack()
            State.NEARBY_DEVICES -> showState(State.SHOW_CODE)
            State.ENTER_CODE -> showState(State.NEARBY_DEVICES)
            State.VERIFYING -> { /* Can't go back during verification */ }
            State.NICKNAME -> { /* Can't go back after verification */ }
            State.SUCCESS -> parentFragmentManager.popBackStack()
            State.ERROR -> showState(State.SHOW_CODE)
        }
    }

    // ========================================================================
    // State Machine
    // ========================================================================

    private fun showState(state: State) {
        currentState = state

        // Hide all layouts
        binding.layoutShowCode.visibility = View.GONE
        binding.layoutNearbyDevices.visibility = View.GONE
        binding.layoutEnterCode.visibility = View.GONE
        binding.layoutVerifying.visibility = View.GONE
        binding.layoutNickname.visibility = View.GONE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        // Show the appropriate layout
        when (state) {
            State.SHOW_CODE -> {
                binding.layoutShowCode.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_title)
            }
            State.NEARBY_DEVICES -> {
                binding.layoutNearbyDevices.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_nearby_title)
                updateDeviceList()
            }
            State.ENTER_CODE -> {
                binding.layoutEnterCode.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_enter_code_title)
                binding.editCode.text?.clear()
                binding.btnVerify.isEnabled = false
            }
            State.VERIFYING -> {
                binding.layoutVerifying.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_verifying_title)
                // Show our code so the other person can still enter it
                binding.textMyCodeVerifying.text = pairingSession?.myCode ?: ""
            }
            State.NICKNAME -> {
                binding.layoutNickname.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_nickname_title)
                binding.editNickname.text?.clear()
            }
            State.SUCCESS -> {
                binding.layoutSuccess.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_success_title)
            }
            State.ERROR -> {
                binding.layoutError.visibility = View.VISIBLE
                binding.title.text = getString(R.string.pairing_error_title)
            }
        }
    }

    // ========================================================================
    // Pairing Logic
    // ========================================================================

    private fun startPairingSession() {
        // Get our public ID
        val myPublicId = friendStore.getMyPublicIdString()
        if (myPublicId == null) {
            showError(getString(R.string.pairing_error_no_identity))
            return
        }

        // Create a new pairing session
        pairingSession = BlePairingProtocol.createSession(myPublicId)

        // Update UI with our code
        binding.textMyCode.text = pairingSession!!.myCode
        binding.textMyCodeSmall.text = pairingSession!!.myCode
        binding.textMyCodeEnterScreen.text = pairingSession!!.myCode

        // Start BLE advertising and scanning
        startBle()

        // Go directly to nearby devices screen
        showState(State.NEARBY_DEVICES)
    }

    private fun startBle() {
        // Check permissions
        if (bleScanner?.hasPermissions() != true) {
            showError(getString(R.string.pairing_error_no_bluetooth))
            return
        }

        // Set pairing mode active - this signals the service to pause BLE exchanges
        BleAdvertiser.pairingModeActive = true

        // Set global pairing callback - this takes priority over the service's callback
        BleAdvertiser.pairingModeCallback = { device, data ->
            handleIncomingPairingMessage(device.address, data)
        }

        // Start scanning for other devices (advertising is handled by the service)
        bleScanner?.startScanning()

        // Observe discovered peers
        viewLifecycleOwner.lifecycleScope.launch {
            bleScanner?.peers?.collectLatest { peers ->
                handleDiscoveredPeers(peers)
            }
        }

        // Periodically send pairing announcements
        // Use a longer interval to avoid BLE resource contention
        scope.launch {
            while (isActive) {
                sendPairingAnnouncement()
                delay(3000) // Every 3 seconds to avoid BLE contention
            }
        }

        Timber.i("$TAG: BLE pairing mode started")
    }

    private fun stopBle() {
        bleScanner?.stopScanning()
        // Clear pairing mode - service can resume BLE exchanges
        BleAdvertiser.pairingModeActive = false
        // Clear global pairing callback so service handles messages again
        BleAdvertiser.pairingModeCallback = null
        Timber.i("$TAG: BLE pairing mode stopped")
    }

    private fun sendPairingAnnouncement() {
        // Skip announcements during verification to avoid BLE resource contention
        if (verificationInProgress) {
            Timber.d("$TAG: Skipping announcement - verification in progress")
            return
        }

        val session = pairingSession ?: return
        if (session.isExpired()) {
            // Generate a new code if expired
            startPairingSession()
            return
        }

        // Send announcement to discovered peers ONE AT A TIME with delays
        // This avoids overwhelming the BLE stack with concurrent GATT connections
        val announcement = BlePairingProtocol.createAnnounceMessage(session)
        val peersList = bleScanner?.peers?.value ?: emptyList()
        Timber.d("$TAG: Sending announcement to ${peersList.size} scanner peers (serialized)")

        scope.launch(Dispatchers.IO) {
            for (peer in peersList) {
                // Check if we should stop (verification started or fragment destroyed)
                if (verificationInProgress || !isActive) break

                try {
                    Timber.d("$TAG: Sending announcement to scanner peer ${peer.address}")
                    val response = bleScanner?.exchange(peer, announcement)
                    // Parse the response - if they're in pairing mode, they'll respond with their announcement
                    if (response != null) {
                        Timber.i("$TAG: Got response from ${peer.address}, size=${response.size}")
                        val parsed = BlePairingProtocol.parseAnnounceMessage(response)
                        if (parsed != null) {
                            val (code, shortId, _) = parsed
                            Timber.i("$TAG: Peer ${peer.address} is in pairing mode - code=$code, shortId=$shortId")
                            val pairingPeer = BlePairingProtocol.PairingPeer(
                                address = peer.address,
                                shortId = shortId,
                                code = code,
                                rssi = peer.rssi,
                                lastSeen = System.currentTimeMillis()
                            )
                            discoveredPairingPeers[peer.address] = pairingPeer

                            withContext(Dispatchers.Main) {
                                if (currentState == State.NEARBY_DEVICES) {
                                    updateDeviceList()
                                }
                            }
                        } else {
                            Timber.d("$TAG: Response from ${peer.address} is not a pairing announcement")
                        }
                    } else {
                        Timber.d("$TAG: No response from ${peer.address}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to send announcement to ${peer.address}")
                }

                // Add delay between peers to let BLE stack settle
                delay(1500)
            }
        }
    }

    private fun handleDiscoveredPeers(peers: List<DiscoveredPeer>) {
        // Update the device list if we're in the NEARBY_DEVICES state
        if (currentState == State.NEARBY_DEVICES) {
            updateDeviceList()
        }
    }

    private fun handleIncomingPairingMessage(address: String, data: ByteArray): ByteArray? {
        Timber.i("$TAG: handleIncomingPairingMessage from $address, data size=${data.size}")
        val session = pairingSession
        if (session == null) {
            Timber.w("$TAG: No pairing session, returning null")
            return null
        }
        val messageType = BlePairingProtocol.getMessageType(data)
        Timber.i("$TAG: Message type: $messageType")

        return when (messageType) {
            BlePairingProtocol.MSG_PAIRING_ANNOUNCE -> {
                // Another device is announcing itself
                val parsed = BlePairingProtocol.parseAnnounceMessage(data)
                if (parsed != null) {
                    val (code, shortId, timestamp) = parsed
                    Timber.i("$TAG: Received announcement from $address - code=$code, shortId=$shortId")

                    // IMPORTANT: The server-side address may differ from scanner address due to BLE
                    // address randomization. Try to find existing entry by shortId to avoid duplicates.
                    val existingEntry = discoveredPairingPeers.values.find { it.shortId == shortId }

                    if (existingEntry != null) {
                        // Update lastSeen for existing entry, but keep the scanner's address
                        Timber.i("$TAG: Updating existing entry for $shortId (scanner addr: ${existingEntry.address}, server addr: $address)")
                        discoveredPairingPeers[existingEntry.address] = existingEntry.copy(
                            lastSeen = System.currentTimeMillis()
                        )
                    } else {
                        // Check if scanner knows about this device with matching address
                        val scannerPeer = bleScanner?.peers?.value?.find { it.address == address }
                        if (scannerPeer != null) {
                            // Scanner knows this address, safe to add
                            Timber.i("$TAG: Adding new pairing peer from server (scanner knows this addr)")
                            val peer = BlePairingProtocol.PairingPeer(
                                address = address,
                                shortId = shortId,
                                code = code,
                                rssi = scannerPeer.rssi,
                                lastSeen = System.currentTimeMillis()
                            )
                            discoveredPairingPeers[address] = peer
                        } else {
                            // Server-side address doesn't match scanner - log but don't add
                            // Let the client-side flow in sendPairingAnnouncement() handle discovery
                            Timber.i("$TAG: Received announcement from unknown address $address (shortId=$shortId), waiting for scanner match")
                        }
                    }

                    activity?.runOnUiThread {
                        if (currentState == State.NEARBY_DEVICES) {
                            updateDeviceList()
                        }
                    }
                }
                // Respond with our own announcement
                BlePairingProtocol.createAnnounceMessage(session)
            }

            BlePairingProtocol.MSG_PAIRING_VERIFY -> {
                // Peer is trying to verify our code
                Timber.i("$TAG: Received VERIFY from $address")
                val parsed = BlePairingProtocol.parseVerifyMessage(data)
                if (parsed != null) {
                    Timber.i("$TAG: VERIFY parsed - theirCode=${parsed.theirCode}, theirShortId=${parsed.theirShortId}, enteredCode=${parsed.enteredCode}, myCode=${session.myCode}")

                    // Check if they entered our code correctly
                    if (parsed.enteredCode == session.myCode) {
                        // They verified us correctly! Remember this
                        peersWhoVerifiedUs[address] = parsed.theirCode
                        Timber.i("$TAG: Peer $address verified us correctly, their code is ${parsed.theirCode}")

                        // Check if we've verified them (either through UI or they're the selected peer)
                        val weVerifiedThem = session.peerCode == parsed.theirCode ||
                            (selectedPairingPeer?.code == parsed.theirCode)
                        Timber.i("$TAG: weVerifiedThem=$weVerifiedThem, session.peerCode=${session.peerCode}, selectedPairingPeer?.code=${selectedPairingPeer?.code}")

                        if (weVerifiedThem) {
                            // Both verified! Send confirmation with our public ID
                            session.verified = true
                            session.peerShortId = parsed.theirShortId
                            session.peerCode = parsed.theirCode

                            // If their VERIFY includes their public ID, we can complete locally!
                            if (parsed.theirPublicId != null) {
                                session.peerPublicId = parsed.theirPublicId
                                Timber.i("$TAG: Mutual verification complete with public ID! Completing locally.")
                                activity?.runOnUiThread {
                                    onPairingComplete()
                                }
                            } else {
                                Timber.i("$TAG: Mutual verification complete (no public ID in VERIFY). Sending CONFIRM.")
                            }
                            BlePairingProtocol.createConfirmMessage(session)
                        } else {
                            // We haven't verified them yet, send announce so they know we're active
                            Timber.d("$TAG: They verified us but we haven't verified them yet")
                            BlePairingProtocol.createAnnounceMessage(session)
                        }
                    } else {
                        // Wrong code
                        Timber.w("$TAG: Peer entered wrong code: ${parsed.enteredCode} vs ${session.myCode}")
                        BlePairingProtocol.createRejectMessage("invalid_code")
                    }
                } else {
                    Timber.w("$TAG: Failed to parse VERIFY message")
                    null
                }
            }

            BlePairingProtocol.MSG_PAIRING_CONFIRM -> {
                // Peer confirmed pairing and sent their public ID
                val parsed = BlePairingProtocol.parseConfirmMessage(data)
                if (parsed != null && session.verified) {
                    val (publicId, shortId) = parsed
                    session.peerPublicId = publicId
                    session.peerShortId = shortId

                    // Pairing complete!
                    activity?.runOnUiThread {
                        onPairingComplete()
                    }

                    // Send our confirmation back
                    BlePairingProtocol.createConfirmMessage(session)
                } else null
            }

            else -> {
                Timber.w("$TAG: Unknown message type: $messageType")
                null
            }
        }
    }

    private fun updateDeviceList() {
        val blePeers = bleScanner?.peers?.value ?: emptyList()

        Timber.d("$TAG: updateDeviceList - ${blePeers.size} scanner peers, ${discoveredPairingPeers.size} pairing peers")
        blePeers.forEach { p -> Timber.d("$TAG:   scanner peer: ${p.address}") }
        discoveredPairingPeers.forEach { (addr, p) -> Timber.d("$TAG:   pairing peer: $addr -> ${p.shortId}/${p.code}") }

        // Only show devices that are in pairing mode (sent a pairing announcement)
        val displayPeers = blePeers.mapNotNull { blePeer ->
            val pairingInfo = discoveredPairingPeers[blePeer.address]
            if (pairingInfo != null) {
                DisplayPeer(
                    blePeer = blePeer,
                    shortId = pairingInfo.shortId,
                    code = pairingInfo.code
                )
            } else {
                null  // Don't show devices not in pairing mode
            }
        }

        Timber.i("$TAG: updateDeviceList - showing ${displayPeers.size} devices in pairing mode")

        if (displayPeers.isEmpty()) {
            binding.recyclerDevices.visibility = View.GONE
            binding.emptyDevices.visibility = View.VISIBLE
        } else {
            binding.recyclerDevices.visibility = View.VISIBLE
            binding.emptyDevices.visibility = View.GONE
            deviceAdapter.submitList(displayPeers)
        }
    }

    private fun selectDevice(peer: DisplayPeer) {
        selectedPeer = peer.blePeer
        selectedPairingPeer = discoveredPairingPeers[peer.blePeer.address]

        Timber.i("$TAG: Selected device - blePeer.address=${peer.blePeer.address}, shortId=${peer.shortId}, code=${peer.code}")
        Timber.i("$TAG: selectedPairingPeer=${selectedPairingPeer?.address}, code=${selectedPairingPeer?.code}")

        // Update the enter code screen with peer info
        binding.textPeerInfo.text = getString(R.string.pairing_device_format, peer.shortId)

        showState(State.ENTER_CODE)
    }

    private fun verifyCode(enteredCode: String) {
        val session = pairingSession ?: return
        val peer = selectedPeer ?: return

        // Note: We don't check against cached pairingPeer.code here because
        // the cache can be stale if the peer regenerated their session.
        // The protocol will validate - if wrong, peer responds with REJECT.

        // Store the code we entered
        session.peerCode = enteredCode

        // Check if this peer has already verified us (entered our code)
        val theyVerifiedUs = peersWhoVerifiedUs[peer.address] == enteredCode
        Timber.d("$TAG: We entered $enteredCode for ${peer.address}, they verified us: $theyVerifiedUs")

        showState(State.VERIFYING)

        // Pause announcements during verification to avoid BLE resource contention
        verificationInProgress = true

        // Send verification message with retry logic
        scope.launch {
            try {
                val verifyMessage = BlePairingProtocol.createVerifyMessage(session, enteredCode)
                Timber.i("$TAG: Sending VERIFY to ${peer.address}, message size=${verifyMessage.size}")
                Timber.i("$TAG: bleScanner is ${if (bleScanner != null) "available" else "NULL"}")

                // Wait a moment for any in-flight BLE operations to complete
                delay(500)

                // Try up to 3 times with increasing delays
                var response: ByteArray? = null
                for (attempt in 1..3) {
                    Timber.i("$TAG: VERIFY attempt $attempt/3")
                    response = withContext(Dispatchers.IO) {
                        val result = bleScanner?.exchange(peer, verifyMessage)
                        Timber.i("$TAG: exchange() returned ${result?.size ?: "null"} bytes")
                        result
                    }
                    if (response != null) break
                    if (attempt < 3) {
                        Timber.i("$TAG: Retrying after ${attempt * 500}ms delay")
                        delay(attempt * 500L)
                    }
                }

                if (response != null) {
                    val messageType = BlePairingProtocol.getMessageType(response)
                    Timber.i("$TAG: Got response type: $messageType, size=${response.size}")

                    when (messageType) {
                        BlePairingProtocol.MSG_PAIRING_CONFIRM -> {
                            // They confirmed! Extract their public ID
                            val parsed = BlePairingProtocol.parseConfirmMessage(response)
                            if (parsed != null) {
                                session.peerPublicId = parsed.first
                                session.peerShortId = parsed.second
                                session.verified = true
                                verificationInProgress = false
                                onPairingComplete()
                            } else {
                                verificationInProgress = false
                                showError(getString(R.string.pairing_error_invalid_response))
                            }
                        }

                        BlePairingProtocol.MSG_PAIRING_REJECT -> {
                            verificationInProgress = false
                            showError(getString(R.string.pairing_error_rejected))
                        }

                        else -> {
                            // Not confirmed yet, they may still be entering our code
                            // Wait a bit and retry (up to 10 times = 20 seconds)
                            Timber.d("$TAG: Not confirmed yet, retrying...")
                            delay(2000)
                            retryVerification(enteredCode, 10)
                        }
                    }
                } else {
                    verificationInProgress = false
                    showError(getString(R.string.pairing_error_no_response))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Verification failed")
                verificationInProgress = false
                showError(getString(R.string.pairing_error_connection))
            }
        }
    }

    private suspend fun retryVerification(enteredCode: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            verificationInProgress = false
            showError(getString(R.string.pairing_error_timeout))
            return
        }

        val session = pairingSession ?: return
        val peer = selectedPeer ?: return

        try {
            val verifyMessage = BlePairingProtocol.createVerifyMessage(session, enteredCode)
            val response = withContext(Dispatchers.IO) {
                bleScanner?.exchange(peer, verifyMessage)
            }

            if (response != null) {
                val messageType = BlePairingProtocol.getMessageType(response)
                if (messageType == BlePairingProtocol.MSG_PAIRING_CONFIRM) {
                    val parsed = BlePairingProtocol.parseConfirmMessage(response)
                    if (parsed != null) {
                        session.peerPublicId = parsed.first
                        session.peerShortId = parsed.second
                        session.verified = true
                        verificationInProgress = false
                        withContext(Dispatchers.Main) {
                            onPairingComplete()
                        }
                        return
                    }
                } else if (messageType == BlePairingProtocol.MSG_PAIRING_REJECT) {
                    verificationInProgress = false
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.pairing_error_rejected))
                    }
                    return
                }
            }

            // Wait and retry
            delay(2000)
            retryVerification(enteredCode, attemptsLeft - 1)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Retry verification failed")
            verificationInProgress = false
            withContext(Dispatchers.Main) {
                showError(getString(R.string.pairing_error_connection))
            }
        }
    }

    private fun onPairingComplete() {
        val session = pairingSession ?: return

        if (session.peerPublicId == null) {
            showError(getString(R.string.pairing_error_no_public_id))
            return
        }

        // Stop BLE since pairing is complete
        stopBle()

        // Check if already friends - skip nickname prompt if so
        if (friendStore.hasFriend(session.peerPublicId!!)) {
            Timber.i("$TAG: Already friends with ${session.peerShortId}")
            binding.textFriendName.text = getString(R.string.pairing_already_friends_format, session.peerShortId)
            showState(State.SUCCESS)
            return
        }

        // Show nickname prompt
        showState(State.NICKNAME)
    }

    private fun saveFriend(nickname: String?) {
        val session = pairingSession ?: return
        val publicId = session.peerPublicId ?: return

        val success = friendStore.addFriendFromString(publicId, nickname)

        if (success) {
            val displayName = nickname ?: "Friend ${session.peerShortId}"
            binding.textFriendName.text = getString(R.string.pairing_friend_added_format, displayName)
            showState(State.SUCCESS)
            Timber.i("$TAG: Friend added successfully: $displayName")
        } else {
            showError(getString(R.string.pairing_error_already_friends))
        }
    }

    private fun showError(message: String) {
        binding.textErrorMessage.text = message
        showState(State.ERROR)
    }

    private fun cleanup() {
        stopBle()
        pairingSession = null
        discoveredPairingPeers.clear()
        peersWhoVerifiedUs.clear()
        selectedPeer = null
        selectedPairingPeer = null
    }
}

/**
 * Data class for displaying a nearby device with pairing info.
 */
data class DisplayPeer(
    val blePeer: DiscoveredPeer,
    val shortId: String,
    val code: String
)

/**
 * Adapter for the nearby device list.
 */
class NearbyDeviceAdapter(
    private val onDeviceClick: (DisplayPeer) -> Unit
) : RecyclerView.Adapter<NearbyDeviceAdapter.ViewHolder>() {

    private var devices = listOf<DisplayPeer>()

    fun submitList(newDevices: List<DisplayPeer>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textDeviceId: TextView = itemView.findViewById(R.id.text_device_id)
        private val textDeviceCode: TextView = itemView.findViewById(R.id.text_device_code)
        private val textRssi: TextView = itemView.findViewById(R.id.text_rssi)

        fun bind(peer: DisplayPeer) {
            textDeviceId.text = itemView.context.getString(R.string.pairing_device_format, peer.shortId)
            textDeviceCode.text = peer.code  // Show code directly, large font
            textRssi.text = "${peer.blePeer.rssi}"

            itemView.setOnClickListener {
                onDeviceClick(peer)
            }
        }
    }
}
