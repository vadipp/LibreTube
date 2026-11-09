package com.github.libretube.helpers

import com.github.libretube.api.obj.PipedStream

object PlaybackSourceSelector {
    fun adaptiveVideos(streams: List<PipedStream>): List<PipedStream> = streams.filter {
        it.videoOnly == true && !it.url.isNullOrBlank() &&
            !it.url.startsWith("sabr://") && (it.indexEnd ?: 0) > 0
    }

    fun progressiveVideo(streams: List<PipedStream>): PipedStream? = streams
        .filter {
            it.videoOnly == false &&
                (it.url?.startsWith("https://") == true || it.url?.startsWith("http://") == true)
        }
        .maxByOrNull { it.height ?: 0 }
}
