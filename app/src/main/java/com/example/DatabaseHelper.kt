package com.example

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
                + "$COLUMN_FILE_PATH TEXT, "
                + "$COLUMN_DATE TEXT, "
                + "$COLUMN_START_TIME TEXT, "
                + "$COLUMN_DURATION TEXT, "
                + "$COLUMN_FILE_SIZE TEXT)")
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECORDINGS")
        onCreate(db)
    }

    fun insertRecording(fileName: String, filePath: String, date: String, startTime: String, duration: String, fileSize: String): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_FILE_NAME, fileName)
            put(COLUMN_FILE_PATH, filePath)
            put(COLUMN_DATE, date)
            put(COLUMN_START_TIME, startTime)
            put(COLUMN_DURATION, duration)
            put(COLUMN_FILE_SIZE, fileSize)
        }
        val id = db.insert(TABLE_RECORDINGS, null, values)
        db.close()
        return id
    }

    fun getAllRecordings(): List<RecordingModel> {
        val recordings = mutableListOf<RecordingModel>()
        val selectQuery = "SELECT * FROM $TABLE_RECORDINGS ORDER BY $COLUMN_ID DESC"
        val db = this.readableDatabase
        val cursor: Cursor? = db.rawQuery(selectQuery, null)

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
        db.close()
        return recordings
    }

    fun deleteRecording(id: Long): Int {
        val db = this.writableDatabase
        val rows = db.delete(TABLE_RECORDINGS, "$COLUMN_ID = ?", arrayOf(id.toString()))
        db.close()
        return rows
    }
}
