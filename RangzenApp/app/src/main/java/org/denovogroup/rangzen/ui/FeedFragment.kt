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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.databinding.FragmentFeedBinding
import org.denovogroup.rangzen.objects.RangzenMessage

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
        setupRecyclerView()
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

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            onLikeClick = { message ->
                messageStore.likeMessage(message.messageId, !message.isLiked)
            },
            onMessageClick = { message ->
                messageStore.markAsRead(message.messageId)
            }
        )

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = messageAdapter
        }
    }

    private fun observeMessages() {
        viewLifecycleOwner.lifecycleScope.launch {
            messageStore.messages.collectLatest { messages ->
                updateUI(messages)
            }
        }
    }

    private fun observeServiceStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            rangzenService?.peers?.collectLatest { peers ->
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

    private fun updateStatusText(peerCount: Int) {
        binding.statusText.text = if (peerCount > 0) {
            getString(R.string.status_peers_found, peerCount)
        } else {
            getString(R.string.status_discovering)
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
            textMessage.text = message.text
            textPseudonym.text = message.pseudonym ?: "Anonymous"
            textTimestamp.text = formatTimestamp(message.timestamp)
            textLikes.text = message.likes.toString()
            btnLike.setImageResource(
                if (message.isLiked) R.drawable.ic_liked else R.drawable.ic_like
            )
            val trustColor = when {
                message.trustScore >= 0.7 -> R.color.trust_high
                message.trustScore >= 0.4 -> R.color.trust_medium
                else -> R.color.trust_low
            }
            trustIndicator.setBackgroundColor(
                itemView.context.getColor(trustColor)
            )
            btnLike.setOnClickListener { onLikeClick(message) }
            itemView.setOnClickListener { onMessageClick(message) }
            itemView.alpha = if (message.isRead) 1.0f else 0.9f
        }

        private fun formatTimestamp(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000 -> "now"
                diff < 3600_000 -> "${diff / 60_000}m"
                diff < 86400_000 -> "${diff / 3600_000}h"
                else -> "${diff / 86400_000}d"
            }
        }
    }
}
