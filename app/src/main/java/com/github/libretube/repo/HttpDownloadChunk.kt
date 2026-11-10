package com.github.libretube.repo

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import java.io.IOException

/** HTTP download logic independent of Android, including unknown lengths and resume validation. */
class HttpDownloadChunk(
    private val client: OkHttpClient,
    private val maxBytes: Long = 512 * 1024L,
    private val onCall: (Call) -> Unit = {}
) {
    data class Result(val bytes: Long, val totalSize: Long?, val complete: Boolean)

    fun fetch(url: HttpUrl, offset: Long, expectedSize: Long?, sink: BufferedSink): Result {
        val knownSize = expectedSize?.takeIf { it > 0 } ?: probeSize(url)
        if (knownSize != null && offset == knownSize) return Result(0, knownSize, true)
        if (knownSize != null && offset > knownSize) throw IOException("Partial file exceeds media size; restart download")
        val end = minOf(offset + maxBytes - 1, knownSize?.minus(1) ?: Long.MAX_VALUE)
        val request = Request.Builder().url(url).apply {
            if (knownSize != null) header("Range", "bytes=$offset-$end")
            else if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(request).also(onCall).execute().use { response ->
            val range = response.header("Content-Range")
            if (response.code == 416) {
                val total = range?.substringAfter("bytes */", "")?.toLongOrNull()
                if (total != null && total > 0 && offset == total) return Result(0, total, true)
            }
            if (response.code != 200 && response.code != 206) {
                throw IOException("Media download returned HTTP ${response.code}")
            }
            // Never append a full response to a partial file when a server ignores Range.
            if (response.code == 200 && offset != 0L) throw IOException("Server ignored download resume range")
            val match = range?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it) }
            if (response.code == 206 && (match == null || match.groupValues[1].toLong() != offset)) {
                throw IOException("Invalid media download Content-Range")
            }
            val declaredEnd = match?.groupValues?.get(2)?.toLongOrNull()
            val declaredTotal = match?.groupValues?.get(3)?.toLongOrNull()
            val bodyLength = response.body.contentLength()
            // Some Piped proxies synthesize range totals/end from this chunk's length.
            // A full-file HEAD/metadata size plus the exact requested byte count
            // lets us validate these responses without treating a chunk as the file.
            val chunkTotalBug = response.code == 206 && knownSize != null &&
                bodyLength > 0 && declaredTotal != null && declaredTotal == bodyLength && declaredEnd == bodyLength - 1 &&
                declaredTotal < knownSize
            val rangeLength = if (chunkTotalBug) end - offset + 1
                else declaredEnd?.let { it - offset + 1 }
            val total = if (response.code == 200) bodyLength.takeIf { it > 0 } ?: knownSize
                else if (chunkTotalBug) knownSize else declaredTotal ?: knownSize
            if (response.code == 206 && knownSize == null && offset == 0L) {
                throw IOException("Unexpected partial response to full media request")
            }
            var read = 0L
            val source = response.body.source()
            while (true) {
                val count = source.read(sink.buffer, 8192)
                if (count == -1L) break
                sink.emit()
                read += count
            }
            if (rangeLength != null && read != rangeLength) throw IOException("Incomplete media download range")
            if (response.code == 200) {
                if (read <= 0 || (total != null && read != total)) throw IOException("Incomplete media download")
                return Result(read, read, true)
            }
            if (read <= 0) throw IOException("Empty media download range")
            return Result(read, total, total != null && offset + read == total)
        }
    }
    private fun probeSize(url: HttpUrl): Long? = try {
        client.newCall(Request.Builder().url(url).head().build()).also(onCall).execute().use {
            if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull()?.takeIf { size -> size > 0 }
            else null
        }
    } catch (_: IOException) { null }

}
