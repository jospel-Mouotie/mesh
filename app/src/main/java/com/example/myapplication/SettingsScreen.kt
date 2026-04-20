package com.example.myapplication

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// --- PALETTE DE COULEURS ÉLARGIE ---
private val CyberBlue      = Color(0xFF00F2FF)
private val CyberPurple    = Color(0xFF7000FF)
private val CyberDark      = Color(0xFF0B1015)
private val CyberCard      = Color(0xFF141B24)
private val CyberText      = Color(0xFFE1E4E8)
private val CyberMuted     = Color(0xFF8B949E)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    backgroundColor: Color,
    onColorChange: (Color) -> Unit,
    onSelectImage: () -> Unit,
    onClearImage: () -> Unit,
    currentImageUri: String? = null
) {
    val presetColors = listOf(
        Color(0xFF0B1015), Color(0xFF1A73E8), Color(0xFF0D904F), Color(0xFF8E24AA),
        Color(0xFFE65100), Color(0xFFD32F2F), Color(0xFF263238), Color(0xFF455A64),
        Color(0xFF009688), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF212121)
    )

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = 650.dp)
                    .clickable(enabled = false) {}
                    .shadow(24.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = CyberCard,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    SettingsHeader(onBack)
                    Spacer(Modifier.height(24.dp))

                    Text(
                        "APERÇU DU SYSTÈME",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = CyberBlue,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemePreviewCard(backgroundColor, currentImageUri)

                    Spacer(Modifier.height(24.dp))

                    SettingsSectionTitle(Icons.Rounded.Palette, "Palette d'Arrière-plan")
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(50.dp),
                        modifier = Modifier.height(120.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(presetColors) { color ->
                            ColorOption(
                                color = color,
                                isSelected = backgroundColor == color,
                                onClick = { onColorChange(color) }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    SettingsSectionTitle(Icons.Rounded.Wallpaper, "Média d'Arrière-plan")
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionButton(
                            text = "Choisir Image",
                            icon = Icons.Rounded.AddPhotoAlternate,
                            containerColor = CyberBlue.copy(alpha = 0.1f),
                            contentColor = CyberBlue,
                            modifier = Modifier.weight(1f),
                            onClick = onSelectImage
                        )
                        ActionButton(
                            text = "Réinitialiser",
                            icon = Icons.Rounded.RestartAlt,
                            containerColor = Color.White.copy(alpha = 0.05f),
                            contentColor = CyberMuted,
                            modifier = Modifier.weight(1f),
                            onClick = onClearImage
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberBlue,
                            contentColor = CyberDark
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("APPLIQUER LES CHANGEMENTS", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Interface",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = CyberText
            )
            Text(
                "Personnalisez votre terminal Mesh",
                fontSize = 13.sp,
                color = CyberMuted
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier.background(Color.White.copy(alpha = 0.05f), CircleShape)
        ) {
            Icon(Icons.Rounded.Close, null, tint = CyberText)
        }
    }
}

@Composable
fun ThemePreviewCard(color: Color, imageUri: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUri != null) {
            Text("IMAGE ACTIVE", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        } else {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = 100f,
                    center = Offset(size.width * 0.8f, size.height * 0.2f)
                )
            }
            Text("MODE COULEUR UNIE", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingsSectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = CyberBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = CyberText
        )
    }
}

@Composable
fun ColorOption(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isSelected) 1.1f else 1f, label = "scale")

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) CyberBlue else Color.White.copy(alpha = 0.2f),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}