/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Storage for support messages and submitted bug reports.
 */
package org.denovogroup.rangzen.backend.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * SQLite-based storage for support messages and submitted bug reports.
 * Handles local persistence for the bug report messaging system.
 */
class SupportStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "support.db"
        private const val DATABASE_VERSION = 1

        // Support messages table (replies from server)
        private const val TABLE_MESSAGES = "support_messages"
        private const val COL_MSG_ID = "id"
        private const val COL_MSG_MESSAGE = "message"
        private const val COL_MSG_REPORT_ID = "report_id"
        private const val COL_MSG_READ_AT = "read_at"
        private const val COL_MSG_CREATED_AT = "created_at"

        // Submitted bug reports table
        private const val TABLE_REPORTS = "submitted_bug_reports"
        private const val COL_RPT_ID = "id"
        private const val COL_RPT_CATEGORY = "category"
        private const val COL_RPT_DESCRIPTION = "description"
        private const val COL_RPT_STATUS = "status"
        private const val COL_RPT_CREATED_AT = "created_at"
        private const val COL_RPT_UPDATED_AT = "updated_at"

        @Volatile
        private var instance: SupportStore? = null

        fun getInstance(context: Context): SupportStore {
            return instance ?: synchronized(this) {
                instance ?: SupportStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _messages = MutableStateFlow<List<SupportMessage>>(emptyList())
    val messages: StateFlow<List<SupportMessage>> = _messages.asStateFlow()

    private val _reports = MutableStateFlow<List<SubmittedBugReport>>(emptyList())
    val reports: StateFlow<List<SubmittedBugReport>> = _reports.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        refreshAll()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create support messages table
        db.execSQL("""
            CREATE TABLE $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_MESSAGE TEXT NOT NULL,
                $COL_MSG_REPORT_ID TEXT,
                $COL_MSG_READ_AT INTEGER,
                $COL_MSG_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())

        // Create submitted bug reports table
        db.execSQL("""
            CREATE TABLE $TABLE_REPORTS (
                $COL_RPT_ID TEXT PRIMARY KEY,
                $COL_RPT_CATEGORY TEXT NOT NULL,
                $COL_RPT_DESCRIPTION TEXT NOT NULL,
                $COL_RPT_STATUS TEXT DEFAULT 'open',
                $COL_RPT_CREATED_AT INTEGER NOT NULL,
                $COL_RPT_UPDATED_AT INTEGER
            )
        """.trimIndent())

        // Index for looking up messages by report
        db.execSQL("CREATE INDEX idx_msg_report_id ON $TABLE_MESSAGES($COL_MSG_REPORT_ID)")

        Timber.d("Support database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    // ========== Support Messages ==========

    /**
     * Add or update a support message from the server.
     * Returns true if the message was new.
     */
    fun addMessage(message: SupportMessage): Boolean {
        val existing = getMessage(message.id)
        if (existing != null) {
            // Already exists - nothing to update
            return false
        }

        val values = ContentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_MSG_MESSAGE, message.message)
            put(COL_MSG_REPORT_ID, message.reportId)
            put(COL_MSG_READ_AT, message.readAt)
            put(COL_MSG_CREATED_AT, message.createdAt)
        }

        val id = writableDatabase.insert(TABLE_MESSAGES, null, values)
        if (id != -1L) {
            Timber.d("Support message added: ${message.id}")
            refreshMessages()
            return true
        }
        return false
    }

    /**
     * Add multiple messages from a sync response.
     * Returns the count of new messages added.
     */
    fun addMessages(messages: List<DeviceMessage>): Int {
        var newCount = 0
        messages.forEach { dm ->
            val supportMessage = SupportMessage(
                id = dm.id,
                message = dm.message,
                reportId = dm.reportId,
                readAt = null,
                createdAt = parseTimestamp(dm.createdAt)
            )
            if (addMessage(supportMessage)) {
                newCount++
            }
        }
        return newCount
    }

    /**
     * Get a message by ID.
     */
    fun getMessage(id: String): SupportMessage? {
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "$COL_MSG_ID = ?",
            arrayOf(id),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToMessage(it) else null
        }
    }

    /**
     * Get all messages, sorted by creation time (newest first).
     */
    fun getAllMessages(): List<SupportMessage> {
        val messages = mutableListOf<SupportMessage>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null, null, null, null, null,
            "$COL_MSG_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    /**
     * Get messages for a specific bug report (conversation thread).
     */
    fun getMessagesForReport(reportId: String): List<SupportMessage> {
        val messages = mutableListOf<SupportMessage>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "$COL_MSG_REPORT_ID = ?",
            arrayOf(reportId),
            null, null,
            "$COL_MSG_CREATED_AT ASC" // Oldest first for conversation
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    /**
     * Get standalone messages (no report_id).
     */
    fun getStandaloneMessages(): List<SupportMessage> {
        val messages = mutableListOf<SupportMessage>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "$COL_MSG_REPORT_ID IS NULL",
            null,
            null, null,
            "$COL_MSG_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    /**
     * Get unread message count.
     */
    fun getUnreadCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE $COL_MSG_READ_AT IS NULL",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Mark a message as read.
     */
    fun markMessageRead(id: String): Boolean {
        val values = ContentValues().apply {
            put(COL_MSG_READ_AT, System.currentTimeMillis())
        }
        val rows = writableDatabase.update(
            TABLE_MESSAGES,
            values,
            "$COL_MSG_ID = ?",
            arrayOf(id)
        )
        if (rows > 0) {
            refreshMessages()
            return true
        }
        return false
    }

    /**
     * Check if there are any unread messages for a specific report.
     */
    fun hasUnreadForReport(reportId: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_MESSAGES WHERE $COL_MSG_REPORT_ID = ? AND $COL_MSG_READ_AT IS NULL",
            arrayOf(reportId)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) > 0 else false
        }
    }

    // ========== Submitted Bug Reports ==========

    /**
     * Store a submitted bug report locally.
     */
    fun addReport(report: SubmittedBugReport): Boolean {
        val existing = getReport(report.id)
        if (existing != null) {
            return false
        }

        val values = ContentValues().apply {
            put(COL_RPT_ID, report.id)
            put(COL_RPT_CATEGORY, report.category)
            put(COL_RPT_DESCRIPTION, report.description)
            put(COL_RPT_STATUS, report.status)
            put(COL_RPT_CREATED_AT, report.createdAt)
            put(COL_RPT_UPDATED_AT, report.updatedAt)
        }

        val id = writableDatabase.insert(TABLE_REPORTS, null, values)
        if (id != -1L) {
            Timber.d("Bug report stored locally: ${report.id}")
            refreshReports()
            return true
        }
        return false
    }

    /**
     * Get a report by ID.
     */
    fun getReport(id: String): SubmittedBugReport? {
        val cursor = readableDatabase.query(
            TABLE_REPORTS,
            null,
            "$COL_RPT_ID = ?",
            arrayOf(id),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToReport(it) else null
        }
    }

    /**
     * Get all submitted reports, sorted by creation time (newest first).
     */
    fun getAllReports(): List<SubmittedBugReport> {
        val reports = mutableListOf<SubmittedBugReport>()
        val cursor = readableDatabase.query(
            TABLE_REPORTS,
            null, null, null, null, null,
            "$COL_RPT_CREATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                reports.add(cursorToReport(it))
            }
        }
        return reports
    }

    /**
     * Update a report's status.
     */
    fun updateReportStatus(id: String, status: String): Boolean {
        val values = ContentValues().apply {
            put(COL_RPT_STATUS, status)
            put(COL_RPT_UPDATED_AT, System.currentTimeMillis())
        }
        val rows = writableDatabase.update(
            TABLE_REPORTS,
            values,
            "$COL_RPT_ID = ?",
            arrayOf(id)
        )
        if (rows > 0) {
            refreshReports()
            return true
        }
        return false
    }

    // ========== Helpers ==========

    private fun cursorToMessage(cursor: Cursor): SupportMessage {
        return SupportMessage(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_ID)),
            message = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_MESSAGE)),
            reportId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_REPORT_ID)),
            readAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MSG_READ_AT)).takeIf { it > 0 },
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MSG_CREATED_AT))
        )
    }

    private fun cursorToReport(cursor: Cursor): SubmittedBugReport {
        return SubmittedBugReport(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_RPT_ID)),
            category = cursor.getString(cursor.getColumnIndexOrThrow(COL_RPT_CATEGORY)),
            description = cursor.getString(cursor.getColumnIndexOrThrow(COL_RPT_DESCRIPTION)),
            status = cursor.getString(cursor.getColumnIndexOrThrow(COL_RPT_STATUS)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_RPT_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_RPT_UPDATED_AT)).takeIf { it > 0 }
        )
    }

    private fun parseTimestamp(isoString: String): Long {
        return try {
            java.time.Instant.parse(isoString).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun refreshMessages() {
        _messages.value = getAllMessages()
        _unreadCount.value = getUnreadCount()
    }

    private fun refreshReports() {
        _reports.value = getAllReports()
    }

    private fun refreshAll() {
        refreshMessages()
        refreshReports()
    }
}

/**
 * A support message (reply from admin or server-initiated message).
 */
data class SupportMessage(
    val id: String,
    val message: String,
    val reportId: String?,  // null for standalone messages
    val readAt: Long?,      // null if unread
    val createdAt: Long
) {
    val isRead: Boolean get() = readAt != null
}

/**
 * A locally stored submitted bug report.
 */
data class SubmittedBugReport(
    val id: String,
    val category: String,
    val description: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long?
)
