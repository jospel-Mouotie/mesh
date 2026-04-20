package com.example.myapplication

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// --- PALETTE DE COULEURS PREMIUM (DARK MODE ENGINEERING) ---
private val CyberBackground   = Color(0xFF080E14)
private val CyberSurface      = Color(0xFF101820)
private val CyberOutline      = Color(0xFF1E2A37)
private val CyberPrimary      = Color(0xFF00F2FF) // Cyan Électrique
private val CyberSecondary    = Color(0xFF7000FF) // Deep Purple
private val CyberSuccess      = Color(0xFF00FF85) // Green Neon
private val CyberWarning      = Color(0xFFFFB800) // Gold
private val CyberDanger       = Color(0xFFFF2E55) // Red Neon
private val CyberTextMain     = Color(0xFFE1E4E8)
private val CyberTextMuted    = Color(0xFF8B949E)

// Easing personnalisé pour l'onde sinusoïdale
private val SineEaseInOut: Easing = Easing { fraction ->
    (-(kotlin.math.cos(Math.PI * fraction) - 1) / 2).toFloat()
}

@Composable
fun RadarScreen(
    knownNodes: Map<String, MeshNode>,
    myPseudo: String,
    onClose: () -> Unit
) {
    // --- LOGIQUE DE CALCUL ---
    val nodesList by rememberUpdatedState(knownNodes.values.toList())
    val allNodes = nodesList
    val now = System.currentTimeMillis()
    val activeNodes = allNodes.filter { now - it.lastSeen < 30000 }

    // --- ANIMATIONS ---
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSystem")

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "sweep_angle"
    )

    val pingScale by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing)),
        label = "ping_scale"
    )

    val scanPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = SineEaseInOut), repeatMode = RepeatMode.Reverse),
        label = "scan_pulse"
    )

    Scaffold(
        containerColor = CyberBackground,
        topBar = {
            RadarHeader(
                activeCount = activeNodes.size,
                wifiCount = activeNodes.count { it.connectionType == ConnectionType.WIFI_DIRECT },
                relayCount = activeNodes.count { it.isRelay },
                onClose = onClose
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- SECTION CANVAS RADAR ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                // Effet de halo ambiant derrière le radar
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.8f)
                        .blur(80.dp)
                        .background(CyberPrimary.copy(alpha = 0.15f * scanPulse), CircleShape)
                )

                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val maxR = size.width / 2f - 10f
                    val maxDistance = 100f

                    // 1. GRILLE (Cercles concentriques)
                    for (i in 1..4) {
                        val r = maxR * i / 4f
                        drawCircle(
                            color = CyberOutline,
                            radius = r,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // 2. LIGNES RADIALES (Axes)
                    for (deg in 0..315 step 45) {
                        val rad = Math.toRadians(deg.toDouble())
                        drawLine(
                            color = CyberOutline.copy(alpha = 0.5f),
                            start = Offset(cx, cy),
                            end = Offset(cx + maxR * cos(rad).toFloat(), cy + maxR * sin(rad).toFloat()),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // 3. BALAYAGE ROTATIF (Sweep avec gradient)
                    rotate(sweepAngle, Offset(cx, cy)) {
                        // Traînée lumineuse (Gradient)
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.8f to CyberPrimary.copy(alpha = 0.05f),
                                1f to CyberPrimary.copy(alpha = 0.4f),
                                center = Offset(cx, cy)
                            ),
                            startAngle = -60f,
                            sweepAngle = 60f,
                            useCenter = true,
                            size = size
                        )
                        // Ligne de balayage principale
                        drawLine(
                            color = CyberPrimary,
                            start = Offset(cx, cy),
                            end = Offset(cx + maxR, cy),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // 4. PULSATION CENTRALE
                    drawCircle(
                        color = CyberPrimary.copy(alpha = (1f - pingScale) * 0.6f),
                        radius = maxR * pingScale,
                        center = Offset(cx, cy),
                        style = Stroke(1.5.dp.toPx())
                    )

                    // 5. RENDU DES NŒUDS ACTIFS
                    activeNodes.forEachIndexed { index, node ->
                        // Distance normalisée (inverse pour que les nœuds proches soient à l'intérieur)
                        val rawDistance = node.estimatedDistance.coerceIn(1f, maxDistance)
                        val normalizedDistance = (rawDistance / maxDistance).coerceIn(0.1f, 0.9f)
                        val r = maxR * normalizedDistance

                        // Angle calculé pour une répartition harmonieuse
                        val angleDeg = if (activeNodes.size > 1) 360f / activeNodes.size * index else 0f
                        val rad = Math.toRadians(angleDeg.toDouble())
                        val nx = cx + r * cos(rad).toFloat()
                        val ny = cy + r * sin(rad).toFloat()

                        // Couleur basée sur la fiabilité bayésienne
                        val score = node.bayesianReliability.toFloat()
                        val nodeColor = when {
                            node.isRelay -> CyberSecondary
                            node.isSubGo -> CyberWarning
                            score > 0.7f -> CyberSuccess
                            score > 0.4f -> CyberWarning
                            else -> CyberDanger
                        }

                        // Halo lumineux
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(nodeColor.copy(alpha = 0.4f), Color.Transparent),
                                center = Offset(nx, ny),
                                radius = 40f
                            ),
                            radius = 40f,
                            center = Offset(nx, ny)
                        )

                        // Ligne pointillée vers le centre
                        drawLine(
                            color = nodeColor.copy(alpha = 0.2f),
                            start = Offset(cx, cy),
                            end = Offset(nx, ny),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )

                        // Point principal
                        drawCircle(color = nodeColor, radius = 8.dp.toPx(), center = Offset(nx, ny))
                        drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(nx, ny))

                        // TEXTES AVEC CANVAS NATIF
                        drawIntoCanvas { canvas ->
                            val textPaint = Paint().apply {
                                isAntiAlias = true
                                textAlign = Paint.Align.CENTER
                                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            }

                            // Pseudo (en majuscules)
                            textPaint.color = android.graphics.Color.WHITE
                            textPaint.textSize = 24f
                            canvas.nativeCanvas.drawText(node.pseudo.uppercase(), nx, ny - 35f, textPaint)

                            // Distance et fiabilité
                            textPaint.textSize = 18f
                            textPaint.color = android.graphics.Color.argb(200, 255, 255, 255)
                            canvas.nativeCanvas.drawText("${rawDistance.toInt()}m | ${(score * 100).toInt()}%", nx, ny + 45f, textPaint)
                        }
                    }

                    // 6. NŒUD CENTRAL (Utilisateur)
                    drawCircle(CyberPrimary, 12.dp.toPx(), Offset(cx, cy))
                    drawCircle(Color.White, 4.dp.toPx(), Offset(cx, cy))
                }
            }

            // --- LÉGENDE & STATS ---
            RadarLegend()

            Spacer(modifier = Modifier.height(16.dp))

            // --- LISTE DÉTAILLÉE DES NŒUDS ---
            NodeListContainer(allNodes)
        }
    }
}

@Composable
fun RadarHeader(activeCount: Int, wifiCount: Int, relayCount: Int, onClose: () -> Unit) {
    Surface(
        color = CyberSurface,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        border = BorderStroke(1.dp, CyberOutline)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "MESHDAR SYSTEM v2.0",
                    color = CyberPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(CyberSuccess, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "SCAN EN COURS : $activeCount ACTIFS",
                        color = CyberTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(CyberOutline, CircleShape)
            ) {
                Icon(Icons.Rounded.Close, "Fermer", tint = Color.White)
            }
        }
    }
}

@Composable
fun RadarLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        LegendItem(CyberSuccess, "Stable (>70%)")
        LegendItem(CyberWarning, "Moyen (40-70%)")
        LegendItem(CyberDanger, "Critique (<40%)")
        LegendItem(CyberSecondary, "Relais / Sous-GO")
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = CyberTextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NodeListContainer(allNodes: List<MeshNode>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CyberOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TOPOLOGIE DÉTECTÉE",
                    color = CyberPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Icon(Icons.Rounded.Analytics, null, tint = CyberPrimary, modifier = Modifier.size(16.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (allNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Recherche de signaux...", color = CyberTextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allNodes.sortedByDescending { it.lastSeen }) { node ->
                        EnhancedNodeRow(node)
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedNodeRow(node: MeshNode) {
    val isActive = System.currentTimeMillis() - node.lastSeen < 30000
    val score = node.bayesianReliability.toFloat()

    val scoreColor = when {
        node.isRelay -> CyberSecondary
        score > 0.7f -> CyberSuccess
        score > 0.4f -> CyberWarning
        else -> CyberDanger
    }

    Surface(
        color = CyberBackground.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isActive) scoreColor.copy(alpha = 0.3f) else CyberOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cercle de progression (fiabilité)
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { score },
                    modifier = Modifier.size(42.dp),
                    color = scoreColor,
                    strokeWidth = 3.dp,
                    trackColor = CyberOutline
                )
                Text(
                    "${(score * 100).toInt()}%",
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Informations texte
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.pseudo,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = if (node.connectionType == ConnectionType.WIFI_DIRECT) Icons.Rounded.Wifi else Icons.Rounded.Bluetooth
                    Icon(icon, null, tint = CyberTextMuted, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        node.connectionType.name,
                        color = CyberTextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // Métriques distance / statut
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${node.estimatedDistance.toInt()} m",
                    color = CyberPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
                Text(
                    if (isActive) "ACTIF" else "PERDU",
                    color = if (isActive) CyberSuccess else CyberDanger,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}