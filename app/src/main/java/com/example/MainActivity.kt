package com.example

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        // Perform automatic recovery scan on startup
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
    var recordingsList by remember { mutableStateOf(dbHelper.getAllRecordings()) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(settings.getSelectedQualityMode()) }
    var totalStorageSize by remember { mutableStateOf(fileManager.formatFileSize(fileManager.calculateTotalDirectorySize())) }

    // Audio player states
    var activePlayingId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPlaybackPosition by remember { mutableFloatStateOf(0f) }
    var totalPlaybackDuration by remember { mutableFloatStateOf(1f) }
    var currentPlayingTitle by remember { mutableStateOf("") }

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
            Toast.makeText(context, "إذن الميكروفون مطلوب للتسجيل", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // Refresh saved segments when returning from Settings or another app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recordingsList = dbHelper.getAllRecordings()
                totalStorageSize = fileManager.formatFileSize(fileManager.calculateTotalDirectorySize())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun refreshData() {
        onRefreshTriggered()
        recordingsList = dbHelper.getAllRecordings()
        totalStorageSize = fileManager.formatFileSize(fileManager.calculateTotalDirectorySize())
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

    // Playback progress ticker
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
        // Async MediaPlayer preparation updates isPlaying via onPlaybackStateChanged.
    }

    val estimates = settings.calculateEstimatedSizes(selectedQuality.bitrate)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المسجل الصوتي المستمر (RTL)", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F1F1F)),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Control Card (Dark opaque card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) "الحالة: جاري التسجيل في الخلفية (تقسيم كل 30 دقيقة)" else "الحالة: متوقف",
                        color = if (isRecording) Color(0xFF4CAF50) else Color.LightGray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    // Quality mode selector
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
                                label = { Text(mode.titleAr, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text(
                        text = "التقدير: ساعة (${estimates.first}) | 24 ساعة (${estimates.second})",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_STOP
                                    }
                                    context.startService(intent)
                                    isRecording = false
                                    Toast.makeText(context, "تم إيقاف التسجيل", Toast.LENGTH_SHORT).show()
                                    Handler(Looper.getMainLooper()).postDelayed({ refreshData() }, 500)
                                } else {
                                    val intent = Intent(context, RecordingService::class.java).apply {
                                        action = RecordingService.ACTION_START
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    isRecording = true
                                    Toast.makeText(context, "بدء التسجيل المستمر", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF388E3C)
                            )
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecording) "إيقاف التسجيل" else "بدء التسجيل",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "إعدادات البطارية غير متاحة", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1))
                        ) {
                            Text(text = "استثناء البطارية", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Audio Player Bar Card
            if (activePlayingId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "جاري التشغيل: $currentPlayingTitle",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = currentPlaybackPosition,
                            onValueChange = { newVal ->
                                currentPlaybackPosition = newVal
                                playbackManager.seekTo(newVal.toInt())
                            },
                            valueRange = 0f..totalPlaybackDuration,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = formatMillis(currentPlaybackPosition.toLong()), color = Color.LightGray, fontSize = 11.sp)
                            IconButton(onClick = {
                                if (isPlaying) playbackManager.pause() else playbackManager.resume()
                            }) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Text(text = formatMillis(totalPlaybackDuration.toLong()), color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Stats Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "التسجيلات المحفوظة (${recordingsList.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.Black
                )
                Text(
                    text = "إجمالي الحجم: $totalStorageSize",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }

            // Recordings List
            if (recordingsList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد تسجيلات حتى الآن.\nاضغط 'بدء التسجيل' للبدء.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                                        // M4A/AAC is audio/mp4. audio/m4a is not recognized by every receiver.
                                        type = "audio/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة التسجيل"))
                                } else {
                                    Toast.makeText(context, "الملف غير موجود على الجهاز", Toast.LENGTH_SHORT).show()
                                }
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
}

@Composable
fun RecordingItemCard(
    item: RecordingModel,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.fileName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "التاريخ: ${item.date} | الوقت: ${item.startTime}",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Text(
                    text = "المدة: ${item.duration} | الحجم: ${item.fileSize}",
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "تشغيل",
                        tint = if (isPlaying) Color(0xFF4CAF50) else Color.White
                    )
                }
                IconButton(onClick = onShareClick) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "مشاركة",
                        tint = Color(0xFF29B6F6)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = Color(0xFFEF5350)
                    )
                }
            }
        }
    }
}

fun formatMillis(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = ms / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
