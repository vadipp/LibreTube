package com.github.libretube

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.helpers.PlaybackSourceSelector
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceSelectorTest {
    @Test fun combinedMp4UsesProgressiveSourceInsteadOfEmptyDash() {
        val stream = PipedStream(
            url = "https://proxy.example.com/videoplayback?signature=test",
            videoOnly = false, mimeType = "video/mp4", height = 360
        )
        assertTrue(PlaybackSourceSelector.adaptiveVideos(listOf(stream)).isEmpty())
        assertSame(stream, PlaybackSourceSelector.progressiveVideo(listOf(stream)))
    }

    @Test fun indexedVideoRemainsAdaptiveAndDoesNotBecomeProgressive() {
        val stream = PipedStream(
            url = "https://proxy.example.com/video", videoOnly = true, indexEnd = 600
        )
        assertEquals(listOf(stream), PlaybackSourceSelector.adaptiveVideos(listOf(stream)))
        assertNull(PlaybackSourceSelector.progressiveVideo(listOf(stream)))
    }

    @Test fun rejectsMissingIndexesAndLocalSabrAndChoosesBestCombinedVideo() {
        val low = PipedStream(url = "https://proxy.example.com/low", videoOnly = false, height = 360)
        val high = low.copy(url = "https://proxy.example.com/high", height = 720)
        val invalid = listOf(
            PipedStream(url = "sabr://video", videoOnly = true, indexEnd = 600),
            PipedStream(url = "https://proxy.example.com/no-index", videoOnly = true),
            PipedStream(videoOnly = false)
        )
        assertTrue(PlaybackSourceSelector.adaptiveVideos(invalid + low + high).isEmpty())
        assertSame(high, PlaybackSourceSelector.progressiveVideo(invalid + low + high))
    }
}
