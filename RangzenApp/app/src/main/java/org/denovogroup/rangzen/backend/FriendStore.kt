/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Friend storage for the social trust graph used in PSI-Ca
 */
package org.denovogroup.rangzen.backend

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.DHPrivateKeyParameters
import org.bouncycastle.crypto.params.DHPublicKeyParameters
import timber.log.Timber

/**
 * Storage for the user's friends list and identity.
 * 
 * Friends are stored as public keys (byte arrays) and are used in the
 * PSI-Ca protocol to compute mutual friend counts without revealing
 * who those friends are.
 */
class FriendStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "rangzen_friends.db"
        private const val DATABASE_VERSION = 1

        // Friends table
        private const val TABLE_FRIENDS = "friends"
        private const val COL_ID = "_id"
        private const val COL_PUBLIC_ID = "public_id"  // Base64-encoded public key
        private const val COL_NICKNAME = "nickname"     // User-assigned name
        private const val COL_ADDED_AT = "added_at"     // Timestamp when added

        // Identity table (stores our own keys)
        private const val TABLE_IDENTITY = "identity"
        private const val COL_KEY_TYPE = "key_type"
        private const val COL_KEY_DATA = "key_data"

        private const val KEY_PUBLIC = "public"
        private const val KEY_PRIVATE = "private"

        @Volatile
        private var instance: FriendStore? = null

        fun getInstance(context: Context): FriendStore {
            return instance ?: synchronized(this) {
                instance ?: FriendStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        // Friends table
        db.execSQL("""
            CREATE TABLE $TABLE_FRIENDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PUBLIC_ID TEXT UNIQUE NOT NULL,
                $COL_NICKNAME TEXT,
                $COL_ADDED_AT INTEGER NOT NULL
            )
        """.trimIndent())

        // Identity table
        db.execSQL("""
            CREATE TABLE $TABLE_IDENTITY (
                $COL_KEY_TYPE TEXT PRIMARY KEY,
                $COL_KEY_DATA TEXT NOT NULL
            )
        """.trimIndent())

        Timber.d("Friend database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FRIENDS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_IDENTITY")
        onCreate(db)
    }

    /**
     * Initialize or retrieve the user's identity keypair.
     */
    fun getOrCreateIdentity(): AsymmetricCipherKeyPair {
        // Check if we already have keys.
        val publicKey = getStoredKey(KEY_PUBLIC)
        val privateKey = getStoredKey(KEY_PRIVATE)

        if (publicKey != null && privateKey != null) {
            try {
                // Reconstruct keypair from stored keys.
                val pubKeyParams = Crypto.decodeDHPublicKey(publicKey)
                // Reconstruct private key from stored bytes.
                val privKeyParams = Crypto.decodeDHPrivateKey(privateKey)
                // Return the restored keypair.
                return AsymmetricCipherKeyPair(pubKeyParams, privKeyParams)
            } catch (e: Exception) {
                // Log the failure so we don't silently hide invalid keys.
                Timber.e(e, "Stored identity keys invalid; regenerating")
                // Clear stored keys to avoid repeated failures.
                clearStoredIdentity()
            }
        }

        // Generate new identity.
        val keypair = Crypto.generateUserID()
        if (keypair != null) {
            // Store the keys.
            storeKey(KEY_PUBLIC, Crypto.generatePublicID(keypair))
            storeKey(KEY_PRIVATE, Crypto.generatePrivateID(keypair))
            Timber.i("New identity generated and stored")
        }

        return keypair!!
    }

    /**
     * Remove any stored identity keys after decode failure.
     */
    private fun clearStoredIdentity() {
        // Delete any identity rows to force regeneration.
        val rows = writableDatabase.delete(TABLE_IDENTITY, null, null)
        // Log how many rows were cleared.
        Timber.w("Cleared $rows stored identity rows")
    }

    /**
     * Get the user's public ID as bytes (for sharing via QR code).
     */
    fun getMyPublicId(): ByteArray? {
        return getStoredKey(KEY_PUBLIC)
    }

    /**
     * Get the user's public ID as a Base64 string (for QR codes).
     */
    fun getMyPublicIdString(): String? {
        return getStoredKey(KEY_PUBLIC)?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    /**
     * Store a key in the identity table.
     */
    private fun storeKey(keyType: String, keyData: ByteArray?) {
        if (keyData == null) return

        val values = ContentValues().apply {
            put(COL_KEY_TYPE, keyType)
            put(COL_KEY_DATA, Base64.encodeToString(keyData, Base64.NO_WRAP))
        }

        writableDatabase.insertWithOnConflict(
            TABLE_IDENTITY, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Retrieve a key from the identity table.
     */
    private fun getStoredKey(keyType: String): ByteArray? {
        val cursor = readableDatabase.query(
            TABLE_IDENTITY,
            arrayOf(COL_KEY_DATA),
            "$COL_KEY_TYPE = ?",
            arrayOf(keyType),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val base64 = it.getString(0)
                Base64.decode(base64, Base64.NO_WRAP)
            } else {
                null
            }
        }
    }

    /**
     * Add a friend by their public ID.
     * 
     * @param publicId The friend's public key bytes
     * @param nickname Optional nickname for the friend
     * @return true if added, false if already exists
     */
    fun addFriend(publicId: ByteArray, nickname: String? = null): Boolean {
        val base64Id = Base64.encodeToString(publicId, Base64.NO_WRAP)

        // Check for duplicate
        if (hasFriend(base64Id)) {
            Timber.d("Friend already exists")
            return false
        }

        val values = ContentValues().apply {
            put(COL_PUBLIC_ID, base64Id)
            put(COL_NICKNAME, nickname)
            put(COL_ADDED_AT, System.currentTimeMillis())
        }

        val id = writableDatabase.insert(TABLE_FRIENDS, null, values)
        if (id != -1L) {
            refreshFriends()
            Timber.i("Friend added: ${nickname ?: base64Id.take(8)}...")
            return true
        }
        return false
    }

    /**
     * Add a friend from a Base64-encoded public ID string.
     */
    fun addFriendFromString(publicIdBase64: String, nickname: String? = null): Boolean {
        return try {
            val publicId = Base64.decode(publicIdBase64, Base64.NO_WRAP)
            addFriend(publicId, nickname)
        } catch (e: Exception) {
            Timber.e(e, "Invalid public ID format")
            false
        }
    }

    /**
     * Check if we have a friend with this public ID.
     */
    fun hasFriend(publicIdBase64: String): Boolean {
        val cursor = readableDatabase.query(
            TABLE_FRIENDS,
            arrayOf(COL_ID),
            "$COL_PUBLIC_ID = ?",
            arrayOf(publicIdBase64),
            null, null, null
        )
        return cursor.use { it.count > 0 }
    }

    /**
     * Remove a friend by their public ID.
     */
    fun removeFriend(publicIdBase64: String): Boolean {
        val rows = writableDatabase.delete(
            TABLE_FRIENDS,
            "$COL_PUBLIC_ID = ?",
            arrayOf(publicIdBase64)
        )
        if (rows > 0) {
            refreshFriends()
            return true
        }
        return false
    }

    /**
     * Get all friends' public IDs as byte arrays.
     * Used for PSI-Ca computation.
     */
    fun getAllFriendIds(): ArrayList<ByteArray> {
        val ids = ArrayList<ByteArray>()
        val cursor = readableDatabase.query(
            TABLE_FRIENDS,
            arrayOf(COL_PUBLIC_ID),
            null, null, null, null, null
        )

        cursor.use {
            while (it.moveToNext()) {
                val base64 = it.getString(0)
                ids.add(Base64.decode(base64, Base64.NO_WRAP))
            }
        }

        return ids
    }

    /**
     * Get all friends as Friend objects.
     */
    fun getAllFriends(): List<Friend> {
        val friends = mutableListOf<Friend>()
        val cursor = readableDatabase.query(
            TABLE_FRIENDS,
            null, null, null, null, null,
            "$COL_ADDED_AT DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                friends.add(Friend(
                    publicId = it.getString(it.getColumnIndexOrThrow(COL_PUBLIC_ID)),
                    nickname = it.getString(it.getColumnIndexOrThrow(COL_NICKNAME)),
                    addedAt = it.getLong(it.getColumnIndexOrThrow(COL_ADDED_AT))
                ))
            }
        }

        return friends
    }

    /**
     * Update a friend's nickname.
     */
    fun updateNickname(publicIdBase64: String, nickname: String): Boolean {
        val values = ContentValues().apply {
            put(COL_NICKNAME, nickname)
        }

        val rows = writableDatabase.update(
            TABLE_FRIENDS,
            values,
            "$COL_PUBLIC_ID = ?",
            arrayOf(publicIdBase64)
        )

        if (rows > 0) {
            refreshFriends()
            return true
        }
        return false
    }

    /**
     * Get friend count.
     */
    fun getFriendCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_FRIENDS",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Refresh the friends flow.
     */
    private fun refreshFriends() {
        _friends.value = getAllFriends()
    }
}

/**
 * Data class representing a friend.
 */
data class Friend(
    val publicId: String,   // Base64-encoded public key
    val nickname: String?,  // User-assigned name
    val addedAt: Long       // When this friend was added
) {
    /**
     * Get display name (nickname or truncated ID).
     */
    fun getDisplayName(): String {
        return nickname ?: "${publicId.take(8)}..."
    }
}
