package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson

/**
 * MessageStore — Persistance des messages reçus et des messages en attente (store-and-forward).
 */
class MessageStore(context: Context) {

    private val TAG = "MessageStore"
    private val db: SQLiteDatabase
    private val gson = Gson()

    companion object {
        private const val DB_NAME = "mesh_messages.db"
        private const val DB_VERSION = 4
        private const val TABLE = "messages"
        private const val PENDING_TABLE = "pending_messages"
        private const val MAX_MESSAGES = 1000
    }

    init {
        db = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
                // Table des messages reçus/stockés
                db.execSQL("""
                    CREATE TABLE $TABLE (
                        id TEXT PRIMARY KEY,
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL,
                        is_mine INTEGER NOT NULL DEFAULT 0,
                        time TEXT NOT NULL,
                        status TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'MESSAGE',
                        receiver_id TEXT NOT NULL DEFAULT 'TOUS',
                        sender_id_raw TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL,
                        audio_path TEXT,
                        file_path TEXT,
                        file_name TEXT,
                        file_size INTEGER NOT NULL DEFAULT 0,
                        reply_to_id TEXT,
                        reply_to_content TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp DESC)")
                db.execSQL("CREATE INDEX idx_sender ON $TABLE(sender_id_raw)")

                // Table des messages en attente (store-and-forward)
                db.execSQL("""
                    CREATE TABLE $PENDING_TABLE (
                        id TEXT PRIMARY KEY,
                        packet_json TEXT NOT NULL,
                        destination_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        ttl INTEGER NOT NULL,
                        retry_count INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_pending_dest ON $PENDING_TABLE(destination_id)")
                db.execSQL("CREATE INDEX idx_pending_created ON $PENDING_TABLE(created_at)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) {
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_path TEXT")
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_name TEXT")
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0")
                }
                if (oldVersion < 3) {
                    db.execSQL("ALTER TABLE $TABLE ADD COLUMN audio_path TEXT")
                }
                if (oldVersion < 4) {
                    db.execSQL("""
                        CREATE TABLE $PENDING_TABLE (
                            id TEXT PRIMARY KEY,
                            packet_json TEXT NOT NULL,
                            destination_id TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            ttl INTEGER NOT NULL,
                            retry_count INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX idx_pending_dest ON $PENDING_TABLE(destination_id)")
                    db.execSQL("CREATE INDEX idx_pending_created ON $PENDING_TABLE(created_at)")
                    // Ajout des colonnes reply dans messages si besoin
                    try {
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN reply_to_id TEXT")
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN reply_to_content TEXT")
                    } catch (e: Exception) { }
                }
            }
        }.writableDatabase

        Log.i(TAG, "✅ MessageStore initialisé (${countMessages()} messages, ${countPending()} en attente)")
    }

    // ==================== MESSAGES REÇUS/ENVOYÉS ====================

    fun save(msg: ChatMessage) {
        try {
            val cv = ContentValues().apply {
                put("id", msg.id)
                put("sender", msg.sender)
                put("content", msg.content)
                put("is_mine", if (msg.isMine) 1 else 0)
                put("time", msg.time)
                put("status", msg.status.name)
                put("type", msg.type.name)
                put("receiver_id", msg.receiverId)
                put("sender_id_raw", msg.senderIdRaw)
                put("timestamp", msg.timestamp)
                put("audio_path", msg.audioPath)
                put("file_path", msg.filePath)
                put("file_name", msg.fileName)
                put("file_size", msg.fileSize)
                put("reply_to_id", msg.replyToId)
                put("reply_to_content", msg.replyToContent)
            }
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            val count = countMessages()
            if (count > MAX_MESSAGES) {
                pruneOldest(count - MAX_MESSAGES)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur save: ${e.message}", e)
        }
    }

    fun updateStatus(id: String, status: MessageStatus) {
        try {
            val cv = ContentValues().apply { put("status", status.name) }
            db.update(TABLE, cv, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur updateStatus: ${e.message}", e)
        }
    }

    fun delete(id: String) {
        try {
            db.delete(TABLE, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur delete: ${e.message}", e)
        }
    }

    fun clearAll() {
        try {
            db.delete(TABLE, null, null)
            Log.i(TAG, "🗑️ Tous les messages supprimés")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur clearAll: ${e.message}", e)
        }
    }

    fun loadRecent(limit: Int = 200): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        try {
            val cursor = db.query(
                TABLE, null, null, null, null, null,
                "timestamp DESC", limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    fromCursor(it)?.let { msg -> result.add(msg) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur loadRecent: ${e.message}", e)
        }
        return result.reversed()
    }

    fun loadForChannel(channelId: String, limit: Int = 100): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        try {
            val selection = if (channelId == "TOUS") {
                "receiver_id = 'TOUS'"
            } else {
                "receiver_id = ? OR sender_id_raw = ?"
            }
            val args = if (channelId == "TOUS") null else arrayOf(channelId, channelId)
            val cursor = db.query(TABLE, null, selection, args, null, null, "timestamp DESC", limit.toString())
            cursor.use {
                while (it.moveToNext()) {
                    fromCursor(it)?.let { msg -> result.add(msg) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur loadForChannel: ${e.message}", e)
        }
        return result.reversed()
    }

    fun search(query: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        try {
            val cursor = db.query(
                TABLE, null,
                "content LIKE ?", arrayOf("%$query%"),
                null, null, "timestamp DESC", "50"
            )
            cursor.use {
                while (it.moveToNext()) {
                    fromCursor(it)?.let { msg -> result.add(msg) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur search: ${e.message}", e)
        }
        return result
    }

    fun countMessages(): Int {
        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    private fun fromCursor(cursor: android.database.Cursor): ChatMessage? {
        return try {
            val type = try {
                PacketType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type")))
            } catch (e: Exception) { PacketType.MESSAGE }

            val status = try {
                MessageStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
            } catch (e: Exception) { MessageStatus.RECEIVED }

            ChatMessage(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                isMine = cursor.getInt(cursor.getColumnIndexOrThrow("is_mine")) == 1,
                time = cursor.getString(cursor.getColumnIndexOrThrow("time")),
                status = status,
                type = type,
                receiverId = cursor.getString(cursor.getColumnIndexOrThrow("receiver_id")),
                senderIdRaw = cursor.getString(cursor.getColumnIndexOrThrow("sender_id_raw")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                replyToId = cursor.getString(cursor.getColumnIndexOrThrow("reply_to_id")),
                replyToContent = cursor.getString(cursor.getColumnIndexOrThrow("reply_to_content"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "fromCursor: ${e.message}")
            null
        }
    }

    private fun pruneOldest(count: Int) {
        try {
            db.execSQL(
                "DELETE FROM $TABLE WHERE id IN (SELECT id FROM $TABLE ORDER BY timestamp ASC LIMIT $count)"
            )
            Log.d(TAG, "🧹 $count anciens messages supprimés")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pruneOldest: ${e.message}", e)
        }
    }

    // ==================== PENDING MESSAGES (STORE-AND-FORWARD) ====================

    fun savePending(packet: MeshPacket, destinationId: String, ttlSeconds: Int = 300) {
        try {
            val cv = ContentValues().apply {
                put("id", packet.id)
                put("packet_json", gson.toJson(packet))
                put("destination_id", destinationId)
                put("created_at", System.currentTimeMillis())
                put("ttl", ttlSeconds * 1000L)
                put("retry_count", 0)
            }
            db.insertWithOnConflict(PENDING_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            Log.i(TAG, "📦 Message en attente pour $destinationId (TTL ${ttlSeconds}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur savePending: ${e.message}", e)
        }
    }

    fun loadPendingForDestination(destinationId: String): List<MeshPacket> {
        val result = mutableListOf<MeshPacket>()
        try {
            val cursor = db.query(
                PENDING_TABLE, null,
                "destination_id = ?", arrayOf(destinationId),
                null, null, "created_at ASC"
            )
            cursor.use {
                while (it.moveToNext()) {
                    val json = it.getString(it.getColumnIndexOrThrow("packet_json"))
                    gson.fromJson(json, MeshPacket::class.java)?.let { result.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur loadPendingForDestination: ${e.message}", e)
        }
        return result
    }

    fun loadAllPending(): List<Pair<MeshPacket, String>> {
        val result = mutableListOf<Pair<MeshPacket, String>>()
        try {
            val cursor = db.query(PENDING_TABLE, null, null, null, null, null, "created_at ASC")
            cursor.use {
                while (it.moveToNext()) {
                    val json = it.getString(it.getColumnIndexOrThrow("packet_json"))
                    val dest = it.getString(it.getColumnIndexOrThrow("destination_id"))
                    gson.fromJson(json, MeshPacket::class.java)?.let { result.add(it to dest) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur loadAllPending: ${e.message}", e)
        }
        return result
    }

    fun deletePending(packetId: String) {
        try {
            db.delete(PENDING_TABLE, "id = ?", arrayOf(packetId))
            Log.d(TAG, "🗑️ Message en attente supprimé: $packetId")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur deletePending: ${e.message}", e)
        }
    }

    fun updatePendingRetry(packetId: String) {
        try {
            db.execSQL("UPDATE $PENDING_TABLE SET retry_count = retry_count + 1 WHERE id = ?", arrayOf(packetId))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur updatePendingRetry: ${e.message}", e)
        }
    }

    fun cleanupExpiredPending() {
        try {
            val now = System.currentTimeMillis()
            val deleted = db.delete(PENDING_TABLE, "created_at + ttl < ?", arrayOf(now.toString()))
            if (deleted > 0) Log.d(TAG, "🧹 $deleted messages en attente expirés supprimés")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur cleanupExpiredPending: ${e.message}", e)
        }
    }

    fun countPending(): Int {
        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $PENDING_TABLE", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    fun close() {
        try { db.close() } catch (e: Exception) { }
    }
}