package com.example.myapplication.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ChatMessage
import com.example.myapplication.ConnectionType
import com.example.myapplication.MeshNode
import com.example.myapplication.MessageStatus
import com.example.myapplication.PacketType
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMeshScreen(
    myId: String,
    myPseudo: String,
    messages: List<ChatMessage>,
    nodes: Map<String, MeshNode>,
    isMeshActive: Boolean,
    networkStatus: String,
    showNetworkMap: Boolean,
    showSearchBar: Boolean,
    searchQuery: String,
    selectedChatId: String,
    playingAudioId: String?,
    isRecording: Boolean,
    replyingToMessage: ChatMessage?,
    backgroundColor: Color,
    backgroundImageUri: Uri?,
    backgroundType: String,
    onChatSelected: (String) -> Unit,
    onMenuClick: () -> Unit,
    onMapToggle: () -> Unit,
    onRadarClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String, String, ChatMessage?) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onRefresh: () -> Unit,
    onClearMessages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFile: (ChatMessage) -> Unit,
    onBecomeGO: () -> Unit,
    onJoinGroup: () -> Unit,
    onForceCleanup: () -> Unit  // ← AJOUTER CE PARAMÈTRE
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val selectedChatName = if (selectedChatId == "TOUS") {
        "MIT MESH"
    } else {
        nodes[selectedChatId]?.pseudo ?: "Discussion"
    }

    val filteredMessages = remember(selectedChatId, messages, searchQuery, myId, myPseudo) {
        val baseList = if (selectedChatId == "TOUS") {
            messages.filter { it.receiverId == "TOUS" }
        } else {
            messages.filter { msg ->
                val isFromSelected = msg.senderIdRaw == selectedChatId || msg.sender == selectedChatId
                val isToSelected = msg.receiverId == selectedChatId
                val isFromMe = msg.senderIdRaw == myId || msg.sender == myPseudo
                val isToMe = msg.receiverId == myId
                (isFromSelected && isToMe) || (isFromMe && isToSelected)
            }
        }
        if (searchQuery.isBlank()) baseList
        else baseList.filter {
            it.content.contains(searchQuery, ignoreCase = true) ||
                    it.sender.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !showNetworkMap,
        drawerContent = {
            DrawerContent(
                myPseudo = myPseudo,
                isMeshActive = isMeshActive,
                nodes = nodes,
                messages = messages,
                selectedChatId = selectedChatId,
                onChatSelected = { id ->
                    onChatSelected(id)
                    scope.launch { drawerState.close() }
                },
                onRefresh = onRefresh,
                onShowRadar = onRadarClick,
                onClearMessages = onClearMessages,
                onOpenSettings = onOpenSettings,
                onBecomeGO = onBecomeGO,
                onJoinGroup = onJoinGroup,
                onForceCleanup = onForceCleanup  // ← PASSER LE PARAMÈTRE
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                AppTopBar(
                    selectedChatId = selectedChatId,
                    selectedChatName = selectedChatName,
                    networkStatus = networkStatus,
                    isMeshActive = isMeshActive,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onMapToggle = onMapToggle,
                    onRadarClick = onRadarClick,
                    onSearchClick = onSearchClick,
                    onSettingsClick = onSettingsClick,
                    showNetworkMap = showNetworkMap
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !showNetworkMap,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    ChatInputBar(
                        selectedId = selectedChatId,
                        onSend = { content ->
                            onSendMessage(content, selectedChatId, replyingToMessage)
                            onCancelReply()
                        },
                        onStartRecord = onStartRecording,
                        onStopRecord = onStopRecording,
                        onPickFile = onPickFile,
                        onPickImage = onPickImage,
                        isRecording = isRecording,
                        replyingTo = replyingToMessage,
                        onCancelReply = onCancelReply
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (backgroundType) {
                    "image" -> {
                        backgroundImageUri?.let { uri ->
                            val bitmap = runCatching {
                                context.contentResolver.openInputStream(uri)?.use {
                                    android.graphics.BitmapFactory.decodeStream(it)
                                }
                            }.getOrNull()
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } ?: Box(Modifier.fillMaxSize().background(backgroundColor))
                        } ?: Box(Modifier.fillMaxSize().background(backgroundColor))
                    }
                    else -> Box(Modifier.fillMaxSize().background(backgroundColor))
                }

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AnimatedVisibility(visible = showSearchBar) {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onClose = onSearchClose
                        )
                    }

                    if (showNetworkMap) {
                        NetworkMapBar(nodes = nodes, myPseudo = myPseudo)
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (filteredMessages.isEmpty()) {
                                EmptyChatState(selectedChatId)
                            } else {
                                ChatView(
                                    messages = filteredMessages,
                                    playingAudioId = playingAudioId,
                                    onPlayAudio = onPlayAudio,
                                    onDeleteMessage = onDeleteMessage,
                                    onReply = onReply,
                                    onOpenFile = onOpenFile
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    selectedChatId: String,
    selectedChatName: String,
    networkStatus: String,
    isMeshActive: Boolean,
    onMenuClick: () -> Unit,
    onMapToggle: () -> Unit,
    onRadarClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showNetworkMap: Boolean
) {
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.White.copy(alpha = 0.95f),
            titleContentColor = Color(0xFF202124),
            navigationIconContentColor = Color(0xFF1A73E8),
            actionIconContentColor = Color(0xFF1A73E8)
        ),
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, null)
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    selectedChatName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF202124)
                )
                Text(
                    networkStatus, fontSize = 10.sp,
                    color = when {
                        isMeshActive -> Color(0xFF0D904F)
                        networkStatus.contains("❌") -> Color(0xFFD32F2F)
                        else -> Color(0xFFE65100)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Rounded.Search, null)
            }
            IconButton(onClick = onRadarClick) {
                Icon(Icons.Default.Radar, null)
            }
            IconButton(onClick = onMapToggle) {
                Icon(if (showNetworkMap) Icons.Rounded.Chat else Icons.Rounded.Map, null)
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Rounded.Settings, null)
            }
        }
    )
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Surface(
        color = Color(0xFFF0F2F5).copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = Color(0xFF5F6368), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            TextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Rechercher...", color = Color(0xFF5F6368), fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color(0xFF202124)
                ),
                singleLine = true
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, null, tint = Color(0xFF5F6368))
            }
        }
    }
}

@Composable
fun ChatView(
    messages: List<ChatMessage>,
    playingAudioId: String?,
    onPlayAudio: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onOpenFile: (ChatMessage) -> Unit
) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.ChatBubbleOutline, null,
                    modifier = Modifier.size(56.dp),
                    tint = Color(0xFF5F6368).copy(0.4f)
                )
                Spacer(Modifier.height(12.dp))
                Text("Aucun message", color = Color(0xFF5F6368), fontSize = 16.sp)
                Text(
                    "Envoyez un message ou attendez vos contacts",
                    color = Color(0xFF5F6368).copy(0.6f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items = messages.reversed(), key = { it.id }) { msg ->
                MessageBubble(
                    msg = msg,
                    isPlaying = playingAudioId == msg.id,
                    onPlayAudio = onPlayAudio,
                    onDelete = { onDeleteMessage(msg) },
                    onReply = { onReply(msg) },
                    onOpenFile = { onOpenFile(msg) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: ChatMessage,
    isPlaying: Boolean,
    onPlayAudio: (ChatMessage) -> Unit,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onOpenFile: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
    ) {
        if (!msg.isMine) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8E24AA).copy(0.2f))
                    .align(Alignment.Bottom),
                contentAlignment = Alignment.Center
            ) {
                Text(msg.sender.take(1).uppercase(), color = Color(0xFF8E24AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(modifier = Modifier.widthIn(max = 280.dp)) {
            if (!msg.isMine) {
                Text(msg.sender, color = Color(0xFF1A73E8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }

            Surface(
                color = if (msg.isMine) Color(0xFF1A73E8).copy(0.1f) else Color.White.copy(alpha = 0.95f),
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (msg.isMine) 16.dp else 4.dp,
                    bottomEnd = if (msg.isMine) 4.dp else 16.dp
                ),
                border = BorderStroke(0.5.dp, if (msg.isMine) Color(0xFF1A73E8).copy(0.3f) else Color.LightGray),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    // Affichage du message auquel on répond (réponse)
                    if (msg.replyToContent != null) {
                        Surface(
                            color = Color(0xFFF1F3F4),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Reply,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF5F6368)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "↩️ Réponse à",
                                        fontSize = 10.sp,
                                        color = Color(0xFF5F6368)
                                    )
                                    Text(
                                        msg.replyToContent,
                                        fontSize = 12.sp,
                                        color = Color(0xFF1A73E8),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Contenu du message
                    when (msg.type) {
                        PacketType.AUDIO -> AudioBubble(msg, isPlaying, onPlayAudio)
                        PacketType.FILE  -> FileBubble(msg, onOpenFile)
                        else -> Text(msg.content, color = Color(0xFF202124), fontSize = 15.sp)
                    }

                    // Heure et statut
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg.time, color = Color(0xFF5F6368), fontSize = 9.sp)
                        if (msg.isMine) {
                            Spacer(Modifier.width(4.dp))
                            StatusIcon(msg.status)
                        }
                    }
                }
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("🗑️ Supprimer") },
                onClick = { showMenu = false; onDelete() }
            )
            DropdownMenuItem(
                text = { Text("↩️ Répondre") },
                onClick = { showMenu = false; onReply() }
            )
            if (msg.filePath != null) {
                DropdownMenuItem(
                    text = { Text("💾 Ouvrir le fichier") },
                    onClick = {
                        showMenu = false
                        onOpenFile()
                    }
                )
            }
        }
    }
}

@Composable
fun AudioBubble(msg: ChatMessage, isPlaying: Boolean, onPlayAudio: (ChatMessage) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onPlayAudio(msg) },
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF7C4DFF).copy(0.15f), CircleShape)
        ) {
            Icon(
                if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("🎤 Message vocal", color = Color(0xFF202124), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(msg.content, color = Color(0xFF5F6368), fontSize = 11.sp)
        }
        if (isPlaying) {
            Spacer(Modifier.width(8.dp))
            val anim by rememberInfiniteTransition(label = "wave").animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label = "wave_alpha"
            )
            Text("▌▌▌", color = Color(0xFF7C4DFF).copy(alpha = anim), fontSize = 14.sp)
        }
    }
}

@Composable
fun FileBubble(msg: ChatMessage, onOpenFile: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF00897B).copy(0.1f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .clickable { onOpenFile() }
    ) {
        Icon(Icons.Rounded.AttachFile, null, tint = Color(0xFF00897B), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                msg.fileName ?: msg.content,
                color = Color(0xFF202124), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (msg.fileSize > 0) {
                Text(formatFileSize(msg.fileSize), color = Color(0xFF5F6368), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun StatusIcon(status: MessageStatus) {
    val (icon, color) = when (status) {
        MessageStatus.SENDING  -> Icons.Rounded.Schedule to Color(0xFFE65100)
        MessageStatus.SENT     -> Icons.Rounded.Check to Color(0xFF5F6368)
        MessageStatus.RECEIVED -> Icons.Rounded.DoneAll to Color(0xFF0D904F)
        MessageStatus.FAILED   -> Icons.Rounded.Error to Color(0xFFD32F2F)
        MessageStatus.RELAYED  -> Icons.Rounded.Sync to Color(0xFF8E24AA)
    }
    Icon(icon, null, modifier = Modifier.size(12.dp), tint = color)
}

@Composable
fun ChatInputBar(
    selectedId: String,
    onSend: (String) -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    isRecording: Boolean,
    replyingTo: ChatMessage?,
    onCancelReply: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showExtra by remember { mutableStateOf(false) }

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            if (replyingTo != null) {
                Surface(
                    color = Color(0xFFF0F2F5).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Reply,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF1A73E8)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Réponse à", fontSize = 10.sp, color = Color(0xFF5F6368))
                                Text(
                                    replyingTo.content.take(40),
                                    fontSize = 12.sp,
                                    color = Color(0xFF1A73E8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onCancelReply) {
                            Icon(Icons.Rounded.Close, null, tint = Color(0xFF5F6368), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(showExtra) {
                Surface(
                    color = Color.White.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    shadowElevation = 2.dp
                ) {
                    Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { if (isRecording) onStopRecord() else onStartRecord() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (isRecording) Color(0xFFD32F2F).copy(0.15f) else Color(
                                            0xFF7C4DFF
                                        ).copy(0.15f), CircleShape
                                    )
                            ) {
                                Icon(
                                    if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    null,
                                    tint = if (isRecording) Color(0xFFD32F2F) else Color(0xFF7C4DFF),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Text(if (isRecording) "Arrêter" else "Audio", fontSize = 10.sp, color = Color(0xFF5F6368))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = onPickFile,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0xFF00897B).copy(0.15f), CircleShape)
                            ) {
                                Icon(Icons.Rounded.AttachFile, null, tint = Color(0xFF00897B), modifier = Modifier.size(24.dp))
                            }
                            Text("Fichier", fontSize = 10.sp, color = Color(0xFF5F6368))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = onPickImage,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0xFF0D904F).copy(0.15f), CircleShape)
                            ) {
                                Icon(Icons.Rounded.Image, null, tint = Color(0xFF0D904F), modifier = Modifier.size(24.dp))
                            }
                            Text("Image", fontSize = 10.sp, color = Color(0xFF5F6368))
                        }
                    }
                }
            }

            if (isRecording) {
                RecordingIndicator(onStopRecord)
            } else {
                Surface(
                    color = Color.White.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showExtra = !showExtra }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (showExtra) Icons.Rounded.Close else Icons.Rounded.Add,
                                null, tint = if (showExtra) Color(0xFF1A73E8) else Color(0xFF5F6368)
                            )
                        }
                        TextField(
                            value = text, onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (selectedId == "TOUS") "Message à tous..." else "Message privé...",
                                    color = Color(0xFF5F6368), fontSize = 14.sp
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color(0xFF202124), cursorColor = Color(0xFF1A73E8)
                            ),
                            maxLines = 4
                        )
                        AnimatedVisibility(text.isNotBlank()) {
                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        onSend(text.trim()); text = ""
                                    },
                                color = Color(0xFF1A73E8)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingIndicator(onStop: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p"
    )
    Surface(
        color = Color(0xFFD32F2F).copy(0.1f), shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFD32F2F).copy(pulse))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier
                    .size(10.dp)
                    .background(Color(0xFFD32F2F).copy(alpha = pulse), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("🎤 Enregistrement en cours...", color = Color(0xFFD32F2F), fontSize = 14.sp)
            }
            IconButton(onClick = onStop, modifier = Modifier
                .size(36.dp)
                .background(Color(0xFFD32F2F).copy(0.15f), CircleShape)) {
                Icon(Icons.Rounded.Stop, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun EmptyChatState(chatId: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val icon = if (chatId == "TOUS") Icons.Rounded.Groups else Icons.Rounded.Forum
        val text = if (chatId == "TOUS") {
            "Canal Public Mesh\nTous les appareils à proximité recevront vos messages."
        } else {
            "Conversation Privée P2P\nLes messages sont routés directement vers ce nœud."
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.White.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun NetworkMapBar(nodes: Map<String, MeshNode>, myPseudo: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .height(110.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = Color(0xFF1A73E8).copy(0.2f),
                border = BorderStroke(2.dp, Color(0xFF1A73E8)), modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(myPseudo.take(1).uppercase(), color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)
                }
            }
            nodes.values.take(4).forEach { node ->
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFF5F6368), modifier = Modifier.size(14.dp))
                val isWifi = node.connectionType == ConnectionType.WIFI_DIRECT
                Surface(shape = CircleShape,
                    color = (if (isWifi) Color(0xFF1A73E8) else Color(0xFF8E24AA)).copy(0.15f),
                    border = BorderStroke(1.5.dp, if (isWifi) Color(0xFF1A73E8) else Color(0xFF8E24AA)),
                    modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(node.pseudo.take(1).uppercase(), color = if (isWifi) Color(0xFF1A73E8) else Color(0xFF8E24AA), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            if (nodes.size > 4) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFF5F6368), modifier = Modifier.size(14.dp))
                Surface(shape = CircleShape, color = Color(0xFFF0F2F5), modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+${nodes.size - 4}", color = Color(0xFF5F6368), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
    else -> "${bytes / (1024 * 1024)} Mo"
}