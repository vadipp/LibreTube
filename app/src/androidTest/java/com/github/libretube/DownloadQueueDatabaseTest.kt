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
        open(name).use { db ->
            assertEquals("Existing download", db.downloadDao().findById("ABCDEFGHIJK")?.download?.title)
            db.downloadQueueDao().put(QueuedDownload("LMNOPQRSTUV", "Queued fixture", "local", 1280, 720, 1, selection = "[]", downloadedBytes = 123))
        }
        open(name).use { db ->
            val saved = db.downloadQueueDao().get("LMNOPQRSTUV")!!
            assertEquals("QUEUED", saved.state)
            assertEquals(123L, saved.downloadedBytes)
            assertEquals("[]", saved.selection)
            assertNotNull(db.downloadDao().findById("ABCDEFGHIJK"))
        }
        context.deleteDatabase(name)
    }

    @Test fun cancellationWinsOverLateProgressAndResolution() = runBlocking {
        val name = "queue-cancel-test"
        context.deleteDatabase(name)
        open(name).use { db ->
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
    }
}
