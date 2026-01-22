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
     */
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            importContactsFromPhonebook()
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

        binding.btnImportContacts.setOnClickListener {
            requestContactsPermissionAndImport()
        }
    }

    /**
     * Request contacts permission if needed, then import contacts.
     */
    private fun requestContactsPermissionAndImport() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                importContactsFromPhonebook()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                // Show explanation dialog
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
     * Import contacts from the device phonebook.
     * Phone numbers are normalized to E.164 format and then hashed.
     */
    private fun importContactsFromPhonebook() {
        viewLifecycleOwner.lifecycleScope.launch {
            var addedCount = 0
            var totalProcessed = 0

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
                                processedNumbers.add(e164Number)
                                totalProcessed++

                                // Hash the normalized phone number
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
                        Toast.makeText(
                            context,
                            R.string.contacts_no_phone_numbers,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    addedCount > 0 -> {
                        Toast.makeText(
                            context,
                            getString(R.string.contacts_imported, addedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        Toast.makeText(
                            context,
                            R.string.contacts_already_added,
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
     * @param rawNumber The original phone number in any format
     * @param androidNormalized Android's pre-normalized number (may be null)
     * @param context Context for accessing TelephonyManager
     * @return E.164 formatted number, or best-effort cleanup if normalization fails
     */
    private fun normalizePhoneNumberToE164(
        rawNumber: String,
        androidNormalized: String?,
        context: Context
    ): String? {
        // If Android already provided a normalized E.164 number, use it
        if (!androidNormalized.isNullOrEmpty() && androidNormalized.startsWith("+")) {
            return androidNormalized
        }

        // Get country code from device locale/SIM
        val countryCode = getDeviceCountryCode(context)

        // Try using PhoneNumberUtils.formatNumberToE164 (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val formatted = PhoneNumberUtils.formatNumberToE164(rawNumber, countryCode)
            if (!formatted.isNullOrEmpty()) {
                return formatted
            }
        }

        // Fallback: manual normalization
        return manualNormalizePhoneNumber(rawNumber, countryCode)
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
     * Strips all non-digit characters and attempts to add country code.
     */
    private fun manualNormalizePhoneNumber(rawNumber: String, countryCode: String): String? {
        // Strip all non-digit characters except leading +
        val hasPlus = rawNumber.trimStart().startsWith("+")
        val digitsOnly = rawNumber.replace(Regex("[^0-9]"), "")

        if (digitsOnly.isEmpty()) {
            return null
        }

        // If already has country code (starts with +), just clean it up
        if (hasPlus) {
            return "+$digitsOnly"
        }

        // Try to add country code based on number length and format
        return when (countryCode.uppercase(Locale.US)) {
            "US", "CA" -> {
                // North American Numbering Plan
                when {
                    digitsOnly.length == 10 -> "+1$digitsOnly"
                    digitsOnly.length == 11 && digitsOnly.startsWith("1") -> "+$digitsOnly"
                    else -> "+$digitsOnly" // Best effort
                }
            }
            else -> {
                // For other countries, try to find the country calling code
                val callingCode = getCallingCodeForCountry(countryCode)
                if (callingCode != null && !digitsOnly.startsWith(callingCode)) {
                    "+$callingCode$digitsOnly"
                } else {
                    "+$digitsOnly"
                }
            }
        }
    }

    /**
     * Get the calling code for a country ISO code.
     * Returns common codes; extends as needed.
     */
    private fun getCallingCodeForCountry(countryIso: String): String? {
        return when (countryIso.uppercase(Locale.US)) {
            "US", "CA" -> "1"
            "GB" -> "44"
            "DE" -> "49"
            "FR" -> "33"
            "IT" -> "39"
            "ES" -> "34"
            "AU" -> "61"
            "JP" -> "81"
            "CN" -> "86"
            "IN" -> "91"
            "BR" -> "55"
            "MX" -> "52"
            "IR" -> "98"  // Iran - relevant for Farsi localization
            "AF" -> "93"  // Afghanistan
            "PK" -> "92"  // Pakistan
            "TR" -> "90"  // Turkey
            else -> null
        }
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
