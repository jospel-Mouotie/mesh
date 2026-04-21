package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PseudoEntryScreen(myId: String, onJoin: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = Color(0xFF1A73E8).copy(0.15f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Hub, null, modifier = Modifier.size(64.dp), tint = Color(0xFF1A73E8))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("MIT MESH NETWORK", color = Color(0xFF202124), fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Communication décentralisée hors-infrastructure", color = Color(0xFF5F6368), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 40.dp))

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Votre pseudo", color = Color(0xFF5F6368)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF1A73E8), unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color(0xFF202124), cursorColor = Color(0xFF1A73E8)
            ),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Person, null, tint = Color(0xFF1A73E8)) }
        )
        Text("ID: $myId", color = Color(0xFF5F6368).copy(0.6f), fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { if (text.isNotBlank()) onJoin(text.trim()) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
            enabled = text.isNotBlank()
        ) {
            Text("REJOINDRE LE RÉSEAU", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
        }
    }
}

@Composable
fun LoadingOverlay(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(220.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF1A73E8), modifier = Modifier.size(44.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(14.dp))
                Text(message, color = Color(0xFF202124), fontSize = 13.sp, textAlign = TextAlign.Center)
                if (message.contains("Délai")) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { /* Action à gérer par l'appelant */ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                    ) { Text("Réessayer", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
    }
}

@Composable
fun ErrorSnackbar(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Snackbar(
            action = {
                TextButton(onClick = onDismiss) {
                    Text("OK", color = Color(0xFF1A73E8))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = message,
                color = if (message.startsWith("❌")) Color(0xFFD32F2F) else Color(0xFF202124),
                fontSize = 13.sp
            )
        }
    }
}

