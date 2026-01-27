/*
 * Copyright (c) 2026, De Novo Group
 * Settings Fragment
 */
package org.denovogroup.rangzen.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.backend.discovery.TransportCapabilities
import org.denovogroup.rangzen.backend.telemetry.SupportStore
import org.denovogroup.rangzen.backend.telemetry.SupportSyncWorker
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.update.UpdateClient
import org.denovogroup.rangzen.backend.update.UpdateState
import org.denovogroup.rangzen.databinding.FragmentSettingsBinding
import timber.log.Timber

/**
 * Fragment for app settings.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // Activity result launcher for QA mode confirmation
    // When user closes the testers guide, QA mode is enabled
    private val qaModeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User has read and dismissed the testers guide - enable QA mode
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)
        prefs.edit().putBoolean("qa_mode", true).apply()
        TelemetryClient.getInstance()?.setEnabled(true)

        // Start OTA update checks and check immediately
        UpdateClient.getInstance()?.let { client ->
            client.startPeriodicChecks()
            CoroutineScope(Dispatchers.IO).launch {
                val update = client.checkForUpdate()
                if (update != null) {
                    Timber.i("Update available: ${update.versionName}")
                }
            }
        }

        // Start support message sync worker
        SupportSyncWorker.schedule(requireContext())

        // Update UI to show the help icon now that QA mode is on
        updateQaHelpIconVisibility()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSettings()
        setupQaHelpIcon()
        setupQaTools()
        setupRadioToggles()
        loadStats()
        checkForPendingUpdate()
        setupShareApp()
        setupHelp()
    }

    private fun setupHelp() {
        // Tutorial row - opens How to Use guide
        binding.rowTutorial.setOnClickListener {
            openHelpActivity(HelpActivity.DOC_TUTORIAL)
        }
        
        // FAQ row - opens FAQ
        binding.rowFaq.setOnClickListener {
            openHelpActivity(HelpActivity.DOC_FAQ)
        }
    }

    private fun openHelpActivity(docType: String) {
        val intent = Intent(requireContext(), HelpActivity::class.java).apply {
            putExtra(HelpActivity.EXTRA_DOC_TYPE, docType)
        }
        startActivity(intent)
    }

    private fun setupShareApp() {
        // Share App button - opens ShareFragment
        binding.cardShareApp.setOnClickListener {
            // Navigate to ShareFragment (uses same container as other fragments)
            parentFragmentManager.beginTransaction()
                .replace(org.denovogroup.rangzen.R.id.fragment_container, ShareFragment.newInstance())
                .addToBackStack("share")
                .commit()
        }
    }

    private fun checkForPendingUpdate() {
        val updateClient = UpdateClient.getInstance() ?: return

        // Check for already downloaded update
        val pending = updateClient.checkPendingInstall()
        if (pending != null) {
            val (release, apkFile) = pending
            showUpdateDialog(release.versionName, apkFile, release)
            return
        }

        // If QA mode is on, check for new updates
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)
        if (prefs.getBoolean("qa_mode", false)) {
            CoroutineScope(Dispatchers.IO).launch {
                val update = updateClient.checkForUpdate()
                // The UpdateClient will auto-download if configured
                // Wait for state to become ReadyToInstall (with timeout)
                if (update != null) {
                    // Poll state until ReadyToInstall or timeout (60 seconds for large APKs)
                    val startTime = System.currentTimeMillis()
                    val timeoutMs = 60_000L
                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        val state = updateClient.state.value
                        when (state) {
                            is UpdateState.ReadyToInstall -> {
                                activity?.runOnUiThread {
                                    showUpdateDialog(state.release.versionName, state.apkFile, state.release)
                                }
                                return@launch
                            }
                            is UpdateState.Error -> {
                                Timber.w("Update check failed: ${state.message}")
                                return@launch
                            }
                            is UpdateState.Idle -> {
                                // Check completed but no update - shouldn't happen since we got update
                                return@launch
                            }
                            else -> {
                                // Still checking or downloading, wait a bit
                                kotlinx.coroutines.delay(500)
                            }
                        }
                    }
                    Timber.w("Update download timed out after ${timeoutMs}ms")
                }
            }
        }
    }

    private fun showUpdateDialog(versionName: String, apkFile: java.io.File, release: org.denovogroup.rangzen.backend.update.ReleaseInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_message, versionName))
            .setPositiveButton(R.string.update_install) { _, _ ->
                UpdateClient.getInstance()?.promptInstall(apkFile, release)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun setupSettings() {
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)

        // Service enabled toggle
        binding.switchService.isChecked = prefs.getBoolean("service_enabled", true)
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            
            if (isChecked) {
                startService()
            } else {
                stopService()
            }
        }

        // QA Mode toggle
        binding.switchQaMode.isChecked = prefs.getBoolean("qa_mode", false)
        binding.switchQaMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Show testers guide when enabling - QA mode enabled on dismiss
                showQaModeConfirmation(prefs)
            } else {
                // Disable without confirmation
                prefs.edit().putBoolean("qa_mode", false).apply()
                TelemetryClient.getInstance()?.setEnabled(false)
                UpdateClient.getInstance()?.stopPeriodicChecks()
                SupportSyncWorker.cancel(requireContext())
                // Hide the help icon since QA mode is now off
                updateQaHelpIconVisibility()
            }
        }
    }

    /**
     * Shows the Testers Guide when enabling QA mode.
     * QA mode is enabled when user dismisses the guide (reads and closes it).
     */
    private fun showQaModeConfirmation(prefs: android.content.SharedPreferences) {
        // Launch the testers guide - QA mode will be enabled when user closes it
        val intent = Intent(requireContext(), HelpActivity::class.java).apply {
            putExtra(HelpActivity.EXTRA_DOC_TYPE, HelpActivity.DOC_TESTERS_GUIDE)
        }
        qaModeLauncher.launch(intent)
    }

    /**
     * Sets up the help icon next to the QA mode toggle.
     * Only visible when QA mode is enabled, allows re-reading the testers guide.
     */
    private fun setupQaHelpIcon() {
        binding.btnQaHelp.setOnClickListener {
            openHelpActivity(HelpActivity.DOC_TESTERS_GUIDE)
        }
        updateQaHelpIconVisibility()
    }

    /**
     * Updates the visibility of the QA help icon based on QA mode state.
     */
    private fun updateQaHelpIconVisibility() {
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)
        val qaEnabled = prefs.getBoolean("qa_mode", false)
        // Show help icon only when QA mode is ON
        binding.btnQaHelp.visibility = if (qaEnabled) View.VISIBLE else View.GONE
        // Also update QA tools and radios visibility
        binding.cardQaTools.visibility = if (qaEnabled) View.VISIBLE else View.GONE
        binding.cardRadios.visibility = if (qaEnabled) View.VISIBLE else View.GONE
    }

    /**
     * Sets up the QA tools section (bug report, inbox).
     */
    private fun setupQaTools() {
        // Report Bug button
        binding.btnReportBug.setOnClickListener {
            startActivity(Intent(requireContext(), BugReportActivity::class.java))
        }

        // QA Inbox button - shows broadcasts and messages
        binding.btnQaInbox.setOnClickListener {
            showQaInbox()
        }

        // Update inbox count badge
        updateInboxBadge()
    }

    /**
     * Sets up the radio toggle switches for enabling/disabling transports.
     * These are QA/debug features for advanced testers.
     */
    private fun setupRadioToggles() {
        val prefs = requireContext().getSharedPreferences("rangzen_prefs", 0)

        // Load saved states (default all enabled)
        binding.switchRadioBle.isChecked = prefs.getBoolean("radio_ble_enabled", true)
        binding.switchRadioWifiDirect.isChecked = prefs.getBoolean("radio_wifi_direct_enabled", true)
        binding.switchRadioWifiAware.isChecked = prefs.getBoolean("radio_wifi_aware_enabled", true)
        binding.switchRadioLan.isChecked = prefs.getBoolean("radio_lan_enabled", true)

        // Check WiFi Aware support and update UI
        val wifiAwareSupported = TransportCapabilities.isWifiAwareSupported(requireContext())
        if (!wifiAwareSupported) {
            binding.textWifiAwareStatus.text = getString(R.string.radio_wifi_aware_not_supported)
            binding.switchRadioWifiAware.isEnabled = false
            binding.switchRadioWifiAware.isChecked = false
        }

        // BLE toggle
        binding.switchRadioBle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("radio_ble_enabled", isChecked).apply()
            Timber.i("Radio BLE enabled: $isChecked")
            notifyServiceOfRadioChange()
        }

        // WiFi Direct toggle
        binding.switchRadioWifiDirect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("radio_wifi_direct_enabled", isChecked).apply()
            Timber.i("Radio WiFi Direct enabled: $isChecked")
            notifyServiceOfRadioChange()
        }

        // WiFi Aware toggle
        binding.switchRadioWifiAware.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("radio_wifi_aware_enabled", isChecked).apply()
            Timber.i("Radio WiFi Aware enabled: $isChecked")
            notifyServiceOfRadioChange()
        }

        // LAN toggle
        binding.switchRadioLan.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("radio_lan_enabled", isChecked).apply()
            Timber.i("Radio LAN enabled: $isChecked")
            notifyServiceOfRadioChange()
        }
    }

    /**
     * Notify the service that radio settings have changed.
     * The service will restart with the new configuration.
     */
    private fun notifyServiceOfRadioChange() {
        val intent = Intent(requireContext(), RangzenService::class.java).apply {
            action = RangzenService.ACTION_RADIO_CONFIG_CHANGED
        }
        requireContext().startService(intent)
    }

    private fun showQaInbox() {
        // Open the full support inbox activity
        startActivity(Intent(requireContext(), SupportInboxActivity::class.java))
    }

    private fun updateInboxBadge() {
        val supportStore = SupportStore.getInstance(requireContext())
        val unreadCount = supportStore.getUnreadCount()
        val reportCount = supportStore.getAllReports().size

        if (unreadCount > 0) {
            binding.textInboxCount.text = getString(R.string.qa_inbox_new_count, unreadCount)
            binding.textInboxCount.visibility = View.VISIBLE
        } else if (reportCount > 0) {
            binding.textInboxCount.text = getString(R.string.qa_inbox_reports_count, reportCount)
            binding.textInboxCount.visibility = View.VISIBLE
        } else {
            binding.textInboxCount.visibility = View.GONE
        }
    }

    private fun loadStats() {
        val messageStore = MessageStore.getInstance(requireContext())
        val friendStore = FriendStore.getInstance(requireContext())

        binding.textMessageCount.text = getString(R.string.settings_messages_count, messageStore.getMessageCount())
        binding.textFriendCount.text = getString(R.string.settings_friends_count, friendStore.getFriendCount())

        // App version and device ID (for debugging sync issues)
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            val deviceHash = TelemetryClient.getInstance()?.deviceIdHash
            val shortHash = deviceHash?.take(8) ?: "N/A"
            binding.textVersion.text = getString(R.string.settings_version_device, packageInfo.versionName, shortHash)
        } catch (e: Exception) {
            binding.textVersion.text = getString(R.string.settings_version_unknown)
        }
    }

    private fun startService() {
        val intent = Intent(requireContext(), RangzenService::class.java).apply {
            action = RangzenService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(requireContext(), RangzenService::class.java).apply {
            action = RangzenService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
