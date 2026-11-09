package com.github.libretube.api

import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.StreamItem
import org.junit.Assert.*
import org.junit.Test

class PipedItemNormalizerTest {
    @Test fun searchVideoLinkBecomesPlayerId() {
        val item = ContentItem(
            url = "/watch?v=abcdefghijk", type = "stream", thumbnail = "https://proxy.example.com/image",
            title = "Example", uploaderUrl = "/channel/UCexample"
        )
        val normalized = PipedItemNormalizer.content(item)
        assertEquals("abcdefghijk", normalized.url)
        assertEquals("UCexample", normalized.uploaderUrl)
        assertEquals(item.thumbnail, normalized.thumbnail)
        assertEquals(item.title, normalized.title)
        assertEquals("abcdefghijk", normalized.toStreamItem().url)
    }

    @Test fun channelAndPlaylistSearchResultsKeepTheirTypeAndUseIds() {
        for ((url, type, id) in listOf(
            Triple("/channel/UCexample", "channel", "UCexample"),
            Triple("/playlist?list=PLexample", "playlist", "PLexample"),
            Triple("/shorts/abcdefghijk", "stream", "abcdefghijk")
        )) {
            val normalized = PipedItemNormalizer.content(ContentItem(url, type, ""))
            assertEquals(id, normalized.url)
            assertEquals(type, normalized.type)
        }
    }

    @Test fun relatedChannelAndPlaylistVideosNormalizeWithoutChangingFeedIds() {
        val item = StreamItem(url = "/watch?v=abcdefghijk", uploaderUrl = "/channel/UCexample")
        val normalized = PipedItemNormalizer.stream(item)
        assertEquals("abcdefghijk", normalized.url)
        assertEquals("UCexample", normalized.uploaderUrl)
        assertEquals(normalized, PipedItemNormalizer.stream(normalized))
        assertNull(PipedItemNormalizer.stream(StreamItem()).url)
    }
}
