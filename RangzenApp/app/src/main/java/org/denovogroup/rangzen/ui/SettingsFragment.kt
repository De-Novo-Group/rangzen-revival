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
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.backend.discovery.TransportCapabilities
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
                // After download completes, state will be ReadyToInstall
                if (update != null) {
                    // Observe state for ReadyToInstall
                    kotlinx.coroutines.delay(2000) // Give time for download to complete
                    val state = updateClient.state.value
                    if (state is UpdateState.ReadyToInstall) {
                        activity?.runOnUiThread {
                            showUpdateDialog(state.release.versionName, state.apkFile, state.release)
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(versionName: String, apkFile: java.io.File, release: org.denovogroup.rangzen.backend.update.ReleaseInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Update Available")
            .setMessage("Version $versionName is ready to install.\n\nWould you like to install it now?")
            .setPositiveButton("Install") { _, _ ->
                UpdateClient.getInstance()?.promptInstall(apkFile, release)
            }
            .setNegativeButton("Later", null)
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
            binding.textWifiAwareStatus.text = "Not supported on this device"
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
        val telemetry = TelemetryClient.getInstance() ?: return

        // Trigger a sync to get latest messages
        CoroutineScope(Dispatchers.IO).launch {
            telemetry.sync()

            activity?.runOnUiThread {
                // Only show device messages (replies to bug reports) here.
                // Broadcasts now appear in the main feed.
                val messages = telemetry.messages.value

                if (messages.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Bug Report Replies")
                        .setMessage("No replies to your bug reports yet.\n\nBroadcasts now appear in the main feed.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    val items = messages.map { "[Reply] ${it.message}" }.toTypedArray()

                    AlertDialog.Builder(requireContext())
                        .setTitle("Bug Report Replies (${messages.size})")
                        .setItems(items) { _, index ->
                            // Mark as read when tapped
                            CoroutineScope(Dispatchers.IO).launch {
                                telemetry.markMessageRead(messages[index].id)
                            }
                        }
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
        }
    }

    private fun updateInboxBadge() {
        val telemetry = TelemetryClient.getInstance() ?: return
        // Only count device messages (replies), not broadcasts
        val count = telemetry.messages.value.size
        if (count > 0) {
            binding.textInboxCount.text = "$count replies"
            binding.textInboxCount.visibility = View.VISIBLE
        } else {
            binding.textInboxCount.visibility = View.GONE
        }
    }

    private fun loadStats() {
        val messageStore = MessageStore.getInstance(requireContext())
        val friendStore = FriendStore.getInstance(requireContext())

        binding.textMessageCount.text = "Messages: ${messageStore.getMessageCount()}"
        binding.textFriendCount.text = "Friends: ${friendStore.getFriendCount()}"
        
        // App version
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            binding.textVersion.text = "Version: ${packageInfo.versionName}"
        } catch (e: Exception) {
            binding.textVersion.text = "Version: Unknown"
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
