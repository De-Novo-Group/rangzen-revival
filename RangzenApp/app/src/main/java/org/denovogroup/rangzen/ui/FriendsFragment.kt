/*
 * Copyright (c) 2026, De Novo Group
 * Friends Fragment - manage friend list for trust scoring
 */
package org.denovogroup.rangzen.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.Crypto
import org.denovogroup.rangzen.backend.Friend
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.PhoneUtils
import org.denovogroup.rangzen.databinding.FragmentFriendsBinding
import java.util.Locale

/**
 * Fragment for managing friends (social trust graph).
 */
class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var friendStore: FriendStore
    private lateinit var friendAdapter: FriendAdapter

    /**
     * Permission request launcher for READ_CONTACTS.
     * When permission is granted, proceeds to hash contacts locally.
     */
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted - proceed with hashing
            hashContactsFromPhonebook()
        } else {
            Toast.makeText(
                context,
                R.string.permission_contacts_message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        friendStore = FriendStore.getInstance(requireContext())

        setupRecyclerView()
        setupButtons()
        observeFriends()
        showMyQRCode()
    }

    private fun setupRecyclerView() {
        friendAdapter = FriendAdapter { friend ->
            // Long press to remove friend
            friendStore.removeFriend(friend.publicId)
            Toast.makeText(context, "Friend removed", Toast.LENGTH_SHORT).show()
        }

        binding.recyclerFriends.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = friendAdapter
        }
    }

    private fun setupButtons() {
        binding.btnShowQr.setOnClickListener {
            showMyQRCode()
        }

        binding.btnScanQr.setOnClickListener {
            scanFriendQR()
        }

        // "Hash Contacts" button (Casific's privacy-preserving friend discovery)
        binding.btnHashContacts.setOnClickListener {
            showHashContactsExplanation()
        }
    }

    /**
     * Show an explanatory dialog BEFORE requesting contacts permission.
     * This explains how contact hashing works and that numbers never leave the device.
     */
    private fun showHashContactsExplanation() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.hash_contacts_explain_title)
            .setMessage(R.string.hash_contacts_explain_message)
            .setPositiveButton(R.string.hash_contacts_explain_continue) { _, _ ->
                // User understood the explanation, now request permission
                requestContactsPermissionAndHash()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Request contacts permission if needed, then hash contacts.
     * Called AFTER user has seen and accepted the explanation dialog.
     */
    private fun requestContactsPermissionAndHash() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                hashContactsFromPhonebook()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                // Show system rationale dialog, then request
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.permission_contacts_title)
                    .setMessage(R.string.permission_contacts_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> {
                // Request permission directly
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    /**
     * Hash contacts from the device phonebook (privacy-preserving friend discovery).
     * 
     * Phone numbers are:
     * 1. Filtered to device's country only (e.g., IR for Iran users)
     * 2. Filtered to mobile numbers only (no landlines)
     * 3. Normalized to E.164 format
     * 4. One-way hashed using SHA-256 (via Crypto.encodeString)
     * 
     * The hashes are stored locally and used during PSI (Private Set Intersection)
     * when meeting other Murmur users to compute trust overlap.
     * 
     * NOTE: Phone numbers NEVER leave the device - only the count of shared contacts
     * is used for trust scoring, not the identities.
     */
    private fun hashContactsFromPhonebook() {
        // Show processing toast (hashing can take a moment for large contact lists)
        Toast.makeText(context, R.string.contacts_processing, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            var addedCount = 0
            var totalProcessed = 0
            // Track stats for debugging/logging
            var skippedWrongCountry = 0
            var skippedNotMobile = 0

            // Get device country code ONCE before starting (used for filtering)
            val deviceCountryCode = getDeviceCountryCode(requireContext())

            withContext(Dispatchers.IO) {
                val contentResolver = requireContext().contentResolver

                // Query all contacts with phone numbers
                val contactsCursor: Cursor? = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER
                    ),
                    "${ContactsContract.Contacts.HAS_PHONE_NUMBER} > 0",
                    null,
                    null
                )

                contactsCursor?.use { contacts ->
                    val idIndex = contacts.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameIndex = contacts.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                    while (contacts.moveToNext()) {
                        val contactId = contacts.getString(idIndex)
                        val contactName = contacts.getString(nameIndex) ?: "Unknown"

                        // Get all phone numbers for this contact
                        val phoneCursor: Cursor? = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                            ),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )

                        phoneCursor?.use { phones ->
                            val numberIndex = phones.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            )
                            val normalizedIndex = phones.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                            )

                            // Track processed numbers to avoid duplicates within same contact
                            val processedNumbers = mutableSetOf<String>()

                            while (phones.moveToNext()) {
                                val rawNumber = phones.getString(numberIndex) ?: continue
                                // Try Android's normalized number first
                                val androidNormalized = phones.getString(normalizedIndex)

                                // Normalize to E.164 format
                                val e164Number = normalizePhoneNumberToE164(
                                    rawNumber,
                                    androidNormalized,
                                    requireContext()
                                )

                                // Skip if we couldn't normalize or already processed
                                if (e164Number == null || e164Number in processedNumbers) {
                                    continue
                                }

                                // FILTER 1: Country filter - only hash numbers from device's country
                                // This is important for Iran use case: don't expose foreign contacts.
                                if (!PhoneUtils.isNumberFromCountry(e164Number, deviceCountryCode)) {
                                    skippedWrongCountry++
                                    continue
                                }

                                // FILTER 2: Mobile-only filter - skip landlines, toll-free, etc.
                                // Murmur is a mobile app; landline contacts can't be Murmur users.
                                if (!PhoneUtils.isMobileNumber(e164Number, deviceCountryCode)) {
                                    skippedNotMobile++
                                    continue
                                }

                                processedNumbers.add(e164Number)
                                totalProcessed++

                                // Hash the normalized phone number using one-way SHA-256
                                // The hash is what gets stored and compared during PSI.
                                val hashedNumber = Crypto.encodeString(e164Number)
                                if (hashedNumber != null) {
                                    // Generate a display name suffix for multiple numbers
                                    val displayName = if (processedNumbers.size > 1) {
                                        "$contactName (${e164Number.takeLast(4)})"
                                    } else {
                                        contactName
                                    }

                                    // Add as friend (will silently skip duplicates)
                                    if (friendStore.addFriend(hashedNumber, displayName)) {
                                        addedCount++
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Show aggregate feedback
            withContext(Dispatchers.Main) {
                when {
                    totalProcessed == 0 -> {
                        // No mobile numbers from device's country found
                        Toast.makeText(
                            context,
                            R.string.contacts_no_mobile_numbers,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    addedCount > 0 -> {
                        Toast.makeText(
                            context,
                            getString(R.string.contacts_hashed, addedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        // All contacts were already hashed previously
                        Toast.makeText(
                            context,
                            R.string.contacts_already_hashed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Normalize a phone number to E.164 format for consistent hashing.
     * 
     * E.164 format is "+[country code][number]" with no spaces/dashes.
     * Examples: "+15551234567", "+442071234567"
     * 
     * Uses Android's PhoneNumberUtils when available, then falls back to
     * PhoneUtils.normalizePhoneNumber() which is fully testable.
     * 
     * @param rawNumber The original phone number in any format
     * @param androidNormalized Android's pre-normalized number (may be null)
     * @param context Context for accessing TelephonyManager
     * @return E.164 formatted number, or null if invalid
     */
    private fun normalizePhoneNumberToE164(
        rawNumber: String,
        androidNormalized: String?,
        context: Context
    ): String? {
        // Get country code from device locale/SIM
        val countryCode = getDeviceCountryCode(context)

        // Try using Android's PhoneNumberUtils.formatNumberToE164 (API 21+)
        // This is more accurate than manual normalization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val formatted = PhoneNumberUtils.formatNumberToE164(rawNumber, countryCode)
            if (!formatted.isNullOrEmpty()) {
                return formatted
            }
        }

        // Fallback: use testable PhoneUtils normalization
        // This handles androidNormalized and manual fallback
        return PhoneUtils.normalizePhoneNumber(rawNumber, androidNormalized, countryCode)
    }

    /**
     * Get the ISO country code for the device.
     * Tries SIM card first, then network, then falls back to locale.
     */
    private fun getDeviceCountryCode(context: Context): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // Try SIM country first (most reliable for phone numbers)
        telephonyManager?.simCountryIso?.takeIf { it.isNotEmpty() }?.let {
            return it.uppercase(Locale.US)
        }

        // Try network country
        telephonyManager?.networkCountryIso?.takeIf { it.isNotEmpty() }?.let {
            return it.uppercase(Locale.US)
        }

        // Fall back to device locale
        return Locale.getDefault().country.ifEmpty { "US" }
    }

    /**
     * Manual phone number normalization as fallback.
     * Delegates to PhoneUtils for testable implementation.
     */
    private fun manualNormalizePhoneNumber(rawNumber: String, countryCode: String): String? {
        return PhoneUtils.manualNormalizeToE164(rawNumber, countryCode)
    }

    private fun observeFriends() {
        viewLifecycleOwner.lifecycleScope.launch {
            friendStore.friends.collectLatest { friends ->
                updateUI(friends)
            }
        }
    }

    private fun updateUI(friends: List<Friend>) {
        binding.friendCount.text = "Friends: ${friends.size}"
        
        if (friends.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerFriends.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerFriends.visibility = View.VISIBLE
            friendAdapter.submitList(friends)
        }
    }

    private fun showMyQRCode() {
        val publicId = friendStore.getMyPublicIdString()
        if (publicId == null) {
            Toast.makeText(context, "Identity not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(
                publicId,
                BarcodeFormat.QR_CODE,
                400,
                400
            )
            binding.qrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanFriendQR() {
        // Use ZXing scanner
        IntentIntegrator.forSupportFragment(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan your friend's Rangzen QR code")
            .setBeepEnabled(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                // Got a QR code - try to add as friend
                val success = friendStore.addFriendFromString(result.contents)
                if (success) {
                    Toast.makeText(context, "Friend added!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Already friends or invalid code", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * RecyclerView adapter for friends.
 */
class FriendAdapter(
    private val onFriendLongClick: (Friend) -> Unit
) : RecyclerView.Adapter<FriendAdapter.FriendViewHolder>() {

    private var friends = listOf<Friend>()

    fun submitList(newFriends: List<Friend>) {
        friends = newFriends
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return FriendViewHolder(view)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        holder.bind(friends[position])
    }

    override fun getItemCount(): Int = friends.size

    inner class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val textId: TextView = itemView.findViewById(R.id.text_id)

        fun bind(friend: Friend) {
            textName.text = friend.getDisplayName()
            textId.text = friend.publicId.take(16) + "..."

            itemView.setOnLongClickListener {
                onFriendLongClick(friend)
                true
            }
        }
    }
}
