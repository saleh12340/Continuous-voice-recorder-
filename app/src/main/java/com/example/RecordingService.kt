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
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var fileManager: RecordingFileManager
    private lateinit var settings: RecordingSettings

    companion object {
        const val ACTION_START = "com.example.action.START_RECORDING"
        const val ACTION_STOP = "com.example.action.STOP_RECORDING"
        const val ACTION_STATUS = "com.example.action.RECORDING_STATUS"
        const val ACTION_HEARTBEAT_CHECK = "com.example.action.HEARTBEAT_CHECK"
        const val SEGMENT_DURATION_MS = 30 * 60 * 1000L
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes watchdog
        private const val CHANNEL_ID = "continuous_recording_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "RecordingService"
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        fileManager = RecordingFileManager(this)
        settings = RecordingSettings(this)
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

        val notification = createNotification("جارٍ بدء التسجيل...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            acquireWakeLock()
            startNewSegment()
            isRecording = true
            RecordingStateHolder.onRecordingStarted(currentFileName, recordingStartTime)
            updateNotification("التسجيل مستمر في الخلفية • تقسيم كل 30 دقيقة")
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ContinuousRecording").apply {
            setReferenceCounted(false)
            acquire()
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
        val task = object : Runnable {
            override fun run() {
                if (!isRecording) return
                stopAndSaveCurrentSegment()
                try {
                    startNewSegment()
                    RecordingStateHolder.onSegmentRotated(currentFileName, recordingStartTime)
                    updateNotification("التسجيل مستمر في الخلفية • ملف جديد")
                    handler.postDelayed(this, SEGMENT_DURATION_MS)
                    scheduleWatchdogHeartbeat()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not rotate recording segment", e)
                    stopRecordingService()
                }
            }
        }
        segmentRunnable = task
        handler.postDelayed(task, SEGMENT_DURATION_MS)
    }

    private fun startNewSegment() {
        val quality = settings.getSelectedQualityMode()
        val file = fileManager.createNewSegmentFile()
        currentFilePath = file.absolutePath
        currentFileName = file.name
        recordingStartTime = System.currentTimeMillis()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(quality.bitrate)
                setAudioSamplingRate(quality.sampleRate)
                setOutputFile(currentFilePath)
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
        if (!file.isFile || file.length() < 512L) {
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
        RecordingStateHolder.onRecordingStopped()
        segmentRunnable?.let(handler::removeCallbacks)
        segmentRunnable = null
        cancelWatchdogHeartbeat()
        stopAndSaveCurrentSegment()
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
            val channel = NotificationChannel(CHANNEL_ID, "التسجيل الصوتي المستمر", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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
            .setOnlyAlertOnce(true)
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
        RecordingStateHolder.onRecordingStopped()
        segmentRunnable?.let(handler::removeCallbacks)
        segmentRunnable = null
        cancelWatchdogHeartbeat()
        stopAndSaveCurrentSegment()
        releaseWakeLock()
        super.onDestroy()
    }
}
