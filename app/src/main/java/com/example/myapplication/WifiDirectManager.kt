package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class WifiDirectManager(
    private val context: Context,
    private val myId: String,
    private val myPseudo: String,
    private val myTcpPort: Int,
    private val onStatusUpdate: (String) -> Unit,
    private val onPeerDiscovered: (WifiP2pDevice) -> Unit,
    private val onMessageReceived: (MeshPacket) -> Unit,
    private val onTcpConnectionEstablished: (String, Socket) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "WifiDirectManager"
    private val gson = Gson()

    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }

    private val channel: WifiP2pManager.Channel by lazy {
        wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    }

    private val connectedPeers = ConcurrentHashMap<String, WifiP2pDevice>()
    private val tcpSockets = ConcurrentHashMap<String, Socket>()
    private val receivedMessageIds = Collections.synchronizedSet(mutableSetOf<String>())

    private val executor = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = true
    private var serverSocket: ServerSocket? = null
    private var thisDeviceAddress: String? = null
    private var groupOwnerIp: InetAddress? = null
    private var amIGroupOwner = false
    private var isConnectedToGroup = false
    private var pendingConnection: WifiP2pDevice? = null

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "📡 WiFi Direct ${if (enabled) "activé" else "désactivé"}")
                    if (enabled) {
                        startDiscovery()
                        createGroup()
                        onStatusUpdate("📡 WiFi Direct actif")
                    } else {
                        onError("⚠️ WiFi Direct désactivé")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "👥 Liste des pairs modifiée")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.i(TAG, "🔄 CONNECTION CHANGED ACTION reçu")
                    handleConnectionChange(intent)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        thisDeviceAddress = it.deviceAddress
                        Log.d(TAG, "📱 Cet appareil: ${it.deviceName} (${it.deviceAddress})")
                        onStatusUpdate("📱 ${it.deviceName}")
                    }
                }
            }
        }
    }

    init {
        registerReceiver()
        startTcpServer()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(wifiReceiver, filter)
            }
            Log.d(TAG, "✅ Récepteur enregistré")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur enregistrement", e)
        }
    }

    private fun checkPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun startTcpServer() {
        executor.execute {
            try {
                serverSocket = ServerSocket(0)
                val localPort = serverSocket!!.localPort
                Log.i(TAG, "🖧 SERVEUR TCP local sur port $localPort")
                Log.i(TAG, "📡 Mon port TCP est: $localPort (attendu: $myTcpPort)")

                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    val clientIp = clientSocket.inetAddress.hostAddress
                    Log.i(TAG, "🔥 NOUVELLE CONNEXION TCP ENTRANTE de $clientIp")
                    executor.execute { handleTcpClient(clientSocket, clientIp) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "❌ Erreur serveur TCP", e)
            }
        }
    }

    private fun handleTcpClient(socket: Socket, ip: String) {
        var peerId: String? = null
        try {
            socket.soTimeout = 60000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?

            Log.i(TAG, "📡 Début écoute TCP sur connexion de $ip")

            while (isRunning && !socket.isClosed) {
                try {
                    line = reader.readLine()
                    if (line != null) {
                        val packet = gson.fromJson(line, MeshPacket::class.java)
                        Log.d(TAG, "📨 Packet reçu: ${packet.id} de ${packet.senderPseudo}")

                        if (peerId == null && packet.senderId != myId) {
                            peerId = packet.senderId
                            tcpSockets[peerId] = socket
                            Log.i(TAG, "✅ Identifié peer: ${packet.senderPseudo} ($peerId)")
                            onTcpConnectionEstablished(peerId, socket)
                        }

                        if (!receivedMessageIds.contains(packet.id)) {
                            receivedMessageIds.add(packet.id)
                            Log.i(TAG, "📨 Message reçu de ${packet.senderPseudo}: ${packet.content}")
                            onMessageReceived(packet)
                        } else {
                            Log.d(TAG, "⏭️ Doublon ignoré: ${packet.id}")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal, continue
                } catch (e: IOException) {
                    if (isRunning) Log.d(TAG, "Connexion TCP fermée: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Erreur gestion client TCP: ${e.message}")
        } finally {
            peerId?.let {
                tcpSockets.remove(it)
                Log.i(TAG, "🔌 Connexion TCP fermée pour $peerId")
            }
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun handleConnectionChange(intent: Intent) {
        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        if (networkInfo?.isConnected == true) {
            Log.i(TAG, "🔌 Connecté au groupe WiFi Direct")
            isConnectedToGroup = true
            onStatusUpdate("🔌 Connecté au groupe")

            wifiP2pManager.requestConnectionInfo(channel) { info ->
                val goIpTemp = info.groupOwnerAddress
                val isGo = info.isGroupOwner

                groupOwnerIp = goIpTemp
                amIGroupOwner = isGo

                Log.i(TAG, "📡 IP du groupe: $goIpTemp")
                Log.i(TAG, "👑 Je suis ${if (isGo) "GROUP OWNER (GO)" else "CLIENT"}")

                // Si je suis client, je dois me connecter au GO via TCP
                if (!isGo && goIpTemp != null) {
                    Log.i(TAG, "🔌 CLIENT - Tentative de connexion TCP au GO sur ${goIpTemp.hostAddress}:$myTcpPort")

                    // Attendre un peu que le GO soit prêt
                    handler.postDelayed({
                        executor.execute {
                            connectToGroupOwner()
                        }
                    }, 2000)
                } else if (isGo) {
                    Log.i(TAG, "👑 GROUP OWNER - Attente des connexions des clients")
                    // Envoyer un message broadcast pour annoncer ma présence
                    broadcastMyPresence()
                }
            }
        } else {
            if (isConnectedToGroup) {
                Log.d(TAG, "🔌 Déconnecté du groupe")
                isConnectedToGroup = false
                groupOwnerIp = null
                // Nettoyer les connexions
                tcpSockets.values.forEach { try { it.close() } catch(e: Exception) {} }
                tcpSockets.clear()
            }
        }
    }
    private fun connectToGroupOwner() {
        try {
            // Utiliser une variable locale pour éviter le problème de smart cast
            val goIp = groupOwnerIp
            if (goIp == null) {
                Log.e(TAG, "❌ IP du GO inconnue")
                return
            }

            Log.i(TAG, "🔌 Connexion TCP au GO ${goIp.hostAddress}:$myTcpPort")
            val socket = Socket()
            socket.connect(InetSocketAddress(goIp, myTcpPort), 15000)
            Log.i(TAG, "✅ Connexion TCP client -> GO établie!")

            // Enregistrer la connexion
            tcpSockets["group_owner"] = socket
            onTcpConnectionEstablished("group_owner", socket)

            // Envoyer mes informations
            sendMyInfo(socket)

            // Démarrer l'écoute sur cette connexion
            handleTcpClient(socket, goIp.hostAddress)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Échec connexion TCP au GO: ${e.message}")
            // Réessayer après un délai
            handler.postDelayed({
                if (isRunning && isConnectedToGroup) {
                    Log.i(TAG, "🔄 Nouvelle tentative de connexion au GO...")
                    connectToGroupOwner()
                }
            }, 5000)
        }
    }
    private fun broadcastMyPresence() {
        // Envoyer un message à tous les clients via le broadcast WiFi Direct
        // Les clients se connecteront via TCP après avoir reçu ce message
        val presencePacket = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",
            content = "GO_PRESENT:${myTcpPort}",
            type = PacketType.NODE_INFO,
            hopCount = 0,
            timestamp = System.currentTimeMillis()
        )
        // On ne peut pas envoyer via TCP car pas encore de clients, on utilise un broadcast
        // Les clients verront ce message via le broadcast WiFi Direct
    }

    private fun sendMyInfo(socket: Socket) {
        try {
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Envoyer mon pseudo
            val pseudoPacket = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderPseudo = myPseudo,
                receiver = "TOUS",
                content = "PSEUDO:$myPseudo",
                type = PacketType.NODE_INFO,
                hopCount = 0,
                timestamp = System.currentTimeMillis()
            )
            writer.println(gson.toJson(pseudoPacket))

            // Envoyer message de bienvenue
            val welcomePacket = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderPseudo = myPseudo,
                receiver = "TOUS",
                content = "👋 ${myPseudo} a rejoint le réseau",
                type = PacketType.MESSAGE,
                hopCount = 0,
                timestamp = System.currentTimeMillis()
            )
            writer.println(gson.toJson(welcomePacket))
            writer.flush()

            Log.i(TAG, "✅ Infos envoyées au GO")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur envoi infos: ${e.message}")
        }
    }

    fun startDiscovery() {
        if (!checkPermission()) {
            onError("❌ Permissions WiFi manquantes")
            return
        }
        try {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Découverte démarrée")
                    onStatusUpdate("🔍 Recherche de téléphones...")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Échec découverte: $reason")
                    onError("⚠️ Découverte: échec ($reason)")
                    handler.postDelayed({ startDiscovery() }, 10000)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permission refusée", e)
        }
    }

    private fun createGroup() {
        if (!checkPermission()) return
        try {
            wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Groupe créé")
                }
                override fun onFailure(reason: Int) {
                    Log.d(TAG, "ℹ️ Groupe existant ou erreur: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permission refusée", e)
        }
    }

    private fun requestPeers() {
        if (!checkPermission()) return
        try {
            wifiP2pManager.requestPeers(channel) { peers ->
                val devices = peers.deviceList
                Log.d(TAG, "👥 ${devices.size} pairs trouvés")
                devices.forEach { device ->
                    if (device.deviceAddress != thisDeviceAddress && device.deviceAddress != null) {
                        Log.i(TAG, "📱 Découvert: ${device.deviceName} (${device.deviceAddress})")
                        onPeerDiscovered(device)
                        connectToPeer(device)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permission refusée", e)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        if (connectedPeers.containsKey(device.deviceAddress)) {
            Log.d(TAG, "Déjà connecté à ${device.deviceName}")
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15 // 0-15, plus élevé = plus de chance d'être GO
        }

        if (!checkPermission()) return

        try {
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Connexion demandée à ${device.deviceName}")
                    connectedPeers[device.deviceAddress] = device
                    onStatusUpdate("✅ Connexion: ${device.deviceName}")
                    pendingConnection = device
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Échec connexion à ${device.deviceName}: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permission refusée", e)
        }
    }

    fun sendMessage(packet: MeshPacket) {
        val json = gson.toJson(packet)
        val data = (json + "\n").toByteArray()

        Log.d(TAG, "📤 Envoi message via ${tcpSockets.size} connexion(s) TCP")

        tcpSockets.values.forEach { socket ->
            executor.execute {
                try {
                    if (socket.isConnected && !socket.isClosed) {
                        socket.getOutputStream().write(data)
                        socket.getOutputStream().flush()
                        Log.d(TAG, "📤 Message envoyé via TCP")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur envoi", e)
                }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "🛑 Arrêt WifiDirectManager")
        isRunning = false
        isConnectedToGroup = false

        try { context.unregisterReceiver(wifiReceiver) } catch (e: Exception) { }
        try { serverSocket?.close() } catch (e: Exception) { }
        try { tcpSockets.values.forEach { it.close() } } catch (e: Exception) { }

        if (checkPermission()) {
            try { wifiP2pManager.removeGroup(channel, null) } catch (e: Exception) { }
        }

        handler.removeCallbacksAndMessages(null)
        receivedMessageIds.clear()
        connectedPeers.clear()
        tcpSockets.clear()
    }
}