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

    private val isServiceReady = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 MeshService onCreate")
        createNotificationChannel()

        // CRITIQUE : startForeground() doit être appelé dans les 5 secondes suivant
        // onCreate() sur Android 8+, et avec foregroundServiceType sur Android 14+.
        // Le faire ici garantit qu'on ne rate jamais la fenêtre.
        startForegroundNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "📞 onStartCommand (action=${intent?.action})")

        // Mise à jour des IDs si fournis (premier démarrage ou redémarrage)
        intent?.getStringExtra("user_id")?.let { myId = it }
        intent?.getStringExtra("user_pseudo")?.let { myPseudo = it }

        // Redémarrage système (intent null via START_STICKY) sans IDs → arrêt propre
        if (myId == null || myPseudo == null) {
            Log.e(TAG, "❌ IDs manquants — arrêt du service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialisation du MeshManager une seule fois
        if (!isServiceReady.get() && !isInitializing.get()) {
            isInitializing.set(true)
            Log.i(TAG, "👤 ID: $myId | Pseudo: $myPseudo")
            setupMeshManager()
        }

        // Traiter les actions (envoi message, topologie, etc.)
        intent?.let { handleIntentAction(it) }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 MeshService onDestroy")
        handler.removeCallbacksAndMessages(null)
        meshManager?.stop()
        meshManager = null
        isServiceReady.set(false)
        isInitializing.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== FOREGROUND ====================

    private fun startForegroundNow() {
        try {
            val notification = buildNotification()
            when {
                // Android 14+ : foregroundServiceType obligatoire
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

    // ==================== SETUP MESHMANAGER ====================

    private fun setupMeshManager() {
        Log.i(TAG, "🔄 Configuration MeshManager")
        try {
            val id = myId ?: run { isInitializing.set(false); return }
            val pseudo = myPseudo ?: run { isInitializing.set(false); return }

            // Arrêter l'ancien manager si existant (ex: redémarrage du service)
            meshManager?.stop()
            meshManager = null

            meshManager = MeshManager(
                context = this,
                myId = id,
                myPseudo = pseudo,
                mode = MeshMode.HYBRID,
                onStatusUpdate = { status ->
                    Log.i(TAG, "📡 Status: $status")
                    sendBroadcast(Intent("MESH_STATUS_CHANGED").apply {
                        putExtra("status", status)
                        setPackage(packageName)
                    })
                },
                onMessageReceived = { chatMsg ->
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
                },
                onNodeDiscovered = { node ->
                    Log.i(TAG, "✅ Nœud découvert: ${node.pseudo} (${node.deviceId})")
                    sendBroadcast(Intent("MESH_NODE_DISCOVERED").apply {
                        putExtra("node_id", node.deviceId)
                        putExtra("node_name", node.deviceName)
                        putExtra("node_pseudo", node.pseudo)
                        putExtra("connection_type", node.connectionType.name)
                        setPackage(packageName)
                    })
                },
                onNodeLost = { nodeId ->
                    Log.i(TAG, "❌ Nœud perdu: $nodeId")
                    sendBroadcast(Intent("MESH_NODE_LOST").apply {
                        putExtra("node_id", nodeId)
                        setPackage(packageName)
                    })
                },
                onError = { error ->
                    Log.e(TAG, "⚠️ Erreur MeshManager: $error")
                    sendBroadcast(Intent("MESH_ERROR").apply {
                        putExtra("error", error)
                        setPackage(packageName)
                    })
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
            // Réessayer après 3 secondes
            handler.postDelayed({
                if (!isServiceReady.get() && !isInitializing.get()) {
                    Log.i(TAG, "🔄 Nouvelle tentative setupMeshManager")
                    isInitializing.set(true)
                    setupMeshManager()
                }
            }, 3000)
        }
    }

    // ==================== ACTIONS ====================

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
        sendBroadcast(Intent("MESH_TOPOLOGY_UPDATE").apply {
            putExtra("topology", if (isServiceReady.get()) 1 else 0)
            setPackage(packageName)
        })
    }
}