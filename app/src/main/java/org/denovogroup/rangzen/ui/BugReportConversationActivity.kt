/*
 * Copyright (c) 2026, De Novo Group
 * Bug Report Conversation Activity - view and reply to a bug report
 */
package org.denovogroup.rangzen.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.telemetry.BugReportReply
import org.denovogroup.rangzen.backend.telemetry.SupportStore
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.databinding.ActivityBugReportConversationBinding
import org.denovogroup.rangzen.databinding.ItemConversationMessageBinding
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for viewing a bug report conversation.
 */
class BugReportConversationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REPORT_ID = "report_id"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_DESCRIPTION = "description"
    }

    private lateinit var binding: ActivityBugReportConversationBinding
    private lateinit var adapter: ConversationAdapter

    private var reportId: String = ""
    private var category: String = ""
    private var description: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBugReportConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reportId = intent.getStringExtra(EXTRA_REPORT_ID) ?: ""
        category = intent.getStringExtra(EXTRA_CATEGORY) ?: ""
        description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: ""

        if (reportId.isEmpty()) {
            Toast.makeText(this, "Invalid report", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Handle edge-to-edge display
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.conversationRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }

        setupUI()
        loadConversation()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.textTitle.text = "Bug Report: ${category.replaceFirstChar { it.uppercase() }}"
        binding.textStatus.text = "Loading..."

        adapter = ConversationAdapter()
        binding.recyclerConversation.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerConversation.adapter = adapter

        binding.btnSend.setOnClickListener {
            sendReply()
        }
    }

    private fun loadConversation() {
        showLoading(true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val telemetry = TelemetryClient.getInstance()
                if (telemetry == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BugReportConversationActivity,
                            "Telemetry not initialized", Toast.LENGTH_SHORT).show()
                        showLoading(false)
                    }
                    return@launch
                }

                val response = telemetry.fetchBugReport(reportId)

                withContext(Dispatchers.Main) {
                    showLoading(false)

                    if (response != null) {
                        // Update status
                        binding.textStatus.text = "Status: ${response.report.status}"

                        // Update local status
                        val supportStore = SupportStore.getInstance(this@BugReportConversationActivity)
                        supportStore.updateReportStatus(reportId, response.report.status)

                        // Build conversation items
                        val items = mutableListOf<ConversationItem>()

                        // Add original report as first message
                        items.add(ConversationItem(
                            id = reportId,
                            message = description,
                            isFromUser = true,
                            timestamp = response.report.createdAt
                        ))

                        // Add replies
                        response.replies.forEach { reply ->
                            items.add(ConversationItem(
                                id = reply.id,
                                message = reply.message,
                                isFromUser = !reply.fromDashboard,
                                timestamp = reply.createdAt
                            ))

                            // Mark message as read locally if it's from dashboard
                            if (reply.fromDashboard) {
                                val localMessage = supportStore.getMessage(reply.id)
                                if (localMessage != null && !localMessage.isRead) {
                                    supportStore.markMessageRead(reply.id)
                                    telemetry.markMessageRead(reply.id)
                                }
                            }
                        }

                        adapter.submitList(items)

                        // Scroll to bottom
                        if (items.isNotEmpty()) {
                            binding.recyclerConversation.scrollToPosition(items.size - 1)
                        }
                    } else {
                        // Couldn't fetch from server - show local data only
                        val items = mutableListOf<ConversationItem>()
                        items.add(ConversationItem(
                            id = reportId,
                            message = description,
                            isFromUser = true,
                            timestamp = ""
                        ))

                        // Add any locally cached messages
                        val supportStore = SupportStore.getInstance(this@BugReportConversationActivity)
                        supportStore.getMessagesForReport(reportId).forEach { msg ->
                            items.add(ConversationItem(
                                id = msg.id,
                                message = msg.message,
                                isFromUser = false, // Local messages are always from support
                                timestamp = formatTimestampLong(msg.createdAt)
                            ))
                        }

                        adapter.submitList(items)
                        binding.textStatus.text = "Status: Unknown (offline)"
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load conversation")
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@BugReportConversationActivity,
                        "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendReply() {
        val message = binding.editReply.text?.toString()?.trim()
        if (message.isNullOrEmpty()) {
            return
        }

        binding.editReply.isEnabled = false
        binding.btnSend.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val telemetry = TelemetryClient.getInstance()
                if (telemetry == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BugReportConversationActivity,
                            "Telemetry not initialized", Toast.LENGTH_SHORT).show()
                        binding.editReply.isEnabled = true
                        binding.btnSend.isEnabled = true
                    }
                    return@launch
                }

                val replyId = telemetry.sendReply(reportId, message)

                withContext(Dispatchers.Main) {
                    binding.editReply.isEnabled = true
                    binding.btnSend.isEnabled = true

                    if (replyId != null) {
                        binding.editReply.text?.clear()
                        Toast.makeText(this@BugReportConversationActivity,
                            "Reply sent!", Toast.LENGTH_SHORT).show()
                        // Reload conversation to show the new reply
                        loadConversation()
                    } else {
                        Toast.makeText(this@BugReportConversationActivity,
                            "Failed to send reply", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send reply")
                withContext(Dispatchers.Main) {
                    binding.editReply.isEnabled = true
                    binding.btnSend.isEnabled = true
                    Toast.makeText(this@BugReportConversationActivity,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }
}

data class ConversationItem(
    val id: String,
    val message: String,
    val isFromUser: Boolean,
    val timestamp: String
)

class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private var items: List<ConversationItem> = emptyList()

    fun submitList(list: List<ConversationItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemConversationMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConversationItem) {
            binding.textSender.text = if (item.isFromUser) "You" else "Support"
            binding.textMessage.text = item.message
            binding.textTimestamp.text = if (item.timestamp.isNotEmpty()) {
                parseAndFormatTimestamp(item.timestamp)
            } else {
                ""
            }

            // Align messages based on sender
            val params = binding.cardMessage.layoutParams as FrameLayout.LayoutParams
            if (item.isFromUser) {
                params.gravity = android.view.Gravity.END
                binding.cardMessage.setCardBackgroundColor(
                    binding.root.context.getColor(R.color.primary_light)
                )
            } else {
                params.gravity = android.view.Gravity.START
                binding.cardMessage.setCardBackgroundColor(
                    binding.root.context.getColor(R.color.surface)
                )
            }
            binding.cardMessage.layoutParams = params
        }
    }
}

private fun parseAndFormatTimestamp(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val date = Date.from(instant)
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
    } catch (e: Exception) {
        isoString
    }
}

private fun formatTimestampLong(timestamp: Long): String {
    return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
}
