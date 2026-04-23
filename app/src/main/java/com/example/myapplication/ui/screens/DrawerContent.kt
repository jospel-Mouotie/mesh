package com.example.myapplication.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ChatMessage
import com.example.myapplication.ConnectionType
import com.example.myapplication.MeshNode
@Composable
fun DrawerContent(
    myPseudo: String,
    isMeshActive: Boolean,
    nodes: Map<String, MeshNode>,
    messages: List<ChatMessage>,
    selectedChatId: String,
    onChatSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onShowRadar: () -> Unit,
    onClearMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onBecomeGO: () -> Unit,
    onJoinGroup: () -> Unit,
    onForceCleanup: () -> Unit  // ← AJOUTER CE PARAMÈTRE
) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFFF8FAFF),
        modifier = Modifier.width(320.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
    ) {
        Surface(
            color = Color(0xFF1A73E8).copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1A73E8),
                        modifier = Modifier.size(56.dp),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                myPseudo.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(myPseudo, fontWeight = FontWeight.Black, color = Color(0xFF202124), fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (isMeshActive) Color(0xFF0D904F) else Color(0xFFE65100), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isMeshActive) "Maillage Actif" else "Scan en cours...",
                                fontSize = 12.sp,
                                color = Color(0xFF5F6368)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Première rangée d'actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(Icons.Rounded.Refresh, "Sync", Color(0xFF1A73E8), onRefresh)
                    QuickActionButton(Icons.Rounded.Radar, "Radar", Color(0xFF8E24AA), onShowRadar)
                    QuickActionButton(Icons.Rounded.Settings, "Config", Color(0xFF00897B), onOpenSettings)
                }

                Spacer(Modifier.height(12.dp))

                // Deuxième rangée d'actions (groupe)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(Icons.Rounded.GroupAdd, "Créer GO", Color(0xFF1A73E8), onBecomeGO)
                    QuickActionButton(Icons.Rounded.Group, "Rejoindre", Color(0xFF8E24AA), onJoinGroup)
                    QuickActionButton(Icons.Rounded.CleaningServices, "Nettoyer", Color(0xFFE65100), onForceCleanup)
                }
            }
        }

        SectionTitle("COMMUNAUTÉ")
        NavigationDrawerItem(
            icon = { Icon(Icons.Rounded.Groups, null, tint = if(selectedChatId == "TOUS") Color(0xFF1A73E8) else Color(0xFF5F6368)) },
            label = { Text("Canal Général", fontWeight = FontWeight.Bold) },
            selected = selectedChatId == "TOUS",
            onClick = { onChatSelected("TOUS") },
            badge = {
                val unread = messages.count { !it.isMine && it.receiverId == "TOUS" }
                if (unread > 0) Badge(containerColor = Color(0xFF1A73E8)) { Text("$unread", color = Color.White) }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
            colors = navigationItemColors()
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp), color = Color.LightGray.copy(0.2f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CONTACTS PROCHES", color = Color(0xFF5F6368), fontSize = 11.sp, fontWeight = FontWeight.Black)
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1A73E8).copy(0.1f)) {
                Text("${nodes.size} en ligne", fontSize = 10.sp, color = Color(0xFF1A73E8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }

        if (nodes.isEmpty()) {
            EmptyNodesState()
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(nodes.values.toList().sortedByDescending { it.lastSeen }) { node ->
                    NodeDrawerItem(node, selectedChatId == node.deviceId) { onChatSelected(node.deviceId) }
                }
            }
        }

        Column(modifier = Modifier.background(Color(0xFFF0F2F5).copy(0.5f)).padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniStat("Nœuds", nodes.size.toString())
                MiniStat("Flux", "${messages.size}")
                MiniStat("DB", "?")
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onClearMessages,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F).copy(0.8f))
            ) {
                Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Réinitialiser le stockage", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable
fun QuickActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
            modifier = Modifier.size(44.dp),
            shadowElevation = 2.dp
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.padding(12.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF5F6368),
            modifier = Modifier.padding(top = 4.dp),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun NodeDrawerItem(node: MeshNode, isSelected: Boolean, onClick: () -> Unit) {
    val isActive = System.currentTimeMillis() - node.lastSeen < 45000

    NavigationDrawerItem(
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(contentAlignment = Alignment.Center) {
                    if (isActive) {
                        Box(Modifier.size(12.dp).background(Color(0xFF0D904F).copy(0.2f), CircleShape))
                    }
                    Box(Modifier.size(8.dp).background(if (isActive) Color(0xFF0D904F) else Color.Gray, CircleShape))
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(node.pseudo, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF202124))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (node.connectionType) {
                                ConnectionType.WIFI_DIRECT -> "📡 WiFi"
                                ConnectionType.BLUETOOTH -> "🔵 BLE"
                                else -> "🔗 Mesh"
                            },
                            fontSize = 10.sp,
                            color = Color(0xFF1A73E8),
                            fontWeight = FontWeight.Bold
                        )
                        if (node.estimatedDistance > 0) {
                            Text(" • ${node.estimatedDistance.toInt()}m", fontSize = 10.sp, color = Color(0xFF5F6368))
                        }
                    }
                }
            }
        },
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.padding(vertical = 2.dp, horizontal = 12.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
            selectedTextColor = Color(0xFF1A73E8),
            unselectedTextColor = Color(0xFF202124)
        )
    )
}

@Composable
fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color(0xFF202124))
        Text(text = label, fontSize = 9.sp, color = Color(0xFF5F6368), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyNodesState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.SensorsOff,
                contentDescription = null,
                tint = Color(0xFF5F6368).copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Aucun contact à portée",
                color = Color(0xFF5F6368),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun navigationItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = Color(0xFF1A73E8).copy(alpha = 0.12f),
    selectedTextColor = Color(0xFF1A73E8),
    selectedIconColor = Color(0xFF1A73E8),
    unselectedContainerColor = Color.Transparent,
    unselectedTextColor = Color(0xFF202124),
    unselectedIconColor = Color(0xFF5F6368)
)

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        color = Color(0xFF5F6368).copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.2.sp
    )
}