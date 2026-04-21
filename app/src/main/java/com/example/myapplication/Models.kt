package com.example.myapplication

import java.util.Random
import java.util.UUID

// ==================== ENUMS ====================
enum class MessageStatus { SENDING, SENT, RECEIVED, FAILED, RELAYED }
enum class MeshMode { BLUETOOTH, WIFI_DIRECT, HYBRID }
enum class ConnectionType { WIFI_DIRECT, BLUETOOTH, RELAY_BRIDGE, SUB_GO }

enum class PacketType {
    MESSAGE, AUDIO, FILE, HEARTBEAT, NODE_INFO, ACK,
    ROUTE_UPDATE, GO_HEARTBEAT, TEST_REACHABILITY, CAN_REACH,
    BECOME_RELAY, GROUP_DISSOLVE, RELAY_REGISTER, RELAY_UNREGISTER,
    GO_TO_GO_PEERING, GO_TO_GO_ACCEPT, HIERARCHY_MERGE,
    CROSS_GROUP_MESSAGE, GROUP_ANNOUNCEMENT, CLIENT_LIST,
    CLIENT_LIST_REQUEST, ROUTE_DISCOVERY,
    FILE_TRANSFER_START, FILE_CHUNK, FILE_CHUNK_ACK,
    FILE_TRANSFER_COMPLETE, FILE_TRANSFER_CANCEL, FILE_TRANSFER_RESUME
}

// ==================== TRANSFERT DE FICHIERS ====================
data class FileTransferStart(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Int,
    val chunkSize: Int,
    val senderPort: Int
)

data class FileChunkInfo(
    val transferId: String,
    val chunkIndex: Int,
    val offset: Long,
    val length: Int
)

data class ClientInfo(val id: String, val pseudo: String, val ip: String)

// ==================== PAQUET RÉSEAU ====================
data class MeshPacket(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderPseudo: String,
    val receiver: String,
    val content: String,
    val type: PacketType,
    var hopCount: Int = 10,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceGroupId: String = "",
    val viaBle: Boolean = false,
    val audioData: ByteArray? = null,
    val fileData: ByteArray? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val routeEntries: List<RoutingEntry>? = null,
    val targetGoAddress: String? = null,
    val canReachResult: Boolean? = null,
    val reachabilityQuality: Double? = null,
    val relayForClients: List<String>? = null,
    val newGoInfo: GoInfo? = null,
    val relayInfo: RelayInfo? = null,
    val hierarchyInfo: HierarchyInfo? = null,
    val clientList: List<ClientInfo>? = null,
    val fileTransferStart: FileTransferStart? = null,
    val fileChunk: FileChunkInfo? = null,
    val fileChunkData: ByteArray? = null,
    val transferId: String? = null,
    val chunkIndex: Int = -1
) {
    override fun equals(other: Any?): Boolean = other is MeshPacket && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

// ==================== AUTRES CLASSES ====================
data class GoInfo(val goId: String, val goPseudo: String, val goAddress: String, val goPriority: Long, val clientCount: Int)
data class RelayInfo(val relayId: String, val relayAddress: String, val relayPseudo: String, val relayBleAddress: String?, val supportedClients: List<String>)
data class HierarchyInfo(val superGoId: String, val superGoAddress: String, val subGroupId: String, val isSubGo: Boolean = false)

data class RoutingEntry(
    val destinationId: String,
    val nextHopId: String,
    var alpha: Double = 1.0,
    var beta: Double = 1.0,
    var latency: Double = 0.0,
    var timestamp: Long = System.currentTimeMillis(),
    var isRelayLink: Boolean = false
) {
    val reliability: Double get() = if (alpha + beta > 0) alpha / (alpha + beta) else 0.5
    fun toThompsonSample(): Double = sampleBeta(Random(), alpha, beta)
    private fun sampleBeta(random: Random, a: Double, b: Double): Double {
        if (a <= 0 || b <= 0) return 0.5
        val ga = sampleGamma(random, a, 1.0)
        val gb = sampleGamma(random, b, 1.0)
        return if (ga + gb > 0) ga / (ga + gb) else 0.5
    }
    private fun sampleGamma(random: Random, shape: Double, scale: Double): Double {
        if (shape < 1) return sampleGamma(random, shape + 1, scale) * Math.pow(random.nextDouble(), 1.0 / shape)
        val d = shape - 1.0 / 3.0
        val c = 1.0 / Math.sqrt(9.0 * d)
        while (true) {
            val x = random.nextGaussian()
            val v = Math.pow(1.0 + c * x, 3.0)
            if (v > 0) {
                val u = random.nextDouble()
                if (u < 1.0 - 0.0331 * x * x * x * x) return d * v * scale
                if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v * scale
            }
        }
    }
}

data class MeshNode(
    val deviceId: String,
    val deviceName: String,
    val pseudo: String,
    val connectionType: ConnectionType,
    val lastSeen: Long,
    val isDirectConnection: Boolean = false,
    var estimatedDistance: Float = 0f,
    val groupId: String = "",
    val bayesianReliability: Double = 0.5,
    val isRelay: Boolean = false,
    val relayFor: List<String> = emptyList(),
    val isSubGo: Boolean = false,
    val superGoId: String? = null,
    val batteryLevel: Int = 100,
    val ip: String? = null,
    val isConnected: Boolean = true
)

data class ChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val isMine: Boolean,
    val time: String,
    var status: MessageStatus,
    val type: PacketType,
    val receiverId: String,
    val senderIdRaw: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    var isPlaying: Boolean = false,
    val replyToId: String? = null,
    val replyToContent: String? = null
)