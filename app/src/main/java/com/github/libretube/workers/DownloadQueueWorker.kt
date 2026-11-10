package com.github.libretube.workers

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PipedMediaPolicy
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.QueuedDownload
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.toLocalDate
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadQueue
import com.github.libretube.helpers.DownloadQueuePolicy
import com.github.libretube.helpers.DownloadSelection
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.repo.DownloadProgressResult
import com.github.libretube.repo.RawByteStreamDownloadProvider
import com.github.libretube.repo.SabrDownloadProvider
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.buffer
import okio.sink
import retrofit2.HttpException
import java.io.IOException
import java.nio.file.StandardOpenOption
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/** One durable drain: resolve A, finish all of A's tracks, then resolve B. */
class DownloadQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = Database.downloadQueueDao()
    private class Waiting(val state: String) : Exception()
    private val client by lazy {
        RetrofitInstance.buildClient().newBuilder()
            .addInterceptor(PipedMediaPolicy { PlayerHelper.usePipedPlayback })
            .addNetworkInterceptor(PipedMediaPolicy { PlayerHelper.usePipedPlayback })
            .callTimeout(java.time.Duration.ofSeconds(20)).build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        DownloadQueue.executionMutex.withLock {
            var waiting = false
            for (entry in dao.all().filter { DownloadQueuePolicy.active(it.state) }) {
                currentCoroutineContext().ensureActive()
                if (entry.state == "CANCELLING") {
                    DownloadQueue.cleanupCancelled(entry.videoId)
                    continue
                }
                // No service/quality prefetch for later items. This child is awaited to completion.
                DownloadQueue.activeVideo = entry.videoId
                try {
                    setForeground(notification(entry.title))
                    supervisorScope {
                        val task = async { download(entry) }
                        DownloadQueue.activeJob = task
                        try { task.await() } finally { DownloadQueue.activeJob = null }
                    }
                    dao.state(entry.videoId, "COMPLETE")
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (dao.get(entry.videoId)?.state == "CANCELLING")
                            DownloadQueue.cleanupCancelled(entry.videoId)
                        else dao.state(entry.videoId, "RETRY")
                    }
                    currentCoroutineContext().ensureActive()
                } catch (e: Waiting) {
                    dao.state(entry.videoId, e.state)
                    waiting = waiting || e.state in setOf("SPACE", "SIZE", "RETRY")
                } catch (e: HttpException) {
                    dao.state(entry.videoId, if (e.code() in listOf(400, 401, 403, 404, 410)) "ATTENTION" else "RETRY")
                    if (e.code() !in listOf(400, 401, 403, 404, 410)) return@withLock Result.retry()
                } catch (_: IOException) {
                    if (dao.get(entry.videoId)?.state == "CANCELLING") DownloadQueue.cleanupCancelled(entry.videoId)
                    else {
                        dao.state(entry.videoId, "RETRY")
                        return@withLock Result.retry()
                    }
                } catch (_: Exception) {
                    dao.state(entry.videoId, "ATTENTION")
                } finally {
                    DownloadQueue.activeProvider = null
                    DownloadQueue.activeCall = null
                    DownloadQueue.activeVideo = null
                }
            }
            if (waiting) Result.retry() else Result.success()
        }
    }

    private suspend fun check(entry: QueuedDownload) {
        currentCoroutineContext().ensureActive()
        if (dao.get(entry.videoId)?.state in listOf("CANCELLING", "CANCELLED")) throw CancellationException()
        if (entry.source != DownloadQueue.source()) throw Waiting("INSTANCE")
    }

    private fun bytes(item: DownloadItem) = runCatching { item.path.fileSize() }.getOrDefault(0)
    private fun available() = StatFs(DownloadHelper.getDownloadDir(applicationContext, DownloadHelper.VIDEO_DIR).toString()).availableBytes

    private suspend fun download(entry: QueuedDownload) {
        check(entry)
        if (com.github.libretube.services.DownloadService.IS_DOWNLOAD_RUNNING) throw Waiting("RETRY")
        val previous = entry.selection?.let { JsonHelper.json.decodeFromString<List<PipedStream>>(it) }
        val saved = Database.downloadDao().findById(entry.videoId)
        val oldItems = saved?.downloadItems.orEmpty()
        if (previous != null && DownloadQueuePolicy.complete(saved, previous)) return
        if (entry.state == "SPACE" && entry.totalBytes > 0 &&
            !DownloadQueuePolicy.fits(available(), (entry.totalBytes - oldItems.sumOf(::bytes)).coerceAtLeast(0))) throw Waiting("SPACE")
        dao.state(entry.videoId, "RESOLVING")
        val info = MediaServiceRepository.instance.getDownloadStreams(entry.videoId)
        check(entry)
        if (info.isLive) throw Waiting("ATTENTION")
        val videos = info.videoStreams.filter { !it.url.isNullOrBlank() && !it.format.orEmpty().contains("HLS") }
        val audios = info.audioStreams.filter { !it.url.isNullOrBlank() }
            .sortedWith(compareBy<PipedStream> { PlayerHelper.getFullAudioRoleFlags(0, it.audioTrackType.orEmpty()) }
                .thenByDescending { it.bitrate ?: 0 })
        val selected = if (previous == null) {
            val video = DownloadSelection.videoIndex(videos, entry.screenWidth, entry.screenHeight)?.let(videos::get)
            listOfNotNull(video, audios.firstOrNull())
        } else previous.map { saved ->
            (if (saved.mimeType?.startsWith("audio/") == true) audios else videos)
                .firstOrNull { it.format == saved.format && it.quality == saved.quality && it.audioTrackLocale == saved.audioTrackLocale }
                ?: throw Waiting("RETRY")
        }
        if (selected.isEmpty()) throw Waiting("ATTENTION")
        val fresh = selected.map { stream ->
            val length = stream.contentLength.takeIf { it > 0 } ?: probeSize(stream)
            stream.copy(contentLength = length ?: -1,
                lastModified = stream.url?.toHttpUrlOrNull()?.queryParameter("lmt")?.toLongOrNull() ?: stream.lastModified)
        }
        val total = DownloadSelection.estimatedBytes(fresh, info.duration, false) ?: throw Waiting("SIZE")
        // Plans contain representation identity, never an expiring signed media URL.
        val encoded = JsonHelper.json.encodeToString(fresh.map { it.copy(url = null) })
        val completed = fresh.withIndex().sumOf { (index, stream) ->
            if (previous == null || !DownloadQueuePolicy.sameRepresentation(previous[index], stream)) 0L
            else oldItems.filter { it.format == stream.format && it.quality == stream.quality && it.language == stream.audioTrackLocale }.sumOf(::bytes)
        }
        if (!DownloadQueuePolicy.fits(available(), (total - completed).coerceAtLeast(0))) {
            // Keep the OLD identity until any incompatible partial file has actually been reset.
            dao.plan(entry.videoId, entry.selection ?: encoded, info.title, total)
            throw Waiting("SPACE")
        }
        check(entry)
        val items = mutableListOf<DownloadItem>()
        Database.withTransaction {
            check(entry)
            if (Database.downloadDao().findById(entry.videoId) == null) {
                val thumbnail = DownloadHelper.getDownloadDir(applicationContext, DownloadHelper.THUMBNAIL_DIR) / entry.videoId
                Database.downloadDao().insertDownload(Download(entry.videoId, info.title, info.description, info.uploader,
                    info.duration, info.uploadTimestamp?.toLocalDate(), thumbnail, info.uploaderUrl, info.views, info.likes, info.dislikes))
                for (chapter in info.chapters) Database.downloadDao().insertDownloadChapter(
                    DownloadChapter(videoId = entry.videoId, name = chapter.title, start = chapter.start, thumbnailUrl = chapter.image))
            }
            for ((index, stream) in fresh.withIndex()) {
                val type = if (stream.mimeType?.startsWith("audio/") == true) FileType.AUDIO else FileType.VIDEO
                val old = oldItems.firstOrNull { it.type == type && it.format == stream.format && it.quality == stream.quality && it.language == stream.audioTrackLocale }
                val item = old ?: stream.toDownloadItem(type, entry.videoId).apply {
                    path = DownloadHelper.getDownloadDir(applicationContext,
                        if (type == FileType.AUDIO) DownloadHelper.AUDIO_DIR else DownloadHelper.VIDEO_DIR) / fileName
                }
                if (old != null && (previous == null || !DownloadQueuePolicy.sameRepresentation(previous[index], stream))) {
                    item.path.deleteIfExists()
                    item.currentDownloadPositionMillis = null
                }
                if (!item.path.exists()) item.path.createFile()
                item.downloadSize = stream.contentLength.takeIf { it > 0 } ?: item.downloadSize
                if (old == null) item.id = Database.downloadDao().insertDownloadItem(item).toInt()
                else Database.downloadDao().updateDownloadItem(item)
                items.add(item)
            }
            dao.plan(entry.videoId, encoded, info.title, total)
        }
        dao.state(entry.videoId, "DOWNLOADING")
        var lastUpdate = 0L
        for ((index, item) in items.withIndex()) {
            check(entry)
            if (item.isFinished) continue
            val stream = fresh[index]
            val provider = if (stream.url?.startsWith("http") == true) RawByteStreamDownloadProvider(stream.url!!.toHttpUrl())
                else {
                    if (PlayerHelper.usePipedPlayback) throw Waiting("ATTENTION")
                    SabrDownloadProvider(item, info, stream)
                }
            DownloadQueue.activeProvider = provider
            try {
                item.path.sink(StandardOpenOption.APPEND).buffer().use { sink ->
                    while (!item.isFinished) {
                        check(entry)
                        val remaining = (total - items.sumOf(::bytes)).coerceAtLeast(0)
                        if (!DownloadQueuePolicy.fits(available(), remaining)) throw Waiting("SPACE")
                        when (provider.downloadNextChunk(item, sink)) {
                            DownloadProgressResult.Failed -> throw IOException("Download interrupted")
                            DownloadProgressResult.DownloadComplete -> {
                                sink.flush()
                                if (item.downloadSize <= 0) item.downloadSize = bytes(item)
                                Database.downloadDao().updateDownloadItem(item)
                                break
                            }
                            is DownloadProgressResult.Progressed -> {
                                if (System.currentTimeMillis() - lastUpdate >= 1000) {
                                    Database.downloadDao().updateDownloadItem(item)
                                    dao.progress(entry.videoId, items.sumOf(::bytes), total)
                                    setForeground(notification(info.title, items.sumOf(::bytes), total))
                                    lastUpdate = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                }
                Database.downloadDao().updateDownloadItem(item)
            } finally { DownloadQueue.activeProvider = null }
        }
        check(entry)
        if (items.any { !it.isFinished }) throw IOException("Incomplete media")
        dao.progress(entry.videoId, items.sumOf(::bytes), items.sumOf { it.downloadSize })
        val thumbnail = Database.downloadDao().findById(entry.videoId)?.download?.thumbnailPath
        if (thumbnail != null) {
            try { ImageHelper.downloadImage(applicationContext, info.thumbnailUrl, thumbnail) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Offline media does not depend on the thumbnail. */ }
        }
    }

    private suspend fun probeSize(stream: PipedStream): Long? {
        val url = stream.url?.takeIf { it.startsWith("http") } ?: return null
        return try {
            val call = client.newCall(Request.Builder().url(url).head().build())
            DownloadQueue.activeCall = call
            call.execute().use { response ->
                if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } else null
            }
        } catch (_: IOException) { null } finally { DownloadQueue.activeCall = null }
    }

    private fun notification(title: String, downloaded: Long = 0, total: Long = -1): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntentCompat.getActivity(applicationContext, 4200, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT, false)
        val builder = NotificationCompat.Builder(applicationContext, LibreTubeApp.DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_download).setContentTitle(title.ifBlank { applicationContext.getString(R.string.download_queue) })
            .setContentText(applicationContext.getString(R.string.download_queue))
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(pending)
            .setProgress(100, if (total > 0) (downloaded.toDouble() / total * 100).toInt() else 0, total <= 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(4200, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4200, builder.build())
    }
}
