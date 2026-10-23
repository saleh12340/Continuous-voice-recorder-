package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("المسجل الصوتي المستمر", appName)
    }

    @Test
    fun `public recordings directory is in public storage and not in Android data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = RecordingFileManager(context)
        val rootDir = fileManager.getRootDirectory()

        assertTrue("Root dir name should match", rootDir.name.contains("تسجيلاتي الصوتية"))
        assertFalse("Root dir should NOT be inside Android/data", rootDir.absolutePath.contains("Android/data"))
    }

    @Test
    fun `staging file and publishing workflow`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = RecordingFileManager(context)

        val stagingFile = fileManager.createStagingFile(".m4a")
        assertTrue(stagingFile.name.endsWith(".m4a"))
        stagingFile.writeBytes(ByteArray(1024) { 1 })

        val published = fileManager.publishRecordingToPublicStorage(stagingFile)
        assertTrue(published.exists())
        assertEquals(1024L, published.length())
        assertFalse("Published file should NOT be inside Android/data", published.absolutePath.contains("Android/data"))
    }

    @Test
    fun `database helper insert and update path`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = DatabaseHelper(context)

        val id = dbHelper.insertRecording(
            fileName = "REC_test.m4a",
            filePath = "/storage/emulated/0/Recordings/تسجيلاتي الصوتية/REC_test.m4a",
            date = "2026-10-23",
            startTime = "12:00:00",
            duration = "01:00",
            fileSize = "1.00 MB"
        )
        assertTrue(id > 0)

        val success = dbHelper.updateRecordingPathByOldPath(
            oldFilePath = "/storage/emulated/0/Recordings/تسجيلاتي الصوتية/REC_test.m4a",
            newFilePath = "/storage/emulated/0/Recordings/تسجيلاتي الصوتية/REC_test_migrated.m4a"
        )
        assertTrue(success)

        val recordings = dbHelper.getAllRecordings()
        val found = recordings.find { it.id == id }
        assertEquals("/storage/emulated/0/Recordings/تسجيلاتي الصوتية/REC_test_migrated.m4a", found?.filePath)
    }
}
