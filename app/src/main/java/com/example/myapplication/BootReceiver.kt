package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "📱 Démarrage du système, lancement du MeshService")

            val prefs = context.getSharedPreferences("MIT_MESH_SETTINGS", Context.MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)
            val userPseudo = prefs.getString("user_pseudo", null)

            if (userId != null && userPseudo != null) {
                val serviceIntent = Intent(context, MeshService::class.java).apply {
                    putExtra("user_id", userId)
                    putExtra("user_pseudo", userPseudo)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("BootReceiver", "✅ MeshService démarré avec ID: $userId")
            } else {
                Log.w("BootReceiver", "⚠️ Pas d'identifiants stockés, démarrage différé")
                // Créer des identifiants temporaires
                val tempId = "MIT-" + java.util.UUID.randomUUID().toString().take(6).uppercase()
                val tempPseudo = "Guest-${tempId.takeLast(4)}"

                prefs.edit().putString("user_id", tempId).putString("user_pseudo", tempPseudo).apply()

                val serviceIntent = Intent(context, MeshService::class.java).apply {
                    putExtra("user_id", tempId)
                    putExtra("user_pseudo", tempPseudo)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("BootReceiver", "✅ MeshService démarré avec ID temporaire: $tempId")
            }
        }
    }
}