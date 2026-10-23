package com.example

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingFileManager(private val context: Context) {

    companion object {
        const val ROOT_FOLDER_NAME = "تسجيلاتي الصوتية"
    }

    fun getRootDirectory(): File {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val rootDir = File(musicDir, ROOT_FOLDER_NAME)
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        return rootDir
    }

    fun getTodayDirectory(): File {
        val rootDir = getRootDirectory()
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todayDir = File(rootDir, dateString)
        if (!todayDir.exists()) {
            todayDir.mkdirs()
        }
        return todayDir
    }

    fun createNewSegmentFile(): File {
        val todayDir = getTodayDirectory()
        val timeString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_$timeString.m4a"
        return File(todayDir, fileName)
    }

    fun formatFileSize(sizeBytes: Long): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
            else -> String.format(Locale.getDefault(), "%.2f KB", kb)
        }
    }

    fun calculateTotalDirectorySize(): Long {
        val root = getRootDirectory()
        return getFolderSizeRecursive(root)
    }

    private fun getFolderSizeRecursive(file: File): Long {
        var size = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                size += getFolderSizeRecursive(child)
            }
        } else if (file.isFile) {
            size = file.length()
        }
        return size
    }
}
