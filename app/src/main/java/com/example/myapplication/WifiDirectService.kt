package com.example.myapplication

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class WifiDirectService : Service() {

    private val TAG = "WifiDirectService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service WiFi Direct créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service WiFi Direct démarré")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service WiFi Direct arrêté")
    }
}