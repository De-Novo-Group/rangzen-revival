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
    private var bleAdvertiser: BleAdvertiser? = null

    // Pairing session
    private var pairingSession: BlePairingProtocol.PairingSession? = null
    private var discoveredPairingPeers = mutableMapOf<String, BlePairingProtocol.PairingPeer>()
    private var selectedPeer: DiscoveredPeer? = null
    private var selectedPairingPeer: BlePairingProtocol.PairingPeer? = null

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
        bleAdvertiser = BleAdvertiser(requireContext())

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

        // Start BLE advertising and scanning
        startBle()

        // Show initial state
        showState(State.SHOW_CODE)
    }

    private fun startBle() {
        // Check permissions
        if (bleScanner?.hasPermissions() != true || bleAdvertiser?.hasPermissions() != true) {
            showError(getString(R.string.pairing_error_no_bluetooth))
            return
        }

        // Set up exchange callback for pairing protocol
        bleAdvertiser?.setExchangeCallback { device, data ->
            handleIncomingPairingMessage(device.address, data)
        }

        // Start advertising our presence
        bleAdvertiser?.startAdvertising()

        // Start scanning for other devices
        bleScanner?.startScanning()

        // Observe discovered peers
        viewLifecycleOwner.lifecycleScope.launch {
            bleScanner?.peers?.collectLatest { peers ->
                handleDiscoveredPeers(peers)
            }
        }

        // Periodically send pairing announcements
        scope.launch {
            while (isActive) {
                sendPairingAnnouncement()
                delay(3000) // Every 3 seconds
            }
        }

        Timber.i("$TAG: BLE pairing mode started")
    }

    private fun stopBle() {
        bleScanner?.stopScanning()
        bleAdvertiser?.stopAdvertising()
        Timber.i("$TAG: BLE pairing mode stopped")
    }

    private fun sendPairingAnnouncement() {
        val session = pairingSession ?: return
        if (session.isExpired()) {
            // Generate a new code if expired
            startPairingSession()
            return
        }

        // Send announcement to all discovered peers
        val announcement = BlePairingProtocol.createAnnounceMessage(session)
        scope.launch(Dispatchers.IO) {
            bleScanner?.peers?.value?.forEach { peer ->
                try {
                    bleScanner?.exchange(peer, announcement)
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to send announcement to ${peer.address}")
                }
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
        val session = pairingSession ?: return null
        val messageType = BlePairingProtocol.getMessageType(data)

        return when (messageType) {
            BlePairingProtocol.MSG_PAIRING_ANNOUNCE -> {
                // Another device is announcing itself
                val parsed = BlePairingProtocol.parseAnnounceMessage(data)
                if (parsed != null) {
                    val (code, shortId, timestamp) = parsed
                    val peer = BlePairingProtocol.PairingPeer(
                        address = address,
                        shortId = shortId,
                        code = code,
                        rssi = bleScanner?.peers?.value?.find { it.address == address }?.rssi ?: -100,
                        lastSeen = System.currentTimeMillis()
                    )
                    discoveredPairingPeers[address] = peer

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
                val parsed = BlePairingProtocol.parseVerifyMessage(data)
                if (parsed != null) {
                    val (theirCode, theirShortId, enteredCode) = parsed

                    // Check if they entered our code correctly
                    if (enteredCode == session.myCode) {
                        // They verified us, now check if we've verified them
                        if (session.peerCode == theirCode) {
                            // Both verified! Send confirmation with our public ID
                            session.verified = true
                            session.peerShortId = theirShortId
                            BlePairingProtocol.createConfirmMessage(session)
                        } else {
                            // We haven't verified them yet, just acknowledge
                            BlePairingProtocol.createAnnounceMessage(session)
                        }
                    } else {
                        // Wrong code
                        BlePairingProtocol.createRejectMessage("invalid_code")
                    }
                } else null
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

            else -> null
        }
    }

    private fun updateDeviceList() {
        val blePeers = bleScanner?.peers?.value ?: emptyList()

        // Combine BLE peers with pairing info
        val displayPeers = blePeers.mapNotNull { blePeer ->
            val pairingInfo = discoveredPairingPeers[blePeer.address]
            if (pairingInfo != null) {
                DisplayPeer(
                    blePeer = blePeer,
                    shortId = pairingInfo.shortId,
                    code = pairingInfo.code
                )
            } else {
                // Show BLE peer even without pairing info, with placeholder
                DisplayPeer(
                    blePeer = blePeer,
                    shortId = blePeer.address.takeLast(4),
                    code = "------"
                )
            }
        }

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

        // Update the enter code screen with peer info
        binding.textPeerInfo.text = getString(R.string.pairing_device_format, peer.shortId)

        showState(State.ENTER_CODE)
    }

    private fun verifyCode(enteredCode: String) {
        val session = pairingSession ?: return
        val peer = selectedPeer ?: return
        val pairingPeer = selectedPairingPeer

        // Check if the entered code matches what we see for this peer
        if (pairingPeer != null && enteredCode != pairingPeer.code) {
            Toast.makeText(context, getString(R.string.pairing_code_mismatch), Toast.LENGTH_SHORT).show()
            return
        }

        // Store the code they entered
        session.peerCode = enteredCode

        showState(State.VERIFYING)

        // Send verification message
        scope.launch {
            try {
                val verifyMessage = BlePairingProtocol.createVerifyMessage(session, enteredCode)
                val response = withContext(Dispatchers.IO) {
                    bleScanner?.exchange(peer, verifyMessage)
                }

                if (response != null) {
                    val messageType = BlePairingProtocol.getMessageType(response)

                    when (messageType) {
                        BlePairingProtocol.MSG_PAIRING_CONFIRM -> {
                            // They confirmed! Extract their public ID
                            val parsed = BlePairingProtocol.parseConfirmMessage(response)
                            if (parsed != null) {
                                session.peerPublicId = parsed.first
                                session.peerShortId = parsed.second
                                session.verified = true
                                onPairingComplete()
                            } else {
                                showError(getString(R.string.pairing_error_invalid_response))
                            }
                        }

                        BlePairingProtocol.MSG_PAIRING_REJECT -> {
                            showError(getString(R.string.pairing_error_rejected))
                        }

                        else -> {
                            // Not confirmed yet, they may still be entering our code
                            // Wait a bit and retry
                            delay(2000)
                            retryVerification(enteredCode, 3)
                        }
                    }
                } else {
                    showError(getString(R.string.pairing_error_no_response))
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Verification failed")
                showError(getString(R.string.pairing_error_connection))
            }
        }
    }

    private suspend fun retryVerification(enteredCode: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
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
                        withContext(Dispatchers.Main) {
                            onPairingComplete()
                        }
                        return
                    }
                } else if (messageType == BlePairingProtocol.MSG_PAIRING_REJECT) {
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
            textDeviceCode.text = itemView.context.getString(R.string.pairing_code_format, peer.code)
            textRssi.text = "${peer.blePeer.rssi}"

            itemView.setOnClickListener {
                onDeviceClick(peer)
            }
        }
    }
}
