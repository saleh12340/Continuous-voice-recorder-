package com.example

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingRecoveryManager(private val context: Context, private val dbHelper: DatabaseHelper) {

    companion object {
        private const val TAG = "RecordingRecovery"
    }

    fun performRecoveryScan(): Int {
        val fileManager = RecordingFileManager(context)

        // 1. Migrate any legacy recordings from Android/data/ to the public Recordings directory
        val migrated = fileManager.migrateLegacyInternalRecordings(dbHelper)
        if (migrated > 0) {
            Log.i(TAG, "Migrated $migrated recordings to public Files directory")
        }

        // 2. Scan public directory and sync database
        val rootDir = fileManager.getRootDirectory()
        if (!rootDir.exists()) return migrated

        val existingRecords = dbHelper.getAllRecordings()
        val existingPaths = existingRecords.map { it.filePath }.toSet()

        var recoveredCount = 0

        fun scanFolder(folder: File) {
            folder.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    scanFolder(file)
                } else if (file.isFile && file.length() > 256L &&
                    (file.name.endsWith(".m4a", ignoreCase = true) ||
                     file.name.endsWith(".3gp", ignoreCase = true) ||
                     file.name.endsWith(".aac", ignoreCase = true))) {

                    val absolutePath = file.absolutePath
                    if (!existingPaths.contains(absolutePath)) {
                        val fileName = file.name
                        val fileSizeFormatted = fileManager.formatFileSize(file.length())
                        // Approximate duration from file size if bitrate ~32kbps (4000 bytes per second)
                        val estimatedDurationMs = (file.length() * 1000L) / 4000L
                        val durationFormatted = formatDuration(estimatedDurationMs)
                        val lastModified = file.lastModified()
                        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastModified))
                        val startTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastModified))

                        dbHelper.insertRecording(
                            fileName = fileName,
                            filePath = absolutePath,
                            date = dateStr,
                            startTime = startTime,
                            duration = durationFormatted,
                            fileSize = fileSizeFormatted
                        )

                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(absolutePath),
                            arrayOf(fileManager.getMimeTypeForExtension(file.extension)),
                            null
                        )
                        recoveredCount++
                    }
                }
            }
        }

        scanFolder(rootDir)
        return recoveredCount + migrated
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
