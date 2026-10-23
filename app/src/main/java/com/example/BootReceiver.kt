package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HEARTBEAT = "com.example.action.SERVICE_HEARTBEAT"
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "BootReceiver received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val dbHelper = DatabaseHelper(context)
                val recoveryManager = RecordingRecoveryManager(context, dbHelper)
                recoveryManager.performRecoveryScan()

                showResumePromptNotification(context)
            }
            ACTION_HEARTBEAT -> {
                // Watchdog heartbeat: verify recording state and ensure service continuity
                if (RecordingStateHolder.isRecording.value) {
                    val serviceIntent = Intent(context, RecordingService::class.java).apply {
                        this.action = RecordingService.ACTION_HEARTBEAT_CHECK
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog failed to ping RecordingService", e)
                    }
                }
            }
        }
    }

    private fun showResumePromptNotification(context: Context) {
        val channelId = "boot_recovery_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "تنبيهات استعادة التسجيل بعد الإقلاع",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("المسجل الصوتي المستمر")
            .setContentText("اكتمل تشغيل الهاتف، اضغط هنا لفتح التطبيق ومتابعة التسجيل الصوتي.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(201, notification)
    }
}
