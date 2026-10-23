package com.example

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class RecordingModel(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val date: String,
    val startTime: String,
    val duration: String,
    val fileSize: String
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DATABASE_NAME = "audio_recordings.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_RECORDINGS = "recordings"
        const val COLUMN_ID = "id"
        const val COLUMN_FILE_NAME = "file_name"
        const val COLUMN_FILE_PATH = "file_path"
        const val COLUMN_DATE = "date"
        const val COLUMN_START_TIME = "start_time"
        const val COLUMN_DURATION = "duration"
        const val COLUMN_FILE_SIZE = "file_size"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = ("CREATE TABLE $TABLE_RECORDINGS ("
                + "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "$COLUMN_FILE_NAME TEXT, "
                + "$COLUMN_FILE_PATH TEXT UNIQUE, "
                + "$COLUMN_DATE TEXT, "
                + "$COLUMN_START_TIME TEXT, "
                + "$COLUMN_DURATION TEXT, "
                + "$COLUMN_FILE_SIZE TEXT)")
        db.execSQL(createTableQuery)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recordings_date ON $TABLE_RECORDINGS ($COLUMN_DATE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECORDINGS")
        onCreate(db)
    }

    fun insertRecording(
        fileName: String,
        filePath: String,
        date: String,
        startTime: String,
        duration: String,
        fileSize: String
    ): Long {
        val db = this.writableDatabase
        var id = -1L
        try {
            val values = ContentValues().apply {
                put(COLUMN_FILE_NAME, fileName)
                put(COLUMN_FILE_PATH, filePath)
                put(COLUMN_DATE, date)
                put(COLUMN_START_TIME, startTime)
                put(COLUMN_DURATION, duration)
                put(COLUMN_FILE_SIZE, fileSize)
            }
            id = db.insertWithOnConflict(TABLE_RECORDINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting recording: $fileName", e)
        }
        return id
    }

    fun getAllRecordings(): List<RecordingModel> {
        return searchRecordings("", null)
    }

    fun searchRecordings(query: String, dateFilter: String? = null): List<RecordingModel> {
        val recordings = mutableListOf<RecordingModel>()
        val db = this.readableDatabase

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (query.isNotBlank()) {
            selectionParts.add("($COLUMN_FILE_NAME LIKE ? OR $COLUMN_DATE LIKE ?)")
            selectionArgs.add("%$query%")
            selectionArgs.add("%$query%")
        }

        if (!dateFilter.isNullOrBlank() && dateFilter != "الكل") {
            selectionParts.add("$COLUMN_DATE = ?")
            selectionArgs.add(dateFilter)
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val cursor: Cursor? = try {
            db.query(
                TABLE_RECORDINGS,
                null,
                selection,
                args,
                null,
                null,
                "$COLUMN_ID DESC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying recordings", e)
            null
        }

        cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(COLUMN_ID)
                val nameIndex = it.getColumnIndex(COLUMN_FILE_NAME)
                val pathIndex = it.getColumnIndex(COLUMN_FILE_PATH)
                val dateIndex = it.getColumnIndex(COLUMN_DATE)
                val timeIndex = it.getColumnIndex(COLUMN_START_TIME)
                val durationIndex = it.getColumnIndex(COLUMN_DURATION)
                val sizeIndex = it.getColumnIndex(COLUMN_FILE_SIZE)

                do {
                    val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                    val fileName = if (nameIndex != -1) it.getString(nameIndex) else ""
                    val filePath = if (pathIndex != -1) it.getString(pathIndex) else ""
                    val date = if (dateIndex != -1) it.getString(dateIndex) else ""
                    val startTime = if (timeIndex != -1) it.getString(timeIndex) else ""
                    val duration = if (durationIndex != -1) it.getString(durationIndex) else "00:00"
                    val fileSize = if (sizeIndex != -1) it.getString(sizeIndex) else "0 KB"

                    recordings.add(RecordingModel(id, fileName, filePath, date, startTime, duration, fileSize))
                } while (it.moveToNext())
            }
        }
        return recordings
    }

    fun getDistinctDates(): List<String> {
        val dates = mutableListOf<String>()
        val db = this.readableDatabase
        val cursor = try {
            db.rawQuery("SELECT DISTINCT $COLUMN_DATE FROM $TABLE_RECORDINGS ORDER BY $COLUMN_DATE DESC", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching distinct dates", e)
            null
        }
        cursor?.use {
            if (it.moveToFirst()) {
                do {
                    val date = it.getString(0)
                    if (!date.isNullOrBlank()) {
                        dates.add(date)
                    }
                } while (it.moveToNext())
            }
        }
        return dates
    }

    fun updateRecordingNameAndPath(id: Long, newFileName: String, newFilePath: String): Boolean {
        val db = this.writableDatabase
        return try {
            val values = ContentValues().apply {
                put(COLUMN_FILE_NAME, newFileName)
                put(COLUMN_FILE_PATH, newFilePath)
            }
            val rows = db.update(TABLE_RECORDINGS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating recording $id", e)
            false
        }
    }

    fun deleteRecording(id: Long): Int {
        val db = this.writableDatabase
        return try {
            db.delete(TABLE_RECORDINGS, "$COLUMN_ID = ?", arrayOf(id.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording $id", e)
            0
        }
    }
}
