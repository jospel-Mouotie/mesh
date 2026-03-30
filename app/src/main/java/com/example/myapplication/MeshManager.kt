package com.example.myapplication

import android.content.Context
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MeshManager(
    private val context: Context,
    private val myId: String,
    private val myPseudo: String,
    private val mode: MeshMode,
    private val onStatusUpdate: (String) -> Unit,
    private val onMessageReceived: (ChatMessage) -> Unit,
    private val onNodeDiscovered: (MeshNode) -> Unit,
    private val onNodeLost: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val gson = Gson()
    private val TAG = "MeshManager"

    private val knownNodes = ConcurrentHashMap<String, NodeInfo>()
    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val tcpConnections = ConcurrentHashMap<String, Socket>()
    private var wifiDirectManager: WifiDirectManager? = null

    private val executor = Executors.newCachedThreadPool()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var serverPort = 0
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    data class NodeInfo(
        val nodeId: String,
        var pseudo: String,
        var ip: String,
        var tcpPort: Int,
        var lastSeen: Long = System.currentTimeMillis(),
        var isConnected: Boolean = false,
        var connectionType: ConnectionType = ConnectionType.WIFI_DIRECT
    )

    private val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion")
    }

    private val myIp: String by lazy { getLocalIpAddress() }

    fun startMesh() {
        try {
            Log.i(TAG, "==========================================")
            Log.i(TAG, "🚀 Démarrage Mesh")
            Log.i(TAG, "📱 Android: ${Build.VERSION.SDK_INT}, Modèle: ${Build.MODEL}")
            Log.i(TAG, "📱 Mon ID: $myId, Pseudo: $myPseudo")
            Log.i(TAG, "==========================================")

            isRunning.set(true)

            knownNodes[myId] = NodeInfo(
                nodeId = myId,
                pseudo = myPseudo,
                ip = myIp,
                tcpPort = 0,
                lastSeen = System.currentTimeMillis(),
                isConnected = true
            )

            startTcpServer()
            startWifiDirectMode()
            startHeartbeat()

            onStatusUpdate("📡 Recherche de téléphones...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur démarrage: ${e.message}", e)
            onError("Erreur démarrage: ${e.message}")
        }
    }

    // ==================== SERVEUR TCP ====================
    private fun startTcpServer() {
        executor.execute {
            try {
                serverSocket = ServerSocket(0)
                serverPort = serverSocket!!.localPort
                Log.i(TAG, "🖧 SERVEUR TCP - Port: $serverPort")

                while (isRunning.get()) {
                    val clientSocket = serverSocket!!.accept()
                    val clientIp = clientSocket.inetAddress.hostAddress
                    Log.i(TAG, "🔥 NOUVELLE CONNEXION TCP de $clientIp")
                    executor.execute { handleTcpConnection(clientSocket, clientIp) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "❌ Erreur serveur TCP", e)
            }
        }
    }

    private fun handleTcpConnection(socket: Socket, clientIp: String) {
        var nodeId: String? = null
        try {
            socket.soTimeout = 60000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?

            // Enregistrer la connexion
            val clientAddress = socket.inetAddress.hostAddress
            Log.i(TAG, "📡 Nouvelle connexion TCP depuis $clientAddress")

            while (isRunning.get() && !socket.isClosed) {
                try {
                    line = reader.readLine()
                    if (line != null) {
                        val packet = gson.fromJson(line, MeshPacket::class.java)

                        // Mettre à jour le nodeId si on le connaît
                        if (nodeId == null && packet.senderId != myId) {
                            nodeId = packet.senderId
                            tcpConnections[nodeId] = socket

                            // Mettre à jour les infos du noeud
                            knownNodes[nodeId]?.let {
                                it.lastSeen = System.currentTimeMillis()
                                it.isConnected = true
                                it.ip = clientAddress ?: ""
                                it.pseudo = packet.senderPseudo
                            }
                        }

                        handleIncomingPacket(packet, clientAddress ?: "")
                    }
                } catch (e: SocketTimeoutException) {
                    // Timeout normal, continue
                } catch (e: IOException) {
                    if (isRunning.get()) Log.d(TAG, "Connexion TCP fermée: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connexion TCP fermée: ${e.message}")
        } finally {
            nodeId?.let {
                tcpConnections.remove(it)
                knownNodes[it]?.isConnected = false
            }
            try { socket.close() } catch (e: Exception) { }
        }
    }

    // ==================== MODE WIFI DIRECT ====================
    private fun startWifiDirectMode() {
        try {
            wifiDirectManager = WifiDirectManager(
                context = context,
                myId = myId,
                myPseudo = myPseudo,
                myTcpPort = serverPort,
                onStatusUpdate = { status ->
                    mainHandler.post { onStatusUpdate(status) }
                },
                onPeerDiscovered = { device ->
                    Log.i(TAG, "📱 WiFi Direct découvert: ${device.deviceName}")

                    val nodeId = device.deviceAddress

                    if (!knownNodes.containsKey(nodeId) && nodeId != myId) {
                        val nodeInfo = NodeInfo(
                            nodeId = nodeId,
                            pseudo = device.deviceName ?: "Inconnu",
                            ip = "",
                            tcpPort = 8888,
                            lastSeen = System.currentTimeMillis(),
                            isConnected = false,
                            connectionType = ConnectionType.WIFI_DIRECT
                        )
                        knownNodes[nodeId] = nodeInfo

                        val meshNode = MeshNode(
                            deviceId = nodeId,
                            deviceName = device.deviceName ?: "Appareil",
                            pseudo = device.deviceName ?: "Voisin",
                            connectionType = ConnectionType.WIFI_DIRECT,
                            lastSeen = System.currentTimeMillis()
                        )
                        onNodeDiscovered(meshNode)
                        updateConnectionStatus()
                        Log.i(TAG, "✅ Nouveau téléphone découvert: ${device.deviceName}")
                    }
                },
                onMessageReceived = { packet ->
                    Log.i(TAG, "📨 Message WiFi Direct reçu: ${packet.id} de ${packet.senderPseudo}")
                    handleIncomingPacket(packet, packet.senderId)
                },
                onTcpConnectionEstablished = { deviceAddress, socket ->
                    Log.i(TAG, "🔥 Connexion TCP établie avec $deviceAddress")
                    tcpConnections[deviceAddress] = socket

                    knownNodes[deviceAddress]?.let {
                        it.isConnected = true
                        it.lastSeen = System.currentTimeMillis()
                    }
                    updateConnectionStatus()
                },
                onError = { error ->
                    Log.e(TAG, "❌ WiFi Direct: $error")
                    mainHandler.post { onError(error) }
                }
            )

            wifiDirectManager?.startDiscovery()
            onStatusUpdate("🔍 Recherche de téléphones...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur WiFi Direct", e)
            onError("WiFi Direct indisponible: ${e.message}")
        }
    }

    // ==================== HEARTBEAT ====================
    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            if (isRunning.get()) {
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    Log.d(TAG, "Erreur heartbeat: ${e.message}")
                }
                checkStaleNodes()
            }
        }, 5, 15, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun sendHeartbeat() {
        val heartbeatPacket = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",
            content = "HEARTBEAT",
            type = PacketType.HEARTBEAT,
            hopCount = 0,
            timestamp = System.currentTimeMillis()
        )
        val json = gson.toJson(heartbeatPacket)

        tcpConnections.values.forEach { socket ->
            try {
                if (socket.isConnected && !socket.isClosed) {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json)
                }
            } catch (e: Exception) {
                // Ignorer
            }
        }
    }

    private fun checkStaleNodes() {
        val now = System.currentTimeMillis()
        val staleTimeout = 45000

        val staleNodes = knownNodes.filter { (id, info) ->
            id != myId && now - info.lastSeen > staleTimeout
        }

        staleNodes.forEach { (nodeId, info) ->
            Log.w(TAG, "⚠️ Nœud perdu: ${info.pseudo}")
            knownNodes.remove(nodeId)
            tcpConnections.remove(nodeId)?.close()
            onNodeLost(nodeId)
            mainHandler.post { onStatusUpdate("🔴 Perdu: ${info.pseudo}") }
        }

        updateConnectionStatus()
    }

    private fun updateConnectionStatus() {
        mainHandler.post {
            val activeNodes = knownNodes.values.count { it.nodeId != myId && it.isConnected }
            if (activeNodes > 0) {
                onStatusUpdate("✅ $activeNodes voisin(s) connecté(s)")
            } else {
                onStatusUpdate("📡 Recherche de téléphones...")
            }
        }
    }

    // ==================== ENVOI DE MESSAGE ====================
    fun sendPacket(packet: MeshPacket) {
        Log.d(TAG, "📤 Envoi: ${packet.content}")

        val json = gson.toJson(packet)
        val data = (json + "\n").toByteArray()

        // Envoyer via toutes les connexions TCP
        tcpConnections.values.forEach { socket ->
            executor.execute {
                try {
                    if (socket.isConnected && !socket.isClosed) {
                        socket.getOutputStream().write(data)
                        socket.getOutputStream().flush()
                        Log.d(TAG, "📤 Message envoyé via TCP")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur envoi TCP", e)
                }
            }
        }

        // Envoyer aussi via WiFi Direct (pour les nouveaux peers)
        wifiDirectManager?.sendMessage(packet)
    }

    // ==================== TRAITEMENT DES MESSAGES ====================
    private fun handleIncomingPacket(packet: MeshPacket, sourceId: String) {
        // Éviter les doublons
        if (seenPacketIds.contains(packet.id)) {
            Log.d(TAG, "⏭️ Packet déjà traité: ${packet.id}")
            return
        }
        seenPacketIds.add(packet.id)

        Log.i(TAG, "📥 Message de ${packet.senderPseudo}: ${packet.content}")

        // Mettre à jour ou créer le noeud
        val existingNode = knownNodes.values.find { it.nodeId == packet.senderId }
        if (existingNode != null) {
            existingNode.lastSeen = System.currentTimeMillis()
            existingNode.pseudo = packet.senderPseudo
            existingNode.isConnected = true
        } else if (packet.senderId != myId) {
            val newNode = NodeInfo(
                nodeId = packet.senderId,
                pseudo = packet.senderPseudo,
                ip = sourceId,
                tcpPort = 8888,
                lastSeen = System.currentTimeMillis(),
                isConnected = true,
                connectionType = ConnectionType.WIFI_DIRECT
            )
            knownNodes[packet.senderId] = newNode

            val meshNode = MeshNode(
                deviceId = packet.senderId,
                deviceName = packet.senderPseudo,
                pseudo = packet.senderPseudo,
                connectionType = ConnectionType.WIFI_DIRECT,
                lastSeen = System.currentTimeMillis()
            )
            onNodeDiscovered(meshNode)
            updateConnectionStatus()
        }

        // Traiter le message s'il est pour nous ou broadcast
        if (packet.type != PacketType.HEARTBEAT) {
            if (packet.receiver == "TOUS" || packet.receiver == myId) {
                val chatMsg = ChatMessage(
                    id = packet.id,
                    sender = packet.senderPseudo,
                    content = packet.content,
                    isMine = false,
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(packet.timestamp)),
                    status = MessageStatus.RECEIVED,
                    type = packet.type,
                    senderIdRaw = packet.senderId,
                    receiverId = packet.receiver,
                    timestamp = packet.timestamp,
                    audioUri = packet.audioData?.let {
                        val file = File(context.cacheDir, "audio_${packet.id}.3gp")
                        file.writeBytes(it)
                        Uri.fromFile(file)
                    }
                )
                onMessageReceived(chatMsg)
            }
        }

        // Nettoyer l'historique des IDs
        if (seenPacketIds.size > 1000) {
            seenPacketIds.clear()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur récupération IP", e)
        }
        return "192.168.49.1" // IP par défaut WiFi Direct
    }

    fun stop() {
        Log.i(TAG, "🛑 Arrêt MeshManager")
        isRunning.set(false)

        heartbeatExecutor.shutdown()
        executor.shutdown()

        try {
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            serverSocket?.close()
            wifiDirectManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur arrêt", e)
        }

        knownNodes.clear()
        seenPacketIds.clear()
    }
}