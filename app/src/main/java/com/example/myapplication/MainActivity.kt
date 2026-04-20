package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.ui.screens.ErrorSnackbar
import com.example.myapplication.ui.screens.LoadingOverlay
import com.example.myapplication.ui.screens.MainMeshScreen
import com.example.myapplication.ui.screens.PseudoEntryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    // ==================== COULEURS PAR DÉFAUT ====================
    private val LightBg         = Color(0xFFF5F7FA)
    private val PrimaryBlue     = Color(0xFF1A73E8)
    private val PrimaryPurple   = Color(0xFF8E24AA)
    private val PrimaryTeal     = Color(0xFF00897B)
    private val SurfaceWhite    = Color(0xFFFFFFFF)
    private val SurfaceGray     = Color(0xFFF0F2F5)
    private val TextPrimary     = Color(0xFF202124)
    private val TextSecondary   = Color(0xFF5F6368)
    private val SuccessGreen    = Color(0xFF0D904F)
    private val WarningOrange   = Color(0xFFE65100)
    private val ErrorRed        = Color(0xFFD32F2F)
    private val AudioPurple     = Color(0xFF7C4DFF)

    // ==================== STATE ====================
    private var messages by mutableStateOf<List<ChatMessage>>(emptyList())
    private val nodes    = mutableStateMapOf<String, MeshNode>()
    private var myId       by mutableStateOf("")
    private var myPseudo   by mutableStateOf("")
    private var isMeshActive    by mutableStateOf(false)
    private var selectedNodeId  by mutableStateOf<String?>(null)
    private var networkStatus   by mutableStateOf("🔄 Initialisation...")
    private var showNetworkMap  by mutableStateOf(false)
    private var selectedChatId  by mutableStateOf<String?>(null)
    private var errorMessage    by mutableStateOf<String?>(null)
    private var isRecording     by mutableStateOf(false)
    private var playingAudioId  by mutableStateOf<String?>(null)
    private var pendingPermissionRequest by mutableStateOf(false)
    private var isInitializing  by mutableStateOf(false)
    private var initTimeout     by mutableStateOf(false)
    private var initProgressMessage by mutableStateOf("")
    private var showRadarDialog by mutableStateOf(false)
    private var showSearchBar   by mutableStateOf(false)
    private var searchQuery     by mutableStateOf("")
    private var showSettings    by mutableStateOf(false)

    private var replyingToMessage by mutableStateOf<ChatMessage?>(null)

    // Paramètres de fond
    private var backgroundType by mutableStateOf("color")
    private var backgroundColor by mutableStateOf(LightBg)
    private var backgroundImageUri by mutableStateOf<Uri?>(null)

    // ==================== OUTILS ====================
    private var audioFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var messageStore: MessageStore
    private lateinit var prefs: SharedPreferences

    private val isServiceStarting = AtomicBoolean(false)
    private val isDestroyed       = AtomicBoolean(false)
    private var initializationJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val CHANNEL_ID = "mesh_messages"
    private val TAG = "MainActivity"

    // ==================== LAUNCHERS ====================
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        pendingPermissionRequest = false
        if (perms.values.all { it }) {
            if (myPseudo.isNotEmpty() && !isDestroyed.get()) startMeshServiceWithDelay()
        } else {
            errorMessage = "❌ Permissions refusées: ${perms.filter { !it.value }.keys.joinToString()}"
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { sendFileMessage(it) }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            backgroundImageUri = it
            backgroundType = "image"
            prefs.edit().putString("background_type", "image").apply()
            prefs.edit().putString("background_image_uri", it.toString()).apply()
        }
    }

    // ==================== LIFECYCLE ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "🟢 onCreate SDK=${Build.VERSION.SDK_INT}")

        try {
            createNotificationChannel()
            messageStore = MessageStore(this)
            prefs = getSharedPreferences("MIT_MESH_SETTINGS", MODE_PRIVATE)

            myId     = prefs.getString("user_id", "MIT-" + UUID.randomUUID().toString().take(6).uppercase())!!
            myPseudo = prefs.getString("user_pseudo", "") ?: ""

            backgroundType = prefs.getString("background_type", "color") ?: "color"
            val colorStr = prefs.getString("background_color", "#F5F7FA") ?: "#F5F7FA"
            backgroundColor = try {
                Color(android.graphics.Color.parseColor(colorStr))
            } catch (e: Exception) {
                LightBg
            }
            val uriStr = prefs.getString("background_image_uri", null)
            if (uriStr != null) backgroundImageUri = Uri.parse(uriStr)

            registerReceivers()
            loadPersistedMessages()

            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = PrimaryBlue,
                        secondary = PrimaryPurple,
                        tertiary = PrimaryTeal,
                        background = LightBg,
                        surface = SurfaceWhite,
                        onPrimary = Color.White,
                        onSecondary = Color.White,
                        onTertiary = Color.White,
                        onBackground = TextPrimary,
                        onSurface = TextPrimary
                    )
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (backgroundType) {
                                "image" -> {
                                    backgroundImageUri?.let { uri ->
                                        val bitmap = runCatching {
                                            contentResolver.openInputStream(uri)?.use {
                                                BitmapFactory.decodeStream(it)
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

                            when {
                                myPseudo.isEmpty() -> PseudoEntryScreen(
                                    myId = myId,
                                    onJoin = { pseudo ->
                                        myPseudo = pseudo
                                        prefs.edit().putString("user_pseudo", pseudo).apply()
                                        checkAndRequestPermissions()
                                    }
                                )
                                showSettings -> SettingsScreen(
                                    onBack = { showSettings = false },
                                    backgroundColor = backgroundColor,
                                    onColorChange = { color ->
                                        backgroundColor = color
                                        val hexColor = String.format("#%06X", (0xFFFFFF and color.toArgb()))
                                        prefs.edit().putString("background_color", hexColor).apply()
                                        prefs.edit().putString("background_type", "color").apply()
                                        backgroundType = "color"
                                    },
                                    onSelectImage = { imagePickerLauncher.launch("image/*") },
                                    onClearImage = {
                                        backgroundImageUri = null
                                        backgroundType = "color"
                                        prefs.edit().putString("background_type", "color").apply()
                                        prefs.edit().remove("background_image_uri").apply()
                                    }
                                )
                                else -> MainMeshScreen(
                                    myId = myId,
                                    myPseudo = myPseudo,
                                    messages = messages,
                                    nodes = nodes,
                                    isMeshActive = isMeshActive,
                                    networkStatus = networkStatus,
                                    showNetworkMap = showNetworkMap,
                                    showSearchBar = showSearchBar,
                                    searchQuery = searchQuery,
                                    selectedChatId = selectedChatId ?: "TOUS",
                                    playingAudioId = playingAudioId,
                                    isRecording = isRecording,
                                    replyingToMessage = replyingToMessage,
                                    onChatSelected = { selectedChatId = it },
                                    onMenuClick = { /* handled by scaffold */ },
                                    onMapToggle = { showNetworkMap = !showNetworkMap },
                                    onRadarClick = { showRadarDialog = true },
                                    onSearchClick = { showSearchBar = !showSearchBar },
                                    onSettingsClick = { showSettings = true },
                                    onSendMessage = { content, receiver, replyTo -> sendMessage(content, receiver, replyTo) },
                                    onStartRecording = { startRecording() },
                                    onStopRecording = { stopRecording() },
                                    onPickFile = { filePickerLauncher.launch("*/*") },
                                    onPickImage = { imagePickerLauncher.launch("image/*") },
                                    onPlayAudio = { playAudio(it) },
                                    onDeleteMessage = { msg ->
                                        deleteMessage(msg.id)
                                    },
                                    onReply = { replyingToMessage = it },
                                    onCancelReply = { replyingToMessage = null },
                                    onSearchQueryChange = { searchQuery = it },
                                    onSearchClose = { showSearchBar = false; searchQuery = "" },
                                    onRefresh = { startMeshService() },
                                    onClearMessages = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            messageStore.clearAll()
                                            withContext(Dispatchers.Main) {
                                                messages = emptyList()
                                            }
                                        }
                                    },
                                    onOpenSettings = { showSettings = true },
                                    onOpenFile = { openFile(it) }
                                )
                            }

                            errorMessage?.let { msg ->
                                ErrorSnackbar(msg) { errorMessage = null }
                            }

                            if (pendingPermissionRequest || isInitializing) {
                                LoadingOverlay(
                                    if (pendingPermissionRequest) "📱 Demande de permissions..."
                                    else if (initTimeout) "⏰ Délai dépassé — vérifiez WiFi/BT"
                                    else initProgressMessage.ifEmpty { "🔄 Initialisation..." }
                                )
                            }

                            if (showRadarDialog) {
                                Dialog(
                                    onDismissRequest = { showRadarDialog = false }
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = SurfaceWhite,
                                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                                    ) {
                                        RadarScreen(
                                            knownNodes = nodes, myPseudo = myPseudo,
                                            onClose = { showRadarDialog = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (myPseudo.isNotEmpty()) checkAndRequestPermissions()

        } catch (e: Exception) {
            Log.e(TAG, "💥 onCreate fatal", e)
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (myPseudo.isNotEmpty() && !isMeshActive && !isDestroyed.get() && !isInitializing)
            startMeshServiceWithDelay()
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed.set(true)
        try { unregisterReceiver(meshReceiver) } catch (e: Exception) { }
        try { mediaPlayer?.release(); mediaRecorder?.release() } catch (e: Exception) { }
        try { messageStore.close() } catch (e: Exception) { }
        mainHandler.removeCallbacksAndMessages(null)
        initializationJob?.cancel()
        isServiceStarting.set(false)
    }

    // ==================== PERSISTANCE ====================
    private fun loadPersistedMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stored = messageStore.loadRecent(200)
            withContext(Dispatchers.Main) {
                messages = stored
                Log.i(TAG, "📂 ${stored.size} messages chargés depuis le stockage")
            }
        }
    }

    private fun persistMessage(msg: ChatMessage) {
        lifecycleScope.launch(Dispatchers.IO) { messageStore.save(msg) }
    }

    private fun deleteMessage(id: String) {
        lifecycleScope.launch(Dispatchers.IO) { messageStore.delete(id) }
        messages = messages.filter { it.id != id }
    }

    // ==================== RECEIVERS ====================
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction("MESH_MESSAGE_RECEIVED")
            addAction("MESH_STATUS_CHANGED")
            addAction("MESH_NODE_DISCOVERED")
            addAction("MESH_NODE_LOST")
            addAction("MESH_TOPOLOGY_UPDATE")
            addAction("MESH_ERROR")
            addAction("MESH_NODE_RELIABILITY_UPDATE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(meshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(meshReceiver, filter, Context.RECEIVER_EXPORTED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceivers: ${e.message}")
        }
    }

    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isDestroyed.get()) return
            when (intent?.action) {
                "MESH_MESSAGE_RECEIVED" -> {
                    try {
                        val id = intent.getStringExtra("id") ?: return
                        val sender = intent.getStringExtra("sender") ?: "Inconnu"
                        val content = intent.getStringExtra("content") ?: ""
                        val receiver = intent.getStringExtra("receiver") ?: "TOUS"
                        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                        val typeStr = intent.getStringExtra("type") ?: "MESSAGE"
                        val type = try { PacketType.valueOf(typeStr) } catch (e: Exception) { PacketType.MESSAGE }
                        val audioPath = intent.getStringExtra("audio_path")
                        val filePath = intent.getStringExtra("file_path")
                        val fileName = intent.getStringExtra("file_name")
                        val fileSize = intent.getLongExtra("file_size", 0)
                        val replyToId = intent.getStringExtra("reply_to_id")
                        val replyToContent = intent.getStringExtra("reply_to_content")

                        if (messages.any { it.id == id }) return

                        val msg = ChatMessage(
                            id = id, sender = sender, content = content, isMine = false,
                            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
                            status = MessageStatus.RECEIVED, type = type,
                            receiverId = receiver, senderIdRaw = sender,
                            timestamp = timestamp,
                            audioPath = audioPath, filePath = filePath,
                            fileName = fileName, fileSize = fileSize,
                            replyToId = replyToId, replyToContent = replyToContent
                        )
                        messages = messages + msg
                        persistMessage(msg)
                        if (messages.size > 500) {
                            val oldest = messages.firstOrNull()?.id
                            oldest?.let { messageStore.delete(it) }
                            messages = messages.drop(1)
                        }
                        showMessageNotification(msg)

                    } catch (e: Exception) { Log.e(TAG, "MESH_MESSAGE_RECEIVED: ${e.message}", e) }
                }

                "MESH_STATUS_CHANGED" -> {
                    networkStatus = intent.getStringExtra("status") ?: ""
                    isMeshActive = networkStatus.contains("✅") || networkStatus.contains("actif") ||
                            networkStatus.contains("Relais") || networkStatus.contains("voisin")
                }

                "MESH_NODE_DISCOVERED" -> {
                    val nodeId = intent.getStringExtra("node_id") ?: return
                    val nodeName = intent.getStringExtra("node_name") ?: "Inconnu"
                    val nodePseudo = intent.getStringExtra("node_pseudo") ?: "Anonyme"
                    val ctStr = intent.getStringExtra("connection_type") ?: "WIFI_DIRECT"
                    val ct = try { ConnectionType.valueOf(ctStr) } catch (e: Exception) { ConnectionType.WIFI_DIRECT }
                    val isRelay = intent.getBooleanExtra("is_relay", false)
                    val isSubGo = intent.getBooleanExtra("is_sub_go", false)
                    val rssi = intent.getIntExtra("rssi", 0)

                    val distance = if (ct == ConnectionType.BLUETOOTH && rssi != 0) {
                        rssiToDistance(rssi)
                    } else {
                        10f
                    }

                    val existing = nodes[nodeId]
                    if (existing != null) {
                        nodes[nodeId] = existing.copy(
                            lastSeen = System.currentTimeMillis(),
                            estimatedDistance = if (distance > 0) distance else existing.estimatedDistance,
                            connectionType = ct,
                            isRelay = isRelay,
                            isSubGo = isSubGo,
                            isConnected = true
                        )
                    } else {
                        nodes[nodeId] = MeshNode(
                            deviceId = nodeId, deviceName = nodeName, pseudo = nodePseudo,
                            connectionType = ct, lastSeen = System.currentTimeMillis(),
                            estimatedDistance = distance, isRelay = isRelay, isSubGo = isSubGo,
                            isConnected = true
                        )
                    }
                    Log.d(TAG, "✅ Nœud ajouté: $nodePseudo ($nodeId) type=$ct distance=$distance m")
                }

                "MESH_NODE_LOST" -> { nodes.remove(intent.getStringExtra("node_id") ?: return) }

                "MESH_ERROR" -> { errorMessage = "⚠️ ${intent.getStringExtra("error")}" }

                "MESH_NODE_RELIABILITY_UPDATE" -> {
                    val nodeId = intent.getStringExtra("node_id") ?: return
                    val reliability = intent.getDoubleExtra("reliability", 0.5)
                    nodes[nodeId]?.let { existing ->
                        nodes[nodeId] = existing.copy(bayesianReliability = reliability)
                        Log.d(TAG, "📊 Fiabilité bayésienne mise à jour pour $nodeId : $reliability")
                    }
                }
            }
        }
    }

    // ==================== PERMISSIONS ====================
    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms += listOf(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms += listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        perms += Manifest.permission.INTERNET

        val toRequest = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            pendingPermissionRequest = true
            permissionLauncher.launch(toRequest.toTypedArray())
        } else {
            startMeshServiceWithDelay()
        }
    }

    // ==================== SERVICE ====================
    private fun startMeshServiceWithDelay() {
        if (isServiceStarting.getAndSet(true)) return
        isInitializing = true; initTimeout = false; initProgressMessage = "🔄 Démarrage..."
        initializationJob?.cancel()
        mainHandler.postDelayed({
            if (isInitializing && !isDestroyed.get()) {
                isInitializing = false; initTimeout = true; isServiceStarting.set(false)
            }
        }, 15000)
        initializationJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            withContext(Dispatchers.Main) {
                if (!isDestroyed.get()) {
                    startMeshService()
                    isInitializing = false; isServiceStarting.set(false)
                    mainHandler.removeCallbacksAndMessages(null)
                }
            }
        }
    }

    private fun startMeshService() {
        if (isDestroyed.get()) return
        val intent = Intent(this, MeshService::class.java).apply {
            putExtra("user_id", myId); putExtra("user_pseudo", myPseudo)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            networkStatus = "🔄 Connexion..."
        } catch (e: Exception) { errorMessage = "❌ ${e.message}"; Log.e(TAG, "startMeshService", e) }
    }

    // ==================== ENVOI ====================
    private fun sendMessage(content: String, receiver: String, replyTo: ChatMessage? = null) {
        if (content.isBlank()) return
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

        val actualReceiver = if (receiver == "PUBLIC") "TOUS" else receiver

        val msg = ChatMessage(
            id = id, sender = "Moi", content = content, isMine = true,
            time = time, status = MessageStatus.SENDING,
            receiverId = actualReceiver, senderIdRaw = myId, type = PacketType.MESSAGE,
            timestamp = now,
            replyToId = replyTo?.id,
            replyToContent = replyTo?.content?.take(50)
        )
        messages = messages + msg
        persistMessage(msg)

        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id); putExtra("content", content)
            putExtra("receiver", actualReceiver); putExtra("type", PacketType.MESSAGE.name)
            putExtra("reply_to_id", replyTo?.id)
            putExtra("reply_to_content", replyTo?.content?.take(50))
        }
        startServiceCompat(intent)

        lifecycleScope.launch {
            delay(1500)
            updateMessageStatus(id, MessageStatus.SENT)
            delay(1500)
            updateMessageStatus(id, MessageStatus.RECEIVED)
        }
    }

    private fun sendAudioMessage(file: File, receiver: String) {
        if (!file.exists()) {
            errorMessage = "Fichier audio introuvable"
            return
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

        val actualReceiver = if (receiver == "PUBLIC") "TOUS" else receiver

        val durationSec = try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val durationMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            mmr.release()
            (durationMs / 1000).coerceAtLeast(1)
        } catch (e: Exception) {
            (file.length() / 1525).toInt().coerceAtLeast(1)
        }

        val msg = ChatMessage(
            id = id, sender = "Moi", content = "${durationSec}s", isMine = true,
            time = time, status = MessageStatus.SENDING,
            receiverId = actualReceiver, senderIdRaw = myId, type = PacketType.AUDIO,
            audioPath = file.absolutePath, timestamp = now
        )
        messages = messages + msg
        persistMessage(msg)

        val audioData = file.readBytes()
        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id); putExtra("content", "Audio ${durationSec}s")
            putExtra("receiver", actualReceiver); putExtra("type", PacketType.AUDIO.name)
            putExtra("audio_data", audioData)
        }
        startServiceCompat(intent)
        lifecycleScope.launch { delay(1500); updateMessageStatus(id, MessageStatus.SENT) }
    }

    private fun sendFileMessage(uri: Uri) {
        val id = UUID.randomUUID().toString()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val receiver = selectedNodeId ?: "TOUS"
        val actualReceiver = if (receiver == "PUBLIC") "TOUS" else receiver

        val cursor = contentResolver.query(uri, null, null, null, null)
        val fileName = cursor?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("_display_name")) else "fichier"
        } ?: "fichier_${id.take(6)}"
        cursor?.close()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileData = contentResolver.openInputStream(uri)?.readBytes()
                if (fileData == null) { withContext(Dispatchers.Main) { errorMessage = "❌ Impossible de lire le fichier" }; return@launch }
                if (fileData.size > 15 * 1024 * 1024) { withContext(Dispatchers.Main) { errorMessage = "❌ Fichier trop grand (max 15 Mo)" }; return@launch }

                val localFile = File(filesDir, "sent_$fileName")
                localFile.writeBytes(fileData)

                withContext(Dispatchers.Main) {
                    val msg = ChatMessage(
                        id = id, sender = "Moi", content = fileName, isMine = true,
                        time = time, status = MessageStatus.SENDING,
                        receiverId = actualReceiver, senderIdRaw = myId, type = PacketType.FILE,
                        filePath = localFile.absolutePath, fileName = fileName, fileSize = fileData.size.toLong()
                    )
                    messages = messages + msg
                    persistMessage(msg)

                    val intent = Intent(this@MainActivity, MeshService::class.java).apply {
                        action = "ACTION_SEND_MESSAGE"
                        putExtra("id", id); putExtra("content", fileName)
                        putExtra("receiver", actualReceiver); putExtra("type", PacketType.FILE.name)
                        putExtra("file_name", fileName); putExtra("file_size", fileData.size.toLong())
                        putExtra("file_data", fileData)
                    }
                    startServiceCompat(intent)
                    delay(1500); updateMessageStatus(id, MessageStatus.SENT)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "❌ Erreur fichier: ${e.message}" }
            }
        }
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { errorMessage = "❌ ${e.message}" }
    }

    private fun updateMessageStatus(id: String, status: MessageStatus) {
        messages = messages.map {
            if (it.id == id) it.copy(status = status) else it
        }
        lifecycleScope.launch(Dispatchers.IO) { messageStore.updateStatus(id, status) }
    }

    // ==================== AUDIO ====================
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            errorMessage = "🎤 Permission microphone requise"; return
        }
        try {
            audioFile = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
            mediaRecorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
        } catch (e: IOException) { errorMessage = "🎤 Erreur enregistrement: ${e.message}" }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null; isRecording = false
            audioFile?.let { if (it.exists() && it.length() > 0) sendAudioMessage(it, selectedNodeId ?: "TOUS") }
        } catch (e: Exception) { errorMessage = "Erreur arrêt: ${e.message}"; isRecording = false }
    }

    private fun playAudio(msg: ChatMessage) {
        try {
            if (playingAudioId == msg.id) {
                mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null; playingAudioId = null
                return
            }
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null

            val path = msg.audioPath
            if (path.isNullOrEmpty()) {
                errorMessage = "Chemin audio manquant"
                return
            }
            val file = File(path)
            if (!file.exists()) {
                errorMessage = "Fichier audio introuvable : $path"
                return
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener {
                    playingAudioId = null
                    release()
                    mediaPlayer = null
                }
            }
            playingAudioId = msg.id
        } catch (e: Exception) {
            errorMessage = "Erreur lecture: ${e.message}"
            playingAudioId = null
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun openFile(msg: ChatMessage) {
        val path = msg.filePath
        if (path.isNullOrEmpty()) {
            errorMessage = "Chemin fichier manquant"
            return
        }
        val file = File(path)
        if (!file.exists()) {
            errorMessage = "Fichier introuvable : $path"
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            errorMessage = "Aucune application pour ouvrir ce fichier"
        }
    }

    // ==================== DISTANCE RSSI ====================
    private fun rssiToDistance(rssi: Int, txPower: Int = -55, n: Double = 2.5): Float {
        if (rssi == 0) return 100f
        val ratio = (txPower - rssi) / (10 * n)
        val distance = Math.pow(10.0, ratio).toFloat()
        return distance.coerceIn(1f, 100f)
    }

    // ==================== NOTIFICATIONS ====================
    private fun showMessageNotification(msg: ChatMessage) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("📨 ${msg.sender}")
                .setContentText(when (msg.type) {
                    PacketType.AUDIO -> "🎤 Message vocal"
                    PacketType.FILE  -> "📎 ${msg.fileName ?: "Fichier"}"
                    else -> msg.content
                })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            NotificationManagerCompat.from(this).notify(msg.id.hashCode(), builder.build())
        } catch (e: SecurityException) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Messages Mesh", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nouveaux messages"
                enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ==================== UTILITAIRES ====================
    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
        else -> "${bytes / (1024 * 1024)} Mo"
    }
}