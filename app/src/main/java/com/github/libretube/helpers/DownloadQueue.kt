package com.github.libretube.helpers

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.QueuedDownload
import com.github.libretube.repo.DownloadProvider
import com.github.libretube.workers.DownloadQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists

object DownloadQueue {
    private const val WORK = "default-download-queue"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val executionMutex = Mutex()
    @Volatile var activeVideo: String? = null
    @Volatile var activeJob: Job? = null
    @Volatile var activeCall: okhttp3.Call? = null
    @Volatile var activeProvider: DownloadProvider? = null

    fun source() = if (PlayerHelper.usePipedPlayback)
        "piped:" + RetrofitInstance.pipedAuthUrl + ":" + PreferenceHelper.getUsername() else "local"

    suspend fun enqueue(context: Context, videoId: String, title: String, width: Int, height: Int) {
        if (DownloadProgressTracker.downloads.value.containsKey(videoId)) return
        Database.withTransaction {
            val existing = Database.downloadQueueDao().get(videoId)
            if (existing != null && DownloadQueuePolicy.active(existing.state)) return@withTransaction
            if ((existing == null || existing.state == "COMPLETE") && DownloadQueuePolicy.complete(Database.downloadDao().findById(videoId))) return@withTransaction
            Database.downloadQueueDao().put(QueuedDownload(videoId, title, source(), width, height, System.currentTimeMillis()))
        }
        ensureRecovery(context)
        schedule(context)
    }

    fun schedule(context: Context, restart: Boolean = false) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Appending also covers enqueue racing with an exiting drain worker.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun recover(context: Context) {
        // WorkManager persists the actual work; this also repairs a process death between
        // inserting the application queue row and enqueuing the WorkRequest.
        scope.launch {
            for (entry in Database.downloadQueueDao().all().filter { it.state == "CANCELLING" }) cleanupCancelled(entry.videoId)
            if (Database.downloadQueueDao().all().any { DownloadQueuePolicy.active(it.state) }) {
                ensureRecovery(context)
                schedule(context)
            }
        }
    }

    private fun ensureRecovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "$WORK-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DownloadQueueRecoveryWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    suspend fun cancel(videoId: String) {
        Database.downloadQueueDao().requestCancel(videoId)
        if (activeVideo == videoId) {
            activeCall?.cancel()
            activeProvider?.cancel()
            activeJob?.cancel()
        } else cleanupCancelled(videoId)
    }

    suspend fun cleanupCancelled(videoId: String) {
        val saved = Database.downloadDao().findById(videoId)
        for (item in saved?.downloadItems.orEmpty().filter { !it.isFinished }) {
            item.path.deleteIfExists()
            Database.downloadDao().deleteDownloadItemById(item.id)
        }
        if (saved != null && saved.downloadItems.none { it.isFinished }) {
            DownloadHelper.deleteDownloadIncludingFiles(saved)
        }
        Database.downloadQueueDao().cancelled(videoId)
        DownloadProgressTracker.remove(videoId)
    }
}

class DownloadQueueRecoveryWorker(context: Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Database.downloadQueueDao().all().any { DownloadQueuePolicy.active(it.state) })
            DownloadQueue.schedule(applicationContext)
        return Result.success()
    }
}
