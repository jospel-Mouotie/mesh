package com.example.myapplication

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.border
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    backgroundColor: Color,
    onColorChange: (Color) -> Unit,
    onSelectImage: () -> Unit,
    onClearImage: () -> Unit
) {
    val presetColors = listOf(
        Color(0xFFF5F7FA), Color(0xFFFFFFFF), Color(0xFFFCE4EC), Color(0xFFE8F0FE),
        Color(0xFFE0F2F1), Color(0xFFFFF3E0), Color(0xFFF3E5F5), Color(0xFFE8EAF6)
    )

    Dialog(onDismissRequest = onBack) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Paramètres d'affichage",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124)
                )
                Spacer(Modifier.height(16.dp))

                Text("Couleur d'arrière-plan", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5F6368))
                Spacer(Modifier.height(8.dp))

                // Sélecteur de couleurs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    presetColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { onColorChange(color) }
                                .then(
                                    if (backgroundColor == color) Modifier
                                        .border(2.dp, Color(0xFF1A73E8), CircleShape)
                                    else Modifier
                                )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Image de fond
                Text("Image d'arrière-plan", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5F6368))
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onSelectImage, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Image, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Choisir une image")
                    }
                    OutlinedButton(onClick = onClearImage, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Clear, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Effacer")
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Fermer")
                }
            }
        }
    }
}