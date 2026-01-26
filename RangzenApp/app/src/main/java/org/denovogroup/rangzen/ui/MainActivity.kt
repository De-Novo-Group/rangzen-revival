/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Main Activity for Rangzen
 */
package org.denovogroup.rangzen.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.RangzenService
import org.denovogroup.rangzen.databinding.ActivityMainBinding
import timber.log.Timber

private const val PREF_FIRST_LAUNCH_COMPLETE = "first_launch_complete"

/**
 * Main Activity with bottom navigation for Feed, Compose, Friends, and Settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    /** Service connection */
    private var rangzenService: RangzenService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RangzenService.LocalBinder
            rangzenService = binder.getService()
            serviceBound = true
            Timber.d("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rangzenService = null
            serviceBound = false
            Timber.d("Service disconnected")
        }
    }

    /** Permission launcher */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Timber.i("All permissions granted")
            startRangzenService()
        } else {
            Timber.w("Some permissions denied")
            Toast.makeText(this, "Rangzen needs permissions to work", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * First-time tutorial launcher.
     * Shows the tutorial on first app launch. User can dismiss (skip) at any time.
     * After viewing/skipping, we mark first launch as complete.
     */
    private val tutorialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Mark first launch as complete when user closes/skips the tutorial
        val prefs = getSharedPreferences("rangzen_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_FIRST_LAUNCH_COMPLETE, true).apply()
        Timber.d("First launch tutorial completed/skipped")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge display properly on all Android versions
        // On Android 15+, edge-to-edge is enforced by the system

        // Enable edge-to-edge drawing - content extends behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Status bar icons should be LIGHT on our DARK background
        val windowInsetsController = WindowInsetsControllerCompat(window, binding.root)
        windowInsetsController.isAppearanceLightStatusBars = false  // Light icons on dark background
        windowInsetsController.isAppearanceLightNavigationBars = false  // Light icons on dark nav bar

        // Handle window insets to add padding so content doesn't overlap system bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Add padding for status bar at top, but NOT bottom (BottomNav handles itself)
            view.updatePadding(top = insets.top)
            windowInsets
        }

        // Initialize stores
        MessageStore.getInstance(this)
        FriendStore.getInstance(this).getOrCreateIdentity()

        setupBottomNavigation()
        
        // Show feed by default
        if (savedInstanceState == null) {
            showFragment(FeedFragment())
        }

        // Check if this is first launch - show tutorial if so
        checkFirstLaunch()

        // Request permissions
        checkAndRequestPermissions()
    }

    /**
     * Checks if this is the first time the app is launched.
     * If so, shows the tutorial. User can dismiss/skip at any time.
     */
    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("rangzen_prefs", MODE_PRIVATE)
        val firstLaunchComplete = prefs.getBoolean(PREF_FIRST_LAUNCH_COMPLETE, false)
        
        if (!firstLaunchComplete) {
            // First launch - show the tutorial
            Timber.d("First launch detected, showing tutorial")
            val intent = Intent(this, HelpActivity::class.java).apply {
                putExtra(HelpActivity.EXTRA_DOC_TYPE, HelpActivity.DOC_TUTORIAL)
            }
            tutorialLauncher.launch(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if running
        Intent(this, RangzenService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_feed -> {
                    showFragment(FeedFragment())
                    true
                }
                R.id.nav_compose -> {
                    showFragment(ComposeFragment())
                    true
                }
                R.id.nav_friends -> {
                    showFragment(FriendsFragment())
                    true
                }
                R.id.nav_settings -> {
                    showFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Location permission (needed for WiFi Direct)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Nearby WiFi devices (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            // Notification permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            // All permissions granted, start service
            startRangzenService()
        }
    }

    private fun startRangzenService() {
        val prefs = getSharedPreferences("rangzen_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("service_enabled", true)

        if (isEnabled) {
            val intent = Intent(this, RangzenService::class.java).apply {
                action = RangzenService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    fun getService(): RangzenService? = rangzenService
}
