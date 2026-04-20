package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.BindException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ByteArray::class.java, JsonSerializer<ByteArray> { src, _, _ ->
            JsonPrimitive(Base64.encodeToString(src, Base64.NO_WRAP))
        })
        .registerTypeAdapter(ByteArray::class.java, JsonDeserializer { json, _, _ ->
            Base64.decode(json.asString, Base64.NO_WRAP)
        })
        .create()

    private val TAG = "MeshManager"
    private val TCP_PORT = 8888
    private val MAX_HOP_COUNT = 7
    private val HEARTBEAT_INTERVAL = 10000L
    private val NODE_TIMEOUT = 35000L
    private val ROUTE_UPDATE_INTERVAL = 15000L

    private val knownNodes = ConcurrentHashMap<String, NodeInfo>()
    private val seenPacketIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val tcpConnections = ConcurrentHashMap<String, Socket>()
    private val socketToNodeId = ConcurrentHashMap<Socket, String>()
    private val routingTable = ConcurrentHashMap<String, MutableList<RoutingEntry>>()
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    // WiFi Direct
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var thisDeviceAddress: String? = null
    private var groupOwnerIp: InetAddress? = null
    private var isGroupOwner = false
    private var isConnectedToGroup = false
    private var receiverRegistered = false

    private val isConnecting = AtomicBoolean(false)
    private val tcpConnectInProgress = AtomicBoolean(false)
    private val isBecomingGO = AtomicBoolean(false)
    private var discoverRetryCount = 0

    // Ensemble des appareils déjà connectés pour éviter les reconnexions
    private val previouslyConnectedDevices = mutableSetOf<String>()

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
            startRouteUpdatePropagation()

            onStatusUpdate("📡 Recherche de téléphones...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur démarrage: ${e.message}", e)
            onError("Erreur démarrage: ${e.message}")
        }
    }

    // ==================== WIFI DIRECT (sans suppression systématique du groupe) ====================
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
                    }
                }
            )

            registerWifiReceiver()

            // IMPORTANT : Ne pas supprimer le groupe existant pour éviter de forcer une reconnexion
            // removeGroupAndWait() est supprimé

            // Démarrer la découverte
            startDiscovery()

            // Timer pour devenir GO si aucun pair trouvé
            mainHandler.postDelayed({
                if (isRunning.get() && !isConnectedToGroup && !isConnecting.get() && !isBecomingGO.get()) {
                    Log.i(TAG, "⏱️ Aucun pair trouvé après 10s → je deviens Group Owner")
                    becomeGroupOwner()
                }
            }, 10000)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur init WiFi Direct: ${e.message}", e)
            onError("Erreur WiFi Direct: ${e.message}")
        }
    }

    // Supprimé removeGroupAndWait() car il force une reconnexion à chaque démarrage

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
                    discoverRetryCount = 0
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "⚠️ discoverPeers: $reason ${reasonToString(reason)}")
                    if (reason == 0) {
                        discoverRetryCount++
                        val delay = (5000L * discoverRetryCount).coerceAtMost(30000L)
                        if (discoverRetryCount >= 5) {
                            Log.e(TAG, "❌ Découverte toujours en échec après 5 tentatives.")
                            onError("WiFi Direct indisponible - redémarrez le WiFi")
                        } else {
                            mainHandler.postDelayed({
                                if (isRunning.get() && !isConnectedToGroup) startDiscovery()
                            }, delay)
                        }
                    } else if (reason != 2) {
                        mainHandler.postDelayed({
                            if (isRunning.get() && !isConnectedToGroup) startDiscovery()
                        }, 5000L)
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
                    isBecomingGO.set(false)
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

                // Filtrer les appareils déjà connectés pour éviter les reconnexions inutiles
                val validPeers = devices.filter {
                    it.deviceName != null &&
                            !it.deviceName.contains("HP DeskJet", ignoreCase = true) &&
                            it.deviceAddress != thisDeviceAddress &&
                            !previouslyConnectedDevices.contains(it.deviceAddress)
                }

                if (validPeers.isEmpty()) {
                    Log.i(TAG, "📭 Aucun nouveau pair valide")
                    return@requestPeers
                }

                // Si je suis déjà GO et connecté, ne pas tenter de me reconnecter
                if (isConnectedToGroup && isGroupOwner) {
                    Log.d(TAG, "GO déjà actif, pas de nouvelle connexion")
                    return@requestPeers
                }

                // Si je ne suis pas connecté, je me connecte au premier nouveau pair
                if (!isConnectedToGroup && !isConnecting.get()) {
                    val sorted = validPeers.sortedBy { it.deviceAddress }
                    Log.i(TAG, "📱 Je me connecte au nouveau pair ${sorted.first().deviceName}")
                    connectToPeerWithRetry(sorted.first(), 0)
                } else {
                    Log.d(TAG, "Connexion ignorée (connecté=$isConnectedToGroup, connecting=${isConnecting.get()})")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée requestPeers", e)
        }
    }

    private fun connectToPeerWithRetry(device: WifiP2pDevice, attempt: Int) {
        if (attempt >= 3) {
            Log.e(TAG, "❌ Échec de connexion après 3 tentatives, abandon")
            onError("Impossible de se connecter à ${device.deviceName}")
            isConnecting.set(false)
            startDiscovery()
            return
        }
        if (attempt > 0) {
            val delay = 2000L * attempt
            Log.i(TAG, "⏳ Nouvelle tentative de connexion dans ${delay}ms (tentative ${attempt + 1}/3)")
            mainHandler.postDelayed({
                if (isRunning.get() && !isConnectedToGroup) {
                    connectToPeer(device, attempt + 1)
                }
            }, delay)
        } else {
            connectToPeer(device, attempt + 1)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice, attempt: Int = 1) {
        if (!isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Connexion déjà en cours, ignoré")
            return
        }
        if (!checkWifiDirectPermission()) { isConnecting.set(false); return }

        // Si déjà dans un groupe, ne pas tenter de connexion supplémentaire
        if (isGroupOwner || isConnectedToGroup) {
            Log.w(TAG, "Déjà dans un groupe, connexion ignorée")
            isConnecting.set(false)
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }

        try {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Connexion WiFi Direct demandée à ${device.deviceName}")
                    onStatusUpdate("🔗 Connexion à ${device.deviceName}...")
                    // Ajouter aux appareils déjà connectés pour éviter de le redemander
                    previouslyConnectedDevices.add(device.deviceAddress)
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ connect échoué: $reason ${reasonToString(reason)}")
                    isConnecting.set(false)
                    if (reason == 0) {
                        connectToPeerWithRetry(device, attempt)
                    } else {
                        mainHandler.postDelayed({
                            if (isRunning.get() && !isConnectedToGroup) requestPeers()
                        }, 5000)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée connect", e)
            isConnecting.set(false)
        }

        mainHandler.postDelayed({
            if (isConnecting.get() && !isConnectedToGroup) {
                Log.w(TAG, "⏰ Timeout de connexion (10s)")
                isConnecting.set(false)
                try {
                    wifiP2pManager?.cancelConnect(channel, null)
                } catch (e: Exception) { }
                if (isRunning.get()) requestPeers()
            }
        }, 10000)
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
                socketToNodeId.clear()
                onStatusUpdate("📡 Recherche de téléphones...")

                // Attendre avant de redémarrer la découverte pour laisser le système se stabiliser
                mainHandler.postDelayed({
                    if (isRunning.get() && !isConnectedToGroup) {
                        startDiscovery()
                        if (wasGO) {
                            mainHandler.postDelayed({
                                if (isRunning.get() && !isConnectedToGroup) becomeGroupOwner()
                            }, 5000)
                        }
                    }
                }, 5000)
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
                        socketToNodeId[socket] = nodeId
                        tcpConnections[nodeId!!]?.let { old ->
                            try { old.close() } catch (e: Exception) { }
                        }
                        tcpConnections[nodeId!!] = socket
                        Log.i(TAG, "✅ Pair enregistré: ${packet.senderPseudo} [$nodeId] @ $clientIp")
                        updateNodeInfo(packet.senderId, packet.senderPseudo, clientIp, true)

                        // Ajouter aux appareils déjà connectés pour éviter de le recontacter
                        previouslyConnectedDevices.add(nodeId)

                        val directEntry = RoutingEntry(nodeId, nodeId, alpha = 10.0, beta = 1.0, timestamp = System.currentTimeMillis())
                        routingTable.getOrPut(nodeId) { mutableListOf() }.add(directEntry)

                        if (isGroupOwner) {
                            broadcastClientList()
                        }
                    } else if (nodeId != null) {
                        knownNodes[nodeId]?.lastSeen = System.currentTimeMillis()
                    }

                    handleIncomingPacket(packet, clientIp, socket)

                } catch (e: SocketTimeoutException) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 4) {
                        Log.w(TAG, "⚠️ $clientIp silencieux depuis ${consecutiveTimeouts * 30}s")
                    }
                } catch (e: IOException) {
                    if (isRunning.get()) Log.d(TAG, "📴 IO fermée $clientIp: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur inattendue dans la session TCP", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Erreur session $clientIp: ${e.message}")
        } finally {
            nodeId?.let { id ->
                socketToNodeId.remove(socket)
                tcpConnections.remove(id)
                knownNodes[id]?.let { node ->
                    node.isConnected = false
                    Log.i(TAG, "🔌 Déconnexion: ${node.pseudo}")
                    mainHandler.post {
                        onNodeLost(id)
                        updateConnectionStatus()
                    }
                }
                if (isGroupOwner) {
                    broadcastClientList()
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
                timestamp = System.currentTimeMillis()
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
                isDirectConnection = isDirectNeighbor,
                ip = ip,
                isConnected = true
            )
            mainHandler.post {
                onNodeDiscovered(meshNode)
                updateConnectionStatus()
            }
        }
    }

    // ==================== HEARTBEAT ET PROPAGATION ====================
    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            if (isRunning.get()) {
                try {
                    sendHeartbeatToAll()
                    checkStaleNodes()
                    cleanRoutingTable()
                    if (isGroupOwner) {
                        broadcastClientList()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Erreur heartbeat: ${e.message}")
                }
            }
        }, 5, HEARTBEAT_INTERVAL / 1000, TimeUnit.SECONDS)
    }

    private fun startRouteUpdatePropagation() {
        heartbeatExecutor.scheduleAtFixedRate({
            if (isRunning.get() && tcpConnections.isNotEmpty()) {
                sendRouteUpdate()
            }
        }, 5, ROUTE_UPDATE_INTERVAL / 1000, TimeUnit.SECONDS)
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
            timestamp = System.currentTimeMillis()
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

    private fun buildClientList(): List<ClientInfo> {
        val clients = mutableListOf(ClientInfo(myId, myPseudo, myIp))
        tcpConnections.keys.forEach { clientId ->
            val node = knownNodes[clientId]
            if (node != null) {
                clients.add(ClientInfo(clientId, node.pseudo, node.ip))
            } else {
                clients.add(ClientInfo(clientId, "Inconnu", ""))
            }
        }
        return clients
    }

    private fun broadcastClientList() {
        if (!isGroupOwner) return
        val clientList = buildClientList()
        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",
            content = "CLIENT_LIST",
            type = PacketType.CLIENT_LIST,
            timestamp = System.currentTimeMillis(),
            clientList = clientList
        )
        sendPacket(packet)
    }

    // ==================== ROUTAGE BAYÉSIEN ====================
    fun sendPacket(packet: MeshPacket, excludeSocket: Socket? = null) {
        val destination = packet.receiver

        if (destination == "TOUS") {
            val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
            if (isGroupOwner) {
                tcpConnections.forEach { (_, socket) ->
                    if (excludeSocket != socket) sendOverSocket(socket, data)
                }
            } else {
                tcpConnections.values.firstOrNull()?.let { sendOverSocket(it, data) }
            }
            return
        }

        if (tcpConnections.containsKey(destination)) {
            val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
            sendOverSocket(tcpConnections[destination]!!, data)
            updateReliability(destination, destination, true)
            return
        }

        val nextHop = selectNextHop(destination)
        if (nextHop != null && tcpConnections.containsKey(nextHop)) {
            val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
            sendOverSocket(tcpConnections[nextHop]!!, data)
            Log.i(TAG, "🔄 Routage bayésien: $destination via $nextHop")
        } else {
            Log.w(TAG, "⚠️ Aucune route connue vers $destination, tentative de découverte")
            val routeDiscovery = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderPseudo = myPseudo,
                receiver = "TOUS",
                content = "ROUTE_DISCOVERY:$destination",
                type = PacketType.ROUTE_DISCOVERY,
                hopCount = 0,
                timestamp = System.currentTimeMillis()
            )
            broadcastPacket(routeDiscovery)
        }
    }

    private fun broadcastPacket(packet: MeshPacket) {
        val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
        tcpConnections.values.forEach { socket ->
            sendOverSocket(socket, data)
        }
    }

    private fun sendOverSocket(socket: Socket, data: ByteArray) {
        executor.execute {
            try {
                if (socket.isConnected && !socket.isClosed) {
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur envoi socket", e)
            }
        }
    }

    private fun updateReliability(destinationId: String, nextHopId: String, success: Boolean) {
        val entries = routingTable.getOrPut(destinationId) { mutableListOf() }
        var entry = entries.find { it.nextHopId == nextHopId }
        if (entry == null) {
            entry = RoutingEntry(destinationId, nextHopId, alpha = 1.0, beta = 1.0)
            entries.add(entry)
        }
        if (success) {
            entry.alpha += 1.0
        } else {
            entry.beta += 1.0
        }
        entry.timestamp = System.currentTimeMillis()
        val reliability = entry.reliability
        Log.d(TAG, "📊 Fiabilité mise à jour: dest=$destinationId next=$nextHopId succ=$success → $reliability")

        if (destinationId != myId) {
            notifyNodeReliability(destinationId, reliability)
        }
    }

    private fun selectNextHop(destinationId: String): String? {
        val entries = routingTable[destinationId]
        if (entries.isNullOrEmpty()) return null
        val best = entries.maxByOrNull { it.toThompsonSample() }
        return best?.nextHopId
    }

    private fun cleanRoutingTable() {
        val now = System.currentTimeMillis()
        routingTable.entries.removeIf { (_, entries) ->
            entries.removeAll { now - it.timestamp > NODE_TIMEOUT }
            entries.isEmpty()
        }
    }

    private fun sendRouteUpdate() {
        if (tcpConnections.isEmpty()) return
        val bestRoutes = routingTable.flatMap { (dest, entries) ->
            entries.mapNotNull { entry ->
                if (entry.reliability > 0.3 && entry.nextHopId != dest) {
                    RoutingEntry(dest, entry.nextHopId, entry.alpha, entry.beta, entry.latency, entry.timestamp, entry.isRelayLink)
                } else null
            }
        }.take(30)
        if (bestRoutes.isEmpty()) return

        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",
            content = "ROUTE_UPDATE",
            type = PacketType.ROUTE_UPDATE,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            routeEntries = bestRoutes
        )
        sendPacket(packet)
        Log.d(TAG, "📡 Envoi ROUTE_UPDATE avec ${bestRoutes.size} entrées")
    }

    private fun mergeRouteEntries(entries: List<RoutingEntry>, fromNodeId: String) {
        for (receivedEntry in entries) {
            val destination = receivedEntry.destinationId
            val nextHop = receivedEntry.nextHopId
            if (nextHop != fromNodeId) continue

            val existingEntries = routingTable.getOrPut(destination) { mutableListOf() }
            val existing = existingEntries.find { it.nextHopId == fromNodeId }
            if (existing == null) {
                existingEntries.add(RoutingEntry(
                    destinationId = destination,
                    nextHopId = fromNodeId,
                    alpha = receivedEntry.alpha,
                    beta = receivedEntry.beta,
                    latency = receivedEntry.latency,
                    timestamp = receivedEntry.timestamp,
                    isRelayLink = receivedEntry.isRelayLink
                ))
            } else {
                if (receivedEntry.timestamp > existing.timestamp) {
                    existing.alpha = receivedEntry.alpha
                    existing.beta = receivedEntry.beta
                    existing.latency = receivedEntry.latency
                    existing.timestamp = receivedEntry.timestamp
                    existing.isRelayLink = receivedEntry.isRelayLink
                }
            }
            if (destination != myId) {
                val newReliability = (existing ?: receivedEntry).reliability
                notifyNodeReliability(destination, newReliability)
            }
        }
        Log.d(TAG, "🔀 Fusion ROUTE_UPDATE depuis $fromNodeId : ${entries.size} entrées")
    }

    private fun notifyNodeReliability(nodeId: String, reliability: Double) {
        val intent = Intent("MESH_NODE_RELIABILITY_UPDATE").apply {
            putExtra("node_id", nodeId)
            putExtra("reliability", reliability)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ==================== RÉCEPTION ET RELAI ====================
    private fun handleIncomingPacket(packet: MeshPacket, sourceIp: String, sourceSocket: Socket? = null) {
        if (seenPacketIds.contains(packet.id)) return
        seenPacketIds.add(packet.id)
        if (seenPacketIds.size > 1000) seenPacketIds.clear()

        val packetType = packet.type
        if (packetType == null) {
            Log.w(TAG, "Paquet reçu avec type null, ignoré")
            return
        }

        Log.i(TAG, "📥 [$packetType] ${packet.senderPseudo}: ${packet.content.take(60)}")

        when (packetType) {
            PacketType.HEARTBEAT -> {
                knownNodes[packet.senderId]?.lastSeen = System.currentTimeMillis()
            }
            PacketType.NODE_INFO -> {
                updateNodeInfo(packet.senderId, packet.senderPseudo, sourceIp, true)
            }
            PacketType.CLIENT_LIST -> {
                packet.clientList?.forEach { client ->
                    if (client.id != myId) {
                        val existing = knownNodes[client.id]
                        if (existing == null) {
                            val nodeInfo = NodeInfo(
                                nodeId = client.id,
                                pseudo = client.pseudo,
                                ip = client.ip,
                                lastSeen = System.currentTimeMillis(),
                                isConnected = true,
                                isDirectNeighbor = false,
                                hopDistance = 2
                            )
                            knownNodes[client.id] = nodeInfo
                            val meshNode = MeshNode(
                                deviceId = client.id,
                                deviceName = client.pseudo,
                                pseudo = client.pseudo,
                                connectionType = ConnectionType.RELAY_BRIDGE,
                                lastSeen = System.currentTimeMillis(),
                                isDirectConnection = false,
                                estimatedDistance = 0f,
                                isConnected = true,
                                ip = client.ip
                            )
                            mainHandler.post { onNodeDiscovered(meshNode) }
                        } else {
                            existing.lastSeen = System.currentTimeMillis()
                            existing.pseudo = client.pseudo
                            existing.ip = client.ip
                            existing.isConnected = true
                        }
                    }
                }
                return
            }
            PacketType.ROUTE_UPDATE -> {
                packet.routeEntries?.let { entries ->
                    mergeRouteEntries(entries, packet.senderId)
                }
                return
            }
            PacketType.ROUTE_DISCOVERY -> {
                val target = packet.content.substringAfter("ROUTE_DISCOVERY:").trim()
                if (target == myId) {
                    val response = MeshPacket(
                        id = UUID.randomUUID().toString(),
                        senderId = myId,
                        senderPseudo = myPseudo,
                        receiver = packet.senderId,
                        content = "ROUTE_RESPONSE",
                        type = PacketType.ACK,
                        timestamp = System.currentTimeMillis()
                    )
                    sendPacket(response)
                } else {
                    if (packet.hopCount < MAX_HOP_COUNT) {
                        val relayed = packet.copy(hopCount = packet.hopCount + 1)
                        sendPacket(relayed, excludeSocket = sourceSocket)
                    }
                }
                return
            }
            PacketType.ACK -> {
                updateReliability(packet.senderId, packet.senderId, true)
                return
            }
            else -> {
                if (packet.receiver == "TOUS" || packet.receiver == myId) {
                    var audioPath: String? = null
                    var filePath: String? = null

                    packet.audioData?.let { data ->
                        try {
                            val audioFile = File(context.cacheDir, "audio_${packet.id}.3gp")
                            audioFile.writeBytes(data)
                            if (audioFile.exists() && audioFile.length() > 0) {
                                audioPath = audioFile.absolutePath
                                Log.i(TAG, "✅ Audio sauvegardé: $audioPath (${data.size} bytes)")
                            } else {
                                Log.e(TAG, "❌ Échec sauvegarde audio")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur sauvegarde audio", e)
                        }
                    }

                    packet.fileData?.let { data ->
                        try {
                            val fileName = packet.fileName ?: "file_${packet.id}"
                            val file = File(context.cacheDir, fileName)
                            file.writeBytes(data)
                            if (file.exists() && file.length() > 0) {
                                filePath = file.absolutePath
                                Log.i(TAG, "✅ Fichier sauvegardé: $filePath (${data.size} bytes)")
                            } else {
                                Log.e(TAG, "❌ Échec sauvegarde fichier")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur sauvegarde fichier", e)
                        }
                    }

                    val chatMsg = ChatMessage(
                        id = packet.id,
                        sender = packet.senderPseudo,
                        content = packet.content,
                        isMine = false,
                        time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(packet.timestamp)),
                        status = MessageStatus.RECEIVED,
                        type = packetType,
                        receiverId = packet.receiver,
                        senderIdRaw = packet.senderId,
                        timestamp = packet.timestamp,
                        audioPath = audioPath,
                        filePath = filePath,
                        fileName = packet.fileName,
                        fileSize = packet.fileSize
                    )
                    mainHandler.post {
                        onMessageReceived(chatMsg)
                    }

                    if (packet.receiver == myId && packetType != PacketType.ACK) {
                        val ackPacket = MeshPacket(
                            id = UUID.randomUUID().toString(),
                            senderId = myId,
                            senderPseudo = myPseudo,
                            receiver = packet.senderId,
                            content = "ACK",
                            type = PacketType.ACK,
                            timestamp = System.currentTimeMillis()
                        )
                        sendPacket(ackPacket)
                    }
                }
            }
        }

        if (packet.senderId != myId) {
            updateReliability(packet.senderId, packet.senderId, true)
        }

        if (packet.hopCount < MAX_HOP_COUNT &&
            packetType != PacketType.HEARTBEAT &&
            packetType != PacketType.CLIENT_LIST &&
            packetType != PacketType.NODE_INFO &&
            packetType != PacketType.ROUTE_UPDATE &&
            packetType != PacketType.ACK &&
            packetType != PacketType.ROUTE_DISCOVERY) {

            if (packet.receiver != myId || packet.receiver == "TOUS") {
                val newPacket = packet.copy(hopCount = packet.hopCount + 1)
                Log.i(TAG, "🔄 Relai multi-saut (${packet.hopCount} -> ${newPacket.hopCount}) de ${packet.senderPseudo} vers ${packet.receiver}")
                sendPacket(newPacket, excludeSocket = sourceSocket)
            }
        }

        if (isGroupOwner && packet.receiver != "TOUS" && packet.receiver != myId) {
            Log.i(TAG, "🔄 Relai GO direct de ${packet.senderPseudo} vers ${packet.receiver}")
            sendPacket(packet)
        }

        if (isGroupOwner && packet.receiver == "TOUS") {
            Log.i(TAG, "📢 Relai GO broadcast de ${packet.senderPseudo} à tous les clients")
            sendPacket(packet, excludeSocket = sourceSocket)
        }
    }

    // ==================== UTILITAIRES ====================
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
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
            routingTable.values.forEach { entries ->
                entries.removeAll { it.nextHopId == nodeId || it.destinationId == nodeId }
            }
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
        socketToNodeId.clear()

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
        previouslyConnectedDevices.clear()
    }
}