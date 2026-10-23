package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath = ""
    private var currentFileName = ""
    private var recordingStartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var segmentRunnable: Runnable? = null
    private var isRecording = false
    private var isPausedForCall = false

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var fileManager: RecordingFileManager
    private lateinit var settings: RecordingSettings
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_HEARTBEAT_CHECK = "com.example.action.HEARTBEAT_CHECK"
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes watchdog
        private const val CHANNEL_ID = "continuous_recording_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "RecordingService"
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost (incoming phone call or system audio). Saving segment safely...")
                if (isRecording && !isPausedForCall) {
                    isPausedForCall = true
                    stopAndSaveCurrentSegment()
                    updateNotification("متوقف مؤقتاً أثناء المكالمة الهاتفية...")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus regained (phone call ended). Resuming recording...")
                if (isPausedForCall) {
                    isPausedForCall = false
                    if (settings.isAutoResumeAfterCall()) {
                        try {
                            startNewSegment()
                            updateNotification("التسجيل مستمر في الخلفية")
                            scheduleNextSegment()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to resume recording after call", e)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        fileManager = RecordingFileManager(this)
        settings = RecordingSettings(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecordingService()
            ACTION_START -> startRecordingService()
            ACTION_HEARTBEAT_CHECK -> {
                if (isRecording) {
                    scheduleWatchdogHeartbeat()
                }
            }
            else -> if (!isRecording && mediaRecorder == null) {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun startRecordingService() {
        if (isRecording) {
            updateNotification("التسجيل مستمر في الخلفية")
            return
        }

        val notification = createNotification("جارٍ بدء التسجيل المستمر...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            acquireWakeLock()
            requestAudioFocus()
            startNewSegment()
            isRecording = true
            isPausedForCall = false
            RecordingStateHolder.onRecordingStarted(currentFileName, recordingStartTime)
            val durationMin = settings.getSegmentDurationOption().minutes
            updateNotification("التسجيل مستمر في الخلفية • تقسيم كل $durationMin دقيقة")
            scheduleNextSegment()
            scheduleWatchdogHeartbeat()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            stopAndSaveCurrentSegment()
            releaseWakeLock()
            isRecording = false
            RecordingStateHolder.onError(e.localizedMessage ?: "فشل بدء التسجيل")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Continuous partial wake lock to keep MediaRecorder alive during deep sleep on Infinix XOS
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InfinixContinuousRecorder:WakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioManager?.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        }
    }

    private fun scheduleWatchdogHeartbeat() {
        if (!isRecording) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelWatchdogHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun scheduleNextSegment() {
        segmentRunnable?.let(handler::removeCallbacks)
        val segmentDurationMs = settings.getSegmentDurationOption().minutes * 60 * 1000L

        val task = object : Runnable {
            override fun run() {
                if (!isRecording || isPausedForCall) return
                stopAndSaveCurrentSegment()
                try {
                    startNewSegment()
                    RecordingStateHolder.onSegmentRotated(currentFileName, recordingStartTime)
                    updateNotification("التسجيل مستمر في الخلفية • ملف جديد")
                    handler.postDelayed(this, segmentDurationMs)
                    scheduleWatchdogHeartbeat()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not rotate recording segment", e)
                    stopRecordingService()
                }
            }
        }
        segmentRunnable = task
        handler.postDelayed(task, segmentDurationMs)
    }

    private fun startNewSegment() {
        val format = settings.getSelectedAudioFormat()
        val file = fileManager.createNewSegmentFile(format.extension)
        currentFilePath = file.absolutePath
        currentFileName = file.name
        recordingStartTime = System.currentTimeMillis()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(format.outputFormat)
                setAudioEncoder(format.audioEncoder)
                setAudioChannels(1)
                setAudioEncodingBitRate(format.bitrate)
                setAudioSamplingRate(format.sampleRate)
                setOutputFile(currentFilePath)

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                }
                setOnInfoListener { _, what, extra ->
                    Log.d(TAG, "MediaRecorder info: what=$what, extra=$extra")
                }

                prepare()
                start()
            }
            mediaRecorder = recorder
        } catch (e: Exception) {
            try { recorder.reset() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            File(currentFilePath).takeIf { it.exists() && it.length() == 0L }?.delete()
            currentFilePath = ""
            currentFileName = ""
            throw e
        }
    }

    private fun stopAndSaveCurrentSegment() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null

        // Android MediaRecorder requires at least ~1.5s of audio frames before stop() or it throws 'stop failed: -1007'
        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (elapsed in 1..1500) {
            try {
                Thread.sleep(1500 - elapsed)
            } catch (_: Exception) {}
        }

        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Recorder stopped before producing a valid segment", e)
        } finally {
            try { recorder.reset() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }

        val path = currentFilePath
        val name = currentFileName
        val startedAt = recordingStartTime
        currentFilePath = ""
        currentFileName = ""
        recordingStartTime = 0L
        if (path.isBlank()) return

        val file = File(path)
        if (!file.isFile || file.length() < 256L) {
            file.delete()
            return
        }
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedAt))
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startedAt))
        val inserted = dbHelper.insertRecording(
            fileName = name,
            filePath = path,
            date = date,
            startTime = time,
            duration = formatDuration(duration),
            fileSize = fileManager.formatFileSize(file.length())
        )
        if (inserted < 0L) Log.e(TAG, "Failed to save recording metadata for $path")
    }

    private fun stopRecordingService() {
        isRecording = false
        isPausedForCall = false
        RecordingStateHolder.onRecordingStopped()
        segmentRunnable?.let(handler::removeCallbacks)
        segmentRunnable = null
        cancelWatchdogHeartbeat()
        stopAndSaveCurrentSegment()
        abandonAudioFocus()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to release wake lock", e)
        }
        wakeLock = null
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 60_000) % 60
        val hours = ms / 3_600_000
        return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "التسجيل الصوتي المستمر",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "خدمة التسجيل الصوتي في الخلفية مع شاشة مطفأة"
                setShowBadge(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("المسجل الصوتي المستمر")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPendingIntent)
            .setOngoing(isRecording || mediaRecorder != null)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isRecording) {
            scheduleWatchdogHeartbeat()
        }
    }

    override fun onDestroy() {
        isRecording = false
        isPausedForCall = false
        RecordingStateHolder.onRecordingStopped()
        segmentRunnable?.let(handler::removeCallbacks)
        segmentRunnable = null
        cancelWatchdogHeartbeat()
        stopAndSaveCurrentSegment()
        abandonAudioFocus()
        releaseWakeLock()
        super.onDestroy()
    }
}
