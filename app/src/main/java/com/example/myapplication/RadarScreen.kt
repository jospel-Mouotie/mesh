package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

@Composable
fun RadarScreen(
    knownNodes: Map<String, MeshNode>,
    myPseudo: String,
    onClose: () -> Unit,
    bayesianScores: Map<String, Float> = emptyMap()
) {
    var rotationAngle by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            rotationAngle = (rotationAngle + 2) % 360
            kotlinx.coroutines.delay(16)
        }
    }

    fun isNodeActive(node: MeshNode): Boolean {
        return System.currentTimeMillis() - node.lastSeen < 30000
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("RADAR DE TOPOLOGIE", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF1A73E8))
        Text("Nœuds détectés : ${knownNodes.size - 1}", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.size(350.dp).padding(8.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height / 2
                val maxRadius = size.width / 2 - 20

                drawCircle(Color(0xFF1A73E8).copy(alpha = 0.3f), maxRadius, Offset(centerX, centerY), style = Stroke(width = 2f))

                for (i in 1..5) {
                    val radius = maxRadius * i / 5
                    drawCircle(Color.Gray.copy(alpha = 0.3f), radius, Offset(centerX, centerY), style = Stroke(width = 1f))
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 20f; textAlign = android.graphics.Paint.Align.CENTER }
                        drawText("${i * 10}m", centerX + radius, centerY + 10f, paint)
                    }
                }

                for (angle in 0..315 step 45) {
                    val radians = Math.toRadians(angle.toDouble())
                    val endX = centerX + maxRadius * cos(radians).toFloat()
                    val endY = centerY + maxRadius * sin(radians).toFloat()
                    drawLine(Color.Gray.copy(alpha = 0.5f), Offset(centerX, centerY), Offset(endX, endY), 1f)
                }

                rotate(rotationAngle, Offset(centerX, centerY)) {
                    drawLine(Color(0xFF1A73E8), Offset(centerX, centerY), Offset(centerX + maxRadius, centerY), 3f)
                }

                drawCircle(Color(0xFF1A73E8), 12f, Offset(centerX, centerY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                    drawText("MOI", centerX, centerY + 8f, paint)
                }

                val nodesList = knownNodes.values.filter { it.deviceId != myPseudo }.toList()
                val nodeCount = nodesList.size

                if (nodeCount > 0) {
                    nodesList.forEachIndexed { index, node ->
                        val radius = maxRadius * 0.6f
                        val angle = if (nodeCount > 1) (360.0 / nodeCount * index).toFloat() else 0f
                        val radians = Math.toRadians(angle.toDouble())
                        val x = centerX + radius * cos(radians).toFloat()
                        val y = centerY + radius * sin(radians).toFloat()
                        val isActive = isNodeActive(node)
                        val score = bayesianScores[node.deviceId] ?: 0.5f
                        val nodeColor = Color(red = (1f - score).coerceIn(0f, 1f), green = score.coerceIn(0f, 1f), blue = 0.2f, alpha = if (isActive) 0.8f else 0.3f)
                        val circleRadius = if (isActive) 18f else 14f
                        drawCircle(nodeColor, circleRadius, Offset(x, y))
                        if (isActive) {
                            val pulseRadius = circleRadius + 4f + (rotationAngle % 20) / 5
                            drawCircle(nodeColor.copy(alpha = 0.3f), pulseRadius, Offset(x, y), style = Stroke(width = 2f))
                        }
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply { color = if (isActive) android.graphics.Color.WHITE else android.graphics.Color.GRAY; textSize = 22f; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                            drawText(node.pseudo.take(10), x, y - 22f, paint)
                            val scorePaint = android.graphics.Paint().apply { color = if (isActive) android.graphics.Color.YELLOW else android.graphics.Color.GRAY; textSize = 16f; textAlign = android.graphics.Paint.Align.CENTER }
                            drawText(String.format("%.0f%%", score * 100), x, y + 30f, scorePaint)
                            val typePaint = android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 12f; textAlign = android.graphics.Paint.Align.CENTER }
                            val typeText = when (node.connectionType) { ConnectionType.WIFI_DIRECT -> "📡 WiFi Direct"; ConnectionType.BLUETOOTH -> "🔵 Bluetooth"; ConnectionType.WIFI_AWARE -> "✨ WiFi Aware"; else -> "🔗 Connecté" }
                            drawText(typeText, x, y + 48f, typePaint)
                        }
                    }
                } else {
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER }
                        drawText("Aucun voisin", centerX, centerY + 80f, paint)
                        drawText("En attente de connexion...", centerX, centerY + 120f, paint)
                    }
                }
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    LegendItem(Color(0xFF1A73E8), "Moi"); LegendItem(Color(0xFF4CAF50), "Fiable (>75%)")
                    LegendItem(Color(0xFFFF9800), "Moyen (25-75%)"); LegendItem(Color(0xFFF44336), "Faible (<25%)")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    LegendItem(Color.White.copy(alpha = 0.5f), "● Pulsation = Actif")
                    Spacer(modifier = Modifier.width(8.dp))
                    LegendItem(Color.Gray, "○ Inactif (gris)")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F20)), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("📊 DÉTAIL DES NŒUDS", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF1A73E8))
                Spacer(modifier = Modifier.height(8.dp))
                knownNodes.values.filter { it.deviceId != myPseudo }.forEach { node ->
                    val isActive = System.currentTimeMillis() - node.lastSeen < 30000
                    val score = bayesianScores[node.deviceId] ?: 0.5f
                    val scorePercent = (score * 100).toInt()
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(2f)) {
                            Box(modifier = Modifier.size(8.dp).background(if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336), CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(node.pseudo, color = if (isActive) Color.White else Color.Gray, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                        }
                        Text(formatLastSeen(node.lastSeen), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1.5f))
                        Surface(shape = RoundedCornerShape(12.dp), color = when { score > 0.7f -> Color(0xFF4CAF50).copy(alpha = 0.2f); score > 0.3f -> Color(0xFFFF9800).copy(alpha = 0.2f); else -> Color(0xFFF44336).copy(alpha = 0.2f) }) {
                            Text("🎲 $scorePercent%", color = when { score > 0.7f -> Color(0xFF4CAF50); score > 0.3f -> Color(0xFFFF9800); else -> Color(0xFFF44336) }, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    LinearProgressIndicator(progress = score, modifier = Modifier.fillMaxWidth().height(4.dp), color = when { score > 0.7f -> Color(0xFF4CAF50); score > 0.3f -> Color(0xFFFF9800); else -> Color(0xFFF44336) }, trackColor = Color.Gray.copy(alpha = 0.3f))
                }
                if (knownNodes.size <= 1) Text("Aucun voisin détecté", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)), modifier = Modifier.fillMaxWidth()) { Text("Fermer", color = Color.White) }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text, fontSize = 9.sp, color = Color.Gray)
    }
}

private fun formatLastSeen(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 5000 -> "🟢 maintenant"
        diff < 30000 -> "🟡 il y a ${diff / 1000}s"
        diff < 60000 -> "🟠 il y a ${diff / 1000}s"
        else -> "🔴 inactif"
    }
}