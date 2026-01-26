/*
 * Copyright (c) 2026, De Novo Group
 * Support Inbox Activity - view support messages and submitted bug reports
 */
package org.denovogroup.rangzen.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.telemetry.SubmittedBugReport
import org.denovogroup.rangzen.backend.telemetry.SupportMessage
import org.denovogroup.rangzen.backend.telemetry.SupportStore
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.databinding.ActivitySupportInboxBinding
import org.denovogroup.rangzen.databinding.FragmentSupportListBinding
import org.denovogroup.rangzen.databinding.ItemBugReportBinding
import org.denovogroup.rangzen.databinding.ItemSupportMessageBinding
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for viewing support messages and submitted bug reports.
 */
class SupportInboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportInboxBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge display
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.supportInboxRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener {
            syncMessages()
        }

        // Setup ViewPager with tabs
        binding.viewPager.adapter = SupportPagerAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.support_tab_messages)
                1 -> getString(R.string.support_tab_reports)
                else -> ""
            }
        }.attach()

        // Initial sync
        syncMessages()
    }

    private fun syncMessages() {
        val telemetry = TelemetryClient.getInstance() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = telemetry.sync()
                if (response != null) {
                    // Store messages locally
                    val supportStore = SupportStore.getInstance(this@SupportInboxActivity)
                    response.messages?.let { messages ->
                        val newCount = supportStore.addMessages(messages)
                        if (newCount > 0) {
                            Timber.i("Added $newCount new messages to local store")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync messages")
            }
        }
    }
}

/**
 * ViewPager adapter for Messages and Reports tabs.
 */
class SupportPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MessagesListFragment()
            1 -> ReportsListFragment()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }
}

/**
 * Fragment showing standalone support messages.
 */
class MessagesListFragment : Fragment() {

    private var _binding: FragmentSupportListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MessagesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSupportListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MessagesAdapter { message ->
            // Mark as read
            val supportStore = SupportStore.getInstance(requireContext())
            if (!message.isRead) {
                supportStore.markMessageRead(message.id)
                // Also mark on server
                CoroutineScope(Dispatchers.IO).launch {
                    TelemetryClient.getInstance()?.markMessageRead(message.id)
                }
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        loadMessages()
    }

    private fun loadMessages() {
        val supportStore = SupportStore.getInstance(requireContext())
        // Show ALL messages (not just standalone) so replies always appear
        val messages = supportStore.getAllMessages()

        if (messages.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyState.root.visibility = View.VISIBLE
            binding.emptyState.root.findViewById<TextView>(R.id.text_empty).text =
                getString(R.string.support_empty_messages)
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.root.visibility = View.GONE
            adapter.submitList(messages)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Fragment showing submitted bug reports.
 */
class ReportsListFragment : Fragment() {

    private var _binding: FragmentSupportListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReportsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSupportListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ReportsAdapter { report ->
            // Open conversation view
            val intent = Intent(requireContext(), BugReportConversationActivity::class.java).apply {
                putExtra(BugReportConversationActivity.EXTRA_REPORT_ID, report.id)
                putExtra(BugReportConversationActivity.EXTRA_CATEGORY, report.category)
                putExtra(BugReportConversationActivity.EXTRA_DESCRIPTION, report.description)
            }
            startActivity(intent)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        loadReports()
    }

    override fun onResume() {
        super.onResume()
        loadReports()
    }

    private fun loadReports() {
        val supportStore = SupportStore.getInstance(requireContext())
        val reports = supportStore.getAllReports()

        if (reports.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyState.root.visibility = View.VISIBLE
            binding.emptyState.root.findViewById<TextView>(R.id.text_empty).text =
                getString(R.string.support_empty_reports)
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.root.visibility = View.GONE
            adapter.submitList(reports.map { report ->
                val hasUnread = supportStore.hasUnreadForReport(report.id)
                ReportWithUnread(report, hasUnread)
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class ReportWithUnread(val report: SubmittedBugReport, val hasUnread: Boolean)

/**
 * Adapter for support messages.
 */
class MessagesAdapter(
    private val onItemClick: (SupportMessage) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    private var items: List<SupportMessage> = emptyList()

    fun submitList(list: List<SupportMessage>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSupportMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemSupportMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: SupportMessage) {
            binding.textMessage.text = message.message
            binding.textTimestamp.text = formatTimestamp(message.createdAt, binding.root.context)
            binding.unreadIndicator.visibility = if (message.isRead) View.GONE else View.VISIBLE

            binding.root.setOnClickListener {
                onItemClick(message)
                binding.unreadIndicator.visibility = View.GONE
            }
        }
    }
}

/**
 * Adapter for bug reports.
 */
class ReportsAdapter(
    private val onItemClick: (SubmittedBugReport) -> Unit
) : RecyclerView.Adapter<ReportsAdapter.ViewHolder>() {

    private var items: List<ReportWithUnread> = emptyList()

    fun submitList(list: List<ReportWithUnread>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBugReportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemBugReportBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReportWithUnread) {
            val report = item.report
            binding.textCategory.text = report.category.uppercase()
            binding.textStatus.text = report.status
            binding.textDescription.text = report.description
            binding.textTimestamp.text = formatTimestamp(report.createdAt, binding.root.context)
            binding.unreadIndicator.visibility = if (item.hasUnread) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onItemClick(report)
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long, context: android.content.Context): String {
    val date = Date(timestamp)
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> context.getString(R.string.support_time_just_now)
        diff < 3600_000 -> context.getString(R.string.support_time_minutes_ago, diff / 60_000)
        diff < 86400_000 -> context.getString(R.string.support_time_hours_ago, diff / 3600_000)
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}
