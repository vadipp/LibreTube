package com.github.libretube

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.libretube.db.AppDatabase
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.QueuedDownload
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadQueueDatabaseTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(DatabaseHolder.MIGRATION_25_26).build()

    private suspend fun withDatabase(name: String, block: suspend (AppDatabase) -> Unit) {
        val database = open(name)
        try { block(database) } finally { database.close() }
    }

    @Test fun migrationPreservesDownloadsAndQueueSurvivesDatabaseReopen() = runBlocking {
        val name = "queue-migration-test"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open("database-25.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO download(videoId,title,description,uploader,duration,views,likes,dislikes) VALUES ('ABCDEFGHIJK','Existing download','','',60,0,0,0)")
            old.version = 25
        }
        val media = com.github.libretube.helpers.DownloadHelper.getDownloadDir(context,
            com.github.libretube.helpers.DownloadHelper.VIDEO_DIR).resolve("migration-fixture.bin")
        java.nio.file.Files.write(media, byteArrayOf(1, 2, 3))
        withDatabase(name) { db ->
            db.downloadDao().insertDownloadItem(com.github.libretube.db.obj.DownloadItem(
                type = com.github.libretube.enums.FileType.VIDEO, videoId = "ABCDEFGHIJK",
                fileName = "migration-fixture.bin", path = media, downloadSize = 3))
            assertEquals("Existing download", db.downloadDao().findById("ABCDEFGHIJK")?.download?.title)
            db.downloadQueueDao().put(QueuedDownload("LMNOPQRSTUV", "Queued fixture", "local", 1280, 720, 1, selection = "[]", downloadedBytes = 123))
        }
        withDatabase(name) { db ->
            val saved = db.downloadQueueDao().get("LMNOPQRSTUV")!!
            assertEquals("QUEUED", saved.state)
            assertEquals(123L, saved.downloadedBytes)
            assertEquals("[]", saved.selection)
            assertTrue(db.downloadDao().findById("ABCDEFGHIJK")!!.downloadItems.single().isFinished)
        }
        java.nio.file.Files.deleteIfExists(media)
        context.deleteDatabase(name)
        Unit
    }

    @Test fun cancellationWinsOverLateProgressAndResolution() = runBlocking {
        val name = "queue-cancel-test"
        context.deleteDatabase(name)
        withDatabase(name) { db ->
            val dao = db.downloadQueueDao()
            dao.put(QueuedDownload("ABCDEFGHIJK", "Fixture", "local", 1280, 720, 1))
            dao.requestCancel("ABCDEFGHIJK")
            dao.state("ABCDEFGHIJK", "DOWNLOADING")
            dao.plan("ABCDEFGHIJK", "[]", "Late metadata", 100)
            dao.progress("ABCDEFGHIJK", 10, 100)
            assertEquals("CANCELLING", dao.get("ABCDEFGHIJK")?.state)
            assertNull(dao.get("ABCDEFGHIJK")?.selection)
            dao.cancelled("ABCDEFGHIJK")
            dao.state("ABCDEFGHIJK", "COMPLETE")
            assertEquals("CANCELLED", dao.get("ABCDEFGHIJK")?.state)
        }
        context.deleteDatabase(name)
        Unit
    }
}
