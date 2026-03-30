package com.example.myapplication

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class TestActivity : ComponentActivity() {

    private val TAG = "TEST_ACTIVITY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "🟢 TestActivity onCreate DÉMARRÉ")

        setContent {
            TestScreen()
        }

        Log.i(TAG, "✅ TestActivity setContent réussi")
    }
}

@Composable
fun TestScreen() {
    Text("✅ TEST - Application lancée avec succès!")
}