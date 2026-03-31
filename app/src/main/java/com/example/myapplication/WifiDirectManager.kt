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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WifiDirectManager — Couche WiFi Direct uniquement.
 *
 * Ce manager NE gère PAS le TCP. Toute la couche TCP est dans MeshManager.
 * Ce manager est utilisé uniquement si tu veux instancier la couche WiFi Direct
 * séparément de MeshManager (ex. pour des tests ou une architecture modulaire).
 *
 * Dans l'architecture actuelle, MeshManager embarque déjà sa propre gestion
 * WiFi Direct. Ce fichier est conservé pour référence/compatibilité.
 */
class WifiDirectManager(
    private val context: Context,
    private val myId: String,
    private val myPseudo: String,
    private val onStatusUpdate: (String) -> Unit,
    private val onMessageReceived: (MeshPacket) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "WifiDirectManager"

    private val wifiP2pManager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel by lazy {
        wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    }

    private var isRunning = true
    private var thisDeviceAddress: String? = null
    private var isConnectedToGroup = false
    private val isConnecting = AtomicBoolean(false)
    private val isBecomingGO = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())

    // ==================== RECEIVER WIFI DIRECT ====================

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "WiFi Direct: ${if (enabled) "ACTIVÉ" else "DÉSACTIVÉ"}")
                    if (enabled) {
                        startDiscovery()
                        onStatusUpdate("WiFi Direct actif")
                    } else {
                        onError("WiFi Direct désactivé")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!isConnectedToGroup && !isConnecting.get()) {
                        requestPeers()
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleConnectionChange(intent)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
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
            Log.d(TAG, "✅ Receiver enregistré (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur enregistrement receiver: ${e.message}", e)
        }
    }

    // ==================== DÉCOUVERTE ====================

    fun startDiscovery() {
        if (!checkPermission()) { onError("Permission WiFi Direct manquante"); return }
        try {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Découverte démarrée")
                    onStatusUpdate("📡 Recherche de téléphones...")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ discoverPeers: $reason")
                    if (reason != 2) {  // 2 = BUSY, pas grave
                        onError("Échec découverte WiFi Direct ($reason)")
                        handler.postDelayed({ if (isRunning) startDiscovery() }, 10000)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée discoverPeers", e)
        }
    }

    private fun requestPeers() {
        if (!checkPermission() || isConnectedToGroup || isConnecting.get()) return
        try {
            wifiP2pManager.requestPeers(channel) { peers ->
                val devices = peers.deviceList
                Log.d(TAG, "${devices.size} pair(s) trouvé(s)")
                if (devices.isEmpty() || isConnecting.get() || isConnectedToGroup) return@requestPeers
                val target = devices.firstOrNull { it.deviceAddress != thisDeviceAddress }
                if (target != null) connectToPeer(target)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée requestPeers", e)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        if (!isConnecting.compareAndSet(false, true)) return
        if (!checkPermission()) { isConnecting.set(false); return }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        try {
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Connexion demandée à ${device.deviceName}")
                    onStatusUpdate("🔗 Connexion: ${device.deviceName}")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ connect échoué: $reason")
                    isConnecting.set(false)
                    handler.postDelayed({ if (isRunning && !isConnectedToGroup) requestPeers() }, 5000)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission refusée connect", e)
            isConnecting.set(false)
        }
    }

    private fun handleConnectionChange(intent: Intent) {
        @Suppress("DEPRECATION")
        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }

        if (networkInfo?.isConnected == true) {
            isConnectedToGroup = true
            isConnecting.set(false)
            isBecomingGO.set(false)

            wifiP2pManager.requestConnectionInfo(channel) { info ->
                val isGO = info?.isGroupOwner ?: false
                val goIp = info?.groupOwnerAddress?.hostAddress ?: "inconnu"
                Log.i(TAG, "✅ Connecté — GO: $goIp — Rôle: ${if (isGO) "GO 👑" else "client"}")
                if (isGO) onStatusUpdate("👑 En attente de clients")
                else onStatusUpdate("✅ Connecté au groupe")
            }
        } else {
            if (isConnectedToGroup) {
                Log.d(TAG, "🔴 Déconnecté du groupe")
                isConnectedToGroup = false
                isConnecting.set(false)
                isBecomingGO.set(false)
                onStatusUpdate("📡 Recherche de téléphones...")
                handler.postDelayed({ if (isRunning) startDiscovery() }, 2000)
            }
        }
    }

    // ==================== PERMISSIONS ====================

    private fun checkPermission(): Boolean {
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

    // ==================== ARRÊT ====================

    fun stop() {
        Log.i(TAG, "🛑 Arrêt WifiDirectManager")
        isRunning = false
        isConnectedToGroup = false
        isConnecting.set(false)
        isBecomingGO.set(false)

        try { context.unregisterReceiver(wifiReceiver) } catch (e: Exception) { }
        if (checkPermission()) {
            try { wifiP2pManager.removeGroup(channel, null) } catch (e: Exception) { }
        }
        handler.removeCallbacksAndMessages(null)
    }
}