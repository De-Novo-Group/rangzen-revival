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
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.update.UpdateClient
import org.denovogroup.rangzen.backend.update.UpdateState
import org.denovogroup.rangzen.databinding.FragmentSettingsBinding
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Fragment for app settings.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        loadStats()
        checkForPendingUpdate()
        setupShareApp()
        setupHelp()
    }

    private fun setupHelp() {
        // Help & FAQ row - shows help dialog
        binding.rowHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun showHelpDialog() {
        // Load help content from assets
        val helpText = try {
            requireContext().assets.open("help_content.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load help content")
            "Help content not available."
        }

        // Create a scrollable TextView for the dialog
        val scrollView = android.widget.ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = helpText
            setPadding(50, 40, 50, 40)
            setTextIsSelectable(true)
            textSize = 14f
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle("Help & FAQ")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
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
                // Show confirmation dialog when enabling
                showQaModeConfirmation(prefs)
            } else {
                // Disable without confirmation
                prefs.edit().putBoolean("qa_mode", false).apply()
                TelemetryClient.getInstance()?.setEnabled(false)
                UpdateClient.getInstance()?.stopPeriodicChecks()
            }
        }
    }

    private fun showQaModeConfirmation(prefs: android.content.SharedPreferences) {
        AlertDialog.Builder(requireContext())
            .setTitle("Enable QA Testing Mode")
            .setMessage(
                "QA mode sends diagnostic data to De Novo Group's server via internet (WiFi or cellular).\n\n" +
                "What IS sent:\n" +
                "• Device model & app version\n" +
                "• A hashed device ID (to track same device over time)\n" +
                "• Exchange statistics (peer counts, success rates)\n" +
                "• Error logs for debugging\n" +
                "• Location of exchanges (if location permission granted)\n" +
                "• Your IP address (visible to our server)\n\n" +
                "What is NOT sent:\n" +
                "• Your messages or their content\n" +
                "• Your friend list\n" +
                "• Your pseudonym\n" +
                "• Phone number or real identity\n\n" +
                "This mode also enables automatic app updates via internet.\n\n" +
                "⚠️ Do NOT enable in high-risk environments (Iran, etc.)"
            )
            .setPositiveButton("Enable") { _, _ ->
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
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Revert the toggle
                binding.switchQaMode.isChecked = false
            }
            .setOnCancelListener {
                // Revert the toggle if dismissed
                binding.switchQaMode.isChecked = false
            }
            .show()
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
