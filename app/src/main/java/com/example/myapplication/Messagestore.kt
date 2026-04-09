package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File

/**
 * MessageStore — Persistance des messages sur le stockage interne.
 * Utilise SQLite pour stocker les messages.
 */
class MessageStore(context: Context) {

    private val TAG = "MessageStore"
    private val db: SQLiteDatabase

    companion object {
        private const val DB_NAME = "mesh_messages.db"
        private const val DB_VERSION = 3
        private const val TABLE = "messages"
        private const val MAX_MESSAGES = 1000
    }

    init {
        db = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
            override fun onCreate(db: SQLiteDatabase) {
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
                        file_size INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp DESC)")
                db.execSQL("CREATE INDEX idx_sender ON $TABLE(sender_id_raw)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) {
                    try {
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_path TEXT")
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_name TEXT")
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0")
                    } catch (e: Exception) { }
                }
                if (oldVersion < 3) {
                    try {
                        db.execSQL("ALTER TABLE $TABLE ADD COLUMN audio_path TEXT")
                    } catch (e: Exception) { }
                }
            }
        }.writableDatabase

        Log.i(TAG, "✅ MessageStore initialisé (${countMessages()} messages en base)")
    }

    // ==================== ÉCRITURE ====================

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

    // ==================== LECTURE ====================

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

    fun countUnread(channelId: String): Int {
        return try {
            val selection = if (channelId == "TOUS") "receiver_id = 'TOUS' AND is_mine = 0"
            else "(receiver_id = ? OR sender_id_raw = ?) AND is_mine = 0"
            val args = if (channelId == "TOUS") null else arrayOf(channelId, channelId)
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $selection", args)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) { 0 }
    }

    // ==================== INTERNE ====================

    private fun fromCursor(cursor: android.database.Cursor): ChatMessage? {
        return try {
            val type = try {
                PacketType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type")))
            } catch (e: Exception) { PacketType.MESSAGE }

            val status = try {
                MessageStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
            } catch (e: Exception) { MessageStatus.RECEIVED }

            val audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path"))
            val filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path"))

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
                audioPath = if (audioPath.isNullOrEmpty()) null else audioPath,
                filePath = if (filePath.isNullOrEmpty()) null else filePath,
                fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name")),
                fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size"))
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

    fun close() {
        try { db.close() } catch (e: Exception) { }
    }
}