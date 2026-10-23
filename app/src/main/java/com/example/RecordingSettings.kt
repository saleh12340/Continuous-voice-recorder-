package com.example

import android.content.Context
import android.content.SharedPreferences

enum class QualityMode(val titleAr: String, val bitrate: Int, val sampleRate: Int) {
    EXTREME("توفير شديد للمساحة", 8000, 16000),     // 8 kbps, 16kHz Mono
    BALANCED("وضع متوازن", 16000, 22050),            // 16 kbps, 22.05kHz Mono
    HIGH_CLARITY("وضع وضوح أعلى", 32000, 44100)      // 32 kbps, 44.1kHz Mono
}

class RecordingSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("audio_recorder_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_QUALITY_MODE = "key_quality_mode"
    }

    fun getSelectedQualityMode(): QualityMode {
        val name = prefs.getString(KEY_QUALITY_MODE, QualityMode.BALANCED.name)
        return try {
            QualityMode.valueOf(name ?: QualityMode.BALANCED.name)
        } catch (e: Exception) {
            QualityMode.BALANCED
        }
    }

    fun saveQualityMode(mode: QualityMode) {
        prefs.edit().putString(KEY_QUALITY_MODE, mode.name).apply()
    }

    fun calculateEstimatedSizes(bitrate: Int): Pair<String, String> {
        // bitrate in bits per second. 
        // 1 hour = 3600 seconds. Size in bytes = (bitrate * 3600) / 8
        val oneHourBytes = (bitrate.toLong() * 3600L) / 8L
        val twentyFourHourBytes = oneHourBytes * 24L

        return Pair(formatBytes(oneHourBytes), formatBytes(twentyFourHourBytes))
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.2f جيجابايت", gb)
            mb >= 1.0 -> String.format(java.util.Locale.getDefault(), "%.2f ميجابايت", mb)
            else -> String.format(java.util.Locale.getDefault(), "%.2f كيلوبايت", kb)
        }
    }
}
