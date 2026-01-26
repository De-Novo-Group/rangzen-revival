/*
 * Copyright (c) 2026, De Novo Group
 * Bug Report Activity - allows QA testers to submit bug reports
 */
package org.denovogroup.rangzen.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.BuildConfig
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.backend.telemetry.LocationHelper
import org.denovogroup.rangzen.backend.telemetry.SubmittedBugReport
import org.denovogroup.rangzen.backend.telemetry.SupportStore
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.databinding.ActivityBugReportBinding
import timber.log.Timber

/**
 * Activity for submitting bug reports.
 * Available only when QA mode is enabled.
 */
class BugReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBugReportBinding
    private var selectedCategory: String = "other"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBugReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge display
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.bugReportRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            windowInsets
        }

        setupUI()
        displayDeviceInfo()
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Category chip selection
        binding.chipGroupCategory.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                selectedCategory = when (chip?.id) {
                    R.id.chip_connectivity -> "connectivity"
                    R.id.chip_exchange -> "exchange"
                    R.id.chip_discovery -> "discovery"
                    R.id.chip_qr -> "qr"
                    R.id.chip_performance -> "performance"
                    else -> "other"
                }
            }
        }

        // Default selection
        binding.chipOther.isChecked = true

        // Submit button
        binding.btnSubmit.setOnClickListener {
            submitReport()
        }
    }

    private fun displayDeviceInfo() {
        val deviceInfo = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        binding.textDeviceInfo.text = deviceInfo
    }

    private fun submitReport() {
        val description = binding.editDescription.text?.toString()?.trim()

        if (description.isNullOrEmpty()) {
            Toast.makeText(this, "Please describe the issue", Toast.LENGTH_SHORT).show()
            return
        }

        if (description.length < 10) {
            Toast.makeText(this, "Please provide more details", Toast.LENGTH_SHORT).show()
            return
        }

        val telemetryClient = TelemetryClient.getInstance()
        if (telemetryClient == null) {
            Toast.makeText(this, "Telemetry not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading state
        binding.btnSubmit.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get current location if available
                val location = try {
                    LocationHelper(this@BugReportActivity).getCurrentLocation()
                } catch (e: Exception) {
                    Timber.w("Failed to get location for bug report: ${e.message}")
                    null
                }

                // Get transport state from service
                val transportState = RangzenService.getServiceInstance()?.getTransportState()

                // Get display name from prefs
                val prefs = getSharedPreferences("rangzen_prefs", 0)
                val displayName = prefs.getString("display_name", null)

                // Submit the report
                val reportId = telemetryClient.submitBugReport(
                    category = selectedCategory,
                    description = description,
                    displayName = displayName,
                    transportState = transportState,
                    lastExchangeId = RangzenService.getServiceInstance()?.getLastExchangeId(),
                    location = location
                )

                // Store locally if successful
                if (reportId != null) {
                    val supportStore = SupportStore.getInstance(this@BugReportActivity)
                    supportStore.addReport(SubmittedBugReport(
                        id = reportId,
                        category = selectedCategory,
                        description = description,
                        status = "open",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = null
                    ))
                }

                withContext(Dispatchers.Main) {
                    binding.btnSubmit.isEnabled = true
                    binding.progressBar.visibility = View.GONE

                    if (reportId != null) {
                        Toast.makeText(
                            this@BugReportActivity,
                            "Report submitted! We may reply in-app.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@BugReportActivity,
                            "Failed to submit report. Please try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to submit bug report")
                withContext(Dispatchers.Main) {
                    binding.btnSubmit.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@BugReportActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
