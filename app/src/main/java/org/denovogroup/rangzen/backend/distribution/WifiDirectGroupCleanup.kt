/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WifiDirectGroupCleanup - Ensures no WiFi Direct groups persist across app sessions.
 * 
 * SAFETY PURPOSE:
 * WiFi Direct groups can persist even after the app closes. This creates:
 * 1. Privacy risk: Group membership reveals "who shared with whom"
 * 2. UX issue: Unexpected auto-reconnections
 * 3. Resource leak: Groups consume system resources
 * 
 * This utility cleans up any stale groups on app startup and share mode exit.
 */
package org.denovogroup.rangzen.backend.distribution

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import timber.log.Timber

/**
 * Utility for cleaning up WiFi Direct groups.
 * 
 * Call [cleanupOnStartup] from Application.onCreate() or Service.onCreate()
 * to ensure no stale groups persist from previous sessions.
 */
object WifiDirectGroupCleanup {
    
    private const val TAG = "WifiDirectGroupCleanup"
    
    /**
     * Clean up any persistent WiFi Direct groups.
     * 
     * This should be called:
     * 1. On app startup (Application.onCreate)
     * 2. When ShareMode exits
     * 3. When the app is being destroyed
     * 
     * @param context Android context
     */
    @SuppressLint("MissingPermission")
    fun cleanupOnStartup(context: Context) {
        Timber.d("$TAG: Checking for stale WiFi Direct groups")
        
        try {
            val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wifiP2pManager == null) {
                Timber.d("$TAG: WiFi P2P not available")
                return
            }
            
            val channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
            if (channel == null) {
                Timber.w("$TAG: Failed to initialize WiFi P2P channel")
                return
            }
            
            // Request current group info
            wifiP2pManager.requestGroupInfo(channel) { group ->
                if (group != null) {
                    Timber.i("$TAG: Found stale WiFi Direct group, removing...")
                    
                    // Remove the group
                    wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Timber.i("$TAG: Successfully removed stale WiFi Direct group")
                        }
                        
                        override fun onFailure(reason: Int) {
                            val reasonStr = when (reason) {
                                WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                                WifiP2pManager.BUSY -> "System busy"
                                WifiP2pManager.ERROR -> "Internal error"
                                else -> "Unknown ($reason)"
                            }
                            Timber.w("$TAG: Failed to remove stale group: $reasonStr")
                        }
                    })
                } else {
                    Timber.d("$TAG: No stale WiFi Direct groups found")
                }
            }
            
        } catch (e: SecurityException) {
            // Missing permissions - this is OK, just log
            Timber.d("$TAG: Cannot check WiFi Direct groups (permissions not granted)")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error during WiFi Direct cleanup")
        }
    }
    
    /**
     * Force remove any WiFi Direct group.
     * 
     * Call this when ShareMode exits to ensure clean state.
     * 
     * @param context Android context
     * @param callback Optional callback for completion
     */
    @SuppressLint("MissingPermission")
    fun forceRemoveGroup(context: Context, callback: ((success: Boolean) -> Unit)? = null) {
        try {
            val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wifiP2pManager == null) {
                callback?.invoke(false)
                return
            }
            
            val channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
            if (channel == null) {
                callback?.invoke(false)
                return
            }
            
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("$TAG: WiFi Direct group removed")
                    callback?.invoke(true)
                }
                
                override fun onFailure(reason: Int) {
                    // Failure might mean no group exists - that's OK
                    Timber.d("$TAG: removeGroup returned $reason (may be no group)")
                    callback?.invoke(reason == WifiP2pManager.ERROR) // ERROR often means no group
                }
            })
            
        } catch (e: SecurityException) {
            Timber.d("$TAG: Cannot remove WiFi Direct group (permissions not granted)")
            callback?.invoke(false)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error removing WiFi Direct group")
            callback?.invoke(false)
        }
    }
    
    /**
     * Cancel any ongoing WiFi Direct discovery.
     * 
     * Call this when ShareMode exits if discovery was active.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery(context: Context) {
        try {
            val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return
            
            val channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
                ?: return
            
            wifiP2pManager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("$TAG: WiFi Direct discovery stopped")
                }
                
                override fun onFailure(reason: Int) {
                    // Failure might mean discovery wasn't running - that's OK
                    Timber.d("$TAG: stopPeerDiscovery returned $reason")
                }
            })
            
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error stopping WiFi Direct discovery")
        }
    }
}
