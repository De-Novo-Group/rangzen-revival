/*
 * Copyright (c) 2026, De Novo Group
 * Friends Fragment - manage friend list for trust scoring
 */
package org.denovogroup.rangzen.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.Friend
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.databinding.FragmentFriendsBinding

/**
 * Fragment for managing friends (social trust graph).
 */
class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private lateinit var friendStore: FriendStore
    private lateinit var friendAdapter: FriendAdapter

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

        binding.btnAddNearby.setOnClickListener {
            openBlePairing()
        }
    }

    private fun openBlePairing() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, BlePairingFragment.newInstance())
            .addToBackStack("ble_pairing")
            .commit()
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
        // Use ZXing scanner with portrait-locked activity
        IntentIntegrator.forSupportFragment(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan your friend's Murmur QR code")
            .setBeepEnabled(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
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
