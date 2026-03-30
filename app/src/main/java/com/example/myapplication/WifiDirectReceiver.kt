package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class WifiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    private val TAG = "WifiDirectReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "WiFi Direct ${if (enabled) "activé" else "désactivé"}")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Liste des pairs modifiée")

                // Vérification des permissions avant d'appeler requestPeers
                if (checkWifiPermissions(context)) {
                    try {
                        manager.requestPeers(channel) { peers ->
                            val devices = peers.deviceList
                            Log.d(TAG, "Pairs trouvés: ${devices.size}")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission refusée pour requestPeers", e)
                    }
                } else {
                    Log.w(TAG, "Permissions WiFi non accordées")
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "État de connexion changé")

                if (checkWifiPermissions(context)) {
                    try {
                        manager.requestConnectionInfo(channel) { info ->
                            Log.d(TAG, "Info connexion: ${info.groupFormed}")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission refusée pour requestConnectionInfo", e)
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "Cet appareil a changé")
            }
        }
    }

    private fun checkWifiPermissions(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}