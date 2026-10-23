package com.example

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingFileManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingFileManager"
        const val ROOT_FOLDER_NAME = "تسجيلاتي الصوتية"
    }

    /**
     * Returns the primary public directory visible in the device's Files (الملفات) app.
     * Path: /storage/emulated/0/Recordings/تسجيلاتي الصوتية/ (or fallback to Music/تسجيلاتي الصوتية).
     * This ensures the files are in standard user-accessible storage and NEVER hidden in 'Android/data'.
     */
    fun getRootDirectory(): File {
        val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        } else {
            val recDir = File(Environment.getExternalStorageDirectory(), "Recordings")
            if (recDir.exists() || recDir.mkdirs()) {
                recDir
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            }
        }

        val targetDir = File(baseDir, ROOT_FOLDER_NAME)
        if (!targetDir.exists()) {
            try {
                targetDir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Could not mkdirs for public recordings folder directly", e)
            }
        }
        return targetDir
    }

    /**
     * Staging file for active recording.
     * Prevents MediaRecorder crashes and avoids scoped-storage locking during live capture.
     */
    fun createStagingFile(extension: String = ".m4a"): File {
        val stagingDir = File(context.cacheDir, "recording_staging")
        if (!stagingDir.exists()) {
            stagingDir.mkdirs()
        }
        val cleanExt = if (extension.startsWith(".")) extension else ".$extension"
        val timeString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(stagingDir, "REC_${timeString}$cleanExt")
    }

    /**
     * Backwards-compatible alias for creating segment files.
     */
    fun createNewSegmentFile(extension: String = ".m4a"): File {
        return createStagingFile(extension)
    }

    /**
     * Publishes a completed recording file to the public 'Recordings/تسجيلاتي الصوتية' folder
     * accessible by the system Files app (الملفات), WhatsApp, and third-party media players.
     */
    fun publishRecordingToPublicStorage(
        sourceFile: File,
        desiredFileName: String = sourceFile.name,
        mimeType: String = getMimeTypeForExtension(sourceFile.extension)
    ): File {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return sourceFile
        }

        val publicDir = getRootDirectory()
        var destinationFile = File(publicDir, desiredFileName)

        var publishedViaDirectFile = false
        try {
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            if (publicDir.exists()) {
                // Ensure unique name if a duplicate exists
                if (destinationFile.exists() && destinationFile.absolutePath != sourceFile.absolutePath) {
                    val nameWithoutExt = desiredFileName.substringBeforeLast(".")
                    val ext = desiredFileName.substringAfterLast(".", "")
                    destinationFile = File(publicDir, "${nameWithoutExt}_${System.currentTimeMillis()}.${ext}")
                }

                sourceFile.copyTo(destinationFile, overwrite = true)
                if (destinationFile.exists() && destinationFile.length() > 0) {
                    publishedViaDirectFile = true
                    try { sourceFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct copy to public folder failed, trying MediaStore insert", e)
        }

        // If direct file copy failed or scoped storage requires MediaStore insert on Android 10+
        if (!publishedViaDirectFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val relativePath = "Recordings/$ROOT_FOLDER_NAME/"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, desiredFileName)
                    put(MediaStore.Audio.Media.TITLE, desiredFileName.substringBeforeLast("."))
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri: Uri? = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(output)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)

                    // Retrieve physical file path
                    val resolvedPath = queryFilePathFromUri(uri) ?: destinationFile.absolutePath
                    val resolvedFile = File(resolvedPath)
                    try { sourceFile.delete() } catch (_: Exception) {}
                    destinationFile = resolvedFile
                    publishedViaDirectFile = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore publishing failed", e)
            }
        }

        val finalFile = if (publishedViaDirectFile && destinationFile.exists()) destinationFile else sourceFile

        // Notify MediaScanner immediately so Android Files app and system players index it
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType)
            ) { path, scannedUri ->
                Log.d(TAG, "MediaScanner indexed: $path -> $scannedUri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error triggering MediaScanner", e)
        }

        return finalFile
    }

    private fun queryFilePathFromUri(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Automatically migrates recordings previously trapped in the restricted 'Android/data/.../files'
     * directory into the public 'Recordings/تسجيلاتي الصوتية' folder so the user can see them in the Files app.
     */
    fun migrateLegacyInternalRecordings(dbHelper: DatabaseHelper): Int {
        var count = 0
        try {
            val legacyMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val legacyDir = if (legacyMusicDir != null) File(legacyMusicDir, ROOT_FOLDER_NAME) else null
            if (legacyDir == null || !legacyDir.exists() || !legacyDir.isDirectory) return 0

            val publicDir = getRootDirectory()
            if (!publicDir.exists()) publicDir.mkdirs()

            legacyDir.walkTopDown().filter { file ->
                file.isFile && file.length() > 256L &&
                (file.name.endsWith(".m4a", ignoreCase = true) ||
                 file.name.endsWith(".3gp", ignoreCase = true) ||
                 file.name.endsWith(".aac", ignoreCase = true))
            }.forEach { oldFile ->
                try {
                    val newFile = File(publicDir, oldFile.name)
                    if (!newFile.exists()) {
                        oldFile.copyTo(newFile, overwrite = true)
                    }
                    if (newFile.exists() && newFile.length() > 0) {
                        dbHelper.updateRecordingPathByOldPath(oldFile.absolutePath, newFile.absolutePath)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(newFile.absolutePath),
                            arrayOf(getMimeTypeForExtension(newFile.extension)),
                            null
                        )
                        oldFile.delete()
                        count++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error migrating ${oldFile.name}", e)
                }
            }

            try { legacyDir.deleteRecursively() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration error", e)
        }
        return count
    }

    fun renameRecordingFile(oldFilePath: String, newCustomName: String): Pair<String, String>? {
        val oldFile = File(oldFilePath)
        if (!oldFile.exists() || !oldFile.isFile) return null

        val currentExt = if (oldFile.name.contains(".")) {
            oldFile.name.substring(oldFile.name.lastIndexOf("."))
        } else {
            ".m4a"
        }

        var sanitized = newCustomName.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u0600-\\u06FF]"), "_")
        if (sanitized.endsWith(currentExt, ignoreCase = true)) {
            sanitized = sanitized.substring(0, sanitized.length - currentExt.length)
        }
        val finalFileName = "$sanitized$currentExt"
        val newFile = File(oldFile.parentFile, finalFileName)

        return if (oldFile.renameTo(newFile)) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(newFile.absolutePath, oldFile.absolutePath),
                arrayOf(getMimeTypeForExtension(currentExt), getMimeTypeForExtension(currentExt)),
                null
            )
            Pair(finalFileName, newFile.absolutePath)
        } else {
            null
        }
    }

    fun getMimeTypeForExtension(extension: String): String {
        val clean = extension.lowercase(Locale.US).removePrefix(".")
        return when (clean) {
            "3gp", "3gpp" -> "audio/3gpp"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }
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

    fun getAvailableStorageBytes(): Long {
        return try {
            val root = getRootDirectory()
            val stat = StatFs(root.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
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
