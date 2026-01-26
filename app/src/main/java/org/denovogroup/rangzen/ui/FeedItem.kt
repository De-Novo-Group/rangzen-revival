/*
 * Copyright (c) 2026, De Novo Group
 * Feed Item - unified type for messages and broadcasts in the feed
 */
package org.denovogroup.rangzen.ui

import org.denovogroup.rangzen.backend.telemetry.Broadcast
import org.denovogroup.rangzen.objects.RangzenMessage
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Sealed class representing items that can appear in the feed.
 * This allows us to mix regular mesh messages with broadcast messages
 * from De Novo Group in a single list.
 */
sealed class FeedItem {
    /** Unique identifier for the item */
    abstract val id: String

    /** Timestamp for sorting (millis since epoch) */
    abstract val sortTimestamp: Long

    /**
     * A regular message from the mesh network.
     */
    data class MessageItem(
        val message: RangzenMessage
    ) : FeedItem() {
        override val id: String get() = message.messageId
        override val sortTimestamp: Long get() = message.timestamp
    }

    /**
     * A broadcast message from De Novo Group via the Internet.
     * These are clearly marked to distinguish from mesh messages.
     */
    data class BroadcastItem(
        val broadcast: Broadcast
    ) : FeedItem() {
        override val id: String get() = broadcast.id
        override val sortTimestamp: Long get() = parseIsoTimestamp(broadcast.createdAt)
    }

    companion object {
        private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Parse an ISO timestamp string to millis since epoch.
         * Returns current time if parsing fails.
         */
        fun parseIsoTimestamp(isoString: String): Long {
            return try {
                // Handle various ISO formats (with or without Z, with timezone offset, etc.)
                val cleanedString = isoString
                    .replace("Z", "")
                    .substringBefore("+")
                    .substringBefore("-", isoString.substringBefore("T") + "-" + isoString.substringAfter("T").substringBefore("-"))
                    .take(19) // yyyy-MM-ddTHH:mm:ss
                isoFormat.parse(cleanedString)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
