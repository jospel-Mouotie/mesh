package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
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
    private val messages = mutableStateListOf<ChatMessage>()
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
    private var backgroundType by mutableStateOf("color") // "color" ou "image"
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

            // Charger les préférences de fond (avec gestion d'erreur)
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
                            // Fond personnalisé
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
                                myPseudo.isEmpty() -> PseudoEntryScreen { pseudo ->
                                    myPseudo = pseudo
                                    prefs.edit().putString("user_pseudo", pseudo).apply()
                                    checkAndRequestPermissions()
                                }
                                showSettings -> SettingsScreen(
                                    onBack = { showSettings = false },
                                    backgroundColor = backgroundColor,
                                    onColorChange = { color ->
                                        backgroundColor = color
                                        // Convertir la couleur en hexadécimal #RRGGBB pour le stockage
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
                                else -> MainMeshScreen()
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
                messages.clear()
                messages.addAll(stored)
                Log.i(TAG, "📂 ${stored.size} messages chargés depuis le stockage")
            }
        }
    }

    private fun persistMessage(msg: ChatMessage) {
        lifecycleScope.launch(Dispatchers.IO) { messageStore.save(msg) }
    }

    private fun deleteMessage(id: String) {
        lifecycleScope.launch(Dispatchers.IO) { messageStore.delete(id) }
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
                        messages.add(msg)
                        persistMessage(msg)
                        if (messages.size > 500) { messageStore.delete(messages[0].id); messages.removeAt(0) }
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

        val msg = ChatMessage(
            id = id, sender = "Moi", content = content, isMine = true,
            time = time, status = MessageStatus.SENDING,
            receiverId = receiver, senderIdRaw = myId, type = PacketType.MESSAGE,
            timestamp = now,
            replyToId = replyTo?.id,
            replyToContent = replyTo?.content?.take(50)
        )
        messages.add(msg); persistMessage(msg)

        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id); putExtra("content", content)
            putExtra("receiver", receiver); putExtra("type", PacketType.MESSAGE.name)
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

    private fun replyToMessage(msg: ChatMessage) {
        replyingToMessage = msg
    }

    private fun sendAudioMessage(file: File, receiver: String) {
        if (!file.exists()) {
            errorMessage = "Fichier audio introuvable"
            return
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

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
            receiverId = receiver, senderIdRaw = myId, type = PacketType.AUDIO,
            audioPath = file.absolutePath, timestamp = now
        )
        messages.add(msg); persistMessage(msg)

        val audioData = file.readBytes()
        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id); putExtra("content", "Audio ${durationSec}s")
            putExtra("receiver", receiver); putExtra("type", PacketType.AUDIO.name)
            putExtra("audio_data", audioData)
        }
        startServiceCompat(intent)
        lifecycleScope.launch { delay(1500); updateMessageStatus(id, MessageStatus.SENT) }
    }

    private fun sendFileMessage(uri: Uri) {
        val id = UUID.randomUUID().toString()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val receiver = selectedNodeId ?: "TOUS"

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
                        receiverId = receiver, senderIdRaw = myId, type = PacketType.FILE,
                        filePath = localFile.absolutePath, fileName = fileName, fileSize = fileData.size.toLong()
                    )
                    messages.add(msg); persistMessage(msg)

                    val intent = Intent(this@MainActivity, MeshService::class.java).apply {
                        action = "ACTION_SEND_MESSAGE"
                        putExtra("id", id); putExtra("content", fileName)
                        putExtra("receiver", receiver); putExtra("type", PacketType.FILE.name)
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
        val idx = messages.indexOfLast { it.id == id }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(status = status)
            lifecycleScope.launch(Dispatchers.IO) { messageStore.updateStatus(id, status) }
        }
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

    // ==================== COMPOSABLES ====================
    @Composable
    fun MainMeshScreen() {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var selectedChatId by remember { mutableStateOf("TOUS") }

        // Filtrer les messages selon le canal sélectionné
        val filteredMessages = remember(selectedChatId, messages) {
            if (selectedChatId == "TOUS") {
                messages.filter { it.receiverId == "TOUS" || it.receiverId == myId }
            } else {
                messages.filter { it.receiverId == selectedChatId || (it.senderIdRaw == selectedChatId && it.receiverId == myId) }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    selectedChatId = selectedChatId,
                    onChatSelected = { selectedChatId = it; scope.launch { drawerState.close() } },
                    onRefresh = { startMeshService() },
                    onShowRadar = { showRadarDialog = true },
                    onClearMessages = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            messageStore.clearAll()
                            withContext(Dispatchers.Main) { messages.clear() }
                        }
                    },
                    onOpenSettings = { showSettings = true }
                )
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    AppTopBar(
                        selectedChatId = selectedChatId,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onMapToggle = { showNetworkMap = !showNetworkMap },
                        onRadarClick = { showRadarDialog = true },
                        onSearchClick = { showSearchBar = !showSearchBar },
                        onSettingsClick = { showSettings = true }
                    )
                },
                bottomBar = {
                    if (!showNetworkMap) {
                        ChatInputBar(
                            selectedId = selectedChatId,
                            onSend = { content ->
                                sendMessage(content, selectedChatId, replyingToMessage)
                                replyingToMessage = null
                            },
                            onStartRecord = { startRecording() },
                            onStopRecord = { stopRecording() },
                            onPickFile = { filePickerLauncher.launch("*/*") },
                            isRecording = isRecording,
                            replyingTo = replyingToMessage,
                            onCancelReply = { replyingToMessage = null }
                        )
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    if (showSearchBar) {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClose = { showSearchBar = false; searchQuery = "" }
                        )
                    }
                    if (showNetworkMap) {
                        NetworkMapBar()
                    } else {
                        val displayed = if (searchQuery.isBlank()) filteredMessages
                        else filteredMessages.filter { it.content.contains(searchQuery, ignoreCase = true) || it.sender.contains(searchQuery, ignoreCase = true) }
                        ChatView(
                            messages = displayed,
                            playingAudioId = playingAudioId,
                            onPlayAudio = { playAudio(it) },
                            onDeleteMessage = { msg ->
                                messages.remove(msg)
                                deleteMessage(msg.id)
                            },
                            onReply = { replyToMessage(it) }
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppTopBar(
        selectedChatId: String,
        onMenuClick: () -> Unit,
        onMapToggle: () -> Unit,
        onRadarClick: () -> Unit,
        onSearchClick: () -> Unit,
        onSettingsClick: () -> Unit
    ) {
        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = SurfaceWhite,
                titleContentColor = TextPrimary,
                navigationIconContentColor = PrimaryBlue,
                actionIconContentColor = PrimaryBlue
            ),
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, null, tint = PrimaryBlue)
                }
            },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (selectedChatId == "TOUS") "MIT MESH"
                        else nodes[selectedChatId]?.pseudo ?: "Discussion",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary
                    )
                    Text(
                        networkStatus, fontSize = 10.sp,
                        color = when {
                            isMeshActive -> SuccessGreen
                            networkStatus.contains("❌") -> ErrorRed
                            else -> WarningOrange
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            },
            actions = {
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Rounded.Search, null, tint = PrimaryBlue)
                }
                IconButton(onClick = onRadarClick) {
                    Icon(Icons.Default.Radar, null, tint = PrimaryBlue)
                }
                IconButton(onClick = onMapToggle) {
                    Icon(
                        if (showNetworkMap) Icons.Rounded.Chat else Icons.Rounded.Map,
                        null, tint = PrimaryBlue
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Rounded.Settings, null, tint = PrimaryBlue)
                }
            }
        )
    }

    @Composable
    fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
        Surface(color = SurfaceGray, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = query, onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Rechercher...", color = TextSecondary, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, null, tint = TextSecondary)
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
        onReply: (ChatMessage) -> Unit
    ) {
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, modifier = Modifier.size(56.dp), tint = TextSecondary.copy(0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text("Aucun message", color = TextSecondary, fontSize = 16.sp)
                    Text("Envoyez un message ou attendez vos contacts", color = TextSecondary.copy(0.6f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items = messages.reversed(), key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        isPlaying = playingAudioId == msg.id,
                        onPlayAudio = onPlayAudio,
                        onDelete = { onDeleteMessage(msg) },
                        onReply = { onReply(msg) }
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
        onReply: () -> Unit
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
                        .background(PrimaryPurple.copy(0.2f))
                        .align(Alignment.Bottom),
                    contentAlignment = Alignment.Center
                ) {
                    Text(msg.sender.take(1).uppercase(), color = PrimaryPurple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
            }

            Column(modifier = Modifier.widthIn(max = 280.dp)) {
                if (!msg.isMine) {
                    Text(msg.sender, color = PrimaryBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                }

                Surface(
                    color = if (msg.isMine) PrimaryBlue.copy(0.1f) else SurfaceWhite,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (msg.isMine) 16.dp else 4.dp,
                        bottomEnd = if (msg.isMine) 4.dp else 16.dp
                    ),
                    border = BorderStroke(0.5.dp, if (msg.isMine) PrimaryBlue.copy(0.3f) else Color.LightGray),
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        if (msg.replyToContent != null) {
                            Surface(
                                color = Color(0xFFF1F3F4),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                Column(Modifier.padding(6.dp)) {
                                    Text("↩️ Réponse à", fontSize = 10.sp, color = TextSecondary)
                                    Text(msg.replyToContent, fontSize = 12.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        when (msg.type) {
                            PacketType.AUDIO -> AudioBubble(msg, isPlaying, onPlayAudio)
                            PacketType.FILE  -> FileBubble(msg)
                            else -> Text(msg.content, color = TextPrimary, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg.time, color = TextSecondary, fontSize = 9.sp)
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
                            openFile(msg)
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
                    .background(AudioPurple.copy(0.15f), CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    null, tint = AudioPurple, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("🎤 Message vocal", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(msg.content, color = TextSecondary, fontSize = 11.sp)
            }
            if (isPlaying) {
                Spacer(Modifier.width(8.dp))
                val anim by rememberInfiniteTransition(label = "wave").animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                    label = "wave_alpha"
                )
                Text("▌▌▌", color = AudioPurple.copy(alpha = anim), fontSize = 14.sp)
            }
        }
    }

    @Composable
    fun FileBubble(msg: ChatMessage) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(PrimaryTeal.copy(0.1f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Icon(Icons.Rounded.AttachFile, null, tint = PrimaryTeal, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    msg.fileName ?: msg.content,
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (msg.fileSize > 0) {
                    Text(formatFileSize(msg.fileSize), color = TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }

    @Composable
    fun StatusIcon(status: MessageStatus) {
        val (icon, color) = when (status) {
            MessageStatus.SENDING  -> Icons.Rounded.Schedule to WarningOrange
            MessageStatus.SENT     -> Icons.Rounded.Check to TextSecondary
            MessageStatus.RECEIVED -> Icons.Rounded.DoneAll to SuccessGreen
            MessageStatus.FAILED   -> Icons.Rounded.Error to ErrorRed
            MessageStatus.RELAYED  -> Icons.Rounded.Sync to PrimaryPurple
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
        isRecording: Boolean,
        replyingTo: ChatMessage?,
        onCancelReply: () -> Unit
    ) {
        var text by remember { mutableStateOf("") }
        var showExtra by remember { mutableStateOf(false) }

        Surface(
            color = Color.Transparent,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column {
                if (replyingTo != null) {
                    Surface(
                        color = SurfaceGray,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("↩️ Réponse à", fontSize = 10.sp, color = TextSecondary)
                                Text(replyingTo.content.take(40), fontSize = 12.sp, color = TextPrimary)
                            }
                            IconButton(onClick = onCancelReply) {
                                Icon(Icons.Rounded.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                AnimatedVisibility(showExtra) {
                    Surface(color = SurfaceWhite, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), shadowElevation = 2.dp) {
                        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { if (isRecording) onStopRecord() else onStartRecord() },
                                    modifier = Modifier.size(48.dp).background(
                                        if (isRecording) ErrorRed.copy(0.15f) else AudioPurple.copy(0.15f), CircleShape
                                    )
                                ) {
                                    Icon(
                                        if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                        null,
                                        tint = if (isRecording) ErrorRed else AudioPurple,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Text(if (isRecording) "Arrêter" else "Audio", fontSize = 10.sp, color = TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = onPickFile,
                                    modifier = Modifier.size(48.dp).background(PrimaryTeal.copy(0.15f), CircleShape)
                                ) {
                                    Icon(Icons.Rounded.AttachFile, null, tint = PrimaryTeal, modifier = Modifier.size(24.dp))
                                }
                                Text("Fichier", fontSize = 10.sp, color = TextSecondary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { filePickerLauncher.launch("image/*") },
                                    modifier = Modifier.size(48.dp).background(SuccessGreen.copy(0.15f), CircleShape)
                                ) {
                                    Icon(Icons.Rounded.Image, null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
                                }
                                Text("Image", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                if (isRecording) {
                    RecordingIndicator(onStopRecord)
                } else {
                    Surface(color = SurfaceWhite, shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showExtra = !showExtra }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    if (showExtra) Icons.Rounded.Close else Icons.Rounded.Add,
                                    null, tint = if (showExtra) PrimaryBlue else TextSecondary
                                )
                            }
                            TextField(
                                value = text, onValueChange = { text = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        if (selectedId == "TOUS") "Message à tous..." else "Message privé...",
                                        color = TextSecondary, fontSize = 14.sp
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = TextPrimary, cursorColor = PrimaryBlue
                                ),
                                maxLines = 4
                            )
                            AnimatedVisibility(text.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable {
                                        onSend(text.trim()); text = ""
                                    },
                                    color = PrimaryBlue
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
            color = ErrorRed.copy(0.1f), shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, ErrorRed.copy(pulse))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(ErrorRed.copy(alpha = pulse), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("🎤 Enregistrement en cours...", color = ErrorRed, fontSize = 14.sp)
                }
                IconButton(onClick = onStop, modifier = Modifier.size(36.dp).background(ErrorRed.copy(0.15f), CircleShape)) {
                    Icon(Icons.Rounded.Stop, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    @Composable
    fun DrawerContent(
        selectedChatId: String,
        onChatSelected: (String) -> Unit,
        onRefresh: () -> Unit,
        onShowRadar: () -> Unit,
        onClearMessages: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        ModalDrawerSheet(
            drawerContainerColor = SurfaceWhite,
            modifier = Modifier.width(300.dp)
        ) {
            Surface(color = PrimaryBlue.copy(0.05f), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = PrimaryBlue.copy(0.2f), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(myPseudo.take(1).uppercase(), color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(myPseudo, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                            Text(networkStatus, fontSize = 11.sp,
                                color = if (isMeshActive) SuccessGreen else WarningOrange,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                            border = BorderStroke(1.dp, PrimaryBlue.copy(0.5f))
                        ) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rafraîchir", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onShowRadar, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryPurple),
                            border = BorderStroke(1.dp, PrimaryPurple.copy(0.5f))
                        ) {
                            Icon(Icons.Default.Radar, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Radar", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryTeal),
                            border = BorderStroke(1.dp, PrimaryTeal.copy(0.5f))
                        ) {
                            Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Param.", fontSize = 11.sp)
                        }
                    }
                }
            }

            Text("CANAUX", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = PrimaryBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            NavigationDrawerItem(
                icon = { Icon(Icons.Rounded.Groups, null, modifier = Modifier.size(18.dp), tint = TextPrimary) },
                label = { Text("Canal général", color = TextPrimary) },
                badge = {
                    val unread = messages.count { !it.isMine && it.receiverId == "TOUS" }
                    if (unread > 0) Badge { Text("$unread") }
                },
                selected = selectedChatId == "TOUS",
                onClick = { onChatSelected("TOUS") },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = PrimaryBlue.copy(0.1f),
                    selectedTextColor = PrimaryBlue, unselectedTextColor = TextPrimary
                )
            )

            HorizontalDivider(color = Color.LightGray.copy(0.3f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CONTACTS", color = PrimaryBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Surface(shape = CircleShape, color = PrimaryBlue.copy(0.1f)) {
                    Text("${nodes.size}", fontSize = 10.sp, color = PrimaryBlue, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }

            if (nodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SensorsOff, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Aucun contact", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(nodes.values.toList().sortedByDescending { it.lastSeen }) { node ->
                        NodeDrawerItem(node, selectedChatId == node.deviceId) { onChatSelected(node.deviceId) }
                    }
                }
            }

            HorizontalDivider(color = Color.LightGray.copy(0.3f), modifier = Modifier.padding(16.dp))

            Surface(color = SurfaceGray, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("Nœuds", nodes.size.toString(), PrimaryBlue)
                    StatItem("Msgs", messages.size.toString(), PrimaryPurple)
                    StatItem("DB", "${messageStore.countMessages()}", PrimaryTeal)
                    StatItem("Statut", if (isMeshActive) "✓" else "✗", if (isMeshActive) SuccessGreen else ErrorRed)
                }
            }
            TextButton(onClick = onClearMessages, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.DeleteSweep, null, tint = ErrorRed.copy(0.7f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Effacer tous les messages", color = ErrorRed.copy(0.7f), fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun NodeDrawerItem(node: MeshNode, isSelected: Boolean, onClick: () -> Unit) {
        val isActive = System.currentTimeMillis() - node.lastSeen < 30000
        NavigationDrawerItem(
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (isActive) SuccessGreen else Color.Gray, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(node.pseudo, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (node.connectionType) {
                                    ConnectionType.WIFI_DIRECT -> "📡 WiFi"
                                    ConnectionType.BLUETOOTH -> "🔵 BLE"
                                    else -> "🔗"
                                },
                                fontSize = 10.sp, color = TextSecondary
                            )
                            if (node.estimatedDistance > 0) {
                                Text(" · ${node.estimatedDistance.toInt()}m", fontSize = 10.sp, color = TextSecondary)
                            }
                            if (node.isRelay) {
                                Text(" · 🔁", fontSize = 10.sp, color = PrimaryPurple)
                            } else if (node.isSubGo) {
                                Text(" · 🏛️", fontSize = 10.sp, color = WarningOrange)
                            }
                        }
                    }
                }
            },
            selected = isSelected, onClick = onClick,
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = PrimaryBlue.copy(0.1f),
                selectedTextColor = PrimaryBlue, unselectedTextColor = TextPrimary
            )
        )
    }

    @Composable
    fun StatItem(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 15.sp)
            Text(label, fontSize = 9.sp, color = TextSecondary)
        }
    }

    @Composable
    fun NetworkMapBar() {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp).height(110.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = CircleShape, color = PrimaryBlue.copy(0.2f),
                    border = BorderStroke(2.dp, PrimaryBlue), modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(myPseudo.take(1).uppercase(), color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                }
                nodes.values.take(4).forEach { node ->
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    val isWifi = node.connectionType == ConnectionType.WIFI_DIRECT
                    Surface(shape = CircleShape,
                        color = (if (isWifi) PrimaryBlue else PrimaryPurple).copy(0.15f),
                        border = BorderStroke(1.5.dp, if (isWifi) PrimaryBlue else PrimaryPurple),
                        modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(node.pseudo.take(1).uppercase(), color = if (isWifi) PrimaryBlue else PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
                if (nodes.size > 4) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Surface(shape = CircleShape, color = SurfaceGray, modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("+${nodes.size - 4}", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PseudoEntryScreen(onJoin: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = PrimaryBlue.copy(0.15f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Hub, null, modifier = Modifier.size(64.dp), tint = PrimaryBlue)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("MIT MESH NETWORK", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Communication décentralisée hors-infrastructure", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 40.dp))

            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Votre pseudo", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue, unfocusedBorderColor = Color.Gray,
                    focusedTextColor = TextPrimary, cursorColor = PrimaryBlue
                ),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Person, null, tint = PrimaryBlue) }
            )
            Text("ID: $myId", color = TextSecondary.copy(0.6f), fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (text.isNotBlank()) onJoin(text.trim()) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
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
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                ) {
                    CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(44.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(message, color = TextPrimary, fontSize = 13.sp, textAlign = TextAlign.Center)
                    if (message.contains("Délai")) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { isInitializing = false; initTimeout = false; checkAndRequestPermissions() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
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
                        Text("OK", color = PrimaryBlue)
                    }
                },
                containerColor = SurfaceWhite,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = message,
                    color = if (message.startsWith("❌")) ErrorRed else TextPrimary,
                    fontSize = 13.sp
                )
            }
        }
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