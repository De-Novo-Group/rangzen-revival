/*
 * Copyright (c) 2026, De Novo Group
 * Feed Fragment - displays messages from the mesh network and broadcasts
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.NotificationHelper
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.backend.discovery.TransportType
import org.denovogroup.rangzen.backend.discovery.UnifiedPeer
import org.denovogroup.rangzen.backend.telemetry.Broadcast
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
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

    private lateinit var feedAdapter: FeedAdapter
    private lateinit var messageStore: MessageStore
    private var rangzenService: RangzenService? = null
    private var isServiceBound = false
    // Cache the latest message list for filter toggling.
    private var cachedMessages: List<RangzenMessage> = emptyList()
    // Cache the latest broadcast list.
    private var cachedBroadcasts: List<Broadcast> = emptyList()
    // Track whether the feed should show only liked (hearted) messages.
    private var onlyLiked = false
    // Track whether to hide user's own messages (default: true)
    private var hideMine = true
    // Track current sort mode: false = Recent (by time), true = Most hearted
    private var sortByHearts = false
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
        // Mark UI as visible - notifications will be suppressed while user is looking at feed.
        NotificationHelper.isUiVisible = true
        // Force a refresh when returning to the feed.
        refreshFeedFromDb()
        // Clear any pending message notifications when user views the feed.
        NotificationHelper.clearMessageNotifications(requireContext())
    }
    
    override fun onPause() {
        super.onPause()
        // Mark UI as not visible - notifications can now be shown.
        NotificationHelper.isUiVisible = false
    }

    private fun setupRecyclerView() {
        feedAdapter = FeedAdapter(
            onLikeClick = { message ->
                // Compute the new liked state for instant UI feedback.
                val newLiked = !message.isLiked
                // Update the adapter immediately to avoid perceived lag.
                feedAdapter.updateLikeState(message.messageId, newLiked)
                // Persist the like in the local store.
                messageStore.likeMessage(message.messageId, newLiked)
                // Re-apply filters so unliked messages can disappear instantly.
                updateUI(buildFeedItems())
            },
            onMessageClick = { message ->
                // Mark the message read in the store.
                messageStore.markAsRead(message.messageId)
            },
            onBroadcastClick = { broadcast ->
                // Mark broadcast as read via telemetry client.
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    TelemetryClient.getInstance()?.markBroadcastRead(broadcast.id)
                }
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = feedAdapter
        }

        // Attach swipe handling to hide items locally.
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
                // Look up the swiped item by adapter position.
                val item = feedAdapter.getItemAt(viewHolder.adapterPosition)
                // Guard against missing items.
                if (item == null) {
                    // Re-render the list to reset any swiped state.
                    updateUI(buildFeedItems())
                    return
                }
                // Hide the item in the local UI only.
                hideMessage(item.id)
            }
        }
        // Attach the swipe helper to the recycler view.
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerMessages)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            // Force refresh to pull latest DB state.
            refreshFeedFromDb()
            // Sync broadcasts from telemetry server.
            syncBroadcasts()
            // Trigger a soft force exchange (respects active inbound sessions).
            triggerSoftExchange()
        }
    }

    private fun syncBroadcasts() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            TelemetryClient.getInstance()?.sync()
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
            updateUI(buildFeedItems())
        }

        // Update the feed when "hide mine" filter is toggled.
        binding.checkHideMine.setOnCheckedChangeListener { _, isChecked ->
            hideMine = isChecked
            updateUI(buildFeedItems())
        }

        // Wire up the "Restore swiped" button to clear hidden messages.
        binding.btnShowHidden.setOnClickListener {
            clearHiddenMessages()
            updateUI(buildFeedItems())
        }

        // Wire up the sort toggle button.
        // Cycles between "Recent" (by time) and "Most hearted" (by heart count).
        binding.btnSort.setOnClickListener {
            sortByHearts = !sortByHearts
            // Update button text to reflect current sort mode
            binding.btnSort.text = if (sortByHearts) "♥ Hearts ▼" else "Recent ▼"
            // Re-apply filters with new sort order
            updateUI(buildFeedItems())
        }
    }

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Combine message store flow with telemetry broadcasts flow.
            val broadcastsFlow = TelemetryClient.getInstance()?.broadcasts
                ?: kotlinx.coroutines.flow.flowOf(emptyList())

            messageStore.messages.combine(broadcastsFlow) { messages, broadcasts ->
                Pair(messages, broadcasts)
            }.collectLatest { (messages, broadcasts) ->
                // Cache the latest lists for filtering.
                cachedMessages = messages
                cachedBroadcasts = broadcasts
                // Build combined feed and render.
                updateUI(buildFeedItems())
            }
        }
    }

    /**
     * Build the combined feed item list from messages and broadcasts.
     * Applies filters and sorting.
     */
    private fun buildFeedItems(): List<FeedItem> {
        // Filter messages.
        var filteredMessages = cachedMessages

        // Filter by liked (hearted) if enabled.
        if (onlyLiked) {
            filteredMessages = filteredMessages.filter { it.isLiked }
        }

        // Filter out own messages if "hide mine" is checked.
        if (hideMine && myPseudonym != null) {
            filteredMessages = filteredMessages.filter { it.pseudonym != myPseudonym }
        }

        // Filter out swiped/hidden messages.
        filteredMessages = filteredMessages.filter { !hiddenMessageIds.contains(it.messageId) }

        // Convert to FeedItems.
        val messageItems = filteredMessages.map { FeedItem.MessageItem(it) }

        // Filter out hidden broadcasts.
        val filteredBroadcasts = cachedBroadcasts.filter { !hiddenMessageIds.contains(it.id) }
        val broadcastItems = filteredBroadcasts.map { FeedItem.BroadcastItem(it) }

        // Combine all items.
        val allItems = messageItems + broadcastItems

        // Apply sort order.
        return if (sortByHearts) {
            // Sort by hearts descending (broadcasts have 0 hearts, will appear after hearted messages)
            allItems.sortedWith(compareByDescending<FeedItem> {
                when (it) {
                    is FeedItem.MessageItem -> it.message.likes
                    is FeedItem.BroadcastItem -> 0
                }
            }.thenByDescending { it.sortTimestamp })
        } else {
            // Sort by time descending (newest first)
            allItems.sortedByDescending { it.sortTimestamp }
        }
    }

    private fun observeServiceStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            timber.log.Timber.d("FeedFragment: Starting to observe unifiedPeers")
            rangzenService?.unifiedPeers?.collectLatest { unifiedPeers ->
                timber.log.Timber.d("FeedFragment: Received ${unifiedPeers.size} unified peers")
                // Real transport counts from unified peer registry.
                updateStatusText(unifiedPeers)
            }
        }
    }

    private fun updateUI(items: List<FeedItem>) {
        binding.swipeRefresh.isRefreshing = false

        if (items.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerMessages.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerMessages.visibility = View.VISIBLE
            feedAdapter.submitList(items)
        }
    }

    private fun hideMessage(itemId: String) {
        // Add the item to the hidden list.
        hiddenMessageIds.add(itemId)
        // Persist the hidden list to preferences.
        saveHiddenMessageIds(hiddenMessageIds)
        // Refresh the UI without altering the DB.
        updateUI(buildFeedItems())
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

    private fun updateStatusText(unifiedPeers: List<UnifiedPeer>) {
        val peerCount = unifiedPeers.size

        // Show peer count and update status dot color.
        // Status dot: green when peers found, gray otherwise.
        binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            resources.getColor(
                if (peerCount > 0) R.color.peer_active else R.color.text_hint,
                null
            )
        )

        binding.statusText.text = if (peerCount > 0) {
            "$peerCount peers nearby"
        } else {
            getString(R.string.status_discovering)
        }

        // Update transport breakdown icons with real counts.
        updateTransportBreakdown(unifiedPeers)
    }

    /**
     * Update the transport breakdown icons (BT, WD, NAN, LAN).
     * Shows small icons with counts for each active transport type.
     *
     * Calculates real counts from the unified peer list - each peer may
     * be reachable via multiple transports simultaneously.
     */
    private fun updateTransportBreakdown(unifiedPeers: List<UnifiedPeer>) {
        // Count peers reachable via each transport type.
        // A peer can be counted in multiple transports if reachable via both.
        val btCount = unifiedPeers.count { it.hasTransport(TransportType.BLE) }
        val wdCount = unifiedPeers.count { it.hasTransport(TransportType.WIFI_DIRECT) }
        val nanCount = unifiedPeers.count { it.hasTransport(TransportType.WIFI_AWARE) }
        val lanCount = unifiedPeers.count { it.hasTransport(TransportType.LAN) }

        // Show BT icon and count if peers found via Bluetooth.
        if (btCount > 0) {
            binding.iconBt.visibility = View.VISIBLE
            binding.countBt.visibility = View.VISIBLE
            binding.countBt.text = btCount.toString()
        } else {
            binding.iconBt.visibility = View.GONE
            binding.countBt.visibility = View.GONE
        }

        // Show WD icon and count if peers found via WiFi Direct.
        if (wdCount > 0) {
            binding.iconWd.visibility = View.VISIBLE
            binding.countWd.visibility = View.VISIBLE
            binding.countWd.text = wdCount.toString()
        } else {
            binding.iconWd.visibility = View.GONE
            binding.countWd.visibility = View.GONE
        }

        // Show NAN icon and count if peers found via WiFi Aware.
        if (nanCount > 0) {
            binding.iconNan.visibility = View.VISIBLE
            binding.countNan.visibility = View.VISIBLE
            binding.countNan.text = nanCount.toString()
        } else {
            binding.iconNan.visibility = View.GONE
            binding.countNan.visibility = View.GONE
        }

        // Show LAN icon and count if peers found via LAN/hotspot.
        if (lanCount > 0) {
            binding.iconLan.visibility = View.VISIBLE
            binding.countLan.visibility = View.VISIBLE
            binding.countLan.text = lanCount.toString()
        } else {
            binding.iconLan.visibility = View.GONE
            binding.countLan.visibility = View.GONE
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

/**
 * Unified adapter for displaying both messages and broadcasts in the feed.
 */
class FeedAdapter(
    private val onLikeClick: (RangzenMessage) -> Unit,
    private val onMessageClick: (RangzenMessage) -> Unit,
    private val onBroadcastClick: (Broadcast) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_BROADCAST = 1
    }

    private var items = listOf<FeedItem>()

    fun submitList(newItems: List<FeedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updateLikeState(messageId: String, liked: Boolean) {
        // Find the message in the current list.
        val index = items.indexOfFirst {
            it is FeedItem.MessageItem && it.message.messageId == messageId
        }
        if (index == -1) return

        val item = items[index] as? FeedItem.MessageItem ?: return
        // Update liked state immediately for the UI.
        item.message.isLiked = liked
        // Adjust the like count locally for instant feedback.
        val delta = if (liked) 1 else -1
        item.message.likes = kotlin.math.max(0, item.message.likes + delta)
        // Rebind just this item for a smooth update.
        notifyItemChanged(index)
    }

    fun getItemAt(position: Int): FeedItem? {
        if (position < 0 || position >= items.size) return null
        return items[position]
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is FeedItem.MessageItem -> VIEW_TYPE_MESSAGE
            is FeedItem.BroadcastItem -> VIEW_TYPE_BROADCAST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_MESSAGE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message, parent, false)
                MessageViewHolder(view)
            }
            VIEW_TYPE_BROADCAST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_broadcast, parent, false)
                BroadcastViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FeedItem.MessageItem -> (holder as MessageViewHolder).bind(item.message)
            is FeedItem.BroadcastItem -> (holder as BroadcastViewHolder).bind(item.broadcast)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textMessage: android.widget.TextView = itemView.findViewById(R.id.text_message)
        private val textPseudonym: android.widget.TextView = itemView.findViewById(R.id.text_pseudonym)
        private val textTimestamp: android.widget.TextView = itemView.findViewById(R.id.text_timestamp)
        private val textLikes: android.widget.TextView = itemView.findViewById(R.id.text_likes)
        private val btnLike: android.widget.ImageButton = itemView.findViewById(R.id.btn_like)
        private val trustIndicator: View = itemView.findViewById(R.id.trust_indicator)

        fun bind(message: RangzenMessage) {
            textMessage.text = message.text
            textPseudonym.text = message.pseudonym ?: "Anonymous"
            textTimestamp.text = formatHeaderTimes(
                composedAt = message.timestamp,
                receivedAt = message.receivedTimestamp
            )
            textLikes.text = message.likes.toString()
            btnLike.setImageResource(
                if (message.isLiked) R.drawable.ic_liked else R.drawable.ic_like
            )
            val trustColor = when {
                message.trustScore >= 0.7 -> R.color.trust_high
                message.trustScore >= 0.4 -> R.color.trust_medium
                else -> R.color.trust_low
            }
            trustIndicator.setBackgroundColor(itemView.context.getColor(trustColor))
            btnLike.setOnClickListener { onLikeClick(message) }
            itemView.setOnClickListener { onMessageClick(message) }
            itemView.alpha = if (message.isRead) 1.0f else 0.9f
        }

        private fun formatHeaderTimes(composedAt: Long, receivedAt: Long): String {
            val safeReceived = if (receivedAt > 0) receivedAt else composedAt
            val composedText = formatClockTime(composedAt)
            val receivedText = formatClockTime(safeReceived)
            return "C $composedText · R $receivedText"
        }

        private fun formatClockTime(timestamp: Long): String {
            val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
            return formatter.format(Date(timestamp))
        }
    }

    inner class BroadcastViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTitle: android.widget.TextView = itemView.findViewById(R.id.text_title)
        private val textBody: android.widget.TextView = itemView.findViewById(R.id.text_body)
        private val textTimestamp: android.widget.TextView = itemView.findViewById(R.id.text_timestamp)

        fun bind(broadcast: Broadcast) {
            textTitle.text = broadcast.title
            textBody.text = broadcast.body
            textTimestamp.text = formatBroadcastTime(broadcast.createdAt)
            itemView.setOnClickListener { onBroadcastClick(broadcast) }
        }

        private fun formatBroadcastTime(isoTimestamp: String): String {
            val timestamp = FeedItem.parseIsoTimestamp(isoTimestamp)
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val hours = diff / (1000 * 60 * 60)
            val days = hours / 24

            return when {
                hours < 1 -> "Just now"
                hours < 24 -> "${hours}h ago"
                days < 7 -> "${days}d ago"
                else -> {
                    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
                    formatter.format(Date(timestamp))
                }
            }
        }
    }
}
