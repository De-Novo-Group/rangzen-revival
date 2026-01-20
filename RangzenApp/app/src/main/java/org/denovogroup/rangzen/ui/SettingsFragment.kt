/*
 * Copyright (c) 2026, De Novo Group
 * Settings Fragment
 */
package org.denovogroup.rangzen.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.databinding.FragmentSettingsBinding

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
            prefs.edit().putBoolean("qa_mode", isChecked).apply()
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
