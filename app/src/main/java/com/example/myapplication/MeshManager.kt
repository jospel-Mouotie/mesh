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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

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
    private val SCAN_DURATION_BEFORE_GO = 5000L // 5 secondes de scan minimum
    private val GO_FORMATION_DELAY = 8000L      // Délai total avant formation GO

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

    // CORRECTION: Gestion d'état du scan
    private val scanStartTime = AtomicLong(0)
    private val hasScannedEnough = AtomicBoolean(false)
    private val goFormationScheduled = AtomicBoolean(false)
    private val peersDiscoveredDuringScan = Collections.synchronizedSet(mutableSetOf<String>())

    // Ensemble des appareils déjà connectés pour éviter les reconnexions
    private val previouslyConnectedDevices = mutableSetOf<String>()

    // Transferts de fichiers actifs
    private val activeFileTransfers = ConcurrentHashMap<String, FileTransferSession>()
    private var fileTransferServer: ServerSocket? = null

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
        var hopDistance: Int = Int.MAX_VALUE,
        var bayesianReliability: Double = 0.5,
        // CORRECTION: Distance basée sur la latence réelle et le hop count
        var estimatedDistance: Float = -1f,  // -1 = inconnu
        var latencyMs: Double = 0.0,         // Latence mesurée en ms
        var lastLatencyCheck: Long = 0       // Dernier check de latence
    )

    private inner class FileTransferSession(
        val transferId: String,
        val destinationId: String,
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
        val chunkSize: Int,
        var currentChunk: Int = 0,
        var socket: Socket? = null,
        var outputStream: OutputStream? = null,
        var inputStream: InputStream? = null,
        var fileRaf: RandomAccessFile? = null,
        var isSender: Boolean = true
    ) {
        fun close() {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
            try {
                outputStream?.close()
            } catch (e: Exception) {
            }
            try {
                inputStream?.close()
            } catch (e: Exception) {
            }
            try {
                fileRaf?.close()
            } catch (e: Exception) {
            }
        }
    }

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
                hopDistance = 0,
                estimatedDistance = 0f,
                latencyMs = 0.0
            )

            startTcpServer()
            startFileTransferServer()
            startWifiDirect()
            startHeartbeat()
            startRouteUpdatePropagation()

            onStatusUpdate("📡 Recherche de téléphones...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur démarrage: ${e.message}", e)
            onError("Erreur démarrage: ${e.message}")
        }
    }

    // ==================== SERVEUR POUR TRANSFERTS DE FICHIERS ====================
    private fun startFileTransferServer() {
        executor.execute {
            try {
                fileTransferServer = ServerSocket(0)
                val actualPort = fileTransferServer!!.localPort
                Log.i(TAG, "🖧 Serveur transfert fichiers démarré sur port $actualPort")

                while (isRunning.get()) {
                    val clientSocket = fileTransferServer!!.accept()
                    Log.i(TAG, "🔥 Nouvelle connexion entrante sur le serveur de fichiers")
                    executor.execute { handleFileTransferConnection(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Erreur serveur fichiers", e)
            }
        }
    }

    private fun handleFileTransferConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)
            val line = reader.readLine() ?: return
            val packet = gson.fromJson(line, MeshPacket::class.java)
            if (packet.type == PacketType.FILE_TRANSFER_START) {
                acceptFileTransfer(packet, socket, reader, writer)
            } else {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur connexion fichier", e)
        }
    }

    // ==================== ENVOI D'UN GROS FICHIER ====================
    fun startLargeFileSend(
        destinationId: String,
        filePath: String,
        fileName: String,
        fileSize: Long,
        onProgress: (sentBytes: Long, total: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "📤 startLargeFileSend appelé")
        Log.i(TAG, "   destinationId: $destinationId")
        Log.i(TAG, "   filePath: $filePath")
        Log.i(TAG, "   fileName: $fileName")
        Log.i(TAG, "   fileSize: $fileSize")

        if (!tcpConnections.containsKey(destinationId)) {
            Log.e(TAG, "❌ Destination $destinationId non connectée!")
            Log.e(TAG, "   Connexions actives: ${tcpConnections.keys}")
            onError("Destinataire non connecté")
            return
        }

        val transferId = UUID.randomUUID().toString()
        val chunkSize = 256 * 1024
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        Log.i(TAG, "   transferId: $transferId")
        Log.i(TAG, "   totalChunks: $totalChunks")

        executor.execute {
            var serverSocketForTransfer: ServerSocket? = null
            try {
                serverSocketForTransfer = ServerSocket(0)
                val actualPort = serverSocketForTransfer.localPort
                Log.i(TAG, "🔌 Port dynamique pour transfert $transferId : $actualPort")

                val startPacket = MeshPacket(
                    id = UUID.randomUUID().toString(),
                    senderId = myId,
                    senderPseudo = myPseudo,
                    receiver = destinationId,
                    content = "FILE_TRANSFER_START",
                    type = PacketType.FILE_TRANSFER_START,
                    timestamp = System.currentTimeMillis(),
                    fileTransferStart = FileTransferStart(
                        transferId = transferId,
                        fileName = fileName,
                        fileSize = fileSize,
                        totalChunks = totalChunks,
                        chunkSize = chunkSize,
                        senderPort = actualPort
                    )
                )

                Log.i(TAG, "📡 Envoi du paquet FILE_TRANSFER_START à $destinationId")
                sendPacket(startPacket)

                Log.i(TAG, "⏳ Attente de connexion du destinataire sur le port $actualPort...")
                serverSocketForTransfer.soTimeout = 30000
                val dataSocket = serverSocketForTransfer.accept()
                Log.i(TAG, "✅ Connexion acceptée du destinataire!")

                val output = dataSocket.getOutputStream()
                val fileRaf = RandomAccessFile(filePath, "r")

                var sentBytes = 0L
                for (chunkIdx in 0 until totalChunks) {
                    val offset = chunkIdx.toLong() * chunkSize
                    val remaining = fileSize - offset
                    val len = min(chunkSize, remaining.toInt())
                    val buffer = ByteArray(len)
                    fileRaf.seek(offset)
                    fileRaf.readFully(buffer)

                    val chunkPacket = MeshPacket(
                        id = UUID.randomUUID().toString(),
                        senderId = myId,
                        senderPseudo = myPseudo,
                        receiver = destinationId,
                        content = "FILE_CHUNK",
                        type = PacketType.FILE_CHUNK,
                        timestamp = System.currentTimeMillis(),
                        transferId = transferId,
                        chunkIndex = chunkIdx,
                        fileChunk = FileChunkInfo(transferId, chunkIdx, offset, len),
                        fileChunkData = buffer
                    )
                    output.write((gson.toJson(chunkPacket) + "\n").toByteArray())
                    output.flush()

                    sentBytes += len
                    onProgress(sentBytes, fileSize)
                    if (chunkIdx % 10 == 0) {
                        Log.i(
                            TAG,
                            "📤 Chunk $chunkIdx/${totalChunks} envoyé (${sentBytes}/$fileSize)"
                        )
                    }
                }

                val completePacket = MeshPacket(
                    id = UUID.randomUUID().toString(),
                    senderId = myId,
                    senderPseudo = myPseudo,
                    receiver = destinationId,
                    content = "FILE_TRANSFER_COMPLETE",
                    type = PacketType.FILE_TRANSFER_COMPLETE,
                    timestamp = System.currentTimeMillis(),
                    transferId = transferId
                )
                output.write((gson.toJson(completePacket) + "\n").toByteArray())
                output.flush()
                Log.i(TAG, "✅ Transfert terminé avec succès!")
                onComplete()

            } catch (e: SocketTimeoutException) {
                Log.e(
                    TAG,
                    "❌ Timeout: le destinataire n'a pas accepté la connexion sur le port ${serverSocketForTransfer?.localPort}"
                )
                onError("Timeout: le destinataire n'a pas accepté la connexion")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erreur transfert fichier: ${e.message}", e)
                onError(e.message ?: "Erreur transfert fichier")
            } finally {
                serverSocketForTransfer?.close()
                activeFileTransfers.remove(transferId)?.close()
            }
        }
    }

    private fun acceptFileTransfer(
        startPacket: MeshPacket,
        dataSocket: Socket,
        reader: BufferedReader,
        writer: PrintWriter
    ) {
        val startInfo = startPacket.fileTransferStart ?: return
        val transferId = startInfo.transferId
        val senderId = startPacket.senderId
        val fileName = startInfo.fileName
        val fileSize = startInfo.fileSize
        val totalChunks = startInfo.totalChunks
        val chunkSize = startInfo.chunkSize

        Log.i(TAG, "📥 acceptFileTransfer appelé")
        Log.i(TAG, "   transferId: $transferId")
        Log.i(TAG, "   fileName: $fileName")
        Log.i(TAG, "   fileSize: $fileSize")
        Log.i(TAG, "   senderId: $senderId")

        executor.execute {
            var raf: RandomAccessFile? = null
            try {
                val tempFile = File(context.cacheDir, "downloading_$transferId")
                raf = RandomAccessFile(tempFile, "rw")
                Log.i(TAG, "✅ Fichier temporaire créé: ${tempFile.absolutePath}")

                dataSocket.soTimeout = 60000

                var receivedChunks = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    val packet = gson.fromJson(line, MeshPacket::class.java)
                    when (packet.type) {
                        PacketType.FILE_CHUNK -> {
                            val chunkInfo = packet.fileChunk ?: continue
                            val data = packet.fileChunkData ?: continue
                            raf.seek(chunkInfo.offset)
                            raf.write(data)
                            receivedChunks++
                            if (receivedChunks % 10 == 0) {
                                Log.i(TAG, "📥 Chunk ${chunkInfo.chunkIndex}/${totalChunks} reçu")
                            }
                            val ackPacket = MeshPacket(
                                id = UUID.randomUUID().toString(),
                                senderId = myId,
                                senderPseudo = myPseudo,
                                receiver = senderId,
                                content = "ACK_CHUNK",
                                type = PacketType.FILE_CHUNK_ACK,
                                transferId = transferId,
                                chunkIndex = packet.chunkIndex
                            )
                            writer.println(gson.toJson(ackPacket))
                            writer.flush()
                        }

                        PacketType.FILE_TRANSFER_COMPLETE -> {
                            val finalFile = File(context.cacheDir, fileName)
                            tempFile.renameTo(finalFile)
                            Log.i(TAG, "✅ Fichier reçu et sauvegardé: ${finalFile.absolutePath}")
                            val chatMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sender = startPacket.senderPseudo,
                                content = fileName,
                                isMine = false,
                                time = SimpleDateFormat(
                                    "HH:mm",
                                    Locale.getDefault()
                                ).format(Date()),
                                status = MessageStatus.RECEIVED,
                                type = PacketType.FILE,
                                receiverId = myId,
                                senderIdRaw = senderId,
                                filePath = finalFile.absolutePath,
                                fileName = fileName,
                                fileSize = fileSize
                            )
                            mainHandler.post { onMessageReceived(chatMsg) }
                            break
                        }

                        else -> {
                            Log.d(TAG, "Ignoring packet type ${packet.type} in file transfer")
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "❌ Timeout réception fichier", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erreur réception fichier", e)
            } finally {
                activeFileTransfers.remove(transferId)?.close()
                try {
                    raf?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    // ==================== ACTIONS UTILISATEUR ====================
    fun forceBecomeGroupOwner() {
        Log.i(TAG, "🔧 Force devenir Group Owner")
        if (isGroupOwner) {
            onStatusUpdate("👑 Déjà GO")
            return
        }
        // Annuler tout scan en cours et forcer GO immédiatement
        hasScannedEnough.set(true)
        goFormationScheduled.set(false)
        becomeGroupOwner()
    }

    fun forceJoinExistingGroup() {
        Log.i(TAG, "🔧 Force rejoindre un groupe existant")

        if (isGroupOwner || isConnectedToGroup) {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Groupe quitté, recherche d'un groupe à rejoindre...")
                    resetConnectionState()
                    // Reset scan state
                    hasScannedEnough.set(false)
                    goFormationScheduled.set(false)
                    peersDiscoveredDuringScan.clear()
                    startDiscovery()
                    // CORRECTION: Attendre 5s de scan avant de décider
                    mainHandler.postDelayed({
                        if (!isConnectedToGroup && !isGroupOwner && !hasDiscoveredPeers()) {
                            Log.i(TAG, "🔍 Aucun groupe trouvé après scan forcé, je deviens GO")
                            becomeGroupOwner()
                        }
                    }, SCAN_DURATION_BEFORE_GO)
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Échec départ du groupe: $reason")
                    resetConnectionState()
                    hasScannedEnough.set(false)
                    goFormationScheduled.set(false)
                    startDiscovery()
                }
            })
        } else {
            hasScannedEnough.set(false)
            goFormationScheduled.set(false)
            peersDiscoveredDuringScan.clear()
            startDiscovery()
            mainHandler.postDelayed({
                if (!isConnectedToGroup && !isGroupOwner && !hasDiscoveredPeers()) {
                    Log.i(TAG, "🔍 Aucun groupe trouvé, je deviens GO")
                    becomeGroupOwner()
                }
            }, SCAN_DURATION_BEFORE_GO)
        }
    }

    fun forceReconnectTcp() {
        Log.i(TAG, "🔧 Force reconnexion TCP au GO")
        if (!isGroupOwner && isConnectedToGroup && groupOwnerIp != null) {
            tcpConnectInProgress.set(false)
            connectToGroupOwnerTcp()
        }
    }

    private fun resetConnectionState() {
        isGroupOwner = false
        isConnectedToGroup = false
        groupOwnerIp = null
        isConnecting.set(false)
        isBecomingGO.set(false)
        tcpConnectInProgress.set(false)
        tcpConnections.clear()
        socketToNodeId.clear()
        previouslyConnectedDevices.clear()
    }

    private fun hasDiscoveredPeers(): Boolean {
        return peersDiscoveredDuringScan.isNotEmpty() ||
                knownNodes.values.any { it.nodeId != myId && System.currentTimeMillis() - it.lastSeen < 10000 }
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
                        mainHandler.postDelayed({
                            if (isRunning.get() && channel == null) {
                                wifiP2pManager?.initialize(context, Looper.getMainLooper(), this)
                            }
                        }, 3000)
                    }
                }
            )

            registerWifiReceiver()

            wifiP2pManager?.requestGroupInfo(channel) { group ->
                if (group != null && group.networkName != null && group.networkName.isNotEmpty()) {
                    Log.i(TAG, "📡 Groupe persistant existant: ${group.networkName}")
                    isConnectedToGroup = true
                    isGroupOwner = group.isGroupOwner
                    if (isGroupOwner) {
                        onStatusUpdate("👑 GO actif (groupe restauré)")
                        hasScannedEnough.set(true)
                    } else {
                        onStatusUpdate("🔗 Connecté au GO (groupe restauré)")
                        groupOwnerIp = group.owner?.deviceAddress?.let { getIpFromMac(it) }
                        if (groupOwnerIp != null) {
                            mainHandler.postDelayed({ connectToGroupOwnerTcp() }, 2000)
                        }
                    }
                } else {
                    Log.d(TAG, "📭 Aucun groupe persistant, démarrage avec scan obligatoire")
                    // CORRECTION: Démarrer le scan ET marquer le début du scan
                    scanStartTime.set(System.currentTimeMillis())
                    hasScannedEnough.set(false)
                    goFormationScheduled.set(false)
                    peersDiscoveredDuringScan.clear()
                    startDiscovery()

                    // CORRECTION: Programmer la décision GO seulement après 5s de scan
                    scheduleGoFormationAfterScan()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur init WiFi Direct: ${e.message}", e)
            onError("Erreur WiFi Direct: ${e.message}")
        }
    }

    // CORRECTION NOUVELLE: Planifier la formation GO uniquement après scan complet
    private var connectionAttemptCount = 0
    private val MAX_CONNECTION_ATTEMPTS = 8  // Nombre maximum de tentatives avant de devenir GO

    // CORRECTION NOUVELLE: Planifier la formation GO uniquement après scan complet
    private fun scheduleGoFormationAfterScan() {
        if (goFormationScheduled.getAndSet(true)) return

        Log.i(
            TAG,
            "⏱️ Scan réseau démarré, attente de ${SCAN_DURATION_BEFORE_GO}ms avant décision GO..."
        )
        onStatusUpdate("🔍 Scan du réseau en cours...")

        mainHandler.postDelayed({
            if (!isRunning.get()) return@postDelayed
            hasScannedEnough.set(true)

            if (isConnectedToGroup || isGroupOwner) {
                Log.i(TAG, "✅ Déjà connecté, pas besoin de devenir GO")
                connectionAttemptCount = 0
                return@postDelayed
            }

            if (hasDiscoveredPeers()) {
                Log.i(TAG, "📱 Pairs découverts pendant le scan, tentative de connexion...")
                // Commencer les tentatives de connexion
                startConnectionAttempts()
            } else {
                Log.i(TAG, "⏱️ Aucun pair trouvé après scan, je deviens Group Owner")
                becomeGroupOwner()
            }
        }, SCAN_DURATION_BEFORE_GO)
    }

    // Nouvelle méthode pour gérer les tentatives de connexion
    private fun startConnectionAttempts() {
        if (!isRunning.get()) return
        if (isConnectedToGroup || isGroupOwner) return

        connectionAttemptCount = 0
        attemptConnection()
    }

    private fun attemptConnection() {
        if (!isRunning.get()) return
        if (isConnectedToGroup || isGroupOwner) {
            Log.i(TAG, "✅ Connexion établie, arrêt des tentatives")
            connectionAttemptCount = 0
            return
        }

        if (connectionAttemptCount >= MAX_CONNECTION_ATTEMPTS) {
            Log.i(
                TAG,
                "❌ Échec de connexion après $MAX_CONNECTION_ATTEMPTS tentatives, je deviens GO"
            )
            becomeGroupOwner()
            connectionAttemptCount = 0
            return
        }

        connectionAttemptCount++
        val delay = connectionAttemptCount * 2000L  // 2s, 4s, 6s, etc.

        Log.i(
            TAG,
            "🔄 Tentative de connexion $connectionAttemptCount/$MAX_CONNECTION_ATTEMPTS (délai: ${delay}ms)"
        )

        mainHandler.postDelayed({
            if (hasDiscoveredPeers() && !isConnectedToGroup && !isGroupOwner) {
                // Demander à requestPeers de tenter une connexion
                requestPeers()
                // Planifier la prochaine tentative
                attemptConnection()
            } else if (!hasDiscoveredPeers()) {
                Log.i(TAG, "📭 Plus aucun pair détecté, je deviens GO")
                becomeGroupOwner()
                connectionAttemptCount = 0
            }
        }, delay)
    }

    private fun getIpFromMac(macAddress: String): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (intf.isUp && !intf.isLoopback) {
                        val hardwareAddress = intf.hardwareAddress
                        if (hardwareAddress != null) {
                            val mac = hardwareAddress.joinToString(":") { "%02X".format(it) }
                            if (mac.equals(macAddress, ignoreCase = true)) {
                                val addresses = intf.inetAddresses
                                while (addresses.hasMoreElements()) {
                                    val addr = addresses.nextElement()
                                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                        return addr
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur getIpFromMac", e)
        }
        return null
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
        if (!checkWifiDirectPermission()) {
            isBecomingGO.set(false); return
        }

        Log.i(TAG, "👑 Création du groupe WiFi Direct (GO)")

        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "✅ Groupe existant supprimé, création du nouveau...")
                createNewGroup()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "❌ Échec suppression groupe: $reason, tentative directe...")
                createNewGroup()
            }
        })
    }

    private fun createNewGroup() {
        try {
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Groupe créé — je suis Group Owner")
                    isGroupOwner = true
                    isConnectedToGroup = true
                    isBecomingGO.set(false)
                    onStatusUpdate("👑 Mode GO actif")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ createGroup échoué: $reason ${reasonToString(reason)}")
                    isBecomingGO.set(false)

                    when (reason) {
                        2 -> {
                            mainHandler.postDelayed({
                                if (isRunning.get() && !isConnectedToGroup) {
                                    Log.i(TAG, "🔄 Nouvelle tentative de création du groupe...")
                                    becomeGroupOwner()
                                }
                            }, 10000)
                        }

                        else -> {
                            mainHandler.postDelayed({
                                if (isRunning.get() && !isConnectedToGroup) becomeGroupOwner()
                            }, 5000)
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée createGroup", e)
            isBecomingGO.set(false)
        }
    }

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
                    Log.d(
                        TAG,
                        "📢 PEERS_CHANGED reçu (connecté=$isConnectedToGroup, connecting=${isConnecting.get()})"
                    )
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
            intent.getParcelableExtra(
                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                WifiP2pDevice::class.java
            )
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
    }

    @Suppress("DEPRECATION")
    private fun getNetworkInfoFromIntent(intent: Intent): android.net.NetworkInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                WifiP2pManager.EXTRA_NETWORK_INFO,
                android.net.NetworkInfo::class.java
            )
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

                // CORRECTION: Enregistrer les pairs découverts
                devices.forEach { device ->
                    if (device.deviceAddress != thisDeviceAddress) {
                        peersDiscoveredDuringScan.add(device.deviceAddress)
                    }
                }

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

                if (isConnectedToGroup && isGroupOwner) {
                    Log.d(TAG, "GO déjà actif, pas de nouvelle connexion")
                    return@requestPeers
                }

                // Pendant la phase de scan, on essaie de se connecter
                if (!hasScannedEnough.get()) {
                    Log.d(
                        TAG,
                        "⏳ Phase de scan en cours (${System.currentTimeMillis() - scanStartTime.get()}ms), tentative de connexion..."
                    )
                }

                if (!isConnectedToGroup && !isConnecting.get() && !isGroupOwner) {
                    // Choisir le pair avec la meilleure adresse MAC (pour éviter les connexions multiples)
                    val sorted = validPeers.sortedBy { it.deviceAddress }
                    val bestPeer = sorted.first()
                    Log.i(TAG, "📱 Tentative de connexion au pair ${bestPeer.deviceName}")
                    connectToPeerWithRetry(bestPeer, 0)
                } else {
                    Log.d(
                        TAG,
                        "Connexion ignorée (connecté=$isConnectedToGroup, connecting=${isConnecting.get()})"
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée requestPeers", e)
        }
    }

    private fun connectToPeerWithRetry(device: WifiP2pDevice, attempt: Int) {
        // Augmenter le nombre de tentatives à 8
        if (attempt >= 8) {
            Log.e(TAG, "❌ Échec de connexion après 8 tentatives, abandon")
            onError("Impossible de se connecter à ${device.deviceName}")
            isConnecting.set(false)
            // Ne pas devenir GO immédiatement, attendre que startConnectionAttempts décide
            return
        }
        if (attempt > 0) {
            val delay = 3000L * attempt  // 3s, 6s, 9s...
            Log.i(
                TAG,
                "⏳ Nouvelle tentative de connexion dans ${delay}ms (tentative ${attempt + 1}/8)"
            )
            mainHandler.postDelayed({
                if (isRunning.get() && !isConnectedToGroup && !isGroupOwner) {
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
        if (!checkWifiDirectPermission()) {
            isConnecting.set(false); return
        }

        if (isGroupOwner || isConnectedToGroup) {
            Log.w(TAG, "Déjà dans un groupe, connexion ignorée")
            isConnecting.set(false)
            return
        }

        Log.i(TAG, "🔍 Vérification du groupe du pair ${device.deviceName}...")

        // Vérifier si le pair a déjà un groupe persistant
        wifiP2pManager?.requestGroupInfo(channel) { ourGroup ->
            // D'abord, préparer à rejoindre le groupe du pair
            prepareToJoinPeerGroup(device.deviceAddress) {
                // Maintenant on peut tenter la connexion
                val config = WifiP2pConfig().apply {
                    this.deviceAddress = device.deviceAddress
                    groupOwnerIntent = 0  // Force client, pas GO
                }

                try {
                    wifiP2pManager?.connect(
                        channel,
                        config,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Log.i(
                                    TAG,
                                    "✅ Connexion WiFi Direct demandée à ${device.deviceName}"
                                )
                                onStatusUpdate("🔗 Connexion à ${device.deviceName}...")
                                previouslyConnectedDevices.add(device.deviceAddress)
                            }

                            override fun onFailure(reason: Int) {
                                Log.e(TAG, "❌ connect échoué: $reason ${reasonToString(reason)}")
                                isConnecting.set(false)
                                if (reason == 0 || reason == 2) {
                                    // Réessayer avec une nouvelle approche
                                    connectToPeerWithRetry(device, attempt)
                                }
                            }
                        })
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission refusée connect", e)
                    isConnecting.set(false)
                }
            }
        }

        mainHandler.postDelayed({
            if (isConnecting.get() && !isConnectedToGroup) {
                Log.w(TAG, "⏰ Timeout de connexion (15s)")
                isConnecting.set(false)
                try {
                    wifiP2pManager?.cancelConnect(channel, null)
                } catch (e: Exception) {
                }
                if (isRunning.get()) requestPeers()
            }
        }, 15000)
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
                hasScannedEnough.set(true) // On est connecté, plus besoin de scan
                goFormationScheduled.set(false)

                Log.i(TAG, "✅ Groupe WiFi Direct rejoint")
                Log.i(TAG, "   GO IP : ${groupOwnerIp?.hostAddress}")
                Log.i(TAG, "   Rôle  : ${if (isGroupOwner) "GROUP OWNER 👑" else "CLIENT"}")

                if (isGroupOwner) {
                    onStatusUpdate("👑 En attente de clients...")
                    // CORRECTION: Le GO doit immédiatement diffuser sa présence
                    mainHandler.postDelayed({
                        broadcastClientList()
                    }, 1000)
                } else {
                    if (groupOwnerIp != null) {
                        Log.i(TAG, "🔌 Client → connexion TCP au GO dans 2 secondes...")
                        mainHandler.postDelayed({ connectToGroupOwnerTcp() }, 2000)
                    } else {
                        Log.e(TAG, "❌ IP GO inconnue")
                    }
                }
            }
        } else {
            if (isConnectedToGroup) {
                Log.i(TAG, "🔴 Déconnecté du groupe WiFi Direct")
                val wasGO = isGroupOwner
                resetConnectionState()
                onStatusUpdate("📡 Recherche de téléphones...")

                // CORRECTION: Reset scan state et redémarrer le cycle
                hasScannedEnough.set(false)
                goFormationScheduled.set(false)
                peersDiscoveredDuringScan.clear()
                scanStartTime.set(System.currentTimeMillis())

                mainHandler.postDelayed({
                    if (isRunning.get() && !isConnectedToGroup) {
                        startDiscovery()
                        scheduleGoFormationAfterScan()
                    }
                }, 5000)
            }
        }
    }

    private fun connectToGroupOwnerTcp() {
        if (!isRunning.get() || isGroupOwner) return
        if (tcpConnectInProgress.getAndSet(true)) {
            Log.d(TAG, "Connexion TCP GO déjà en cours")
            return
        }

        executor.execute {
            var retries = 0
            val maxRetries = 10
            while (isRunning.get() && isConnectedToGroup && !isGroupOwner && retries < maxRetries) {
                try {
                    val goIp = groupOwnerIp ?: break
                    Log.i(
                        TAG,
                        "🔌 TCP tentative ${retries + 1}/$maxRetries → ${goIp.hostAddress}:$TCP_PORT"
                    )

                    val socket = Socket()
                    socket.connect(InetSocketAddress(goIp, TCP_PORT), 5000)

                    Log.i(TAG, "✅ TCP connecté au GO!")
                    tcpConnectInProgress.set(false)
                    handleTcpConnection(socket, goIp.hostAddress ?: "")
                    return@execute
                } catch (e: ConnectException) {
                    retries++
                    Log.w(TAG, "⏳ GO pas prêt (tentative $retries): ${e.message}")
                    Thread.sleep(3000)
                } catch (e: SocketTimeoutException) {
                    retries++
                    Log.w(TAG, "⏱️ Timeout (tentative $retries)")
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur TCP: ${e.message}")
                    break
                }
            }

            tcpConnectInProgress.set(false)
            if (isRunning.get() && isConnectedToGroup && !isGroupOwner) {
                Log.e(TAG, "❌ Échec connexion GO après $maxRetries tentatives")
                onStatusUpdate("⚠️ Impossible de se connecter au GO")
            }
        }
    }

    // ==================== TCP SERVEUR (CANAL DE CONTRÔLE) ====================
    private fun startTcpServer() {
        executor.execute {
            var port = TCP_PORT
            var retries = 0
            while (retries < 10) {
                try {
                    serverSocket = ServerSocket(port)
                    Log.i(TAG, "🖧 Serveur TCP sur port $port")
                    break
                } catch (e: BindException) {
                    port++
                    retries++
                    Log.w(TAG, "Port $TCP_PORT occupé, essai port $port")
                }
            }

            if (serverSocket == null) {
                onError("Impossible d'ouvrir un port TCP")
                return@execute
            }

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
                            try {
                                old.close()
                            } catch (e: Exception) {
                            }
                        }
                        tcpConnections[nodeId!!] = socket
                        Log.i(
                            TAG,
                            "✅ Pair enregistré: ${packet.senderPseudo} [$nodeId] @ $clientIp"
                        )

                        // CORRECTION: Calculer la latence pour la distance
                        val latency = measureLatency(socket)
                        updateNodeInfo(
                            nodeId = packet.senderId,
                            pseudo = packet.senderPseudo,
                            ip = clientIp,
                            isDirectNeighbor = true,
                            reliability = packet.reachabilityQuality ?: 0.5,
                            latencyMs = latency
                        )

                        previouslyConnectedDevices.add(nodeId)

                        val directEntry = RoutingEntry(
                            nodeId,
                            nodeId,
                            alpha = 10.0,
                            beta = 1.0,
                            timestamp = System.currentTimeMillis()
                        )
                        routingTable.getOrPut(nodeId) { mutableListOf() }.add(directEntry)

                        // CORRECTION: Si on est GO, diffuser la liste des clients à TOUS
                        if (isGroupOwner) {
                            mainHandler.postDelayed({
                                broadcastClientList()
                                // Envoyer aussi la liste au nouveau client
                                broadcastClientListToSocket(socket)
                            }, 500)
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
                // CORRECTION: Si GO, informer les autres clients de la déconnexion
                if (isGroupOwner) {
                    broadcastClientList()
                }
            }
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    // CORRECTION NOUVELLE: Mesurer la latence TCP pour estimer la distance
    private fun measureLatency(socket: Socket): Double {
        return try {
            val start = System.currentTimeMillis()
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
            val pingPacket = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderPseudo = myPseudo,
                receiver = "TOUS",
                content = "PING",
                type = PacketType.HEARTBEAT,
                timestamp = start
            )
            writer.println(gson.toJson(pingPacket))
            writer.flush()
            // Attendre un ack implicite via le heartbeat
            val elapsed = System.currentTimeMillis() - start
            elapsed.toDouble()
        } catch (e: Exception) {
            50.0 // Valeur par défaut
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
    private fun updateNodeInfo(
        nodeId: String,
        pseudo: String,
        ip: String,
        isDirectNeighbor: Boolean,
        reliability: Double = 0.5,
        latencyMs: Double = 50.0
    ) {
        // CORRECTION: Distance basée sur la latence et le hop count, PAS sur la fiabilité bayésienne
        val distance = calculateRealisticDistance(latencyMs, isDirectNeighbor)

        val existing = knownNodes[nodeId]
        if (existing != null) {
            existing.lastSeen = System.currentTimeMillis()
            existing.pseudo = pseudo
            existing.ip = ip
            existing.isConnected = true
            if (isDirectNeighbor) existing.isDirectNeighbor = true
            existing.bayesianReliability = reliability
            existing.latencyMs = latencyMs
            existing.estimatedDistance = distance
            existing.lastLatencyCheck = System.currentTimeMillis()
        } else {
            knownNodes[nodeId] = NodeInfo(
                nodeId = nodeId,
                pseudo = pseudo,
                ip = ip,
                lastSeen = System.currentTimeMillis(),
                isConnected = true,
                isDirectNeighbor = isDirectNeighbor,
                hopDistance = if (isDirectNeighbor) 1 else Int.MAX_VALUE,
                bayesianReliability = reliability,
                estimatedDistance = distance,
                latencyMs = latencyMs,
                lastLatencyCheck = System.currentTimeMillis()
            )
            val meshNode = MeshNode(
                deviceId = nodeId,
                deviceName = pseudo,
                pseudo = pseudo,
                connectionType = ConnectionType.WIFI_DIRECT,
                lastSeen = System.currentTimeMillis(),
                isDirectConnection = isDirectNeighbor,
                estimatedDistance = distance,
                ip = ip,
                isConnected = true,
                bayesianReliability = reliability
            )
            mainHandler.post {
                onNodeDiscovered(meshNode)
                updateConnectionStatus()
            }
        }
    }

    // CORRECTION NOUVELLE: Calcul de distance réaliste basé sur la latence
    private fun calculateRealisticDistance(latencyMs: Double, isDirectNeighbor: Boolean): Float {
        if (!isDirectNeighbor) {
            // Pour les nœuds relayés, on ne peut pas estimer la distance physique
            return -1f
        }

        // WiFi Direct typical range: ~60m max
        // Latency at 1m: ~1-2ms (signal processing)
        // Latency at 60m: ~10-20ms + packet loss
        // Formule: distance ≈ (latency - baseLatency) * speedOfLightFactor * attenuation
        // Simplifié pour WiFi Direct:
        // - < 5ms  → très proche (1-10m)
        // - 5-15ms → proche (10-30m)
        // - 15-30ms → moyen (30-50m)
        // - > 30ms  → loin (50-100m)

        val baseLatency = 2.0 // ms de traitement
        val adjustedLatency = (latencyMs - baseLatency).coerceAtLeast(0.0)

        return when {
            adjustedLatency < 3 -> (1 + adjustedLatency * 3).toFloat()      // 1-10m
            adjustedLatency < 10 -> (10 + (adjustedLatency - 3) * 2.8).toFloat() // 10-30m
            adjustedLatency < 25 -> (30 + (adjustedLatency - 10) * 1.3).toFloat() // 30-50m
            else -> (50 + (adjustedLatency - 25) * 2).toFloat().coerceAtMost(100f) // 50-100m
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
                    // CORRECTION: Le GO diffuse régulièrement sa liste de clients
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
            tcpConnections.remove(id)?.let {
                try {
                    it.close()
                } catch (e: Exception) {
                }
            }
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

        executor.execute {
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
            Log.d(TAG, "📢 CLIENT_LIST diffusée à ${tcpConnections.size} clients: ${clientList.map { it.pseudo }}")
        }
    }

    // CORRECTION NOUVELLE: Envoyer la liste à un socket spécifique (nouveau client)
    private fun broadcastClientListToSocket(targetSocket: Socket) {
        executor.execute {
            try {
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
                val data = (gson.toJson(packet) + "\n").toByteArray(Charsets.UTF_8)
                if (targetSocket.isConnected && !targetSocket.isClosed) {
                    targetSocket.getOutputStream().write(data)
                    targetSocket.getOutputStream().flush()
                    Log.d(TAG, "📢 CLIENT_LIST envoyée au nouveau client: ${clientList.map { it.pseudo }}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur envoi CLIENT_LIST au nouveau client", e)
            }
        }
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
        Log.d(
            TAG,
            "📊 Fiabilité mise à jour: dest=$destinationId next=$nextHopId succ=$success → ${
                String.format(
                    "%.2f",
                    reliability
                )
            }"
        )

        if (destinationId != myId) {
            notifyNodeReliability(destinationId, reliability)
        }
    }

    // CORRECTION: Suppression des méthodes de distance basées sur fiabilité
    // La distance est maintenant calculée par calculateRealisticDistance() via latence

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
                    RoutingEntry(
                        dest,
                        entry.nextHopId,
                        entry.alpha,
                        entry.beta,
                        entry.latency,
                        entry.timestamp,
                        entry.isRelayLink
                    )
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
                existingEntries.add(
                    RoutingEntry(
                        destinationId = destination,
                        nextHopId = fromNodeId,
                        alpha = receivedEntry.alpha,
                        beta = receivedEntry.beta,
                        latency = receivedEntry.latency,
                        timestamp = receivedEntry.timestamp,
                        isRelayLink = receivedEntry.isRelayLink
                    )
                )
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

                // CORRECTION: Mettre à jour la distance du nœud relayé (on ne sait pas la distance exacte)
                val node = knownNodes[destination]
                if (node != null && node.estimatedDistance < 0) {
                    // Marquer comme "distance inconnue" pour les nœuds relayés
                    node.estimatedDistance = -1f
                }
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
    private fun handleIncomingPacket(
        packet: MeshPacket,
        sourceIp: String,
        sourceSocket: Socket? = null
    ) {
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
            // ========== TRANSFERT DE FICHIERS - TRAITEMENT ACTIF ==========
            PacketType.FILE_TRANSFER_START -> {
                Log.i(TAG, "📥 FILE_TRANSFER_START reçu de ${packet.senderPseudo}")
                executor.execute {
                    try {
                        val senderIp = knownNodes[packet.senderId]?.ip ?: sourceIp
                        val senderPort = packet.fileTransferStart?.senderPort
                        if (senderPort == null) {
                            Log.e(TAG, "❌ senderPort est null, impossible de se connecter")
                            return@execute
                        }
                        Log.i(TAG, "🔌 Connexion à l'expéditeur $senderIp:$senderPort")

                        val socket = Socket()
                        socket.connect(InetSocketAddress(senderIp, senderPort), 10000)
                        Log.i(TAG, "✅ Connecté à l'expéditeur!")

                        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                        acceptFileTransfer(packet, socket, reader, writer)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Erreur connexion à l'expéditeur: ${e.message}", e)
                    }
                }
                return
            }

            PacketType.FILE_CHUNK -> {
                Log.d(TAG, "📦 FILE_CHUNK reçu (traité par acceptFileTransfer)")
                return
            }

            PacketType.FILE_CHUNK_ACK -> {
                Log.d(TAG, "✅ FILE_CHUNK_ACK reçu")
                return
            }

            PacketType.FILE_TRANSFER_COMPLETE -> {
                Log.i(TAG, "✅ FILE_TRANSFER_COMPLETE reçu")
                return
            }

            // ========== TYPES EXISTANTS ==========
            PacketType.HEARTBEAT -> {
                knownNodes[packet.senderId]?.lastSeen = System.currentTimeMillis()
            }

            PacketType.NODE_INFO -> {
                updateNodeInfo(
                    packet.senderId,
                    packet.senderPseudo,
                    sourceIp,
                    true,
                    packet.reachabilityQuality ?: 0.5
                )
            }

            PacketType.CLIENT_LIST -> {
                // CORRECTION: Traitement amélioré de la liste clients
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
                                hopDistance = 2,
                                estimatedDistance = -1f // Distance inconnue pour les clients relayés
                            )
                            knownNodes[client.id] = nodeInfo
                            val meshNode = MeshNode(
                                deviceId = client.id,
                                deviceName = client.pseudo,
                                pseudo = client.pseudo,
                                connectionType = ConnectionType.RELAY_BRIDGE,
                                lastSeen = System.currentTimeMillis(),
                                isDirectConnection = false,
                                estimatedDistance = -1f, // CORRECTION: -1 = distance inconnue
                                isConnected = true,
                                ip = client.ip
                            )
                            mainHandler.post { onNodeDiscovered(meshNode) }
                        } else {
                            existing.lastSeen = System.currentTimeMillis()
                            existing.pseudo = client.pseudo
                            existing.ip = client.ip
                            existing.isConnected = true
                            // Ne pas écraser la distance si on l'a déjà mesurée en direct
                            if (existing.estimatedDistance < 0) {
                                existing.estimatedDistance = -1f
                            }
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
                        time = SimpleDateFormat(
                            "HH:mm",
                            Locale.getDefault()
                        ).format(Date(packet.timestamp)),
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
            packetType != PacketType.ROUTE_DISCOVERY &&
            packetType != PacketType.FILE_TRANSFER_START &&
            packetType != PacketType.FILE_CHUNK &&
            packetType != PacketType.FILE_CHUNK_ACK &&
            packetType != PacketType.FILE_TRANSFER_COMPLETE
        ) {

            if (packet.receiver != myId || packet.receiver == "TOUS") {
                val newPacket = packet.copy(hopCount = packet.hopCount + 1)
                Log.i(
                    TAG,
                    "🔄 Relai multi-saut (${packet.hopCount} -> ${newPacket.hopCount}) de ${packet.senderPseudo} vers ${packet.receiver}"
                )
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
            tcpConnections.remove(nodeId)?.let {
                try {
                    it.close()
                } catch (e: Exception) {
                }
            }
            routingTable.remove(nodeId)
            routingTable.values.forEach { entries ->
                entries.removeAll { it.nextHopId == nodeId || it.destinationId == nodeId }
            }
            // Retirer aussi des pairs découverts pendant le scan
            peersDiscoveredDuringScan.remove(nodeId)
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
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp || intf.isLoopback) continue
                    val addresses = intf.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: "192.168.49.1"
                        }
                    }
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

        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        try {
            fileTransferServer?.close()
        } catch (e: Exception) {
        }
        tcpConnections.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
            }
        }
        tcpConnections.clear()
        socketToNodeId.clear()
        activeFileTransfers.values.forEach { it.close() }
        activeFileTransfers.clear()

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(wifiDirectReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        if (checkWifiDirectPermission()) {
            try {
                wifiP2pManager?.removeGroup(channel, null)
            } catch (e: Exception) {
            }
        }

        knownNodes.clear()
        seenPacketIds.clear()
        routingTable.clear()
        previouslyConnectedDevices.clear()
        peersDiscoveredDuringScan.clear()
    }

    // Force la suppression du groupe du pair avant de tenter de s'y connecter
    private fun prepareToJoinPeerGroup(peerAddress: String, onReady: () -> Unit) {
        Log.i(TAG, "🔄 Préparation pour rejoindre le groupe du pair $peerAddress")

        // Étape 1: Supprimer notre propre groupe si on en a un
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "✅ Notre groupe supprimé")
                resetConnectionState()
                // Étape 2: Demander au pair de supprimer son groupe persistant
                requestPeerToRemoveGroup(peerAddress, onReady)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "⚠️ Échec suppression notre groupe: $reason")
                resetConnectionState()
                requestPeerToRemoveGroup(peerAddress, onReady)
            }
        })
    }

    // Demander au pair de supprimer son groupe (via broadcast)
    private fun requestPeerToRemoveGroup(peerAddress: String, onReady: () -> Unit) {
        Log.i(TAG, "📡 Demande de suppression du groupe au pair $peerAddress")

        // Envoyer une requête spéciale au pair
        val requestPacket = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderPseudo = myPseudo,
            receiver = "TOUS",  // Broadcast
            content = "CLEANUP_GROUP_REQUEST",
            type = PacketType.GROUP_DISSOLVE,
            timestamp = System.currentTimeMillis()
        )
        sendPacket(requestPacket)

        // Attendre un peu puis continuer
        mainHandler.postDelayed({
            onReady()
        }, 2000)
    }

    // Répondre à une requête de suppression de groupe (à mettre dans handleIncomingPacket)
    private fun handleCleanupGroupRequest(senderId: String) {
        Log.i(TAG, "🧹 Réception d'une demande de nettoyage de groupe de $senderId")

        if (isGroupOwner) {
            Log.i(TAG, "👑 Je suis GO, je supprime mon groupe persistant")
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Groupe supprimé sur demande de $senderId")
                    resetConnectionState()
                    // Redémarrer la découverte
                    startDiscovery()
                    // Optionnel: envoyer une confirmation
                    val confirmPacket = MeshPacket(
                        id = UUID.randomUUID().toString(),
                        senderId = myId,
                        senderPseudo = myPseudo,
                        receiver = senderId,
                        content = "CLEANUP_CONFIRMED",
                        type = PacketType.ACK,
                        timestamp = System.currentTimeMillis()
                    )
                    sendPacket(confirmPacket)
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Échec suppression groupe: $reason")
                }
            })
        } else {
            Log.i(TAG, "ℹ️ Je ne suis pas GO, pas de groupe à supprimer")
            // Si je ne suis pas GO mais que je reçois cette demande, je réinitialise mon état
            resetConnectionState()
        }
    }

    fun forceResetAndJoin() {
        Log.i(TAG, "🧹 Force reset et tentative de rejoindre un groupe existant")

        if (!checkWifiDirectPermission()) {
            onError("Permission WiFi Direct manquante")
            return
        }

        // Étape 1: Supprimer notre groupe
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "✅ Notre groupe supprimé")
                resetConnectionState()
                // Étape 2: Scanner et tenter de rejoindre
                startDiscovery()
                mainHandler.postDelayed({
                    if (!isConnectedToGroup && !isGroupOwner) {
                        forceConnectToVisiblePeer()
                    } else {
                        Log.i(TAG, "✅ Déjà connecté ou GO après nettoyage")
                    }
                }, 5000)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "❌ Échec suppression groupe: $reason")
                resetConnectionState()
                startDiscovery()
                mainHandler.postDelayed({
                    if (!isConnectedToGroup && !isGroupOwner) {
                        forceConnectToVisiblePeer()
                    }
                }, 5000)
            }
        })
    }

    private fun forceConnectToVisiblePeer() {
        if (!checkWifiDirectPermission()) return

        wifiP2pManager?.requestPeers(channel) { peers ->
            val devices = peers.deviceList
            Log.d(TAG, "📋 ${devices.size} pair(s) détecté(s) pour connexion forcée")
            devices.forEach { device ->
                Log.d(TAG, "   - ${device.deviceName} (${device.deviceAddress})")
            }

            val validPeer = devices.firstOrNull {
                it.deviceName != null &&
                        !it.deviceName.contains("HP DeskJet", ignoreCase = true) &&
                        it.deviceAddress != thisDeviceAddress
            }

            if (validPeer != null) {
                Log.i(TAG, "🔌 Connexion forcée à ${validPeer.deviceName}")
                val config = WifiP2pConfig().apply {
                    deviceAddress = validPeer.deviceAddress
                    groupOwnerIntent = 0
                }
                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "✅ Connexion forcée demandée à ${validPeer.deviceName}")
                        onStatusUpdate("🔗 Connexion à ${validPeer.deviceName}...")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "❌ Échec connexion forcée: $reason")
                        // Si la connexion échoue, devenir GO
                        becomeGroupOwner()
                    }
                })
            } else {
                Log.i(TAG, "📭 Aucun pair visible, je deviens GO")
                becomeGroupOwner()
            }
        }
    }
}