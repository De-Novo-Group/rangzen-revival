/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Exchange history tracking aligned with the original Rangzen/Murmur protocol.
 */
package org.denovogroup.rangzen.backend

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks exchange history to enable backoff and peer selection.
 */
class ExchangeHistoryTracker private constructor(private val context: Context) {

    // SharedPreferences name for exchange counts.
    private val prefsName = "exchange_history_count"
    // Key for the exchange count value.
    private val countKey = "count"
    // SharedPreferences instance for persistence.
    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // In-memory history of peers.
    private val history = mutableListOf<ExchangeHistoryItem>()
    // Total exchange counter.
    private var exchangeCount = prefs.getInt(countKey, 0)

    companion object {
        // Singleton instance holder.
        @Volatile
        private var instance: ExchangeHistoryTracker? = null

        /**
         * Get a singleton instance.
         */
        fun getInstance(context: Context): ExchangeHistoryTracker {
            return instance ?: synchronized(this) {
                instance ?: ExchangeHistoryTracker(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Remove history items not present in the supplied peer list.
     */
    fun cleanHistory(activePeers: Collection<String>) {
        // Filter history to only active peers.
        val updated = history.filter { activePeers.contains(it.address) }
        // Replace the history list.
        history.clear()
        history.addAll(updated)
    }

    /**
     * Update the history item for a peer (or create it).
     */
    fun updateHistory(messageStore: MessageStore, address: String) {
        // Try to find existing history item.
        val item = history.firstOrNull { it.address == address }
        if (item != null) {
            // Reset attempts on successful exchange.
            item.attempts = 0
            // Store the current message store version.
            item.storeVersion = messageStore.getStoreVersion()
            // Track the exchange timestamp.
            item.lastExchangeTime = System.currentTimeMillis()
            // Track when this peer was picked.
            item.lastPicked = System.currentTimeMillis()
            return
        }
        // Create new history item when missing.
        history.add(
            ExchangeHistoryItem(
                address = address,
                storeVersion = messageStore.getStoreVersion(),
                lastExchangeTime = System.currentTimeMillis()
            )
        )
    }

    /**
     * Update the last-picked timestamp for a peer.
     */
    fun updatePickHistory(address: String) {
        // Find the existing history item.
        val item = history.firstOrNull { it.address == address } ?: return
        // Update last picked time.
        item.lastPicked = System.currentTimeMillis()
    }

    /**
     * Increment the attempts counter for a peer.
     */
    fun updateAttemptsHistory(address: String) {
        // Find the existing history item.
        val item = history.firstOrNull { it.address == address } ?: return
        // Increment attempt counter.
        item.attempts += 1
    }

    /**
     * Retrieve a history item by address.
     */
    fun getHistoryItem(address: String): ExchangeHistoryItem? {
        // Return matching item or null.
        return history.firstOrNull { it.address == address }
    }

    /**
     * Record a consecutive failure for a peer.
     */
    fun recordFailure(address: String) {
        val item = history.firstOrNull { it.address == address } ?: return
        item.consecutiveFailures += 1
    }

    /**
     * Reset consecutive failures on success.
     */
    fun resetFailures(address: String) {
        val item = history.firstOrNull { it.address == address } ?: return
        item.consecutiveFailures = 0
    }

    /**
     * Increment the global exchange count.
     */
    fun incrementExchangeCount() {
        // Increment counter.
        exchangeCount += 1
        // Persist counter for continuity.
        prefs.edit().putInt(countKey, exchangeCount).apply()
    }

    /**
     * Reset the global exchange count.
     */
    fun resetExchangeCount() {
        // Reset counter.
        exchangeCount = 0
        // Persist reset state.
        prefs.edit().putInt(countKey, exchangeCount).apply()
    }

    /**
     * Return the total number of exchanges.
     */
    fun getExchangeCount(): Int {
        // Return the stored value.
        return exchangeCount
    }

    /**
     * Model for peer exchange history.
     */
    data class ExchangeHistoryItem(
        val address: String,
        var storeVersion: String,
        var lastExchangeTime: Long,
        var attempts: Int = 0,
        var lastPicked: Long = 0,
        /** Consecutive exchange failures with this peer (reset on success). */
        var consecutiveFailures: Int = 0
    )
}
