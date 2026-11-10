package com.github.libretube.helpers

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType

object DownloadQueuePolicy {
    val terminal = setOf("COMPLETE", "CANCELLED", "ATTENTION", "INSTANCE")
    fun active(state: String) = state !in terminal
    fun sameRepresentation(old: PipedStream, fresh: PipedStream): Boolean =
        old.itag == fresh.itag && old.format == fresh.format && old.quality == fresh.quality &&
            old.codec == fresh.codec &&
            (old.lastModified == null || fresh.lastModified == null || old.lastModified == fresh.lastModified) && old.width == fresh.width && old.height == fresh.height &&
            old.audioTrackLocale == fresh.audioTrackLocale &&
            (old.contentLength <= 0 || fresh.contentLength <= 0 || old.contentLength == fresh.contentLength)

    fun fits(available: Long, remaining: Long): Boolean =
        remaining >= 0 && available >= DownloadSelection.LOW_SPACE_BYTES &&
            remaining <= available - DownloadSelection.LOW_SPACE_BYTES

    fun complete(download: DownloadWithItems?): Boolean {
        val media = download?.downloadItems?.filter { it.type != FileType.SUBTITLE }.orEmpty()
        return media.isNotEmpty() && media.all { it.isFinished }
    }
}
