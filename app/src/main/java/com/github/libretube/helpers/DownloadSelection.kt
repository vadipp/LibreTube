package com.github.libretube.helpers

import com.github.libretube.api.obj.PipedStream
import com.github.libretube.extensions.getWhileDigit

/** Download defaults use physical pixels, independent of the device's current rotation. */
object DownloadSelection {
    fun videoIndex(streams: List<PipedStream>, screenWidth: Int, screenHeight: Int): Int? {
        fun dimensions(stream: PipedStream): Pair<Int, Int> {
            val height = stream.height?.takeIf { it > 0 } ?: stream.quality.getWhileDigit() ?: 0
            val width = stream.width?.takeIf { it > 0 } ?: (height * 16 / 9)
            return minOf(width, height) to maxOf(width, height)
        }
        val shortSide = minOf(screenWidth, screenHeight)
        val longSide = maxOf(screenWidth, screenHeight)
        val sized = streams.indices.filter { dimensions(streams[it]).first > 0 }
        val fitting = sized.filter {
            val (short, long) = dimensions(streams[it])
            short <= shortSide && long <= longSide
        }
        fun area(index: Int): Long {
            val (short, long) = dimensions(streams[index])
            return short.toLong() * long
        }
        return fitting.maxByOrNull(::area) ?: sized.minByOrNull(::area) ?: streams.indices.firstOrNull()
    }

    /** Null means that at least one selected media size cannot be estimated. */
    fun estimatedBytes(streams: List<PipedStream>, durationSeconds: Long, subtitles: Boolean): Long? {
        var total = 0L
        for (stream in streams) {
            val size = stream.contentLength.takeIf { it > 0 } ?: run {
                val bitrate = stream.bitrate?.takeIf { it > 0 } ?: return null
                if (durationSeconds <= 0) return null
                (bitrate.toDouble() * durationSeconds / 8).toLong()
            }
            total = (total.toDouble() + size).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        }
        // Captions/thumbnail/metadata have a small additional cost. Media sizes remain estimates.
        return (total.toDouble() + if (subtitles) 1024 * 1024 else 0)
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    const val LOW_SPACE_BYTES = 500_000_000L
}
