package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val TCP_PORT = 8888
    private val MAX_HOP_COUNT = 7
    private val HEARTBEAT_INTERVAL = 10000L
    private val NODE_TIMEOUT = 35000L

    private val knownNodes = ConcurrentHashMap<String, NodeInfo>()
    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val tcpConnections = ConcurrentHashMap<String, Socket>()
    private val routingTable = ConcurrentHashMap<String, String>()
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    // WiFi Direct state
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var thisDeviceAddress: String? = null
    private var groupOwnerIp: InetAddress? = null
    private var isGroupOwner = false
    private var isConnectedToGroup = false
    private var receiverRegistered = false

    // Flags atomiques
    private val isConnecting = AtomicBoolean(false)
    private val tcpConnectInProgress = AtomicBoolean(false)
    private val isBecomingGO = AtomicBoolean(false)

    private val executor = Executors.newCachedThreadPool()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class NodeInfo(
        val nodeId: String,
        var pseudo: String,
        var ip: String,
        var lastSeen: Long = System.currentTimeMillis(),
        var isConnected: Boolean = false,
        var isDirectNeighbor: Boolean = false,
        var hopDistance: Int = Int.MAX_VALUE
    )

    private val myIp: String by lazy { getLocalIpAddress() }

    // ==================== DÉMARRAGE ====================

    fun startMesh() {
        try {
            Log.i(TAG, "==========================================")
            Log.i(TAG, "🚀 Démarrage Mesh Network")
            Log.i(TAG, "📱 Android: ${Build.VERSION.SDK_INT}, Modèle: ${Build.MODEL}")
            Log.i(TAG, "📱 Mon ID: $myId, Pseudo: $myPseudo")
            Log.i(TAG, "==========================================")

            isRunning.set(true)

            knownNodes[myId] = NodeInfo(
                nodeId = myId,
                pseudo = myPseudo,
                ip = myIp,
                lastSeen = System.currentTimeMillis(),
                isConnected = true,
                isDirectNeighbor = false,
                hopDistance = 0
            )

            startTcpServer()
            startWifiDirect()
            startHeartbeat()

            onStatusUpdate("📡 Recherche de téléphones...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur démarrage: ${e.message}", e)
            onError("Erreur démarrage: ${e.message}")
        }
    }

    // ==================== WIFI DIRECT ====================

    private fun startWifiDirect() {
        if (!checkWifiDirectPermission()) {
            Log.e(TAG, "❌ Permission WiFi Direct manquante")
            onError("Permission WiFi Direct manquante — accordez les permissions")
            return
        }
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = wifiP2pManager?.initialize(
                context,
                Looper.getMainLooper(),
                object : WifiP2pManager.ChannelListener {
                    override fun onChannelDisconnected() {
                        Log.w(TAG, "⚠️ Channel WiFi Direct déconnecté")
                        if (isRunning.get()) {
                            mainHandler.postDelayed({ restartWifiDirect() }, 3000)
                        }
                    }
                }
            )

            registerWifiReceiver()

            // 🔥 SUPPRIMER TOUT GROUPE EXISTANT avant de démarrer
            removeOldGroup {
                // Démarrer la découverte après nettoyage
                startDiscovery()

                // Devenir Group Owner après 8 secondes si aucune connexion
                mainHandler.postDelayed({
                    if (isRunning.get() && !isConnectedToGroup && !isConnecting.get() && !isBecomingGO.get()) {
                        Log.i(TAG, "⏱️ Aucun pair après 8s → je deviens Group Owner")
                        becomeGroupOwner()
                    }
                }, 8000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur init WiFi Direct: ${e.message}", e)
            onError("Erreur WiFi Direct: ${e.message}")
        }
    }

    // Supprime un groupe existant (si présent)
    private fun removeOldGroup(onDone: () -> Unit) {
        if (!checkWifiDirectPermission()) {
            onDone()
            return
        }
        try {
            wifiP2pManager?.requestGroupInfo(channel) { group ->
                if (group != null) {
                    Log.d(TAG, "🗑️ Suppression ancien groupe: ${group.networkName}")
                    wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "✅ Ancien groupe supprimé")
                            onDone()
                        }
                        override fun onFailure(reason: Int) {
                            Log.d(TAG, "Suppression groupe échouée: $reason")
                            onDone()
                        }
                    })
                } else {
                    onDone()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée removeGroup", e)
            onDone()
        }
    }

    private fun restartWifiDirect() {
        if (!isRunning.get()) return
        Log.i(TAG, "🔄 Redémarrage WiFi Direct")
        if (receiverRegistered) {
            try { context.unregisterReceiver(wifiDirectReceiver) } catch (e: Exception) { }
            receiverRegistered = false
        }
        isConnectedToGroup = false
        isGroupOwner = false
        groupOwnerIp = null
        isConnecting.set(false)
        isBecomingGO.set(false)
        tcpConnectInProgress.set(false)
        startWifiDirect()
    }

    private fun registerWifiReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiDirectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(wifiDirectReceiver, filter)
            }
            receiverRegistered = true
            Log.d(TAG, "✅ Receiver WiFi Direct enregistré (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur enregistrement receiver: ${e.message}", e)
        }
    }

    private fun startDiscovery() {
        if (!checkWifiDirectPermission() || !isRunning.get()) return
        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Découverte WiFi Direct démarrée")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "⚠️ discoverPeers: $reason ${reasonToString(reason)}")
                    if (reason != 2) { // 2 = BUSY
                        mainHandler.postDelayed({
                            if (isRunning.get() && !isConnectedToGroup) startDiscovery()
                        }, 5000)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée discoverPeers", e)
        }
    }

    private fun becomeGroupOwner() {
        if (!isRunning.get() || isGroupOwner || isConnectedToGroup) return
        if (!isBecomingGO.compareAndSet(false, true)) return
        if (!checkWifiDirectPermission()) { isBecomingGO.set(false); return }

        Log.i(TAG, "👑 Création du groupe WiFi Direct (GO)")
        try {
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Groupe créé — je suis Group Owner")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ createGroup échoué: $reason ${reasonToString(reason)}")
                    isBecomingGO.set(false)
                    mainHandler.postDelayed({
                        if (isRunning.get() && !isConnectedToGroup) becomeGroupOwner()
                    }, 6000)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée createGroup", e)
            isBecomingGO.set(false)
        }
    }

    // ==================== BROADCAST RECEIVER ====================

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WiFi Direct: ${if (enabled) "ACTIVÉ" else "DÉSACTIVÉ"}")
                    if (!enabled) {
                        onError("WiFi Direct désactivé — activez le WiFi")
                        onStatusUpdate("❌ WiFi Direct désactivé")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "📢 PEERS_CHANGED reçu (connecté=$isConnectedToGroup, connecting=${isConnecting.get()})")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleConnectionChanged(intent)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = getDeviceFromIntent(intent)
                    device?.let {
                        thisDeviceAddress = it.deviceAddress
                        Log.d(TAG, "📱 Cet appareil: ${it.deviceName} (${it.deviceAddress})")
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceFromIntent(intent: Intent): WifiP2pDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
    }

    @Suppress("DEPRECATION")
    private fun getNetworkInfoFromIntent(intent: Intent): android.net.NetworkInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }
    }

    private fun requestPeers() {
        if (!checkWifiDirectPermission()) return
        try {
            wifiP2pManager?.requestPeers(channel) { peers ->
                val devices = peers.deviceList
                Log.d(TAG, "📋 ${devices.size} pair(s) détecté(s)")
                devices.forEach { device ->
                    Log.d(TAG, "   - ${device.deviceName} (${device.deviceAddress})")
                }

                if (devices.isEmpty()) return@requestPeers

                // Si on n'est pas connecté et pas en train de se connecter, on tente la connexion
                if (!isConnectedToGroup && !isConnecting.get()) {
                    val target = devices.firstOrNull { it.deviceAddress != thisDeviceAddress }
                    if (target != null) {
                        Log.i(TAG, "📱 Connexion à: ${target.deviceName} (${target.deviceAddress})")
                        connectToPeer(target)
                    }
                } else {
                    Log.d(TAG, "Connexion ignorée (connecté=$isConnectedToGroup, connecting=${isConnecting.get()})")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée requestPeers", e)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        if (!isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Connexion déjà en cours, ignoré")
            return
        }
        if (!checkWifiDirectPermission()) { isConnecting.set(false); return }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0  // Ce device sera client, l'autre GO
        }

        try {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Connexion WiFi Direct demandée à ${device.deviceName}")
                    onStatusUpdate("🔗 Connexion à ${device.deviceName}...")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ connect échoué: $reason ${reasonToString(reason)}")
                    isConnecting.set(false)
                    mainHandler.postDelayed({
                        if (isRunning.get() && !isConnectedToGroup) requestPeers()
                    }, 5000)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée connect", e)
            isConnecting.set(false)
        }

        // Timeout si pas de réponse dans 20 secondes (augmenté)
        mainHandler.postDelayed({
            if (isConnecting.get() && !isConnectedToGroup) {
                Log.w(TAG, "⏰ Timeout de connexion, réinitialisation")
                isConnecting.set(false)
                if (isRunning.get()) requestPeers()
            }
        }, 20000)
    }

    private fun handleConnectionChanged(intent: Intent) {
        val networkInfo = getNetworkInfoFromIntent(intent)

        if (networkInfo?.isConnected == true) {
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info == null) {
                    Log.e(TAG, "requestConnectionInfo → null")
                    return@requestConnectionInfo
                }

                groupOwnerIp = info.groupOwnerAddress
                isGroupOwner = info.isGroupOwner
                isConnectedToGroup = true
                isConnecting.set(false)
                isBecomingGO.set(false)

                Log.i(TAG, "✅ Groupe WiFi Direct rejoint")
                Log.i(TAG, "   GO IP : ${groupOwnerIp?.hostAddress}")
                Log.i(TAG, "   Rôle  : ${if (isGroupOwner) "GROUP OWNER 👑" else "CLIENT"}")

                if (isGroupOwner) {
                    onStatusUpdate("👑 En attente de clients...")
                } else {
                    val goIp = groupOwnerIp
                    if (goIp != null) {
                        Log.i(TAG, "🔌 Client → connexion TCP au GO dans 1.5s")
                        mainHandler.postDelayed({ connectToGroupOwnerTcp() }, 1500)
                    } else {
                        Log.e(TAG, "❌ IP GO inconnue")
                    }
                }
            }
        } else {
            if (isConnectedToGroup) {
                Log.i(TAG, "🔴 Déconnecté du groupe WiFi Direct")
                val wasGO = isGroupOwner
                isConnectedToGroup = false
                isGroupOwner = false
                groupOwnerIp = null
                isConnecting.set(false)
                isBecomingGO.set(false)
                tcpConnectInProgress.set(false)
                tcpConnections.clear()
                onStatusUpdate("📡 Recherche de téléphones...")

                mainHandler.postDelayed({
                    if (isRunning.get()) {
                        startDiscovery()
                        if (wasGO) {
                            mainHandler.postDelayed({
                                if (isRunning.get() && !isConnectedToGroup) becomeGroupOwner()
                            }, 3000)
                        }
                    }
                }, 2000)
            }
        }
    }

    // ==================== CONNEXION TCP AU GROUP OWNER ====================

    private fun connectToGroupOwnerTcp() {
        if (!isRunning.get() || isGroupOwner) return
        if (tcpConnectInProgress.getAndSet(true)) {
            Log.d(TAG, "Connexion TCP GO déjà en cours")
            return
        }

        executor.execute {
            var retries = 0
            val maxRetries = 5
            while (isRunning.get() && isConnectedToGroup && !isGroupOwner && retries < maxRetries) {
                try {
                    val goIp = groupOwnerIp ?: break
                    Log.i(TAG, "🔌 TCP tentative ${retries + 1}/$maxRetries → ${goIp.hostAddress}:$TCP_PORT")

                    val socket = Socket()
                    socket.connect(InetSocketAddress(goIp, TCP_PORT), 8000)

                    Log.i(TAG, "✅ TCP connecté au GO!")
                    tcpConnectInProgress.set(false)
                    handleTcpConnection(socket, goIp.hostAddress ?: "")
                    return@execute
                } catch (e: ConnectException) {
                    retries++
                    Log.w(TAG, "⏳ GO pas prêt (tentative $retries): ${e.message}")
                    Thread.sleep(2000)
                } catch (e: SocketTimeoutException) {
                    retries++
                    Log.w(TAG, "⏱️ Timeout (tentative $retries)")
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur TCP: ${e.message}")
                    break
                }
            }

            tcpConnectInProgress.set(false)
            if (isRunning.get() && isConnectedToGroup && !isGroupOwner) {
                Log.e(TAG, "❌ Échec connexion GO après $maxRetries tentatives — retry dans 10s")
                mainHandler.postDelayed({
                    if (isRunning.get() && isConnectedToGroup && !isGroupOwner) {
                        connectToGroupOwnerTcp()
                    }
                }, 10000)
            }
        }
    }

    // ==================== SERVEUR TCP ====================

    private fun startTcpServer() {
        executor.execute {
            val port = try {
                serverSocket = ServerSocket(TCP_PORT)
                TCP_PORT
            } catch (e: BindException) {
                Log.w(TAG, "Port $TCP_PORT occupé → essai ${TCP_PORT + 1}")
                try {
                    serverSocket = ServerSocket(TCP_PORT + 1)
                    TCP_PORT + 1
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Impossible d'ouvrir un port TCP: ${e2.message}")
                    onError("Port TCP occupé")
                    return@execute
                }
            }

            Log.i(TAG, "🖧 Serveur TCP prêt sur port $port")

            while (isRunning.get()) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    val clientIp = clientSocket.inetAddress.hostAddress ?: "inconnu"
                    Log.i(TAG, "🔥 Connexion TCP entrante de $clientIp")
                    executor.execute { handleTcpConnection(clientSocket, clientIp) }
                } catch (e: SocketException) {
                    if (isRunning.get()) Log.e(TAG, "❌ ServerSocket fermé: ${e.message}")
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "❌ accept(): ${e.message}")
                }
            }
        }
    }

    // ==================== GESTION D'UNE SESSION TCP ====================

    private fun handleTcpConnection(socket: Socket, clientIp: String) {
        var nodeId: String? = null
        try {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)

            sendIdentity(writer)
            Log.i(TAG, "📡 Session TCP active avec $clientIp")

            var consecutiveTimeouts = 0

            while (isRunning.get() && !socket.isClosed) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.d(TAG, "Connexion fermée par $clientIp")
                        break
                    }
                    if (line.isBlank()) continue

                    consecutiveTimeouts = 0

                    val packet = try {
                        gson.fromJson(line, MeshPacket::class.java)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON invalide de $clientIp: ${e.message}")
                        continue
                    }

                    if (nodeId == null && packet.senderId != myId) {
                        nodeId = packet.senderId
                        tcpConnections[nodeId]?.let { old ->
                            try { old.close() } catch (e: Exception) { }
                        }
                        tcpConnections[nodeId!!] = socket
                        Log.i(TAG, "✅ Pair enregistré: ${packet.senderPseudo} [$nodeId] @ $clientIp")
                        updateNodeInfo(packet.senderId, packet.senderPseudo, clientIp, true)
                    } else if (nodeId != null) {
                        knownNodes[nodeId]?.lastSeen = System.currentTimeMillis()
                    }

                    handleIncomingPacket(packet, clientIp)

                } catch (e: SocketTimeoutException) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 4) {
                        Log.w(TAG, "⚠️ $clientIp silencieux depuis ${consecutiveTimeouts * 30}s")
                    }
                } catch (e: IOException) {
                    if (isRunning.get()) Log.d(TAG, "📴 IO fermée $clientIp: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Erreur session $clientIp: ${e.message}")
        } finally {
            nodeId?.let { id ->
                tcpConnections.remove(id)
                knownNodes[id]?.let { node ->
                    node.isConnected = false
                    Log.i(TAG, "🔌 Déconnexion: ${node.pseudo}")
                    mainHandler.post {
                        onNodeLost(id)
                        updateConnectionStatus()
                    }
                }
            }
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun sendIdentity(writer: PrintWriter) {
        try {
            val packet = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderPseudo = myPseudo,
                receiver = "TOUS",
                content = "HELLO:$myPseudo",
                type = PacketType.NODE_INFO,
                timestamp = System.currentTimeMillis(),
                tcpPort = TCP_PORT
            )
            writer.println(gson.toJson(packet))
            writer.flush()
            Log.i(TAG, "✅ Identité envoyée: $myPseudo")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur envoi identité: ${e.message}")
        }
    }

    // ==================== NŒUDS ====================

    private fun updateNodeInfo(nodeId: String, pseudo: String, ip: String, isDirectNeighbor: Boolean) {
        val existing = knownNodes[nodeId]
        if (existing != null) {
            existing.lastSeen = System.currentTimeMillis()
            existing.pseudo = pseudo
            existing.ip = ip
            existing.isConnected = true
            if (isDirectNeighbor) existing.isDirectNeighbor = true
        } else {
            knownNodes[nodeId] = NodeInfo(
                nodeId = nodeId,
                pseudo = pseudo,
                ip = ip,
                lastSeen = System.currentTimeMillis(),
                isConnected = true,
                isDirectNeighbor = isDirectNeighbor,
                hopDistance = if (isDirectNeighbor) 1 else Int.MAX_VALUE
            )
            val meshNode = MeshNode(
                deviceId = nodeId,
                deviceName = pseudo,
                pseudo = pseudo,
                connectionType = ConnectionType.WIFI_DIRECT,
                lastSeen = System.currentTimeMillis(),
                isDirectConnection = isDirectNeighbor
            )
            mainHandler.post {
                onNodeDiscovered(meshNode)
                updateConnectionStatus()
            }
        }
    }

    // ==================== HEARTBEAT ====================

    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            if (isRunning.get()) {
                try {
                    sendHeartbeatToAll()
                    checkStaleNodes()
                } catch (e: Exception) {
                    Log.d(TAG, "Erreur heartbeat: ${e.message}")
                }
            }
        }, 5, HEARTBEAT_INTERVAL / 1000, TimeUnit.SECONDS)
    }

    private fun sendHeartbeatToAll() {
        if (tcpConnections.isEmpty()) return
        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",
            content = "HEARTBEAT",
            type = PacketType.HEARTBEAT,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            tcpPort = TCP_PORT
        )
        val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)

        val dead = mutableListOf<String>()
        tcpConnections.forEach { (id, socket) ->
            try {
                if (socket.isConnected && !socket.isClosed) {
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                } else dead.add(id)
            } catch (e: Exception) {
                dead.add(id)
            }
        }
        dead.forEach { id ->
            tcpConnections.remove(id)?.let { try { it.close() } catch (e: Exception) { } }
            knownNodes[id]?.isConnected = false
        }
    }

    private fun checkStaleNodes() {
        val now = System.currentTimeMillis()
        val stale = knownNodes.filter { (id, info) ->
            id != myId && (now - info.lastSeen) > NODE_TIMEOUT
        }
        stale.forEach { (nodeId, info) ->
            Log.w(TAG, "⚠️ Nœud perdu (timeout): ${info.pseudo}")
            knownNodes.remove(nodeId)
            tcpConnections.remove(nodeId)?.let { try { it.close() } catch (e: Exception) { } }
            routingTable.remove(nodeId)
            mainHandler.post {
                onNodeLost(nodeId)
                onStatusUpdate("🔴 Perdu: ${info.pseudo}")
                updateConnectionStatus()
            }
        }
    }

    private fun updateConnectionStatus() {
        val connected = knownNodes.values.count { it.isDirectNeighbor && it.isConnected }
        if (connected > 0) {
            onStatusUpdate("✅ $connected voisin(s) connecté(s)")
        } else {
            onStatusUpdate("📡 Recherche de téléphones...")
        }
    }

    // ==================== ENVOI ====================

    fun sendPacket(packet: MeshPacket) {
        if (tcpConnections.isEmpty()) {
            Log.w(TAG, "⚠️ Aucune connexion TCP")
            return
        }
        val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
        tcpConnections.forEach { (id, socket) ->
            executor.execute {
                try {
                    if (socket.isConnected && !socket.isClosed) {
                        socket.getOutputStream().write(data)
                        socket.getOutputStream().flush()
                    } else tcpConnections.remove(id)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Envoi à $id: ${e.message}")
                    tcpConnections.remove(id)
                }
            }
        }
    }

    // ==================== RÉCEPTION ====================

    private fun handleIncomingPacket(packet: MeshPacket, sourceIp: String) {
        if (seenPacketIds.contains(packet.id)) return
        seenPacketIds.add(packet.id)
        if (seenPacketIds.size > 1000) seenPacketIds.clear()

        Log.i(TAG, "📥 [${packet.type}] ${packet.senderPseudo}: ${packet.content.take(60)}")

        when (packet.type) {
            PacketType.HEARTBEAT -> {
                knownNodes[packet.senderId]?.lastSeen = System.currentTimeMillis()
            }
            PacketType.NODE_INFO -> {
                updateNodeInfo(packet.senderId, packet.senderPseudo, sourceIp, true)
            }
            else -> {
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
                    mainHandler.post { onMessageReceived(chatMsg) }
                }
                // Multi-hop relay
                if (packet.hopCount < MAX_HOP_COUNT) {
                    sendPacket(packet.copy(hopCount = packet.hopCount + 1))
                }
            }
        }
    }

    // ==================== UTILITAIRES ====================

    private fun checkWifiDirectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.49.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress
                    if (ip != null && !ip.startsWith("127.")) return ip
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur getLocalIpAddress", e)
        }
        return "192.168.49.1"
    }

    private fun reasonToString(reason: Int) = when (reason) {
        0 -> "(ERROR)"
        1 -> "(P2P_UNSUPPORTED)"
        2 -> "(BUSY)"
        3 -> "(NO_SERVICE_REQUESTS)"
        else -> "($reason)"
    }

    // ==================== ARRÊT ====================

    fun stop() {
        Log.i(TAG, "🛑 Arrêt MeshManager")
        isRunning.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        heartbeatExecutor.shutdownNow()
        executor.shutdownNow()

        try { serverSocket?.close() } catch (e: Exception) { }
        tcpConnections.values.forEach { try { it.close() } catch (e: Exception) { } }
        tcpConnections.clear()

        if (receiverRegistered) {
            try { context.unregisterReceiver(wifiDirectReceiver) } catch (e: Exception) { }
            receiverRegistered = false
        }
        if (checkWifiDirectPermission()) {
            try { wifiP2pManager?.removeGroup(channel, null) } catch (e: Exception) { }
        }

        knownNodes.clear()
        seenPacketIds.clear()
        routingTable.clear()
    }
}