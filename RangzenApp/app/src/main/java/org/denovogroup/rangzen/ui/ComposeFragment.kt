/*
 * Copyright (c) 2026, De Novo Group
 * Compose Fragment - for creating new messages
 */
package org.denovogroup.rangzen.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.databinding.FragmentComposeBinding
import org.denovogroup.rangzen.objects.RangzenMessage

/**
 * Fragment for composing new messages.
 */
class ComposeFragment : Fragment() {

    private var _binding: FragmentComposeBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageStore: MessageStore
    private lateinit var friendStore: FriendStore

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentComposeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageStore = MessageStore.getInstance(requireContext())
        friendStore = FriendStore.getInstance(requireContext())

        setupViews()
    }

    private fun setupViews() {
        // Character counter
        binding.editMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val length = s?.length ?: 0
                binding.charCount.text = getString(R.string.char_count, length)
                
                // Change color when approaching limit
                val color = when {
                    length > RangzenMessage.MAX_MESSAGE_LENGTH -> R.color.error
                    length > 120 -> R.color.warning
                    else -> R.color.text_secondary
                }
                binding.charCount.setTextColor(requireContext().getColor(color))
                
                // Enable/disable send button
                binding.btnSend.isEnabled = length in 1..RangzenMessage.MAX_MESSAGE_LENGTH
            }
        })

        // Send button
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        // Pseudonym field (optional)
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)
        binding.editPseudonym.setText(prefs.getString("default_pseudonym", ""))
    }

    private fun sendMessage() {
        val text = binding.editMessage.text.toString().trim()
        
        if (text.isEmpty()) {
            Toast.makeText(context, "Message cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        if (text.length > RangzenMessage.MAX_MESSAGE_LENGTH) {
            Toast.makeText(context, "Message too long", Toast.LENGTH_SHORT).show()
            return
        }

        // Get pseudonym
        val pseudonym = binding.editPseudonym.text.toString().trim().ifEmpty { "Anonymous" }
        
        // Save pseudonym for next time
        requireContext().getSharedPreferences("rangzen_prefs", 0)
            .edit()
            .putString("default_pseudonym", pseudonym)
            .apply()

        // Create and store message
        val message = RangzenMessage(text, pseudonym).apply {
            // Own messages have maximum trust
            trustScore = 1.0
            // Mark as already read
            isRead = true
        }

        if (messageStore.addMessage(message)) {
            Toast.makeText(context, "Message sent to the mesh network", Toast.LENGTH_SHORT).show()
            // Trigger an immediate outbound exchange attempt after local send.
            forceOutboundExchange()
            // Clear the input
            binding.editMessage.text?.clear()
            
            // Return to feed
            (activity as? MainActivity)?.let {
                it.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    R.id.bottom_nav
                )?.selectedItemId = R.id.nav_feed
            }
        } else {
            Toast.makeText(context, "Failed to send message", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceOutboundExchange() {
        // Build an intent targeting the foreground service.
        val intent = Intent(requireContext(), RangzenService::class.java)
        // Set the action that forces an outbound exchange attempt.
        intent.action = RangzenService.ACTION_FORCE_EXCHANGE
        // Start the service from within the app process.
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
