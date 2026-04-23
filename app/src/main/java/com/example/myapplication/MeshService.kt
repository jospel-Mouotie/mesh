package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MeshService : Service() {

    private var meshManager: MeshManager? = null
    private var myId: String? = null
    private var myPseudo: String? = null

    private val CHANNEL_ID  = "MeshServiceChannel"
    private val NOTIF_ID    = 101
    private val TAG         = "MeshService"
    private val PREFS_NAME  = "MIT_MESH_SETTINGS"

    private val isServiceReady  = AtomicBoolean(false)
    private val isInitializing  = AtomicBoolean(false)
    private val handler         = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "🚀 MeshService onCreate")
            createNotificationChannel()
            startForegroundNow()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur onCreate", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "📞 onStartCommand | Action: $action")

        if (action == "ACTION_STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            var id = intent?.getStringExtra("user_id")
            var pseudo = intent?.getStringExtra("user_pseudo")

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (id == null || pseudo == null) {
                id = prefs.getString("user_id", null)
                pseudo = prefs.getString("user_pseudo", null)
            } else {
                prefs.edit().putString("user_id", id).putString("user_pseudo", pseudo).apply()
            }

            if (id == null || pseudo == null) {
                Log.e(TAG, "❌ Identifiants manquants, arrêt du service")
                stopSelf()
                return START_NOT_STICKY
            }

            myId = id
            myPseudo = pseudo

            if (!isServiceReady.get() && !isInitializing.get()) {
                setupMeshManager()
            }

            intent?.let { handleIntentAction(it) }

        } catch (e: Exception) {
            Log.e(TAG, "💥 Erreur onStartCommand", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "🛑 Destruction du MeshService...")
        isServiceReady.set(false)
        isInitializing.set(false)
        handler.removeCallbacksAndMessages(null)
        try { meshManager?.stop() } catch (e: Exception) { }
        meshManager = null
        super.onDestroy()
        Log.i(TAG, "✅ MeshService totalement arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNow() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.d(TAG, "✅ Service en avant-plan démarré")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur startForeground: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MIT MESH Network")
            .setContentText("🌐 Réseau maillé en cours d'exécution...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Canal Mesh Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Maintient le réseau maillé actif en arrière-plan"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun setupMeshManager() {
        if (isInitializing.getAndSet(true)) return

        Log.i(TAG, "🔄 Initialisation du MeshManager...")

        try {
            val currentId = myId ?: throw Exception("ID null")
            val currentPseudo = myPseudo ?: throw Exception("Pseudo null")

            meshManager?.stop()
            meshManager = null

            meshManager = MeshManager(
                context = this,
                myId = currentId,
                myPseudo = currentPseudo,
                mode = MeshMode.HYBRID,
                onStatusUpdate = { status ->
                    Log.i(TAG, "📡 [Status] $status")
                    broadcastToApp("MESH_STATUS_CHANGED", "status", status)
                },
                onMessageReceived = { msg ->
                    val intent = Intent("MESH_MESSAGE_RECEIVED").apply {
                        putExtra("id", msg.id)
                        putExtra("sender", msg.sender)
                        putExtra("content", msg.content)
                        putExtra("receiver", msg.receiverId)
                        putExtra("type", msg.type.name)
                        putExtra("timestamp", msg.timestamp)
                        putExtra("audio_path", msg.audioPath)
                        putExtra("file_path", msg.filePath)
                        putExtra("file_name", msg.fileName)
                        putExtra("file_size", msg.fileSize)
                        putExtra("reply_to_id", msg.replyToId)
                        putExtra("reply_to_content", msg.replyToContent)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                },
                onNodeDiscovered = { node ->
                    val intent = Intent("MESH_NODE_DISCOVERED").apply {
                        putExtra("node_id", node.deviceId)
                        putExtra("node_name", node.deviceName)
                        putExtra("node_pseudo", node.pseudo)
                        putExtra("connection_type", node.connectionType.name)
                        putExtra("is_relay", node.isRelay)
                        putExtra("is_sub_go", node.isSubGo)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                },
                onNodeLost = { nodeId ->
                    broadcastToApp("MESH_NODE_LOST", "node_id", nodeId)
                },
                onError = { error ->
                    Log.e(TAG, "⚠️ Erreur Mesh: $error")
                    broadcastToApp("MESH_ERROR", "error", error)
                }
            )

            meshManager?.startMesh()
            isServiceReady.set(true)
            isInitializing.set(false)
            Log.i(TAG, "✅ MeshManager prêt et démarré")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Échec setupMeshManager", e)
            isInitializing.set(false)
            handler.postDelayed({ if (!isServiceReady.get()) setupMeshManager() }, 5000)
        }
    }
    private fun handleIntentAction(intent: Intent) {
        when (intent.action) {
            "ACTION_SEND_MESSAGE" -> {
                if (isServiceReady.get()) sendMessage(intent)
                else Log.w(TAG, "Pas d'envoi : MeshManager non prêt")
            }
            "ACTION_SEND_LARGE_FILE" -> {
                if (isServiceReady.get()) sendLargeFile(intent)
                else Log.w(TAG, "Pas d'envoi gros fichier : MeshManager non prêt")
            }
            "ACTION_GET_TOPOLOGY" -> broadcastTopology()
            "ACTION_BECOME_GO" -> {
                if (isServiceReady.get()) {
                    Log.i(TAG, "🔧 Action BECOME_GO reçue")
                    meshManager?.forceBecomeGroupOwner()
                }
            }
            "ACTION_JOIN_GROUP" -> {
                if (isServiceReady.get()) {
                    Log.i(TAG, "🔧 Action JOIN_GROUP reçue")
                    meshManager?.forceJoinExistingGroup()
                }
            }
            "ACTION_RECONNECT_TCP" -> {
                if (isServiceReady.get()) {
                    Log.i(TAG, "🔧 Action RECONNECT_TCP reçue")
                    meshManager?.forceReconnectTcp()
                }
            }
            "ACTION_CLEANUP_AND_JOIN" -> {  // ← AJOUTER CE BLOC
                if (isServiceReady.get()) {
                    Log.i(TAG, "🧹 Action CLEANUP_AND_JOIN reçue")
                    meshManager?.forceResetAndJoin()
                } else {
                    Log.w(TAG, "MeshManager non prêt pour le nettoyage")
                }
            }
        }
    }
    private fun sendMessage(intent: Intent) {
        val content = intent.getStringExtra("content") ?: return
        val receiver = intent.getStringExtra("receiver") ?: "TOUS"
        val typeStr = intent.getStringExtra("type") ?: "MESSAGE"
        val type = try { PacketType.valueOf(typeStr) } catch (e: Exception) { PacketType.MESSAGE }

        val audioData = intent.getByteArrayExtra("audio_data")
        val fileData = intent.getByteArrayExtra("file_data")
        val fileName = intent.getStringExtra("file_name")
        val fileSize = intent.getLongExtra("file_size", 0)

        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId ?: "",
            senderPseudo = myPseudo ?: "",
            receiver = receiver,
            content = content,
            type = type,
            timestamp = System.currentTimeMillis(),
            audioData = audioData,
            fileData = fileData,
            fileName = fileName,
            fileSize = fileSize
        )
        executor.execute { meshManager?.sendPacket(packet) }
    }

    private fun sendLargeFile(intent: Intent) {
        val destination = intent.getStringExtra("destination") ?: return
        val filePath = intent.getStringExtra("file_path") ?: return
        val fileName = intent.getStringExtra("file_name") ?: "fichier"
        val fileSize = intent.getLongExtra("file_size", 0)
        val messageId = intent.getStringExtra("message_id")
        val isAudio = intent.getBooleanExtra("is_audio", false)

        meshManager?.startLargeFileSend(
            destinationId = destination,
            filePath = filePath,
            fileName = fileName,
            fileSize = fileSize,
            onProgress = { sent, total ->
                val progressIntent = Intent("FILE_TRANSFER_PROGRESS").apply {
                    putExtra("destination", destination)
                    putExtra("sent", sent)
                    putExtra("total", total)
                    putExtra("file_name", fileName)
                    setPackage(packageName)
                }
                sendBroadcast(progressIntent)
            },
            onComplete = {
                // Envoyer un message au destinataire avec le BON senderId
                val msgIntent = Intent("MESH_MESSAGE_RECEIVED").apply {
                    putExtra("id", UUID.randomUUID().toString())
                    putExtra("sender", myPseudo)
                    putExtra("sender_id", myId)           // ← ID correct
                    putExtra("content", fileName)
                    putExtra("receiver", destination)
                    putExtra("type", if (isAudio) PacketType.AUDIO.name else PacketType.FILE.name)
                    putExtra("timestamp", System.currentTimeMillis())
                    putExtra("file_path", filePath)
                    putExtra("audio_path", if (isAudio) filePath else null)
                    putExtra("file_name", fileName)
                    putExtra("file_size", fileSize)
                    setPackage(packageName)
                }
                sendBroadcast(msgIntent)

                val completeIntent = Intent("FILE_TRANSFER_COMPLETE").apply {
                    putExtra("destination", destination)
                    putExtra("file_name", fileName)
                    putExtra("file_path", filePath)
                    putExtra("message_id", messageId)
                    setPackage(packageName)
                }
                sendBroadcast(completeIntent)
            },
            onError = { err ->
                broadcastToApp("MESH_ERROR", "error", "Transfert fichier: $err")
            }
        )
    }

    private fun broadcastToApp(action: String, key: String, value: String) {
        val intent = Intent(action).apply {
            putExtra(key, value)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastTopology() {
        val status = if (isServiceReady.get()) "READY" else "INITIALIZING"
        broadcastToApp("MESH_TOPOLOGY_UPDATE", "state", status)
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
}