package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String = ""
    private var currentFileName: String = ""
    private var recordingStartTime: Long = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var segmentRunnable: Runnable? = null
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var fileManager: RecordingFileManager
    private lateinit var settings: RecordingSettings

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val NOTIFICATION_ID = 101
        // 30 minutes in milliseconds = 1,800,000 ms
        const val SEGMENT_DURATION_MS = 30 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        fileManager = RecordingFileManager(this)
        settings = RecordingSettings(this)
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioRecorder::WakelockTag").apply {
            acquire(35 * 60 * 1000L) // 35 minutes safety acquire
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecordingService()
            ACTION_STOP -> stopRecordingService()
        }
        return START_STICKY
    }

    private fun startRecordingService() {
        val notification = createNotification("التسجيل الصوتي قيد العمل في الخلفية...")
        startForeground(NOTIFICATION_ID, notification)

        startNewSegment()

        segmentRunnable = object : Runnable {
            override fun run() {
                stopAndSaveCurrentSegment()
                startNewSegment()
                handler.postDelayed(this, SEGMENT_DURATION_MS)
            }
        }
        handler.postDelayed(segmentRunnable!!, SEGMENT_DURATION_MS)
    }

    private fun startNewSegment() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val qualityMode = settings.getSelectedQualityMode()
        val file = fileManager.createNewSegmentFile()
        currentFilePath = file.absolutePath
        currentFileName = file.name
        recordingStartTime = System.currentTimeMillis()

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(qualityMode.bitrate)
            setAudioSamplingRate(qualityMode.sampleRate)
            setOutputFile(currentFilePath)
            try {
                prepare()
                start()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun stopAndSaveCurrentSegment() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val file = File(currentFilePath)
            if (file.exists() && file.length() > 0) {
                val durationMs = System.currentTimeMillis() - recordingStartTime
                val durationFormatted = formatDuration(durationMs)
                val fileSizeFormatted = fileManager.formatFileSize(file.length())

                val displayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(recordingStartTime))
                val displayTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(recordingStartTime))

                dbHelper.insertRecording(
                    fileName = currentFileName,
                    filePath = currentFilePath,
                    date = displayDate,
                    startTime = displayTime,
                    duration = durationFormatted,
                    fileSize = fileSizeFormatted
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecordingService() {
        segmentRunnable?.let { handler.removeCallbacks(it) }
        stopAndSaveCurrentSegment()

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = ms / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "قناة خدمة التسجيل الصوتي",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("المسجل الصوتي قيد التشغيل")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndSaveCurrentSegment()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
