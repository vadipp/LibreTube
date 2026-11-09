package com.github.libretube.api

import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID

/** The current UI expects IDs; Piped browsing endpoints return relative links. */
internal object PipedItemNormalizer {
    fun stream(item: StreamItem): StreamItem = item.copy(
        url = item.url?.toID(), uploaderUrl = item.uploaderUrl?.toID()
    )

    fun content(item: ContentItem): ContentItem = item.copy(
        url = item.url.toID(), uploaderUrl = item.uploaderUrl?.toID()
    )
}
