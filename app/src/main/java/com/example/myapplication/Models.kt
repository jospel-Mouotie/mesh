package com.example.myapplication

import android.net.Uri
import java.util.Date

// 1. Statut de l'envoi pour l'UI
enum class MessageStatus {
    SENDING,
    SENT,
    RECEIVED,
    FAILED
}

// 2. Types de paquets
enum class PacketType {
    MESSAGE,
    ACK,
    HEARTBEAT,
    AUDIO,
    FILE,
    NODE_INFO
}

// 3. Modèle pour l'UI
data class ChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val isMine: Boolean,
    val time: String,
    val status: MessageStatus,
    val type: PacketType = PacketType.MESSAGE,
    val audioUri: Uri? = null,
    var isPlaying: Boolean = false,
    val receiverId: String = "TOUS",
    val senderIdRaw: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// 4. Modèle pour le transport Réseau
data class MeshPacket(
    val id: String,
    val senderId: String,
    val senderPseudo: String,
    val receiver: String,
    val content: String,
    val type: PacketType = PacketType.MESSAGE,
    val audioData: ByteArray? = null,
    val fileData: ByteArray? = null,
    val fileName: String? = null,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (id != other.id) return false
        if (senderId != other.senderId) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

// 5. Modes de connexion
enum class MeshMode {
    BLUETOOTH,
    WIFI_DIRECT,
    HYBRID
}

// 6. Modèle pour les noeuds du réseau
data class MeshNode(
    val deviceId: String,
    val deviceName: String,
    val pseudo: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val connectionType: ConnectionType,
    val isDirectConnection: Boolean = false,
    var isConnected: Boolean = false
)

enum class ConnectionType {
    BLUETOOTH,
    WIFI_DIRECT,
    WIFI_AWARE,
    MULTIPLE
}

// 7. Modèle pour la topologie du réseau
data class NetworkTopology(
    val nodes: Map<String, MeshNode>,
    val connections: List<Pair<String, String>>
)