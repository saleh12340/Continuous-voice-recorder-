package com.example

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingRecoveryManager(private val context: Context, private val dbHelper: DatabaseHelper) {

    fun performRecoveryScan(): Int {
        val fileManager = RecordingFileManager(context)
        val rootDir = fileManager.getRootDirectory()
        if (!rootDir.exists()) return 0

        val existingRecords = dbHelper.getAllRecordings()
        val existingPaths = existingRecords.map { it.filePath }.toSet()

        var recoveredCount = 0

        // Traverse date folders (YYYY-MM-DD)
        rootDir.listFiles()?.forEach { dateFolder ->
            if (dateFolder.isDirectory) {
                val dateStr = dateFolder.name
                dateFolder.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0 && (file.name.endsWith(".m4a") || file.name.endsWith(".3gp") || file.name.endsWith(".aac"))) {
                        val absolutePath = file.absolutePath
                        if (!existingPaths.contains(absolutePath)) {
                            // Missing from DB, recover it!
                            val fileName = file.name
                            val fileSizeFormatted = fileManager.formatFileSize(file.length())
                            // Estimate duration roughly from file size if bitrate ~16kbps (2000 bytes per second)
                            val estimatedDurationMs = (file.length() * 1000L) / 2000L
                            val durationFormatted = formatDuration(estimatedDurationMs)
                            val startTime = "00:00:00"

                            dbHelper.insertRecording(
                                fileName = fileName,
                                filePath = absolutePath,
                                date = dateStr,
                                startTime = startTime,
                                duration = durationFormatted,
                                fileSize = fileSizeFormatted
                            )
                            recoveredCount++
                        }
                    }
                }
            }
        }
        return recoveredCount
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
}
