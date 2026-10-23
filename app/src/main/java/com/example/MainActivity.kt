package com.example

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: RecordingSettings
    private lateinit var fileManager: RecordingFileManager
    private lateinit var recoveryManager: RecordingRecoveryManager
    private lateinit var playbackManager: AudioPlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)
        settings = RecordingSettings(this)
        fileManager = RecordingFileManager(this)
        recoveryManager = RecordingRecoveryManager(this, dbHelper)
        playbackManager = AudioPlaybackManager(this)

        recoveryManager.performRecoveryScan()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    AudioRecorderApp(
                        dbHelper = dbHelper,
                        settings = settings,
                        fileManager = fileManager,
                        playbackManager = playbackManager,
                        onRefreshTriggered = { recoveryManager.performRecoveryScan() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackManager.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecorderApp(
    dbHelper: DatabaseHelper,
    settings: RecordingSettings,
    fileManager: RecordingFileManager,
    playbackManager: AudioPlaybackManager,
    onRefreshTriggered: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Synchronize recording state with the background service
    val isServiceRecording by RecordingStateHolder.isRecording.collectAsState()
    val segmentStartTime by RecordingStateHolder.currentSegmentStartTime.collectAsState()
    val activeSegmentName by RecordingStateHolder.currentSegmentFileName.collectAsState()
    val sessionStartTime by RecordingStateHolder.sessionStartTime.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedDateFilter by remember { mutableStateOf("الكل") }
    var distinctDates by remember { mutableStateOf(listOf("الكل") + dbHelper.getDistinctDates()) }
    var recordingsList by remember { mutableStateOf(dbHelper.searchRecordings(searchQuery, selectedDateFilter)) }

    var selectedQuality by remember { mutableStateOf(settings.getSelectedQualityMode()) }
    var totalStorageSize by remember { mutableStateOf(fileManager.formatFileSize(fileManager.calculateTotalDirectorySize())) }
    var freeStorageSpace by remember {
        val freeBytes = fileManager.getAvailableStorageBytes()
        mutableStateOf(if (freeBytes >= 0) fileManager.formatFileSize(freeBytes) else "غير متاح")
    }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isBatteryOptimizationIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        )
    }

    // Audio player states
    var activePlayingId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPlaybackPosition by remember { mutableFloatStateOf(0f) }
    var totalPlaybackDuration by remember { mutableFloatStateOf(1f) }
    var currentPlayingTitle by remember { mutableStateOf("") }

    // Dialogs state
    var itemToRename by remember { mutableStateOf<RecordingModel?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var itemForDetails by remember { mutableStateOf<RecordingModel?>(null) }

    // Live tick timer for active recording
    var liveSessionElapsedMs by remember { mutableLongStateOf(0L) }
    var liveSegmentElapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isServiceRecording, sessionStartTime, segmentStartTime) {
        while (isServiceRecording) {
            val now = System.currentTimeMillis()
            if (sessionStartTime > 0L) {
                liveSessionElapsedMs = (now - sessionStartTime).coerceAtLeast(0L)
            }
            if (segmentStartTime > 0L) {
                liveSegmentElapsedMs = (now - segmentStartTime).coerceAtLeast(0L)
            }
            delay(1000)
        }
        liveSessionElapsedMs = 0L
        liveSegmentElapsedMs = 0L
    }

    // Permissions launcher
    val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "إذن الميكروفون مطلوب للتسجيل الصوتي المستمر", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    fun refreshData() {
        onRefreshTriggered()
        distinctDates = listOf("الكل") + dbHelper.getDistinctDates()
        recordingsList = dbHelper.searchRecordings(searchQuery, selectedDateFilter)
        totalStorageSize = fileManager.formatFileSize(fileManager.calculateTotalDirectorySize())
        val freeBytes = fileManager.getAvailableStorageBytes()
        freeStorageSpace = if (freeBytes >= 0) fileManager.formatFileSize(freeBytes) else "غير متاح"
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshData()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isBatteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Playback state callbacks
    LaunchedEffect(playbackManager) {
        playbackManager.onPlaybackStateChanged = { playing, pos, duration ->
            isPlaying = playing
            currentPlaybackPosition = pos.toFloat()
            totalPlaybackDuration = duration.toFloat().coerceAtLeast(1f)
        }
        playbackManager.onPlaybackCompleted = {
            isPlaying = false
            activePlayingId = null
            currentPlaybackPosition = 0f
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPlaybackPosition = playbackManager.getCurrentPosition().toFloat()
            delay(500)
        }
    }

    fun playRecording(item: RecordingModel) {
        if (activePlayingId == item.id) {
            if (isPlaying) {
                playbackManager.pause()
            } else {
                playbackManager.resume()
            }
            return
        }

        activePlayingId = item.id
        currentPlayingTitle = item.fileName
        playbackManager.play(item.filePath) { pos, dur ->
            currentPlaybackPosition = pos.toFloat()
            totalPlaybackDuration = dur.toFloat().coerceAtLeast(1f)
        }
        totalPlaybackDuration = playbackManager.getDuration().toFloat().coerceAtLeast(1f)
    }

    val estimates = settings.calculateEstimatedSizes(selectedQuality.bitrate)

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("المسجل الصوتي المستمر", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF102A43)),
                actions = {
                    IconButton(
                        onClick = { refreshData() },
                        modifier = Modifier.testTag("refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = Color.White)
                    }
                }
            )
        },
        containerColor = Color.White
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Main Control Card
            Card(
                modifier = Modifier.fillMaxWidth().testTag("control_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status row with pulsing mic
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .alpha(if (isServiceRecording) pulseAlpha else 1f)
                                    .clip(CircleShape)
                                    .background(if (isServiceRecording) Color(0xFFEF4444) else Color(0xFF9CA3AF))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isServiceRecording) "التسجيل نشط في الخلفية" else "المسجل متوقف",
                                color = if (isServiceRecording) Color(0xFF4ADE80) else Color(0xFFD1D5DB),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        if (isServiceRecording) {
                            Text(
                                text = formatMillis(liveSessionElapsedMs),
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    // 30-minute segmentation progress indicator
                    if (isServiceRecording) {
                        val segmentTotalMs = RecordingService.SEGMENT_DURATION_MS
                        val segmentRemainingMs = (segmentTotalMs - (liveSegmentElapsedMs % segmentTotalMs)).coerceAtLeast(0L)
                        val progress = ((liveSegmentElapsedMs % segmentTotalMs).toFloat() / segmentTotalMs.toFloat()).coerceIn(0f, 1f)

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF374151)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "جزء 30 د: ${formatMillis(liveSegmentElapsedMs % segmentTotalMs)}",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "متبقي على التقسيم: ${formatMillis(segmentRemainingMs)}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Quality mode selection chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QualityMode.values().forEach { mode ->
                            FilterChip(
                                selected = selectedQuality == mode,
                                onClick = {
                                    selectedQuality = mode
                                    settings.saveQualityMode(mode)
                                },
                                label = { Text(mode.titleAr, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF2563EB),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF374151),
                                    labelColor = Color(0xFFE5E7EB)
                                )
                            )
                        }
                    }

                    Text(
                        text = "تقدير الاستهلاك: ساعة (${estimates.first}) | 24 ساعة (${estimates.second})",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Action buttons (Toggle Recording + Battery Exemption)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isServiceRecording) {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_STOP
                                    }
                                    context.startService(intent)
                                    Toast.makeText(context, "تم إيقاف التسجيل الصوتي", Toast.LENGTH_SHORT).show()
                                    refreshData()
                                } else {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_START
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    Toast.makeText(context, "بدء التسجيل المستمر مع إطفاء الشاشة", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(46.dp).testTag("toggle_recording_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceRecording) Color(0xFFDC2626) else Color(0xFF16A34A)
                            )
                        ) {
                            Icon(
                                imageVector = if (isServiceRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServiceRecording) "إيقاف التسجيل" else "بدء التسجيل المستمر",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    if (!isBatteryOptimizationIgnored) {
                                        try {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                context.startActivity(fallback)
                                            } catch (e2: Exception) {
                                                Toast.makeText(context, "إعدادات تحسين البطارية غير متاحة في هذا الجهاز", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "التطبيق مستثنى بالفعل من قيود البطارية", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.height(46.dp).testTag("battery_optimization_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBatteryOptimizationIgnored) Color(0xFF0D9488) else Color(0xFF0284C7)
                            )
                        ) {
                            Text(
                                text = if (isBatteryOptimizationIgnored) "البطارية مستثناة ✓" else "استثناء البطارية ⚡",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Audio Player Bar
            if (activePlayingId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("audio_player_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentPlayingTitle,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    playbackManager.stop()
                                    activePlayingId = null
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "إغلاق المشغل", tint = Color.LightGray)
                            }
                        }

                        Slider(
                            value = currentPlaybackPosition,
                            onValueChange = { newVal ->
                                currentPlaybackPosition = newVal
                                playbackManager.seekTo(newVal.toInt())
                            },
                            valueRange = 0f..totalPlaybackDuration,
                            modifier = Modifier.fillMaxWidth().height(26.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = formatMillis(currentPlaybackPosition.toLong()), color = Color.LightGray, fontSize = 11.sp)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = {
                                    val target = (playbackManager.getCurrentPosition() - 10000).coerceAtLeast(0)
                                    playbackManager.seekTo(target)
                                }) {
                                    Icon(Icons.Default.FastRewind, contentDescription = "إرجاع 10 ثواني", tint = Color.White)
                                }

                                IconButton(onClick = {
                                    if (isPlaying) playbackManager.pause() else playbackManager.resume()
                                }) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = "تشغيل/إيقاف",
                                        tint = Color(0xFF4ADE80)
                                    )
                                }

                                IconButton(onClick = {
                                    val target = playbackManager.getCurrentPosition() + 10000
                                    playbackManager.seekTo(target)
                                }) {
                                    Icon(Icons.Default.FastForward, contentDescription = "تقديم 10 ثواني", tint = Color.White)
                                }
                            }

                            Text(text = formatMillis(totalPlaybackDuration.toLong()), color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Search Bar & Date Filter Row
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { query ->
                    searchQuery = query
                    recordingsList = dbHelper.searchRecordings(query, selectedDateFilter)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                placeholder = { Text("بحث في التسجيلات...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            recordingsList = dbHelper.searchRecordings("", selectedDateFilter)
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "مسح", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            // Distinct Dates Filter Chips
            if (distinctDates.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    distinctDates.forEach { date ->
                        FilterChip(
                            selected = selectedDateFilter == date,
                            onClick = {
                                selectedDateFilter = date
                                recordingsList = dbHelper.searchRecordings(searchQuery, date)
                            },
                            label = { Text(date, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Storage Overview & Count Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "التسجيلات (${recordingsList.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1E293B)
                )
                Text(
                    text = "مستخدم: $totalStorageSize • حر: $freeStorageSpace",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }

            // Recordings List
            if (recordingsList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "لا توجد نتائج مطابقة لبحثك" else "لا توجد تسجيلات بعد.\nاضغط 'بدء التسجيل المستمر' لبدء الحفظ.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("recordings_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordingsList, key = { it.id }) { item ->
                        RecordingItemCard(
                            item = item,
                            isPlaying = activePlayingId == item.id && isPlaying,
                            onPlayClick = { playRecording(item) },
                            onShareClick = {
                                val file = File(item.filePath)
                                if (file.exists()) {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة التسجيل الصوتي"))
                                } else {
                                    Toast.makeText(context, "الملف غير موجود على الذاكرة", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRenameClick = {
                                itemToRename = item
                                renameInput = item.fileName.removeSuffix(".m4a")
                            },
                            onInfoClick = {
                                itemForDetails = item
                            },
                            onDeleteClick = {
                                if (activePlayingId == item.id) {
                                    playbackManager.stop()
                                    activePlayingId = null
                                }
                                val file = File(item.filePath)
                                if (file.exists()) {
                                    file.delete()
                                }
                                dbHelper.deleteRecording(item.id)
                                refreshData()
                                Toast.makeText(context, "تم حذف التسجيل", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    itemToRename?.let { recording ->
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("إعادة تسمية التسجيل", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل الاسم الجديد للملف:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = fileManager.renameRecordingFile(recording.filePath, renameInput)
                    if (result != null) {
                        dbHelper.updateRecordingNameAndPath(recording.id, result.first, result.second)
                        refreshData()
                        Toast.makeText(context, "تمت إعادة التسمية بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "تعذر إعادة تسمية الملف على الذاكرة", Toast.LENGTH_SHORT).show()
                    }
                    itemToRename = null
                }) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Details Dialog
    itemForDetails?.let { recording ->
        AlertDialog(
            onDismissRequest = { itemForDetails = null },
            title = { Text("تفاصيل التسجيل", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الاسم: ${recording.fileName}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("التاريخ: ${recording.date}", fontSize = 12.sp)
                    Text("وقت البدء: ${recording.startTime}", fontSize = 12.sp)
                    Text("المدة: ${recording.duration}", fontSize = 12.sp)
                    Text("الحجم: ${recording.fileSize}", fontSize = 12.sp)
                    Text("التنسيق: MPEG-4 / AAC Mono", fontSize = 12.sp)
                    Text("المسار: ${recording.filePath}", fontSize = 10.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = { itemForDetails = null }) {
                    Text("إغلاق")
                }
            }
        )
    }
}

@Composable
fun RecordingItemCard(
    item: RecordingModel,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onShareClick: () -> Unit,
    onRenameClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.fileName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Text(
                    text = "التاريخ: ${item.date} • الوقت: ${item.startTime}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Text(
                    text = "المدة: ${item.duration} • الحجم: ${item.fileSize}",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "تشغيل/إيقاف",
                        tint = if (isPlaying) Color(0xFF4ADE80) else Color.White
                    )
                }
                IconButton(onClick = onShareClick) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "مشاركة",
                        tint = Color(0xFF38BDF8)
                    )
                }
                IconButton(onClick = onRenameClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل الاسم",
                        tint = Color(0xFFFBBF24)
                    )
                }
                IconButton(onClick = onInfoClick) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "تفاصيل",
                        tint = Color(0xFFA78BFA)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = Color(0xFFF87171)
                    )
                }
            }
        }
    }
}

fun formatMillis(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60_000) % 60
    val hours = ms / 3_600_000
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
