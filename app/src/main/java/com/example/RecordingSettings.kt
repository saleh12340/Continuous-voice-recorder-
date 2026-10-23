package com.example

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder

enum class AudioFormatOption(
    val id: String,
    val titleAr: String,
    val extension: String,
    val mimeType: String,
    val outputFormat: Int,
    val audioEncoder: Int,
    val bitrate: Int,
    val sampleRate: Int
) {
    M4A_AAC_ECONOMY(
        "m4a_economy",
        "M4A (AAC 32k) - توفير عالي + متوافق مع واتساب",
        ".m4a",
        "audio/mp4",
        MediaRecorder.OutputFormat.MPEG_4,
        MediaRecorder.AudioEncoder.AAC,
        32000,
        44100
    ),
    M4A_AAC_STANDARD(
        "m4a_standard",
        "M4A (AAC 64k) - وضوح متوازن ونقي",
        ".m4a",
        "audio/mp4",
        MediaRecorder.OutputFormat.MPEG_4,
        MediaRecorder.AudioEncoder.AAC,
        64000,
        44100
    ),
    M4A_AAC_HIGH(
        "m4a_high",
        "M4A (AAC 128k) - دقة استوديو عالية",
        ".m4a",
        "audio/mp4",
        MediaRecorder.OutputFormat.MPEG_4,
        MediaRecorder.AudioEncoder.AAC,
        128000,
        44100
    ),
    THREE_GP_AMR_WB(
        "3gp_amr_wb",
        "3GP (AMR-WB 24k) - ضغط فائق جداً ومخصص للكلام",
        ".3gp",
        "audio/3gpp",
        MediaRecorder.OutputFormat.THREE_GPP,
        MediaRecorder.AudioEncoder.AMR_WB,
        23850,
        16000
    )
}

enum class SegmentDurationOption(val minutes: Int, val titleAr: String) {
    MINUTES_15(15, "15 دقيقة"),
    MINUTES_30(30, "30 دقيقة (الافتراضي)"),
    MINUTES_60(60, "ساعة كاملة (60 دقيقة)")
}

class RecordingSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("audio_recorder_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FORMAT_OPTION = "key_format_option"
        private const val KEY_SEGMENT_DURATION = "key_segment_duration"
        private const val KEY_AUTO_RESUME_CALL = "key_auto_resume_call"
    }

    fun getSelectedAudioFormat(): AudioFormatOption {
        val id = prefs.getString(KEY_FORMAT_OPTION, AudioFormatOption.M4A_AAC_ECONOMY.id)
        return AudioFormatOption.values().find { it.id == id } ?: AudioFormatOption.M4A_AAC_ECONOMY
    }

    fun saveAudioFormat(format: AudioFormatOption) {
        prefs.edit().putString(KEY_FORMAT_OPTION, format.id).apply()
    }

    fun getSegmentDurationOption(): SegmentDurationOption {
        val minutes = prefs.getInt(KEY_SEGMENT_DURATION, 30)
        return SegmentDurationOption.values().find { it.minutes == minutes } ?: SegmentDurationOption.MINUTES_30
    }

    fun saveSegmentDurationOption(option: SegmentDurationOption) {
        prefs.edit().putInt(KEY_SEGMENT_DURATION, option.minutes).apply()
    }

    fun isAutoResumeAfterCall(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RESUME_CALL, true)
    }

    fun setAutoResumeAfterCall(enable: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RESUME_CALL, enable).apply()
    }

    fun calculateEstimatedSizes(bitrate: Int): Pair<String, String> {
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
