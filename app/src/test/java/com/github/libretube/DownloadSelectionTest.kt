package com.github.libretube

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.helpers.DownloadSelection
import org.junit.Assert.*
import org.junit.Test

class DownloadSelectionTest {
    private fun video(width: Int, height: Int) = PipedStream(width = width, height = height, quality = "${height}p")

    @Test fun fitsPhysicalScreenInEitherRotationAndRoundsDown() {
        val streams = listOf(video(1920, 1080), video(1280, 720), video(854, 480), video(640, 360))
        assertEquals(1, DownloadSelection.videoIndex(streams, 1600, 900))
        assertEquals(1, DownloadSelection.videoIndex(streams, 900, 1600))
        assertEquals(0, DownloadSelection.videoIndex(streams, 1080, 2400))
        assertEquals(3, DownloadSelection.videoIndex(streams, 320, 240))
    }

    @Test fun verticalVideoAndMissingDimensionsUseSensibleFallbacks() {
        assertEquals(0, DownloadSelection.videoIndex(listOf(video(720, 1280), video(1080, 1920)), 720, 1600))
        assertEquals(1, DownloadSelection.videoIndex(listOf(PipedStream(quality = "1080p"), PipedStream(quality = "720p")), 1280, 720))
        assertNull(DownloadSelection.videoIndex(emptyList(), 1280, 720))
    }

    @Test fun estimatesBothTracksUsingSizesThenBitrateAndFlagsUnknownSizes() {
        val video = PipedStream(contentLength = 10_000_000)
        val audio = PipedStream(bitrate = 128_000)
        assertEquals(10_960_000L, DownloadSelection.estimatedBytes(listOf(video, audio), 60, false))
        assertEquals(12_008_576L, DownloadSelection.estimatedBytes(listOf(video, audio), 60, true))
        assertNull(DownloadSelection.estimatedBytes(listOf(video, PipedStream()), 60, false))
        assertNull(DownloadSelection.estimatedBytes(listOf(audio), 0, false))
    }
}
