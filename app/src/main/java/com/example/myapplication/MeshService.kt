package com.example.myapplication

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MeshService : Service() {

    private var meshManager: MeshManager? = null
    private var myId: String? = null
    private var myPseudo: String? = null

    private val CHANNEL_ID = "MeshServiceChannel"
    private val NOTIF_ID = 101
    private val TAG = "MeshService"
    private val PREFS_NAME = "MIT_MESH_SETTINGS"

    private val isServiceReady = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "🚀 MeshService onCreate")
            createNotificationChannel()
            startForegroundNow()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur dans onCreate", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.i(TAG, "📞 onStartCommand (action=${intent?.action})")

            var id = intent?.getStringExtra("user_id")
            var pseudo = intent?.getStringExtra("user_pseudo")

            if (id == null || pseudo == null) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                id = prefs.getString("user_id", null)
                pseudo = prefs.getString("user_pseudo", null)
                if (id != null && pseudo != null) {
                    Log.i(TAG, "📦 IDs récupérés depuis les préférences : $id / $pseudo")
                }
            } else {
                try {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                        putString("user_id", id)
                        putString("user_pseudo", pseudo)
                        apply()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur sauvegarde des IDs", e)
                }
            }

            if (id == null || pseudo == null) {
                Log.e(TAG, "❌ IDs manquants — arrêt du service")
                stopSelf()
                return START_NOT_STICKY
            }

            myId = id
            myPseudo = pseudo

            if (!isServiceReady.get() && !isInitializing.get()) {
                isInitializing.set(true)
                Log.i(TAG, "👤 ID: $myId | Pseudo: $myPseudo")
                setupMeshManager()
            }

            intent?.let { handleIntentAction(it) }

        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception dans onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 MeshService onDestroy")
        handler.removeCallbacksAndMessages(null)
        try {
            meshManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur arrêt MeshManager", e)
        }
        meshManager = null
        isServiceReady.set(false)
        isInitializing.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNow() {
        try {
            val notification = buildNotification()
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                }
                else -> startForeground(NOTIF_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service démarré (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground échoué: ${e.message}", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MIT MESH")
            .setContentText("🌐 Réseau maillé actif")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Mesh Network",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notifications du réseau maillé"
                    setSound(null, null)
                }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur création canal notification", e)
            }
        }
    }

    private fun setupMeshManager() {
        Log.i(TAG, "🔄 Configuration MeshManager")
        try {
            val id = myId ?: run {
                Log.e(TAG, "myId is null")
                isInitializing.set(false)
                return
            }
            val pseudo = myPseudo ?: run {
                Log.e(TAG, "myPseudo is null")
                isInitializing.set(false)
                return
            }

            meshManager?.stop()
            meshManager = null

            meshManager = MeshManager(
                context = this,
                myId = id,
                myPseudo = pseudo,
                mode = MeshMode.HYBRID,
                onStatusUpdate = { status ->
                    try {
                        Log.i(TAG, "📡 Status: $status")
                        sendBroadcast(Intent("MESH_STATUS_CHANGED").apply {
                            putExtra("status", status)
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur dans onStatusUpdate", e)
                    }
                },
                onMessageReceived = { chatMsg ->
                    try {
                        Log.i(TAG, "📨 Reçu: [${chatMsg.type}] ${chatMsg.sender}: ${chatMsg.content.take(60)}")
                        sendBroadcast(Intent("MESH_MESSAGE_RECEIVED").apply {
                            putExtra("id", chatMsg.id)
                            putExtra("sender", chatMsg.sender)
                            putExtra("content", chatMsg.content)
                            putExtra("receiver", chatMsg.receiverId)
                            putExtra("timestamp", chatMsg.timestamp)
                            putExtra("type", chatMsg.type.name)
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur dans onMessageReceived", e)
                    }
                },
                onNodeDiscovered = { node ->
                    try {
                        Log.i(TAG, "✅ Nœud découvert: ${node.pseudo} (${node.deviceId})")
                        sendBroadcast(Intent("MESH_NODE_DISCOVERED").apply {
                            putExtra("node_id", node.deviceId)
                            putExtra("node_name", node.deviceName)
                            putExtra("node_pseudo", node.pseudo)
                            putExtra("connection_type", node.connectionType.name)
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur dans onNodeDiscovered", e)
                    }
                },
                onNodeLost = { nodeId ->
                    try {
                        Log.i(TAG, "❌ Nœud perdu: $nodeId")
                        sendBroadcast(Intent("MESH_NODE_LOST").apply {
                            putExtra("node_id", nodeId)
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur dans onNodeLost", e)
                    }
                },
                onError = { error ->
                    try {
                        Log.e(TAG, "⚠️ Erreur MeshManager: $error")
                        sendBroadcast(Intent("MESH_ERROR").apply {
                            putExtra("error", error)
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur dans onError", e)
                    }
                }
            )

            meshManager?.startMesh()
            isServiceReady.set(true)
            isInitializing.set(false)
            Log.i(TAG, "✅ MeshManager démarré")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur setupMeshManager: ${e.message}", e)
            isInitializing.set(false)
            isServiceReady.set(false)
            handler.postDelayed({
                if (!isServiceReady.get() && !isInitializing.get()) {
                    Log.i(TAG, "🔄 Nouvelle tentative setupMeshManager")
                    isInitializing.set(true)
                    setupMeshManager()
                }
            }, 3000)
        }
    }

    private fun handleIntentAction(intent: Intent) {
        val action = intent.action ?: return
        if (!isServiceReady.get() && action == "ACTION_SEND_MESSAGE") {
            Log.w(TAG, "⚠️ Service pas encore prêt pour ACTION_SEND_MESSAGE")
            return
        }
        Log.i(TAG, "🔍 Action: $action")
        when (action) {
            "ACTION_SEND_MESSAGE" -> sendMessage(intent)
            "ACTION_GET_TOPOLOGY" -> broadcastTopology()
            "ACTION_RETRY_MESSAGES" -> { /* à implémenter */ }
            "ACTION_STOP_SERVICE" -> stopSelf()
        }
    }

    private fun sendMessage(intent: Intent) {
        val manager = meshManager ?: run {
            Log.e(TAG, "❌ MeshManager non initialisé")
            return
        }
        try {
            val id = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
            val content = intent.getStringExtra("content") ?: ""
            val receiver = intent.getStringExtra("receiver") ?: "TOUS"
            val typeStr = intent.getStringExtra("type") ?: "MESSAGE"
            val audioData = intent.getByteArrayExtra("audio_data")
            val type = try { PacketType.valueOf(typeStr) } catch (e: Exception) { PacketType.MESSAGE }

            val packet = MeshPacket(
                id = id,
                senderId = myId!!,
                senderPseudo = myPseudo!!,
                receiver = receiver,
                content = content,
                type = type,
                audioData = audioData,
                hopCount = 0,
                timestamp = System.currentTimeMillis()
            )
            manager.sendPacket(packet)
            Log.d(TAG, "📤 Message envoyé: $id → $receiver")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur sendMessage: ${e.message}", e)
        }
    }

    private fun broadcastTopology() {
        try {
            sendBroadcast(Intent("MESH_TOPOLOGY_UPDATE").apply {
                putExtra("topology", if (isServiceReady.get()) 1 else 0)
                setPackage(packageName)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Erreur broadcastTopology", e)
        }
    }
}