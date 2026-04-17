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

// Couleurs claires pour le radar
private val RadarBgLight       = Color(0xFFF8FAFE)
private val RadarSurfaceLight  = Color(0xFFFFFFFF)
private val RadarGridColor     = Color(0xFFE0E4E8)
private val RadarSweepColor    = Color(0xFF1A73E8)
private val RadarTextPrimary   = Color(0xFF202124)
private val RadarTextSecondary = Color(0xFF5F6368)
private val RadarDistanceColor = Color(0xFFE65100)
private val RadarRelayColor    = Color(0xFF8E24AA)
private val RadarSubGoColor    = Color(0xFFE65100)
private val RadarHighRel       = Color(0xFF0D904F)
private val RadarMediumRel     = Color(0xFFFFB300)
private val RadarLowRel        = Color(0xFFD32F2F)
private val RadarCenterColor   = Color(0xFF1A73E8)

@Composable
fun RadarScreen(
    knownNodes: Map<String, MeshNode>,
    myPseudo: String,
    onClose: () -> Unit
) {
    // Forcer la recomposition à chaque changement de knownNodes
    val nodesList by rememberUpdatedState(knownNodes.values.toList())
    val allNodes = nodesList
    val now = System.currentTimeMillis()
    val activeNodes = allNodes.filter { now - it.lastSeen < 30000 }

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
            .background(RadarBgLight)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // En-tête
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "RADAR RÉSEAU",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RadarTextPrimary
                )
                Text(
                    "📡 WiFi:${activeNodes.count { it.connectionType == ConnectionType.WIFI_DIRECT }}  " +
                            "🔁 Relais:${activeNodes.count { it.isRelay }}  " +
                            "🏛️ Sous-GO:${activeNodes.count { it.isSubGo }}",
                    fontSize = 12.sp,
                    color = RadarTextSecondary
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = RadarTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Canvas radar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(RadarSurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = size.width / 2f - 8f
                val maxDistance = 100f

                // Fond
                drawCircle(RadarSurfaceLight, maxR, Offset(cx, cy))

                // Cercles concentriques
                for (i in 1..4) {
                    val r = maxR * i / 4f
                    drawCircle(
                        color = RadarGridColor,
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(1f)
                    )
                }

                // Lignes radiales
                for (deg in 0..315 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    drawLine(
                        color = RadarGridColor,
                        start = Offset(cx, cy),
                        end = Offset(cx + maxR * cos(rad).toFloat(), cy + maxR * sin(rad).toFloat()),
                        strokeWidth = 0.8f
                    )
                }

                // Balayage rotatif
                rotate(sweepAngle, Offset(cx, cy)) {
                    drawLine(
                        color = RadarSweepColor.copy(alpha = 0.8f),
                        start = Offset(cx, cy),
                        end = Offset(cx + maxR, cy),
                        strokeWidth = 2f
                    )
                    for (trail in 1..6) {
                        val trailAngle = -trail * 6f
                        val alpha = (1f - trail / 7f) * 0.4f
                        rotate(trailAngle, Offset(cx, cy)) {
                            drawLine(
                                color = RadarSweepColor.copy(alpha = alpha),
                                start = Offset(cx, cy),
                                end = Offset(cx + maxR, cy),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }

                // Pulsation centrale
                drawCircle(
                    color = RadarSweepColor.copy(alpha = (1f - pingScale) * 0.4f),
                    radius = maxR * pingScale * 0.15f,
                    center = Offset(cx, cy),
                    style = Stroke(1.5f)
                )

                // Affichage des nœuds actifs
                if (activeNodes.isNotEmpty()) {
                    activeNodes.forEachIndexed { index, node ->
                        val rawDistance = node.estimatedDistance.coerceIn(1f, maxDistance)
                        val normalizedDistance = rawDistance / maxDistance
                        val r = maxR * normalizedDistance
                        val angleDeg = if (activeNodes.size > 1) 360f / activeNodes.size * index else 0f
                        val rad = Math.toRadians(angleDeg.toDouble())
                        val nx = cx + r * cos(rad).toFloat()
                        val ny = cy + r * sin(rad).toFloat()

                        val score = node.bayesianReliability.toFloat()
                        val nodeColor = when {
                            node.isRelay -> RadarRelayColor
                            node.isSubGo -> RadarSubGoColor
                            score > 0.7f -> RadarHighRel
                            score > 0.4f -> RadarMediumRel
                            else -> RadarLowRel
                        }
                        val isWifi = node.connectionType == ConnectionType.WIFI_DIRECT
                        val dotRadius = 10f

                        // Ligne vers le centre
                        drawLine(
                            color = nodeColor.copy(alpha = 0.4f),
                            start = Offset(cx, cy),
                            end = Offset(nx, ny),
                            strokeWidth = 0.8f
                        )

                        // Pulse
                        val pulse = pingScale * dotRadius * 2.5f
                        drawCircle(
                            color = nodeColor.copy(alpha = (1f - pingScale) * 0.5f),
                            radius = dotRadius + pulse,
                            center = Offset(nx, ny),
                            style = Stroke(1f)
                        )

                        // Cercle principal
                        drawCircle(color = nodeColor, radius = dotRadius, center = Offset(nx, ny))
                        drawCircle(
                            color = if (isWifi) Color.White else RadarTextPrimary,
                            radius = 3f,
                            center = Offset(nx, ny)
                        )

                        // Marqueur relais / sous-GO
                        if (node.isRelay) {
                            drawCircle(
                                color = RadarRelayColor.copy(alpha = 0.7f),
                                radius = dotRadius + 4f,
                                center = Offset(nx, ny),
                                style = Stroke(2f)
                            )
                        } else if (node.isSubGo) {
                            drawCircle(
                                color = RadarSubGoColor.copy(alpha = 0.7f),
                                radius = dotRadius + 4f,
                                center = Offset(nx, ny),
                                style = Stroke(2f)
                            )
                        }

                        // Textes
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply { isAntiAlias = true }
                            // Pseudo
                            paint.color = android.graphics.Color.BLACK
                            paint.textSize = 22f
                            paint.textAlign = android.graphics.Paint.Align.CENTER
                            paint.isFakeBoldText = true
                            canvas.nativeCanvas.drawText(node.pseudo.take(8), nx, ny - dotRadius - 6f, paint)

                            // Distance
                            paint.color = android.graphics.Color.rgb(230, 81, 0)
                            paint.textSize = 18f
                            canvas.nativeCanvas.drawText("${rawDistance.toInt()}m", nx, ny + dotRadius + 18f, paint)

                            // Fiabilité
                            paint.color = android.graphics.Color.rgb(13, 144, 79)
                            paint.textSize = 14f
                            canvas.nativeCanvas.drawText("${(score * 100).toInt()}%", nx, ny + dotRadius + 34f, paint)

                            // Icône
                            if (node.isRelay) {
                                paint.color = android.graphics.Color.rgb(142, 36, 170)
                                paint.textSize = 16f
                                canvas.nativeCanvas.drawText("🔁", nx, ny - dotRadius - 22f, paint)
                            } else if (node.isSubGo) {
                                paint.color = android.graphics.Color.rgb(230, 81, 0)
                                paint.textSize = 16f
                                canvas.nativeCanvas.drawText("🏛️", nx, ny - dotRadius - 22f, paint)
                            }
                        }
                    }
                }

                // Nœud central (moi)
                drawCircle(RadarCenterColor, 14f, Offset(cx, cy))
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

                // Labels de distance
                for (i in 1..4) {
                    val r = maxR * i / 4f
                    val distLabel = "${(maxDistance * i / 4).toInt()}m"
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(180, 95, 99, 104)
                            textSize = 16f
                            textAlign = android.graphics.Paint.Align.LEFT
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawText(distLabel, cx + r + 3f, cy - 3f, paint)
                    }
                }
            }
        }

        // Légende
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RadarLegendItem(RadarHighRel, "Fiable >70%")
            RadarLegendItem(RadarMediumRel, "Moyen 40-70%")
            RadarLegendItem(RadarLowRel, "Faible <40%")
            RadarLegendItem(RadarRelayColor, "Relais")
            RadarLegendItem(RadarSubGoColor, "Sous-GO")
            RadarLegendItem(RadarCenterColor, "Moi")
        }

        // Liste des nœuds
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RadarSurfaceLight),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "📊 NŒUDS DÉTECTÉS (Routage Bayésien)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = RadarTextPrimary
                )
                Spacer(Modifier.height(6.dp))

                if (allNodes.isEmpty()) {
                    Text(
                        "Aucun voisin détecté — en attente de connexion...",
                        color = RadarTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(allNodes.sortedByDescending { it.lastSeen }) { node ->
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
            colors = ButtonDefaults.buttonColors(containerColor = RadarCenterColor)
        ) {
            Text("Fermer", color = Color.White)
        }
    }
}

@Composable
fun RadarNodeRow(node: MeshNode) {
    val isActive = System.currentTimeMillis() - node.lastSeen < 30000
    val score = node.bayesianReliability.toFloat()
    val scoreColor = when {
        node.isRelay -> RadarRelayColor
        node.isSubGo -> RadarSubGoColor
        score > 0.7f -> RadarHighRel
        score > 0.4f -> RadarMediumRel
        else -> RadarLowRel
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RadarSurfaceLight, RoundedCornerShape(8.dp))
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
                        if (isActive) RadarHighRel else RadarTextSecondary, CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        node.pseudo,
                        color = if (isActive) RadarTextPrimary else RadarTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val typeIcon = when (node.connectionType) {
                            ConnectionType.WIFI_DIRECT -> "📡"
                            ConnectionType.BLUETOOTH -> "🔵"
                            ConnectionType.RELAY_BRIDGE -> "🔁"
                            ConnectionType.SUB_GO -> "🏛️"
                        }
                        Text("$typeIcon ${node.connectionType.name}", fontSize = 10.sp, color = RadarTextSecondary)
                        if (node.isRelay) {
                            Spacer(Modifier.width(8.dp))
                            Text("🔁 Relais", fontSize = 10.sp, color = RadarRelayColor)
                        } else if (node.isSubGo) {
                            Spacer(Modifier.width(8.dp))
                            Text("🏛️ Sous-GO", fontSize = 10.sp, color = RadarSubGoColor)
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
                        color = scoreColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    "📏 ${node.estimatedDistance.toInt()} m",
                    color = RadarDistanceColor,
                    fontSize = 11.sp
                )
                Text(
                    formatLastSeenRadar(node.lastSeen),
                    color = RadarTextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = score,
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = scoreColor,
            trackColor = RadarGridColor
        )
    }
}

@Composable
fun RadarLegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 9.sp, color = RadarTextSecondary)
    }
}

private fun formatLastSeenRadar(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 5000 -> "🟢 maintenant"
        diff < 30000 -> "🟡 ${diff / 1000}s"
        diff < 60000 -> "🟠 ${diff / 1000}s"
        else -> "🔴 inactif"
    }
}