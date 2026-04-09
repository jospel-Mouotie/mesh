package com.example.myapplication

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ==================== COULEURS RADAR ====================
private val RadarBg        = Color(0xFF060B14)
private val RadarBlue      = Color(0xFF1A73E8)
private val RadarGreen     = Color(0xFF00E676)
private val RadarAmber     = Color(0xFFFFAB40)
private val RadarRed       = Color(0xFFFF5252)
private val RadarCyan      = Color(0xFF18FFFF)
private val RadarSurface   = Color(0xFF0D1B2A)
private val RadarPurple    = Color(0xFFAA00FF)
private val RadarOrange    = Color(0xFFFF6D00)

// ==================== RADAR SCREEN ====================
@Composable
fun RadarScreen(
    knownNodes: Map<String, MeshNode>,
    myPseudo: String,
    onClose: () -> Unit
) {
    val neighbors = knownNodes.values.toList()
    val activeNodes = neighbors.filter { System.currentTimeMillis() - it.lastSeen < 30000 }
    val wifiNodes = activeNodes.filter { it.connectionType == ConnectionType.WIFI_DIRECT }
    val bleNodes = activeNodes.filter { it.connectionType == ConnectionType.BLUETOOTH }
    val relayNodes = activeNodes.filter { it.isRelay }
    val subGoNodes = activeNodes.filter { it.isSubGo }

    val sweepAngle by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "sweep_angle"
    )

    val pingScale by rememberInfiniteTransition(label = "ping").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing)),
        label = "ping_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarBg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== EN-TÊTE =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "RADAR RÉSEAU",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RadarCyan
                )
                Text(
                    "📡 WiFi:${wifiNodes.size}  🔵 BLE:${bleNodes.size}  🔁 Relais:${relayNodes.size}  🏛️ Sous-GO:${subGoNodes.size}",
                    fontSize = 12.sp, color = Color.Gray
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ===== RADAR CANVAS =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(RadarSurface),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = size.width / 2f - 8f
                val maxDistance = 100f  // distance maximale affichée (mètres)

                // Fond circulaire
                drawCircle(RadarBg, maxR, Offset(cx, cy))

                // Cercles concentriques (25m, 50m, 75m, 100m)
                for (i in 1..4) {
                    val r = maxR * i / 4f
                    drawCircle(
                        color = RadarBlue.copy(alpha = 0.15f),
                        radius = r, center = Offset(cx, cy),
                        style = Stroke(1f)
                    )
                }

                // Lignes radiales
                for (deg in 0..315 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    drawLine(
                        color = RadarBlue.copy(alpha = 0.2f),
                        start = Offset(cx, cy),
                        end = Offset(cx + maxR * cos(rad).toFloat(), cy + maxR * sin(rad).toFloat()),
                        strokeWidth = 0.8f
                    )
                }

                // Balayage rotatif
                rotate(sweepAngle, Offset(cx, cy)) {
                    drawLine(
                        color = RadarGreen.copy(alpha = 0.9f),
                        start = Offset(cx, cy),
                        end = Offset(cx + maxR, cy),
                        strokeWidth = 2f
                    )
                    for (trail in 1..6) {
                        val trailAngle = -trail * 6f
                        val alpha = (1f - trail / 7f) * 0.5f
                        rotate(trailAngle, Offset(cx, cy)) {
                            drawLine(
                                color = RadarGreen.copy(alpha = alpha),
                                start = Offset(cx, cy),
                                end = Offset(cx + maxR, cy),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // Anneau de pulsation central
                drawCircle(
                    color = RadarGreen.copy(alpha = (1f - pingScale) * 0.4f),
                    radius = maxR * pingScale * 0.15f,
                    center = Offset(cx, cy),
                    style = Stroke(1.5f)
                )

                // ===== AFFICHAGE DES NŒUDS =====
                val nodeCount = neighbors.size
                if (nodeCount > 0) {
                    neighbors.forEachIndexed { index, node ->
                        val isActive = System.currentTimeMillis() - node.lastSeen < 30000
                        // Éviter d'afficher 0 m (distance par défaut)
                        val rawDistance = node.estimatedDistance.coerceIn(1f, maxDistance)
                        val normalizedDistance = rawDistance / maxDistance
                        val r = maxR * normalizedDistance
                        val angleDeg = if (nodeCount > 1) 360f / nodeCount * index else 0f
                        val rad = Math.toRadians(angleDeg.toDouble())
                        val nx = cx + r * cos(rad).toFloat()
                        val ny = cy + r * sin(rad).toFloat()

                        val score = node.bayesianReliability.toFloat()
                        val nodeColor = when {
                            !isActive -> Color.Gray.copy(alpha = 0.4f)
                            node.isRelay -> RadarPurple
                            node.isSubGo -> RadarOrange
                            score > 0.7f -> RadarGreen
                            score > 0.4f -> RadarAmber
                            else -> RadarRed
                        }

                        val isWifi = node.connectionType == ConnectionType.WIFI_DIRECT
                        val dotRadius = if (isActive) 10f else 7f

                        // Ligne de connexion au centre
                        drawLine(
                            color = nodeColor.copy(alpha = if (isActive) 0.3f else 0.1f),
                            start = Offset(cx, cy), end = Offset(nx, ny), strokeWidth = 0.8f
                        )

                        // Pulse sur le nœud actif
                        if (isActive) {
                            val pulse = pingScale * dotRadius * 2.5f
                            drawCircle(
                                color = nodeColor.copy(alpha = (1f - pingScale) * 0.5f),
                                radius = dotRadius + pulse, center = Offset(nx, ny),
                                style = Stroke(1f)
                            )
                        }

                        // Cercle principal
                        drawCircle(color = nodeColor, radius = dotRadius, center = Offset(nx, ny))
                        drawCircle(
                            color = if (isWifi) Color.White.copy(alpha = 0.8f) else RadarCyan.copy(alpha = 0.8f),
                            radius = 3f, center = Offset(nx, ny)
                        )

                        // Marqueur spécial pour relais / sous‑GO
                        if (node.isRelay) {
                            drawCircle(
                                color = RadarPurple.copy(alpha = 0.8f),
                                radius = dotRadius + 4f, center = Offset(nx, ny),
                                style = Stroke(2f)
                            )
                        } else if (node.isSubGo) {
                            drawCircle(
                                color = RadarOrange.copy(alpha = 0.8f),
                                radius = dotRadius + 4f, center = Offset(nx, ny),
                                style = Stroke(2f)
                            )
                        }

                        // Texte (pseudo, distance, fiabilité) avec drawIntoCanvas
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply { isAntiAlias = true }
                            // Pseudo
                            paint.color = if (isActive) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                            paint.textSize = 22f
                            paint.textAlign = android.graphics.Paint.Align.CENTER
                            paint.isFakeBoldText = isActive
                            canvas.nativeCanvas.drawText(node.pseudo.take(8), nx, ny - dotRadius - 6f, paint)

                            // Distance (ne sera plus 0)
                            paint.color = android.graphics.Color.YELLOW
                            paint.textSize = 18f
                            canvas.nativeCanvas.drawText("${rawDistance.toInt()}m", nx, ny + dotRadius + 18f, paint)

                            // Fiabilité
                            paint.color = android.graphics.Color.CYAN
                            paint.textSize = 14f
                            canvas.nativeCanvas.drawText("${(score * 100).toInt()}%", nx, ny + dotRadius + 34f, paint)

                            // Icône relais / sous‑GO
                            if (node.isRelay) {
                                paint.color = android.graphics.Color.rgb(170, 0, 255)
                                paint.textSize = 16f
                                canvas.nativeCanvas.drawText("🔁", nx, ny - dotRadius - 22f, paint)
                            } else if (node.isSubGo) {
                                paint.color = android.graphics.Color.rgb(255, 109, 0)
                                paint.textSize = 16f
                                canvas.nativeCanvas.drawText("🏛️", nx, ny - dotRadius - 22f, paint)
                            }
                        }
                    }
                }

                // Nœud central (soi‑même)
                drawCircle(RadarBlue, 14f, Offset(cx, cy))
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 20f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText(myPseudo.take(4).uppercase(), cx, cy + 7f, paint)
                }

                // Labels des distances sur les cercles
                for (i in 1..4) {
                    val r = maxR * i / 4f
                    val distLabel = "${(maxDistance * i / 4).toInt()}m"
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(120, 180, 180, 180)
                            textSize = 16f
                            textAlign = android.graphics.Paint.Align.LEFT
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawText(distLabel, cx + r + 3f, cy - 3f, paint)
                    }
                }
            }
        }

        // ===== LÉGENDE =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RadarLegendItem(RadarGreen, "Fiable >70%")
            RadarLegendItem(RadarAmber, "Moyen 40-70%")
            RadarLegendItem(RadarRed, "Faible <40%")
            RadarLegendItem(RadarPurple, "Relais")
            RadarLegendItem(RadarOrange, "Sous-GO")
            RadarLegendItem(RadarBlue, "Moi")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(Color.White, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("= WiFi Direct", fontSize = 10.sp, color = Color.Gray)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).background(RadarCyan, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("= BLE", fontSize = 10.sp, color = Color.Gray)
        }

        // ===== TABLEAU DES NŒUDS =====
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RadarSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "📊 NŒUDS DÉTECTÉS",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RadarCyan
                )
                Spacer(Modifier.height(6.dp))

                if (neighbors.isEmpty()) {
                    Text(
                        "Aucun voisin détecté — en attente de connexion...",
                        color = Color.Gray, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(neighbors.sortedByDescending { it.lastSeen }) { node ->
                            RadarNodeRow(node)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = RadarBlue)
        ) {
            Text("Fermer", color = Color.White)
        }
    }
}

// ==================== LIGNE NŒUD (TABLEAU) ====================
@Composable
fun RadarNodeRow(node: MeshNode) {
    val isActive = System.currentTimeMillis() - node.lastSeen < 30000
    val score = node.bayesianReliability.toFloat()
    val scoreColor = when {
        node.isRelay -> RadarPurple
        node.isSubGo -> RadarOrange
        score > 0.7f -> RadarGreen
        score > 0.4f -> RadarAmber
        else -> RadarRed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111D2A), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        if (isActive) RadarGreen else Color.Gray, CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        node.pseudo,
                        color = if (isActive) Color.White else Color.Gray,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val typeIcon = when (node.connectionType) {
                            ConnectionType.WIFI_DIRECT -> "📡"
                            ConnectionType.BLUETOOTH -> "🔵"
                            ConnectionType.RELAY_BRIDGE -> "🔁"
                            ConnectionType.SUB_GO -> "🏛️"
                        }
                        Text("$typeIcon ${node.connectionType.name}", fontSize = 10.sp, color = Color.Gray)
                        if (node.groupId.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("Groupe: ${node.groupId.take(8)}", fontSize = 10.sp, color = RadarBlue.copy(alpha = 0.8f))
                        }
                        if (node.isRelay) {
                            Spacer(Modifier.width(8.dp))
                            Text("🔁 Relais", fontSize = 10.sp, color = RadarPurple)
                        } else if (node.isSubGo) {
                            Spacer(Modifier.width(8.dp))
                            Text("🏛️ Sous-GO", fontSize = 10.sp, color = RadarOrange)
                        }
                        if (node.superGoId != null) {
                            Spacer(Modifier.width(8.dp))
                            Text("↑ ${node.superGoId.take(4)}", fontSize = 9.sp, color = Color.Gray)
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = scoreColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        "${(score * 100).toInt()}%",
                        color = scoreColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    "📏 ${node.estimatedDistance.toInt()} m",
                    color = Color(0xFFFFEB3B), fontSize = 11.sp
                )
                Text(
                    formatLastSeenRadar(node.lastSeen),
                    color = Color.Gray, fontSize = 10.sp
                )
                if (node.batteryLevel < 100) {
                    Text(
                        "🔋 ${node.batteryLevel}%",
                        color = if (node.batteryLevel < 20) RadarRed else RadarGreen,
                        fontSize = 9.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = score,
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = scoreColor,
            trackColor = Color.Gray.copy(alpha = 0.2f)
        )
    }
}

// ==================== LÉGENDE ====================
@Composable
fun RadarLegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 9.sp, color = Color.Gray)
    }
}

// ==================== UTILITAIRE ====================
private fun formatLastSeenRadar(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 5000 -> "🟢 maintenant"
        diff < 30000 -> "🟡 ${diff / 1000}s"
        diff < 60000 -> "🟠 ${diff / 1000}s"
        else -> "🔴 inactif"
    }
}