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

    private val messageCache = mutableListOf<MeshPacket>()
    private val knownNodes = mutableMapOf<String, MeshNode>()
    private val isServiceReady = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TIMEOUT_SETUP_MS = 8000L
        private const val DELAY_RESTART_MS = 1000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 [onCreate] Service création")

        try {
            createNotificationChannel()
            Log.d(TAG, "✅ Canal notification créé")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur création canal", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "📞 [onStartCommand] Appelé")

        if (isInitializing.get()) {
            Log.w(TAG, "⏳ Initialisation en cours")
            return START_STICKY
        }

        handler.postDelayed({
            if (!isServiceReady.get()) {
                Log.e(TAG, "⏰ TIMEOUT initialisation")
                handleTimeout()
            }
        }, TIMEOUT_SETUP_MS)

        try {
            isInitializing.set(true)

            intent?.getStringExtra("user_id")?.let {
                if (it != myId) {
                    Log.i(TAG, "👤 Nouvel ID: $it")
                    myId = it
                }
            }
            intent?.getStringExtra("user_pseudo")?.let {
                if (it != myPseudo) {
                    Log.i(TAG, "👤 Nouveau pseudo: $it")
                    myPseudo = it
                }
            }

            if (myId == null || myPseudo == null) {
                Log.e(TAG, "❌ IDs manquants")
                isInitializing.set(false)
                return START_NOT_STICKY
            }

            if (!isServiceReady.get()) {
                setupForegroundService()
            }

            if (meshManager == null) {
                setupMeshManager()
            }

            intent?.let { handleIntentAction(it) }

            isServiceReady.set(true)
            isInitializing.set(false)
            handler.removeCallbacksAndMessages(null)
            Log.d(TAG, "✅ Service prêt")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception", e)
            isInitializing.set(false)
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun handleTimeout() {
        Log.e(TAG, "⚠️ Timeout - redémarrage")
        if (isInitializing.get()) {
            isInitializing.set(false)
            handler.postDelayed({
                val intent = Intent(this, MeshService::class.java).apply {
                    putExtra("user_id", myId)
                    putExtra("user_pseudo", myPseudo)
                }
                startService(intent)
            }, DELAY_RESTART_MS)
        }
    }

    private fun setupForegroundService() {
        Log.i(TAG, "🔄 Configuration foreground service")

        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MIT MESH")
                .setContentText("🌐 Réseau maillé actif")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.d(TAG, "✅ Foreground service démarré")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec foreground", e)
            throw e
        }
    }

    private fun setupMeshManager() {
        Log.i(TAG, "🔄 Configuration MeshManager")

        try {
            meshManager = MeshManager(
                context = this,
                myId = myId!!,
                myPseudo = myPseudo!!,
                mode = MeshMode.HYBRID,
                onStatusUpdate = { status ->
                    Log.i(TAG, "📡 Status: $status")
                    sendBroadcast(Intent("MESH_STATUS_CHANGED").apply {
                        putExtra("status", status)
                        setPackage(packageName)
                    })
                },
                onMessageReceived = { chatMsg ->
                    Log.i(TAG, "📨 Message reçu: ${chatMsg.id} de ${chatMsg.sender}")
                    sendBroadcast(Intent("MESH_MESSAGE_RECEIVED").apply {
                        putExtra("id", chatMsg.id)
                        putExtra("sender", chatMsg.sender)
                        putExtra("content", chatMsg.content)
                        putExtra("receiver", chatMsg.receiverId)
                        putExtra("timestamp", chatMsg.timestamp)
                        putExtra("type", chatMsg.type.name)
                        setPackage(packageName)
                    })
                },
                onNodeDiscovered = { node ->
                    Log.i(TAG, "✅ Noeud découvert: ${node.pseudo}")
                    knownNodes[node.deviceId] = node
                    sendBroadcast(Intent("MESH_NODE_DISCOVERED").apply {
                        putExtra("node_id", node.deviceId)
                        putExtra("node_name", node.deviceName)
                        putExtra("node_pseudo", node.pseudo)
                        putExtra("connection_type", node.connectionType.name)
                        setPackage(packageName)
                    })
                },
                onNodeLost = { nodeId ->
                    Log.i(TAG, "❌ Noeud perdu: $nodeId")
                    knownNodes.remove(nodeId)
                    sendBroadcast(Intent("MESH_NODE_LOST").apply {
                        putExtra("node_id", nodeId)
                        setPackage(packageName)
                    })
                },
                onError = { error ->
                    Log.e(TAG, "⚠️ Erreur: $error")
                    sendBroadcast(Intent("MESH_ERROR").apply {
                        putExtra("error", error)
                        setPackage(packageName)
                    })
                }
            )

            meshManager?.startMesh()
            Log.i(TAG, "✅ MeshManager démarré")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur setup MeshManager", e)
            throw e
        }
    }

    private fun handleIntentAction(intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "🔍 Action: $action")

        when (action) {
            "ACTION_SEND_MESSAGE" -> sendMessage(intent)
            "ACTION_GET_TOPOLOGY" -> broadcastTopology()
            "ACTION_RETRY_MESSAGES" -> retryPendingMessages()
            "ACTION_STOP_SERVICE" -> stopSelf()
        }
    }

    private fun sendMessage(intent: Intent) {
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

            synchronized(messageCache) { messageCache.add(packet) }
            meshManager?.sendPacket(packet)
            Log.d(TAG, "📤 Message envoyé: $id")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur envoi", e)
        }
    }

    private fun broadcastTopology() {
        sendBroadcast(Intent("MESH_TOPOLOGY_UPDATE").apply {
            putExtra("topology", knownNodes.size)
            setPackage(packageName)
        })
    }

    private fun retryPendingMessages() {
        val messages = synchronized(messageCache) { messageCache.toList() }
        messages.forEach { meshManager?.sendPacket(it) }
        Log.d(TAG, "🔄 ${messages.size} messages renvoyés")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications du réseau maillé"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 [onDestroy] Arrêt")
        handler.removeCallbacksAndMessages(null)
        meshManager?.stop()
        meshManager = null
        isServiceReady.set(false)
        isInitializing.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}