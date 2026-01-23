/*
 * Copyright (c) 2026, De Novo Group
 * Feed Fragment - displays messages from the mesh network
 */
package org.denovogroup.rangzen.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.databinding.FragmentFeedBinding
import org.denovogroup.rangzen.objects.RangzenMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.SharedPreferences

/**
 * Fragment displaying the message feed.
 */
class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageStore: MessageStore
    private var rangzenService: RangzenService? = null
    private var isServiceBound = false
    // Cache the latest message list for filter toggling.
    private var cachedMessages: List<RangzenMessage> = emptyList()
    // Track whether the feed should show only liked messages.
    private var onlyLiked = false
    // Track whether to hide user's own messages (default: true)
    private var hideMine = true
    // Shared preferences for UI-only hidden messages.
    private lateinit var feedPrefs: SharedPreferences
    // Local cache of hidden message IDs.
    private var hiddenMessageIds: MutableSet<String> = mutableSetOf()
    // User's pseudonym for filtering own messages.
    private var myPseudonym: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RangzenService.LocalBinder
            rangzenService = binder.getService()
            isServiceBound = true
            observeServiceStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rangzenService = null
            isServiceBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        messageStore = MessageStore.getInstance(requireContext())
        // Initialize feed preferences for hidden messages.
        feedPrefs = requireContext().getSharedPreferences("feed_prefs", 0)
        // Load hidden message IDs from preferences.
        hiddenMessageIds = loadHiddenMessageIds()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFilters()
        observeMessages()
    }

    override fun onStart() {
        super.onStart()
        Intent(requireContext(), RangzenService::class.java).also { intent ->
            requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            requireActivity().unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Force a refresh when returning to the feed.
        refreshFeedFromDb()
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            onLikeClick = { message ->
                // Compute the new liked state for instant UI feedback.
                val newLiked = !message.isLiked
                // Update the adapter immediately to avoid perceived lag.
                messageAdapter.updateLikeState(message.messageId, newLiked)
                // Persist the like in the local store.
                messageStore.likeMessage(message.messageId, newLiked)
                // Re-apply filters so unliked messages can disappear instantly.
                updateUI(applyFilters(cachedMessages))
            },
            onMessageClick = { message ->
                // Mark the message read in the store.
                messageStore.markAsRead(message.messageId)
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = messageAdapter
        }

        // Attach swipe handling to hide messages locally.
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Drag-and-drop is not supported for feed items.
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Look up the swiped message by adapter position.
                val message = messageAdapter.getMessageAt(viewHolder.adapterPosition)
                // Guard against missing items.
                if (message == null) {
                    // Re-render the list to reset any swiped state.
                    updateUI(applyFilters(cachedMessages))
                    return
                }
                // Hide the message in the local UI only.
                hideMessage(message.messageId)
            }
        }
        // Attach the swipe helper to the recycler view.
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerMessages)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            // Force refresh to pull latest DB state.
            refreshFeedFromDb()
            // Trigger a soft force exchange (respects active inbound sessions).
            triggerSoftExchange()
        }
    }

    private fun triggerSoftExchange() {
        // Send soft force exchange intent - skips cooldown but respects inbound sessions.
        val intent = Intent(requireContext(), RangzenService::class.java).apply {
            action = RangzenService.ACTION_SOFT_FORCE_EXCHANGE
        }
        requireContext().startService(intent)
    }

    private fun setupFilters() {
        // Load user's pseudonym for "hide mine" filter.
        myPseudonym = requireContext()
            .getSharedPreferences("rangzen_prefs", Context.MODE_PRIVATE)
            .getString("default_pseudonym", null)
        
        // Initialize the toggles with current state.
        binding.switchOnlyLiked.isChecked = onlyLiked
        binding.checkHideMine.isChecked = hideMine
        
        // Update the feed when liked filter is toggled.
        binding.switchOnlyLiked.setOnCheckedChangeListener { _, isChecked ->
            onlyLiked = isChecked
            updateUI(applyFilters(cachedMessages))
        }
        
        // Update the feed when "hide mine" filter is toggled.
        binding.checkHideMine.setOnCheckedChangeListener { _, isChecked ->
            hideMine = isChecked
            updateUI(applyFilters(cachedMessages))
        }
        
        // Wire up the "show all" button to clear hidden messages.
        binding.btnShowHidden.setOnClickListener {
            clearHiddenMessages()
            updateUI(applyFilters(cachedMessages))
        }
    }

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            messageStore.messages.collectLatest { messages ->
                // Cache the latest list for filtering.
                cachedMessages = messages
                // Apply filters before rendering.
                updateUI(applyFilters(messages))
            }
        }
    }

    private fun observeServiceStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            rangzenService?.peers?.collectLatest { peers ->
                // Currently only BLE peers are tracked in this flow.
                // TODO: Expose unified peer registry to show LAN/WD counts.
                updateStatusText(peers.size)
            }
        }
    }

    private fun updateUI(messages: List<RangzenMessage>) {
        binding.swipeRefresh.isRefreshing = false
        
        if (messages.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerMessages.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerMessages.visibility = View.VISIBLE
            messageAdapter.submitList(messages)
        }
    }

    private fun applyFilters(messages: List<RangzenMessage>): List<RangzenMessage> {
        var result = messages
        
        // Filter by liked if enabled.
        if (onlyLiked) {
            result = result.filter { it.isLiked }
        }
        
        // Filter out own messages if "hide mine" is checked.
        if (hideMine && myPseudonym != null) {
            result = result.filter { it.pseudonym != myPseudonym }
        }
        
        // Filter out swiped/hidden messages.
        result = result.filter { !hiddenMessageIds.contains(it.messageId) }
        
        return result
    }

    private fun hideMessage(messageId: String) {
        // Add the message to the hidden list.
        hiddenMessageIds.add(messageId)
        // Persist the hidden list to preferences.
        saveHiddenMessageIds(hiddenMessageIds)
        // Refresh the UI without altering the DB.
        updateUI(applyFilters(cachedMessages))
    }

    private fun clearHiddenMessages() {
        // Clear the in-memory hidden list.
        hiddenMessageIds.clear()
        // Remove the persisted entry to reset state.
        feedPrefs.edit().remove("hidden_message_ids").apply()
    }

    private fun loadHiddenMessageIds(): MutableSet<String> {
        // Read the persisted set, defaulting to empty.
        val stored = feedPrefs.getStringSet("hidden_message_ids", emptySet())
        // Return a mutable copy to avoid modifying the stored set directly.
        return stored?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveHiddenMessageIds(ids: Set<String>) {
        // Persist a copy of the IDs to avoid SharedPreferences mutation issues.
        feedPrefs.edit().putStringSet("hidden_message_ids", ids.toSet()).apply()
    }

    private fun updateStatusText(peerCount: Int) {
        // Show peer count. Currently only BLE peers are tracked.
        // TODO: Add transport breakdown when unified registry is exposed.
        binding.statusText.text = if (peerCount > 0) {
            "$peerCount peers nearby"
        } else {
            getString(R.string.status_discovering)
        }
    }

    private fun refreshFeedFromDb() {
        // Refresh on a background thread to avoid blocking UI.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Force store refresh then post UI update.
            messageStore.refreshMessagesNow()
            // Stop the spinner on the main thread.
            withContext(Dispatchers.Main) {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class MessageAdapter(
    private val onLikeClick: (RangzenMessage) -> Unit,
    private val onMessageClick: (RangzenMessage) -> Unit
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private var messages = listOf<RangzenMessage>()

    fun submitList(newMessages: List<RangzenMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    fun updateLikeState(messageId: String, liked: Boolean) {
        // Find the message in the current list.
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index == -1) {
            // Nothing to update when the message is missing.
            return
        }
        val message = messages[index]
        // Update liked state immediately for the UI.
        message.isLiked = liked
        // Adjust the like count locally for instant feedback.
        val delta = if (liked) 1 else -1
        message.likes = kotlin.math.max(0, message.likes + delta)
        // Rebind just this item for a smooth update.
        notifyItemChanged(index)
    }

    fun getMessageAt(position: Int): RangzenMessage? {
        // Guard against invalid indices.
        if (position < 0 || position >= messages.size) {
            return null
        }
        // Return the message at the requested position.
        return messages[position]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textMessage: android.widget.TextView = itemView.findViewById(R.id.text_message)
        private val textPseudonym: android.widget.TextView = itemView.findViewById(R.id.text_pseudonym)
        private val textTimestamp: android.widget.TextView = itemView.findViewById(R.id.text_timestamp)
        private val textLikes: android.widget.TextView = itemView.findViewById(R.id.text_likes)
        private val btnLike: android.widget.ImageButton = itemView.findViewById(R.id.btn_like)
        private val trustIndicator: View = itemView.findViewById(R.id.trust_indicator)

        fun bind(message: RangzenMessage) {
            // Render the main message body.
            textMessage.text = message.text
            // Show sender pseudonym or a safe fallback.
            textPseudonym.text = message.pseudonym ?: "Anonymous"
            // Format composed/received timestamps for the header row.
            textTimestamp.text = formatHeaderTimes(
                composedAt = message.timestamp,
                receivedAt = message.receivedTimestamp
            )
            // Render the like count for the action row.
            textLikes.text = message.likes.toString()
            // Swap the like icon based on user state.
            btnLike.setImageResource(
                if (message.isLiked) R.drawable.ic_liked else R.drawable.ic_like
            )
            // Map trust to a visual indicator color.
            val trustColor = when {
                message.trustScore >= 0.7 -> R.color.trust_high
                message.trustScore >= 0.4 -> R.color.trust_medium
                else -> R.color.trust_low
            }
            // Apply the trust indicator color to the bar.
            trustIndicator.setBackgroundColor(
                itemView.context.getColor(trustColor)
            )
            // Wire up like taps.
            btnLike.setOnClickListener { onLikeClick(message) }
            // Wire up message taps to mark as read.
            itemView.setOnClickListener { onMessageClick(message) }
            // Reduce alpha for unread messages to make them stand out.
            itemView.alpha = if (message.isRead) 1.0f else 0.9f
        }

        private fun formatHeaderTimes(composedAt: Long, receivedAt: Long): String {
            // Choose a usable received time fallback if not set.
            val safeReceived = if (receivedAt > 0) receivedAt else composedAt
            // Format the composed time for display.
            val composedText = formatClockTime(composedAt)
            // Format the received time for display.
            val receivedText = formatClockTime(safeReceived)
            // Build a compact header string.
            return "C $composedText · R $receivedText"
        }

        private fun formatClockTime(timestamp: Long): String {
            // Use a stable locale-aware time formatter.
            val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
            // Convert to a displayable time string.
            return formatter.format(Date(timestamp))
        }
    }
}
