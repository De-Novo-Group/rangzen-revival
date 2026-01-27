/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Message storage using SQLite for persistence
 */
package org.denovogroup.rangzen.backend

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.objects.RangzenMessage
import timber.log.Timber

/**
 * SQLite-based storage for Rangzen messages.
 * Handles persistence, querying, and message prioritization.
 */
class MessageStore private constructor(context: Context) : 
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "rangzen_messages.db"
        private const val DATABASE_VERSION = 3

        // Legacy trust bounds for safety.
        private const val MIN_TRUST = 0.01
        private const val MAX_TRUST = 1.0

        private const val TABLE_MESSAGES = "messages"
        private const val COL_ID = "_id"
        private const val COL_MESSAGE_ID = "message_id"
        private const val COL_TEXT = "text"
        private const val COL_TRUST_SCORE = "trust_score"
        private const val COL_LIKES = "likes"
        private const val COL_LIKED = "liked"
        private const val COL_PSEUDONYM = "pseudonym"
        private const val COL_TIMESTAMP = "timestamp"
        // Store local receipt time separately from composed time.
        private const val COL_RECEIVED_TIMESTAMP = "received_timestamp"
        private const val COL_READ = "read"
        private const val COL_HOP_COUNT = "hop_count"
        private const val COL_MIN_CONTACTS = "min_contacts"
        private const val COL_EXPIRATION = "expiration"
        private const val COL_LATLONG = "latlong"
        private const val COL_PARENT = "parent_id"
        private const val COL_BIGPARENT = "bigparent_id"

        // Store version for exchange backoff logic.
        private var storeVersion: String? = null

        /**
         * Enable priority-based exchange ordering (hearts integration).
         * When true (default), messages are sorted by combinedPriority() before sending.
         * When false, messages are sent in insertion order (legacy behavior).
         */
        @Volatile
        var usePriorityOrdering = true

        // Rate limit: max hearts per hour per device (prevents gaming)
        private const val HEARTS_RATE_LIMIT = 5
        private const val HEARTS_RATE_WINDOW_MS = 60 * 60 * 1000L // 1 hour
        private val recentHeartTimestamps = mutableListOf<Long>()

        @Volatile
        private var instance: MessageStore? = null

        fun getInstance(context: Context): MessageStore {
            return instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Check if heart rate limit has been reached.
         * Returns true if user has hearted too many messages in the last hour.
         */
        private fun isHeartRateLimited(): Boolean {
            val now = System.currentTimeMillis()
            val cutoff = now - HEARTS_RATE_WINDOW_MS

            synchronized(recentHeartTimestamps) {
                // Clean old timestamps
                recentHeartTimestamps.removeAll { it < cutoff }
                return recentHeartTimestamps.size >= HEARTS_RATE_LIMIT
            }
        }

        /**
         * Record a heart action for rate limiting.
         */
        private fun recordHeartAction() {
            synchronized(recentHeartTimestamps) {
                recentHeartTimestamps.add(System.currentTimeMillis())
            }
        }
    }

    private val _messages = MutableStateFlow<List<RangzenMessage>>(emptyList())
    val messages: StateFlow<List<RangzenMessage>> = _messages.asStateFlow()

    init {
        // Load existing messages into the flow at startup.
        // This keeps the feed in sync even before new inserts occur.
        refreshMessages()
    }

    init {
        // Load existing messages into the flow at startup.
        // This keeps the feed in sync even before new inserts occur.
        refreshMessages()
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MESSAGE_ID TEXT UNIQUE NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_TRUST_SCORE REAL DEFAULT 0.5,
                $COL_LIKES INTEGER DEFAULT 0,
                $COL_LIKED INTEGER DEFAULT 0,
                $COL_PSEUDONYM TEXT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_RECEIVED_TIMESTAMP INTEGER NOT NULL,
                $COL_READ INTEGER DEFAULT 0,
                $COL_HOP_COUNT INTEGER DEFAULT 0,
                $COL_MIN_CONTACTS INTEGER DEFAULT 0,
                $COL_EXPIRATION INTEGER DEFAULT 0,
                $COL_LATLONG TEXT,
                $COL_PARENT TEXT,
                $COL_BIGPARENT TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)

        // Index for fast lookups
        db.execSQL("CREATE INDEX idx_message_id ON $TABLE_MESSAGES($COL_MESSAGE_ID)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_MESSAGES($COL_TIMESTAMP)")

        Timber.d("Message database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_LATLONG TEXT")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_PARENT TEXT")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_BIGPARENT TEXT")
        }
        if (oldVersion < 3) {
            // Add a received timestamp column for local receipt tracking.
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_RECEIVED_TIMESTAMP INTEGER NOT NULL DEFAULT 0")
            // Backfill received timestamps with the composed timestamp when missing.
            db.execSQL("UPDATE $TABLE_MESSAGES SET $COL_RECEIVED_TIMESTAMP = $COL_TIMESTAMP WHERE $COL_RECEIVED_TIMESTAMP = 0")
        }
    }

    /**
     * Add a new message to the store, or merge hearts if it already exists.
     * 
     * When the message already exists, we merge the heart count (Casific's "endorsement")
     * by taking max(local, received) to preserve the highest count from either source.
     * This follows Casific's behavior in computeNewPriority.
     * 
     * Returns true if added or merged, false on error.
     */
    fun addMessage(message: RangzenMessage): Boolean {
        // Check for existing message to handle merge case
        val existing = getMessage(message.messageId)
        if (existing != null) {
            // Message exists - merge hearts by taking max(local, received)
            // This ensures endorsements propagate and we don't lose hearts.
            val mergedHearts = maxOf(existing.likes, message.likes)
            if (mergedHearts > existing.likes) {
                // Received message has more hearts - update local copy
                Timber.d("Merging hearts for ${message.messageId}: local=${existing.likes}, received=${message.likes}, merged=$mergedHearts")
                val values = ContentValues().apply {
                    put(COL_LIKES, mergedHearts)
                }
                writableDatabase.update(
                    TABLE_MESSAGES,
                    values,
                    "$COL_MESSAGE_ID = ?",
                    arrayOf(message.messageId)
                )
                refreshMessages()
                return true
            }
            // Local has equal or more hearts - no update needed
            Timber.d("Message exists with equal/higher hearts: ${message.messageId} (local=${existing.likes}, received=${message.likes})")
            return false
        }

        // New message - insert it
        // Capture the local receipt time for this insert.
        val receivedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_MESSAGE_ID, message.messageId)
            put(COL_TEXT, message.text)
            // Clamp trust to legacy bounds to avoid invalid values.
            put(COL_TRUST_SCORE, clampTrust(message.trustScore))
            // Hearts (Casific's "endorsement") - stored in COL_LIKES, comes from priority
            put(COL_LIKES, message.likes)
            put(COL_LIKED, if (message.isLiked) 1 else 0)
            put(COL_PSEUDONYM, message.pseudonym)
            // Persist composed time separately from receipt time.
            put(COL_TIMESTAMP, message.timestamp)
            // Persist receipt time, defaulting to "now" when missing.
            put(COL_RECEIVED_TIMESTAMP, if (message.receivedTimestamp > 0) message.receivedTimestamp else receivedAt)
            put(COL_READ, if (message.isRead) 1 else 0)
            put(COL_HOP_COUNT, message.hopCount)
            put(COL_MIN_CONTACTS, message.minContactsForHop)
            put(COL_EXPIRATION, message.expirationTime)
            if (message.latLong != null) put(COL_LATLONG, message.latLong)
            if (message.parentId != null) put(COL_PARENT, message.parentId)
            if (message.bigParentId != null) put(COL_BIGPARENT, message.bigParentId)
        }

        val id = writableDatabase.insert(TABLE_MESSAGES, null, values)
        if (id != -1L) {
            // Update store version to signal new data.
            updateStoreVersion()
            refreshMessages()
            Timber.d("Message added: ${message.messageId}")
            return true
        }
        return false
    }

    /**
     * Check if a message exists in the store.
     */
    fun hasMessage(messageId: String): Boolean {
        return getMessage(messageId) != null
    }

    /**
     * Get a message by its ID.
     */
    fun getMessage(messageId: String): RangzenMessage? {
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "$COL_MESSAGE_ID = ?",
            arrayOf(messageId),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                cursorToMessage(it)
            } else {
                null
            }
        }
    }

    /**
     * Get all messages, sorted by timestamp (newest first).
     */
    fun getAllMessages(): List<RangzenMessage> {
        val messages = mutableListOf<RangzenMessage>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null, null, null, null, null,
            "$COL_TIMESTAMP DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                val message = cursorToMessage(it)
                // Filter out expired messages
                if (!message.isExpired()) {
                    messages.add(message)
                }
            }
        }

        return messages
    }

    /**
     * Get messages for exchange with a peer.
     *
     * When [usePriorityOrdering] is enabled, messages are sorted by combinedPriority()
     * (trust + recency + hearts). Otherwise, uses insertion order (legacy behavior).
     *
     * @param mutualFriends Number of mutual friends with the peer
     * @param limit Maximum number of messages to return
     */
    fun getMessagesForExchange(mutualFriends: Int, limit: Int = 100): List<RangzenMessage> {
        val messages = mutableListOf<RangzenMessage>()
        val now = System.currentTimeMillis()

        // Filter by hop/minContacts and expiration
        val selection =
            "(($COL_HOP_COUNT = 0 AND $COL_MIN_CONTACTS > 0 AND $COL_MIN_CONTACTS <= ?) " +
                "OR ($COL_MIN_CONTACTS <= 0)) " +
                "AND ($COL_EXPIRATION = 0 OR $COL_EXPIRATION > ?)"
        val args = arrayOf(mutualFriends.toString(), now.toString())

        // When priority ordering is enabled, fetch more messages so we can sort and pick top N
        val fetchLimit = if (usePriorityOrdering) limit * 3 else limit

        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            selection,
            args,
            null,
            null,
            "$COL_ID DESC",  // Initial fetch by recency
            fetchLimit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }

        // Log priority distribution for telemetry
        if (messages.isNotEmpty()) {
            val priorities = messages.map { it.combinedPriority() }
            val heartsCount = messages.count { it.likes > 0 }
            val lowTrustCount = messages.count { it.trustScore < 0.3 }

            Timber.d(
                "Exchange priority distribution: min=%.3f, max=%.3f, mean=%.3f, " +
                    "count=%d, hearts>0=%d, trust<0.3=%d, priorityOrdering=%s",
                priorities.minOrNull() ?: 0.0,
                priorities.maxOrNull() ?: 0.0,
                priorities.average(),
                messages.size,
                heartsCount,
                lowTrustCount,
                usePriorityOrdering
            )

            TelemetryClient.getInstance()?.trackPriorityDistribution(
                priorities = priorities,
                heartsCount = heartsCount,
                lowTrustCount = lowTrustCount,
                messageCount = messages.size
            )
        }

        // Sort by combinedPriority if enabled, then take top N
        return if (usePriorityOrdering && messages.isNotEmpty()) {
            Timber.d("Sorting ${messages.size} messages by combinedPriority, returning top $limit")
            messages.sortedByDescending { it.combinedPriority() }.take(limit)
        } else {
            messages.take(limit)
        }
    }

    /**
     * Update a message's heart (Casific's "endorsement") status.
     *
     * This is idempotent: only changes the count (+1/-1) when the liked state
     * actually changes. Repeated taps do not inflate the count.
     *
     * Rate limited: Max 5 hearts per hour to prevent gaming.
     *
     * @param messageId The message to heart/unheart
     * @param liked True to heart, false to unheart
     * @return True if state changed, false if rate limited, already in target state, or error
     */
    fun likeMessage(messageId: String, liked: Boolean): Boolean {
        // Rate limit adding hearts (not removing)
        if (liked && isHeartRateLimited()) {
            Timber.w("Heart rate limited - max $HEARTS_RATE_LIMIT hearts per hour reached")
            return false
        }

        // Get current message state
        val message = getMessage(messageId) ?: return false

        // Check if message is already in the target state (idempotent)
        if (message.isLiked == liked) {
            Timber.d("Heart already in target state for ${messageId}: liked=$liked")
            return false
        }

        // State is changing - update liked flag and adjust heart count
        val newHearts = if (liked) {
            message.likes + 1
        } else {
            maxOf(0, message.likes - 1)
        }

        val values = ContentValues().apply {
            put(COL_LIKED, if (liked) 1 else 0)
            put(COL_LIKES, newHearts)
        }

        val rows = writableDatabase.update(
            TABLE_MESSAGES,
            values,
            "$COL_MESSAGE_ID = ?",
            arrayOf(messageId)
        )

        if (rows > 0) {
            // Track heart action for rate limiting
            if (liked) {
                recordHeartAction()
            }
            Timber.d("Heart toggled for ${messageId}: liked=$liked, hearts=$newHearts")
            refreshMessages()
            return true
        }
        return false
    }

    /**
     * Mark a message as read.
     */
    fun markAsRead(messageId: String): Boolean {
        val values = ContentValues().apply {
            put(COL_READ, 1)
        }

        val rows = writableDatabase.update(
            TABLE_MESSAGES,
            values,
            "$COL_MESSAGE_ID = ?",
            arrayOf(messageId)
        )

        if (rows > 0) {
            refreshMessages()
            return true
        }
        return false
    }

    /**
     * Delete expired messages.
     */
    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        val rows = writableDatabase.delete(
            TABLE_MESSAGES,
            "$COL_EXPIRATION > 0 AND $COL_EXPIRATION < ?",
            arrayOf(now.toString())
        )

        if (rows > 0) {
            refreshMessages()
            Timber.d("Cleaned up $rows expired messages")
        }
        return rows
    }

    /**
     * Get count of unread messages.
     */
    fun getUnreadCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE $COL_READ = 0",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Update the trust score for a message if it exists.
     */
    fun updateTrustScore(messageId: String, trustScore: Double): Boolean {
        val values = ContentValues().apply {
            // Clamp trust to legacy bounds to avoid invalid values.
            put(COL_TRUST_SCORE, clampTrust(trustScore))
        }
        val rows = writableDatabase.update(
            TABLE_MESSAGES,
            values,
            "$COL_MESSAGE_ID = ?",
            arrayOf(messageId)
        )
        if (rows > 0) {
            refreshMessages()
            return true
        }
        return false
    }

    /**
     * Remove outdated or irrelevant messages using legacy rules.
     */
    fun deleteOutdatedOrIrrelevant(
        autodeleteEnabled: Boolean,
        autodeleteTrustThreshold: Double,
        autodeleteAgeDays: Int
    ) {
        // Exit early when auto-delete is disabled.
        if (!autodeleteEnabled) return
        // Compute the age threshold in millis.
        val ageThreshold = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(
            autodeleteAgeDays.toLong()
        )
        // Build the delete predicate: low trust, too old, or expired.
        val whereClause =
            "$COL_TRUST_SCORE <= ? OR " +
                "($COL_TIMESTAMP > 0 AND $COL_TIMESTAMP < ?) OR " +
                "($COL_EXPIRATION > 0 AND $COL_TIMESTAMP > 0 AND ($COL_EXPIRATION + $COL_TIMESTAMP) < ?)"
        // Bind args for the delete clause.
        val args = arrayOf(
            autodeleteTrustThreshold.toString(),
            ageThreshold.toString(),
            System.currentTimeMillis().toString()
        )
        // Execute the delete query.
        val rows = writableDatabase.delete(TABLE_MESSAGES, whereClause, args)
        // Refresh flows if anything was removed.
        if (rows > 0) {
            refreshMessages()
            Timber.d("Auto-delete removed $rows messages")
        }
    }

    /**
     * Return the current store version.
     */
    fun getStoreVersion(): String {
        // Initialize lazily to avoid unnecessary UUID creation.
        if (storeVersion == null) {
            updateStoreVersion()
        }
        return storeVersion!!
    }

    /**
     * Randomize a version code for the store and set it.
     */
    fun updateStoreVersion() {
        // Generate a random UUID to represent store changes.
        storeVersion = java.util.UUID.randomUUID().toString()
    }

    /**
     * Get total message count.
     */
    fun getMessageCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Refresh the messages flow with current data.
     */
    private fun refreshMessages() {
        _messages.value = getAllMessages()
    }

    /**
     * Force a refresh for UI pull-to-refresh actions.
     */
    fun refreshMessagesNow() {
        // Refresh immediately to notify observers even if no new data arrived.
        refreshMessages()
    }

    /**
     * Convert a cursor row to a RangzenMessage object.
     */
    private fun cursorToMessage(cursor: android.database.Cursor): RangzenMessage {
        return RangzenMessage().apply {
            messageId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESSAGE_ID))
            text = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT))
            trustScore = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_TRUST_SCORE))
            likes = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LIKES))
            isLiked = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LIKED)) == 1
            pseudonym = cursor.getString(cursor.getColumnIndexOrThrow(COL_PSEUDONYM))
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
            // Read the local receipt time for UI display.
            receivedTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_RECEIVED_TIMESTAMP))
            isRead = cursor.getInt(cursor.getColumnIndexOrThrow(COL_READ)) == 1
            hopCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HOP_COUNT))
            minContactsForHop = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MIN_CONTACTS))
            expirationTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_EXPIRATION))
            latLong = cursor.getString(cursor.getColumnIndexOrThrow(COL_LATLONG))
            parentId = cursor.getString(cursor.getColumnIndexOrThrow(COL_PARENT))
            bigParentId = cursor.getString(cursor.getColumnIndexOrThrow(COL_BIGPARENT))
        }
    }

    /**
     * Clamp trust to legacy bounds.
     */
    private fun clampTrust(value: Double): Double {
        // Enforce minimum trust.
        if (value < MIN_TRUST) return MIN_TRUST
        // Enforce maximum trust.
        if (value > MAX_TRUST) return MAX_TRUST
        // Value is within bounds.
        return value
    }
}
