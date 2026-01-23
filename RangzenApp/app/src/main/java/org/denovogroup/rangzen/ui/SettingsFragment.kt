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
                "What's sent:\n" +
                "• Device model & app version\n" +
                "• Exchange statistics (peer counts, success rates)\n" +
                "• Error logs for debugging\n\n" +
                "NOT sent:\n" +
                "• Your messages\n" +
                "• Friend list\n" +
                "• Location (unless location is enabled separately)\n\n" +
                "This mode also enables automatic app updates via internet."
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
