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

class WifiDirectBroadcastReceiver : BroadcastReceiver() {

    private val TAG = "WifiDirectBroadcastReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        // Version simplifiée - juste du logging, pas d'appels nécessitant des permissions
        Log.d(TAG, "Broadcast reçu: ${intent.action}")

        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "État WiFi Direct: ${if (enabled) "activé" else "désactivé"}")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Les pairs ont changé - une activité peut demander la mise à jour")
                // Ne pas appeler requestPeers ici car ça nécessite des permissions
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "La connexion a changé")
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "Les infos de ce périphérique ont changé")
            }

            else -> {
                Log.d(TAG, "Action non gérée: ${intent.action}")
            }
        }
    }
}