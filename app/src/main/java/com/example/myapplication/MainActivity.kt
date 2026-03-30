package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    // --- Couleurs ---
    private val GeminiBg = Color(0xFF0F0F0F)
    private val GeminiBlue = Color(0xFF1A73E8)
    private val GeminiPurple = Color(0xFF8E24AA)
    private val SurfaceDark = Color(0xFF1E1F20)
    private val SurfaceMedium = Color(0xFF2C2D30)
    private val SurfaceLight = Color(0xFF3C3D40)
    private val GhostWhite = Color(0xFFE3E3E3)
    private val SuccessGreen = Color(0xFF34A853)
    private val WarningOrange = Color(0xFFFBBC04)
    private val ErrorRed = Color(0xFFEA4335)

    // --- State ---
    private val messages = mutableStateListOf<ChatMessage>()
    private val nodes = mutableStateMapOf<String, MeshNode>()
    private var myId by mutableStateOf("")
    private var myPseudo by mutableStateOf("")
    private var isMeshActive by mutableStateOf(false)
    private var networkStatus by mutableStateOf("🔄 Initialisation...")
    private var showNetworkMap by mutableStateOf(false)
    private var selectedNodeId by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var isRecording by mutableStateOf(false)
    private var audioFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var pendingPermissionRequest by mutableStateOf(false)
    private var isInitializing by mutableStateOf(false)
    private var initTimeout by mutableStateOf(false)
    private var initProgressMessage by mutableStateOf("")
    private var showRadarDialog by mutableStateOf(false)

    // Flags atomiques
    private val isServiceStarting = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)
    private var initializationJob: Job? = null
    private var statusCheckJob: Job? = null

    // Constants
    private val CHANNEL_ID = "mesh_messages"
    private val TAG = "MainActivity"

    // Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // Permission Launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        pendingPermissionRequest = false
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            errorMessage = null
            if (myPseudo.isNotEmpty() && !isDestroyed.get()) {
                startMeshServiceWithDelay()
            }
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            errorMessage = "❌ Permissions refusées: ${deniedPermissions.joinToString(", ")}"
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "🟢 [onCreate] Début - SDK: ${Build.VERSION.SDK_INT}")

        try {
            if (savedInstanceState != null) {
                Log.d(TAG, "Restauration après rotation")
            }

            try {
                createNotificationChannel()
                Log.d(TAG, "✅ Canal notification créé")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur création canal notification", e)
            }

            val prefs = getSharedPreferences("MIT_MESH_SETTINGS", MODE_PRIVATE)
            myId = prefs.getString("user_id", "MIT-" + UUID.randomUUID().toString().take(6).uppercase())!!
            myPseudo = prefs.getString("user_pseudo", "") ?: ""
            Log.d(TAG, "📱 ID: $myId, Pseudo: ${if (myPseudo.isNotEmpty()) myPseudo else "non défini"}")

            try {
                registerReceivers()
                Log.d(TAG, "✅ Receivers enregistrés")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur enregistrement receivers", e)
            }

            setContent {
                MaterialTheme(colorScheme = darkColorScheme(background = GeminiBg, surface = SurfaceDark)) {
                    Surface(modifier = Modifier.fillMaxSize(), color = GeminiBg) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            BackgroundAnimate()

                            if (myPseudo.isEmpty()) {
                                PseudoEntryScreen { pseudo ->
                                    myPseudo = pseudo
                                    prefs.edit().putString("user_pseudo", pseudo).apply()
                                    checkAndRequestPermissions()
                                }
                            } else {
                                MainMeshScreen()
                            }

                            errorMessage?.let { message ->
                                ErrorSnackbar(
                                    message = message,
                                    onDismiss = { errorMessage = null }
                                )
                            }

                            if (pendingPermissionRequest || isInitializing) {
                                LoadingOverlay(
                                    message = if (pendingPermissionRequest)
                                        "📱 Demande de permissions..."
                                    else if (initTimeout)
                                        "⏰ Délai dépassé - Vérifiez WiFi/Bluetooth"
                                    else
                                        initProgressMessage.ifEmpty { "🔄 Initialisation du réseau..." }
                                )
                            }

                            if (showRadarDialog) {
                                androidx.compose.ui.window.Dialog(
                                    onDismissRequest = { showRadarDialog = false }
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.large,
                                        color = GeminiBg,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(600.dp)
                                    ) {
                                        RadarScreen(
                                            knownNodes = nodes,
                                            myPseudo = myPseudo,
                                            onClose = { showRadarDialog = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (myPseudo.isNotEmpty()) {
                checkAndRequestPermissions()
            }

            Log.i(TAG, "🟢 [onCreate] Fin")
        } catch (e: Exception) {
            Log.e(TAG, "💥 ERREUR FATALE onCreate", e)
            errorMessage = "Erreur démarrage: ${e.message}"
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 [onResume] Reprise activité")

        if (myPseudo.isNotEmpty() && !isMeshActive && !isDestroyed.get() && !isInitializing) {
            Log.d(TAG, "🔄 Relance du service depuis onResume")
            startMeshServiceWithDelay()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ [onPause]")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "💥 [onDestroy] Destruction activité")
        isDestroyed.set(true)

        try {
            unregisterReceiver(meshReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Receiver déjà déréférencé")
        }

        try {
            mediaPlayer?.release()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignorer
        }

        mainHandler.removeCallbacksAndMessages(null)
        initializationJob?.cancel()
        statusCheckJob?.cancel()
        isServiceStarting.set(false)
    }

    @Composable
    fun LoadingOverlay(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .width(if (message.length > 30) 220.dp else 180.dp)
                    .clip(RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    CircularProgressIndicator(
                        color = GeminiBlue,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        message,
                        color = GhostWhite,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    if (message.contains("Délai")) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                isInitializing = false
                                initTimeout = false
                                checkAndRequestPermissions()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Réessayer", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ErrorSnackbar(message: String, onDismiss: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Snackbar(
                modifier = Modifier.animateContentSize(),
                action = {
                    TextButton(onClick = onDismiss) {
                        Text("OK", color = GhostWhite)
                    }
                },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(message, color = ErrorRed, fontSize = 13.sp)
            }
        }
    }

    @Composable
    fun BackgroundAnimate() {
        val infiniteTransition = rememberInfiniteTransition(label = "background")
        val alpha1 by infiniteTransition.animateFloat(
            initialValue = 0.03f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(5000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha1"
        )
        val alpha2 by infiniteTransition.animateFloat(
            initialValue = 0.02f,
            targetValue = 0.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(7000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha2"
        )
        val rotation1 by infiniteTransition.animateFloat(
            initialValue = -15f,
            targetValue = 15f,
            animationSpec = infiniteRepeatable(
                animation = tween(8000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rotation1"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = GeminiBlue.copy(alpha = alpha1),
                modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.TopStart)
                    .offset(x = (-80).dp, y = 50.dp)
                    .rotate(rotation1)
            )
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = GeminiPurple.copy(alpha = alpha2),
                modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 80.dp, y = (-50).dp)
                    .rotate(-rotation1)
            )
        }
    }

    @Composable
    fun PseudoEntryScreen(onJoin: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        var isGenerating by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    color = GeminiBlue.copy(alpha = 0.2f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Hub,
                            contentDescription = "Logo",
                            modifier = Modifier.size(80.dp),
                            tint = GeminiBlue
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "MIT MESH NETWORK",
                color = GhostWhite,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Communication décentralisée hors-infrastructure",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Votre pseudo", color = Color.Gray) },
                placeholder = { Text("Entrez votre nom", color = Color.DarkGray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GeminiBlue,
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White,
                    cursorColor = GeminiBlue
                ),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = GeminiBlue)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ID: $myId",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        isGenerating = true
                        onJoin(text)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GeminiBlue,
                    disabledContainerColor = SurfaceLight
                ),
                enabled = text.isNotBlank() && !isGenerating
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("REJOINDRE LE RÉSEAU", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                TechnologyBadge(text = "Bluetooth", color = GeminiBlue)
                Spacer(modifier = Modifier.width(8.dp))
                TechnologyBadge(text = "WiFi Direct", color = GeminiPurple)
            }
        }
    }

    @Composable
    fun TechnologyBadge(text: String, color: Color) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.2f),
            border = BorderStroke(width = 1.dp, color = color.copy(alpha = 0.5f))
        ) {
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainMeshScreen() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var selectedChatId by remember { mutableStateOf("TOUS") }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModernDrawerContent(
                    selectedChatId = selectedChatId,
                    onChatSelected = { id ->
                        selectedChatId = id
                        scope.launch { drawerState.close() }
                    },
                    onRefreshNetwork = { refreshNetwork() },
                    onShowRadar = { showRadarDialog = true }
                )
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        selectedChatId = selectedChatId,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onMapToggle = { showNetworkMap = !showNetworkMap },
                        onShowRadar = { showRadarDialog = true }
                    )
                },
                bottomBar = {
                    if (showNetworkMap) {
                        NetworkMapView()
                    } else {
                        ChatInputBar(
                            selectedId = selectedChatId,
                            onMessageSent = { content, receiver ->
                                sendMessage(content, receiver)
                            },
                            onStartRecording = { startRecording() },
                            onStopRecording = { stopRecording() },
                            isRecording = isRecording
                        )
                    }
                }
            ) { paddingValues ->
                if (showNetworkMap) {
                    NetworkTopologyView(modifier = Modifier.padding(paddingValues))
                } else {
                    // ⭐ AFFICHER TOUS LES MESSAGES SANS FILTRE POUR LE TEST ⭐
                    ChatView(
                        messages = messages.toList(),  // Tous les messages
                        modifier = Modifier.padding(paddingValues),
                        onPlayAudio = { msg -> playAudio(msg) }
                    )
                }
            }
        }
    }

    @Composable
    fun ModernDrawerContent(
        selectedChatId: String,
        onChatSelected: (String) -> Unit,
        onRefreshNetwork: () -> Unit,
        onShowRadar: () -> Unit
    ) {
        ModalDrawerSheet(
            drawerContainerColor = SurfaceDark,
            drawerContentColor = GhostWhite,
            modifier = Modifier.width(300.dp)
        ) {
            Surface(
                color = GeminiBlue.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = GeminiBlue.copy(alpha = 0.3f),
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = myPseudo.take(1).uppercase(),
                                    color = GeminiBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = myPseudo,
                                fontWeight = FontWeight.Bold,
                                color = GhostWhite,
                                fontSize = 16.sp
                            )
                            Text(
                                text = networkStatus,
                                fontSize = 11.sp,
                                color = if (isMeshActive) SuccessGreen else WarningOrange
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onRefreshNetwork,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GeminiBlue),
                        border = BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rafraîchir le réseau")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onShowRadar,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GeminiPurple),
                        border = BorderStroke(1.dp, GeminiPurple.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Radar de topologie")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "CANAUX DE DISCUSSION",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = GeminiBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            NavigationDrawerItem(
                icon = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("CANAL GÉNÉRAL") },
                selected = selectedChatId == "TOUS",
                onClick = { onChatSelected("TOUS") },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = GeminiBlue.copy(alpha = 0.2f),
                    selectedTextColor = GeminiBlue,
                    unselectedTextColor = Color.Gray
                )
            )

            Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "NOEUDS CONNECTÉS", color = GeminiBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Surface(shape = CircleShape, color = GeminiBlue.copy(alpha = 0.2f)) {
                    Text(
                        text = nodes.size.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = GeminiBlue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SensorsOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Aucun noeud détecté", color = Color.Gray, fontSize = 14.sp)
                        Text(
                            text = "Activez WiFi et Bluetooth",
                            color = Color.Gray.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(nodes.values.toList()) { node ->
                        NodeDrawerItem(
                            node = node,
                            isSelected = selectedChatId == node.deviceId,
                            onClick = { onChatSelected(node.deviceId) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            Surface(
                color = SurfaceMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItemCompact(label = "Noeuds", value = nodes.size.toString(), color = GeminiBlue)
                    StatItemCompact(label = "Messages", value = messages.size.toString(), color = GeminiPurple)
                    StatItemCompact(
                        label = "Statut",
                        value = if (isMeshActive) "✓" else "✗",
                        color = if (isMeshActive) SuccessGreen else ErrorRed
                    )
                }
            }
        }
    }

    @Composable
    fun NodeDrawerItem(node: MeshNode, isSelected: Boolean, onClick: () -> Unit) {
        val isActive = System.currentTimeMillis() - node.lastSeen < 30000

        NavigationDrawerItem(
            label = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = CircleShape,
                        color = if (isActive) SuccessGreen else Color.Gray,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = node.pseudo, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Text(text = "${node.deviceName}", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            },
            selected = isSelected,
            onClick = onClick,
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = GeminiBlue.copy(alpha = 0.2f),
                selectedTextColor = GeminiBlue,
                unselectedTextColor = GhostWhite
            )
        )
    }

    @Composable
    fun StatItemCompact(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(text = value, fontWeight = FontWeight.Bold, color = color, fontSize = 16.sp)
            Text(text = label, fontSize = 9.sp, color = Color.Gray)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TopAppBar(
        selectedChatId: String,
        onMenuClick: () -> Unit,
        onMapToggle: () -> Unit,
        onShowRadar: () -> Unit
    ) {
        CenterAlignedTopAppBar(
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                titleContentColor = GhostWhite
            ),
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = GhostWhite)
                }
            },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (selectedChatId == "TOUS") "MIT MESH"
                        else nodes[selectedChatId]?.pseudo ?: "Discussion privée",
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = networkStatus, fontSize = 10.sp, color = if (isMeshActive) SuccessGreen else WarningOrange)
                }
            },
            actions = {
                IconButton(onClick = onShowRadar) {
                    Icon(
                        Icons.Default.Radar,
                        contentDescription = "Radar",
                        tint = GhostWhite
                    )
                }
                IconButton(onClick = onMapToggle) {
                    Icon(
                        imageVector = if (showNetworkMap) Icons.Rounded.Chat else Icons.Rounded.Map,
                        contentDescription = if (showNetworkMap) "Chat" else "Carte",
                        tint = GhostWhite
                    )
                }
                IconButton(onClick = { showSettings() }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Paramètres", tint = GhostWhite)
                }
            }
        )
    }

    private fun showSettings() {
        errorMessage = "📱 Version 1.0 | ID: $myId | Android ${Build.VERSION.SDK_INT}"
    }

    @Composable
    fun ChatView(messages: List<ChatMessage>, modifier: Modifier = Modifier, onPlayAudio: (ChatMessage) -> Unit) {
        // ⭐ LOG POUR VOIR LES MESSAGES ⭐
        Log.d(TAG, "ChatView: ${messages.size} messages à afficher")
        messages.forEach { msg ->
            Log.d(TAG, "  - ${msg.sender}: ${msg.content}")
        }

        if (messages.isEmpty()) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Aucun message", color = Color.Gray, fontSize = 16.sp)
                    Text(text = "Envoyez un message pour commencer", color = Color.Gray.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
                reverseLayout = true
            ) {
                items(items = messages.reversed(), key = { it.id }) { message ->
                    MessageBubble(msg = message, onPlayAudio = onPlayAudio)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    @Composable
    fun MessageBubble(msg: ChatMessage, onPlayAudio: (ChatMessage) -> Unit) {
        val isMine = msg.isMine

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = if (isMine) GeminiBlue.copy(alpha = 0.2f) else SurfaceDark,
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isMine) 18.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 18.dp
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isMine) GeminiBlue.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)
                ),
                shadowElevation = 2.dp,
                modifier = Modifier.animateContentSize()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (!isMine) {
                        Text(text = msg.sender, color = GeminiBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    when (msg.type) {
                        PacketType.AUDIO -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                IconButton(onClick = { onPlayAudio(msg) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        if (msg.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = GeminiBlue
                                    )
                                }
                                Text(text = "🎤 Message vocal (${msg.content})", color = GhostWhite, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        PacketType.FILE -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = GeminiBlue)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = msg.content, color = GhostWhite, fontSize = 14.sp)
                            }
                        }
                        else -> {
                            Text(text = msg.content, color = GhostWhite, fontSize = 15.sp, modifier = Modifier.padding(top = if (!isMine) 4.dp else 0.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = msg.time, color = Color.Gray, fontSize = 9.sp)
                        if (isMine) {
                            Spacer(modifier = Modifier.width(4.dp))
                            MessageStatusIcon(status = msg.status)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MessageStatusIcon(status: MessageStatus) {
        val (icon, color) = when (status) {
            MessageStatus.SENDING -> Icons.Rounded.AccessTime to WarningOrange
            MessageStatus.SENT -> Icons.Rounded.Check to Color.Gray
            MessageStatus.RECEIVED -> Icons.Rounded.DoneAll to SuccessGreen
            MessageStatus.FAILED -> Icons.Rounded.Error to ErrorRed
        }
        Icon(imageVector = icon, contentDescription = status.name, modifier = Modifier.size(12.dp), tint = color)
    }

    @Composable
    fun ChatInputBar(
        selectedId: String,
        onMessageSent: (String, String) -> Unit,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        isRecording: Boolean
    ) {
        var textInput by remember { mutableStateOf("") }
        var isExpanded by remember { mutableStateOf(false) }

        Surface(
            color = Color.Transparent,
            modifier = Modifier.navigationBarsPadding().padding(8.dp)
        ) {
            Column {
                AnimatedVisibility(visible = isExpanded) {
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { if (isRecording) onStopRecording() else onStartRecording() }) {
                                Icon(
                                    if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    contentDescription = if (isRecording) "Arrêter" else "Audio",
                                    tint = if (isRecording) ErrorRed else GeminiBlue
                                )
                            }
                            IconButton(onClick = { errorMessage = "📁 Envoi fichier (bientôt disponible)" }) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = "Fichier", tint = GeminiBlue)
                            }
                        }
                    }
                }

                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isExpanded = !isExpanded }) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.Close else Icons.Rounded.Add,
                                contentDescription = "Plus",
                                tint = Color.Gray
                            )
                        }

                        TextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    text = if (isRecording) "🎤 Enregistrement en cours..."
                                    else if (selectedId == "TOUS") "Message à tous..."
                                    else "Message privé...",
                                    color = if (isRecording) ErrorRed else Color.Gray
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                cursorColor = GeminiBlue
                            ),
                            maxLines = 3,
                            enabled = !isRecording
                        )

                        AnimatedContent(targetState = textInput.isNotBlank() || isRecording, label = "send_button") { hasContent ->
                            if (hasContent && !isRecording) {
                                Surface(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable {
                                        if (textInput.isNotBlank()) {
                                            onMessageSent(textInput, selectedId)
                                            textInput = ""
                                        }
                                    },
                                    color = GeminiBlue
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Envoyer", modifier = Modifier.size(20.dp), tint = Color.White)
                                    }
                                }
                            } else if (isRecording) {
                                Surface(modifier = Modifier.size(40.dp).clip(CircleShape), color = ErrorRed) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NetworkTopologyView(modifier: Modifier = Modifier) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    modifier = Modifier.size(300.dp).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        val centerX = size.width / 2
                        val centerY = size.height / 2

                        drawCircle(color = GeminiBlue, radius = 30f, center = Offset(x = centerX, y = centerY))

                        if (nodes.isEmpty()) {
                            drawContext.canvas.nativeCanvas.apply {
                                val text = "Aucun noeud"
                                val textPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 30f
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                drawText(text, centerX, centerY + 80f, textPaint)
                            }
                        } else {
                            nodes.values.forEachIndexed { index, node ->
                                val angle = (2 * Math.PI * index / maxOf(1, nodes.size)).toFloat()
                                val x = centerX + 100f * kotlin.math.cos(angle)
                                val y = centerY + 100f * kotlin.math.sin(angle)

                                drawCircle(color = GeminiPurple, radius = 20f, center = Offset(x = x, y = y))
                                drawLine(
                                    color = Color.White.copy(alpha = 0.3f),
                                    start = Offset(x = centerX, y = centerY),
                                    end = Offset(x = x, y = y),
                                    strokeWidth = 2f
                                )
                            }
                        }
                    }
                }

                Text(text = "Topologie du réseau", color = GhostWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(text = "${nodes.size} noeud(s) connecté(s)", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { refreshNetwork() }, colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rafraîchir")
                }
            }
        }
    }

    @Composable
    fun NetworkMapView() {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp).height(120.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NodeMapItem(pseudo = myPseudo, isMain = true, connectionCount = nodes.size)

                if (nodes.isNotEmpty()) {
                    nodes.values.take(3).forEach { node ->
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        NodeMapItem(pseudo = node.pseudo, isMain = false, connectionCount = 1)
                    }
                }

                if (nodes.size > 3) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    Surface(shape = CircleShape, color = SurfaceLight, modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "+${nodes.size - 3}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NodeMapItem(pseudo: String, isMain: Boolean, connectionCount: Int) {
        Surface(
            shape = CircleShape,
            color = if (isMain) GeminiBlue.copy(alpha = 0.3f) else GeminiPurple.copy(alpha = 0.3f),
            border = BorderStroke(width = 2.dp, color = if (isMain) GeminiBlue else GeminiPurple),
            modifier = Modifier.size(if (isMain) 50.dp else 40.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pseudo.take(1).uppercase(),
                        color = if (isMain) GeminiBlue else GeminiPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isMain) 16.sp else 14.sp
                    )
                    if (connectionCount > 0 && !isMain) {
                        Text(text = "$connectionCount", fontSize = 8.sp, color = Color.Gray)
                    }
                }
            }
        }
    }

    // ========== LOGIQUE MÉTIER ==========

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction("MESH_MESSAGE_RECEIVED")
            addAction("MESH_STATUS_CHANGED")
            addAction("MESH_NODE_DISCOVERED")
            addAction("MESH_NODE_LOST")
            addAction("MESH_TOPOLOGY_UPDATE")
            addAction("MESH_ERROR")
        }

        try {
            // Correction : On définit le flag dans une variable pour plus de clarté
            val listenFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_EXPORTED
            } else {
                0 // Pas de flag nécessaire pour les anciennes versions (comme ton vivo)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(meshReceiver, filter, listenFlags)
            } else {
                registerReceiver(meshReceiver, filter)
            }

            Log.d(TAG, "✅ Système Mesh : Receivers enregistrés")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur : ${e.message}")
        }
    }
    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isDestroyed.get()) return

            when (intent?.action) {
                "MESH_MESSAGE_RECEIVED" -> {
                    val id = intent.getStringExtra("id") ?: return
                    val sender = intent.getStringExtra("sender") ?: "Inconnu"
                    val content = intent.getStringExtra("content") ?: ""
                    val receiver = intent.getStringExtra("receiver") ?: "TOUS"
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    val typeStr = intent.getStringExtra("type") ?: "MESSAGE"
                    val audioData = intent.getByteArrayExtra("audio_data")

                    val type = try { PacketType.valueOf(typeStr) } catch (e: Exception) { PacketType.MESSAGE }

                    val sId = sender.substringAfterLast("(").substringBefore(")")

                    if (!nodes.containsKey(sId) && sId.isNotEmpty()) {
                        nodes[sId] = MeshNode(
                            deviceId = sId,
                            deviceName = "Appareil",
                            pseudo = sender.substringBefore(" ("),
                            connectionType = ConnectionType.BLUETOOTH,
                            lastSeen = System.currentTimeMillis()
                        )
                    }

                    val message = ChatMessage(
                        id = id,
                        sender = nodes[sId]?.pseudo ?: sender.substringBefore(" ("),
                        content = content,
                        isMine = false,
                        time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
                        status = MessageStatus.RECEIVED,
                        type = type,
                        senderIdRaw = sId,
                        receiverId = receiver,
                        timestamp = timestamp,
                        audioUri = if (audioData != null) {
                            val file = File(cacheDir, "audio_${id}.3gp")
                            file.writeBytes(audioData)
                            Uri.fromFile(file)
                        } else null
                    )

                    // ⭐ AJOUTER LE MESSAGE ET LOGGER ⭐
                    messages.add(message)
                    Log.d(TAG, "📨 Message AJOUTÉ à l'UI: ${message.content} (total: ${messages.size} messages)")

                    showMessageNotification(message)

                    if (messages.size > 200) messages.removeAt(0)
                }

                "MESH_STATUS_CHANGED" -> {
                    networkStatus = intent.getStringExtra("status") ?: ""
                    isMeshActive = networkStatus.contains("✅") || networkStatus.contains("actif")
                    Log.d(TAG, "Status: $networkStatus, actif: $isMeshActive")
                }

                "MESH_NODE_DISCOVERED" -> {
                    val nodeId = intent.getStringExtra("node_id") ?: return
                    val nodeName = intent.getStringExtra("node_name") ?: "Inconnu"
                    val nodePseudo = intent.getStringExtra("node_pseudo") ?: "Anonyme"
                    val connectionTypeStr = intent.getStringExtra("connection_type") ?: "BLUETOOTH"
                    val connectionType = try { ConnectionType.valueOf(connectionTypeStr) } catch (e: Exception) { ConnectionType.BLUETOOTH }

                    nodes[nodeId] = MeshNode(
                        deviceId = nodeId,
                        deviceName = nodeName,
                        pseudo = nodePseudo,
                        connectionType = connectionType,
                        lastSeen = System.currentTimeMillis()
                    )
                    Log.d(TAG, "✅ Noeud découvert: $nodePseudo ($nodeId)")
                }

                "MESH_NODE_LOST" -> {
                    val nodeId = intent.getStringExtra("node_id") ?: return
                    nodes.remove(nodeId)
                    Log.d(TAG, "❌ Noeud perdu: $nodeId")
                }

                "MESH_ERROR" -> {
                    val error = intent.getStringExtra("error") ?: "Erreur inconnue"
                    errorMessage = "❌ $error"
                    Log.e(TAG, "Erreur Mesh: $error")
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "🔐 Vérification permissions - SDK ${Build.VERSION.SDK_INT}")

        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            permissions.add(Manifest.permission.ACCESS_WIFI_STATE)
            permissions.add(Manifest.permission.CHANGE_WIFI_STATE)
        }

        permissions.add(Manifest.permission.ACCESS_NETWORK_STATE)
        permissions.add(Manifest.permission.INTERNET)

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            Log.d(TAG, "📱 Demandes ${toRequest.size} permissions: ${toRequest.joinToString(", ")}")
            pendingPermissionRequest = true
            permissionLauncher.launch(toRequest.toTypedArray())
        } else {
            Log.d(TAG, "✅ Toutes permissions déjà accordées")
            startMeshServiceWithDelay()
        }
    }

    private fun startMeshServiceWithDelay() {
        if (isServiceStarting.getAndSet(true)) {
            Log.d(TAG, "Service déjà en cours de démarrage")
            return
        }

        isInitializing = true
        initTimeout = false
        initProgressMessage = "🔄 Démarrage du service..."

        initializationJob?.cancel()

        mainHandler.postDelayed({
            if (isInitializing && !isDestroyed.get()) {
                isInitializing = false
                initTimeout = true
                isServiceStarting.set(false)
                errorMessage = "⏰ Délai d'initialisation dépassé"
                Log.e(TAG, "Timeout initialisation")
            }
        }, 15000)

        initializationJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(500)
                initProgressMessage = "🌐 Connexion au réseau..."

                withContext(Dispatchers.Main) {
                    if (!isDestroyed.get()) {
                        startMeshService()
                        isInitializing = false
                        isServiceStarting.set(false)
                        mainHandler.removeCallbacksAndMessages(null)
                        Log.d(TAG, "✅ Service démarré avec succès")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed.get()) {
                        isInitializing = false
                        initTimeout = true
                        isServiceStarting.set(false)
                        errorMessage = "❌ Erreur: ${e.message}"
                        Log.e(TAG, "Erreur démarrage", e)
                    }
                }
            }
        }
    }

    private fun startMeshService() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Activity détruite, impossible de démarrer le service")
            return
        }

        val intent = Intent(this, MeshService::class.java).apply {
            putExtra("user_id", myId)
            putExtra("user_pseudo", myPseudo)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            networkStatus = "🔄 Connexion..."
            Log.d(TAG, "Service Mesh démarré")
        } catch (e: Exception) {
            errorMessage = "❌ Erreur: ${e.message}"
            Log.e(TAG, "Erreur démarrage service", e)
        }
    }

    private fun sendMessage(content: String, receiver: String) {
        val id = UUID.randomUUID().toString()
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val message = ChatMessage(
            id = id,
            sender = "Moi",
            content = content,
            isMine = true,
            time = currentTime,
            status = MessageStatus.SENDING,
            receiverId = receiver,
            senderIdRaw = myId,
            type = PacketType.MESSAGE
        )

        messages.add(message)

        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id)
            putExtra("content", content)
            putExtra("receiver", receiver)
            putExtra("type", PacketType.MESSAGE.name)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Message envoyé: $content")
        } catch (e: Exception) {
            errorMessage = "❌ Erreur envoi: ${e.message}"
            Log.e(TAG, "Erreur envoi", e)
        }

        lifecycleScope.launch {
            delay(1000)
            val index = messages.indexOfLast { it.id == id }
            if (index >= 0 && messages[index].status == MessageStatus.SENDING) {
                messages[index] = messages[index].copy(status = MessageStatus.SENT)
            }
            delay(1000)
            val index2 = messages.indexOfLast { it.id == id }
            if (index2 >= 0 && messages[index2].status == MessageStatus.SENT) {
                messages[index2] = messages[index2].copy(status = MessageStatus.RECEIVED)
            }
        }
    }

    private fun sendAudioMessage(audioFile: File, receiver: String) {
        val id = UUID.randomUUID().toString()
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val duration = (audioFile.length() / 16000).toInt()

        val message = ChatMessage(
            id = id,
            sender = "Moi",
            content = "${duration}s",
            isMine = true,
            time = currentTime,
            status = MessageStatus.SENDING,
            receiverId = receiver,
            senderIdRaw = myId,
            type = PacketType.AUDIO,
            audioUri = Uri.fromFile(audioFile)
        )

        messages.add(message)

        val audioData = audioFile.readBytes()
        Log.d(TAG, "Audio size: ${audioData.size} bytes")

        val intent = Intent(this, MeshService::class.java).apply {
            action = "ACTION_SEND_MESSAGE"
            putExtra("id", id)
            putExtra("content", "Audio ${duration}s")
            putExtra("receiver", receiver)
            putExtra("type", PacketType.AUDIO.name)
            putExtra("audio_data", audioData)
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Message audio envoyé")
        } catch (e: Exception) {
            errorMessage = "❌ Erreur envoi audio: ${e.message}"
            Log.e(TAG, "Erreur envoi audio", e)
        }

        lifecycleScope.launch {
            delay(1000)
            val index = messages.indexOfLast { it.id == id }
            if (index >= 0) {
                messages[index] = messages[index].copy(status = MessageStatus.SENT)
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            errorMessage = "🎤 Permission microphone requise"
            return
        }

        try {
            audioFile = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Log.d(TAG, "🎤 Enregistrement démarré")
        } catch (e: IOException) {
            errorMessage = "Erreur enregistrement: ${e.message}"
            Log.e(TAG, "Erreur enregistrement", e)
        } catch (e: Exception) {
            errorMessage = "Erreur: ${e.message}"
            Log.e(TAG, "Erreur", e)
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            audioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    Log.d(TAG, "🎤 Audio enregistré: ${file.length()} bytes")
                    sendAudioMessage(file, selectedNodeId ?: "TOUS")
                } else {
                    errorMessage = "Enregistrement vide"
                }
            }
        } catch (e: Exception) {
            errorMessage = "Erreur arrêt: ${e.message}"
            Log.e(TAG, "Erreur arrêt", e)
        }
    }

    private fun playAudio(msg: ChatMessage) {
        try {
            if (msg.isPlaying) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                msg.isPlaying = false
            } else {
                msg.audioUri?.let { uri ->
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(this@MainActivity, uri)
                        prepare()
                        start()
                        setOnCompletionListener {
                            msg.isPlaying = false
                            mediaPlayer?.release()
                            mediaPlayer = null
                        }
                    }
                    msg.isPlaying = true
                    Log.d(TAG, "Lecture audio démarrée")
                }
            }
        } catch (e: Exception) {
            errorMessage = "Erreur lecture: ${e.message}"
            Log.e(TAG, "Erreur lecture", e)
        }
    }

    private fun showMessageNotification(message: ChatMessage) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("📨 ${message.sender}")
                .setContentText(if (message.type == PacketType.AUDIO) "🎤 Message vocal" else message.content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)

            NotificationManagerCompat.from(this).notify(message.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Erreur notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages Mesh",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications des nouveaux messages"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun refreshNetwork() {
        networkStatus = "🔄 Recherche..."
        startMeshService()
    }
}