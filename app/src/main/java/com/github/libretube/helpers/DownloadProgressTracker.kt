package com.github.libretube.helpers

import com.github.libretube.obj.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process download UI state. The service remains the owner of files and cancellation. */
object DownloadProgressTracker {
    data class Item(val downloaded: Long = 0, val total: Long = -1, val complete: Boolean = false)
    data class Progress(val preparing: Boolean = true, val items: Map<Int, Item> = emptyMap()) {
        val percent: Int? get() {
            if (preparing || items.isEmpty() || items.values.any { it.total <= 0 }) return null
            val total = items.values.sumOf { it.total.toDouble() }
            val downloaded = items.values.sumOf { it.downloaded.toDouble().coerceAtMost(it.total.toDouble()) }
            return (downloaded / total * 100).toInt().coerceIn(0, 100)
        }
    }
    private val mutable = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val downloads = mutable.asStateFlow()

    fun preparing(videoId: String) { mutable.update { it + (videoId to Progress()) } }
    fun items(videoId: String, sizes: Map<Int, Long>) {
        mutable.update { it + (videoId to Progress(false, sizes.mapValues { (_, size) -> Item(total = size) })) }
    }
    fun ensureItem(videoId: String, itemId: Int, size: Long) {
        mutable.update { current ->
            val progress = current[videoId] ?: Progress(false)
            if (itemId in progress.items) current
            else current + (videoId to Progress(false, progress.items + (itemId to Item(total = size))))
        }
    }
    fun status(videoId: String, itemId: Int, status: DownloadStatus) {
        mutable.update { current ->
            val progress = current[videoId] ?: return@update current
            val previous = progress.items[itemId] ?: return@update current
            val next = when (status) {
                is DownloadStatus.Progress -> previous.copy(downloaded = status.downloaded, total = status.total)
                DownloadStatus.Completed -> previous.copy(downloaded = previous.total, complete = true)
                DownloadStatus.Stopped, DownloadStatus.Paused, is DownloadStatus.Error -> return@update current - videoId
            }
            val items = progress.items + (itemId to next)
            if (items.values.all { it.complete }) current - videoId
            else current + (videoId to Progress(false, items))
        }
    }
    fun remove(videoId: String) { mutable.update { it - videoId } }
    fun clear() { mutable.value = emptyMap() }
}
