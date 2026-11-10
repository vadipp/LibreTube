package com.github.libretube

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.DownloadQueuePolicy as Policy
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DownloadQueuePolicyTest {
    @Test fun refreshedUrlsKeepIdentityButChangedMediaMustRestart() {
        val old = PipedStream(url = "https://proxy.example.org/expired", itag = 137, codec = "avc1", quality = "1080p", contentLength = 100, lastModified = 1)
        assertTrue(Policy.sameRepresentation(old, old.copy(url = "https://proxy.example.org/refreshed")))
        assertFalse(Policy.sameRepresentation(old, old.copy(contentLength = 101)))
        assertFalse(Policy.sameRepresentation(old, old.copy(codec = "av01")))
        assertFalse(Policy.sameRepresentation(old, old.copy(lastModified = 2)))
        assertFalse(Policy.sameRepresentation(old, old.copy(itag = 136)))
    }
    @Test fun storageAlwaysLeavesHalfGBAndNeverOverflows() {
        assertTrue(Policy.fits(600_000_000, 100_000_000))
        assertFalse(Policy.fits(599_999_999, 100_000_000))
        assertFalse(Policy.fits(100_000_000, 0))
        assertFalse(Policy.fits(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(Policy.fits(Long.MAX_VALUE, -1))
    }
    @Test fun automaticOfflinePlaybackRequiresActualCompleteMediaFiles() {
        val dir = Files.createTempDirectory("offline-policy")
        val video = dir.resolve("video")
        val audio = dir.resolve("audio")
        Files.write(video, byteArrayOf(1, 2, 3))
        Files.write(audio, byteArrayOf(1))
        val items = listOf(
            DownloadItem(type = FileType.VIDEO, videoId = "example", fileName = "video", path = video, downloadSize = 3),
            DownloadItem(type = FileType.AUDIO, videoId = "example", fileName = "audio", path = audio, downloadSize = 2)
        )
        val saved = DownloadWithItems(Download("example"), items)
        assertFalse(Policy.complete(null))
        assertFalse(Policy.complete(saved.copy(downloadItems = emptyList())))
        assertFalse(Policy.complete(saved))
        Files.write(audio, byteArrayOf(1, 2))
        assertTrue(Policy.complete(saved))
        Files.delete(audio)
        assertFalse(Policy.complete(saved))
        Files.delete(video)
        Files.delete(dir)
    }
    @Test fun missingExpectedAudioCannotBeMistakenForACompleteVideoOnlyDownload() {
        val file = Files.createTempFile("expected-media", ".mp4")
        Files.write(file, byteArrayOf(1, 2))
        val item = DownloadItem(type = FileType.VIDEO, videoId = "example", fileName = "video",
            path = file, format = "MPEG_4", quality = "720p", downloadSize = 2)
        val saved = DownloadWithItems(Download("example"), listOf(item))
        val video = PipedStream(format = "MPEG_4", quality = "720p")
        val audio = PipedStream(format = "M4A", quality = "128 kbps")
        assertTrue(Policy.complete(saved, listOf(video)))
        assertFalse(Policy.complete(saved, listOf(video, audio)))
        Files.delete(file)
    }
}
