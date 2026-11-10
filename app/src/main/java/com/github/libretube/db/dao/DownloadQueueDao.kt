package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.github.libretube.db.obj.QueuedDownload
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {
    @Query("SELECT * FROM queuedDownload ORDER BY createdAt, videoId")
    fun observe(): Flow<List<QueuedDownload>>

    @Query("SELECT * FROM queuedDownload ORDER BY createdAt, videoId")
    suspend fun all(): List<QueuedDownload>

    @Query("SELECT * FROM queuedDownload WHERE videoId = :id")
    suspend fun get(id: String): QueuedDownload?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: QueuedDownload)

    // Cancellation wins over late resolution/progress callbacks.
    @Query("UPDATE queuedDownload SET state = :state WHERE videoId = :id AND state NOT IN ('CANCELLING', 'CANCELLED')")
    suspend fun state(id: String, state: String)

    @Query("UPDATE queuedDownload SET selection = :selection, title = :title, totalBytes = :total WHERE videoId = :id AND state NOT IN ('CANCELLING', 'CANCELLED')")
    suspend fun plan(id: String, selection: String, title: String, total: Long)

    @Query("UPDATE queuedDownload SET downloadedBytes = :downloaded, totalBytes = :total WHERE videoId = :id AND state NOT IN ('CANCELLING', 'CANCELLED')")
    suspend fun progress(id: String, downloaded: Long, total: Long)

    @Query("UPDATE queuedDownload SET state = 'CANCELLING' WHERE videoId = :id AND state != 'COMPLETE'")
    suspend fun requestCancel(id: String)

    @Query("UPDATE queuedDownload SET state = 'CANCELLED' WHERE videoId = :id AND state = 'CANCELLING'")
    suspend fun cancelled(id: String)

    @Query("DELETE FROM queuedDownload WHERE videoId = :id AND state IN ('COMPLETE', 'CANCELLED')")
    suspend fun forgetTerminal(id: String)

    @Query("UPDATE queuedDownload SET state = 'QUEUED' WHERE videoId = :id AND state IN ('SPACE', 'SIZE', 'RETRY', 'ATTENTION', 'INSTANCE')")
    suspend fun retry(id: String)
}
