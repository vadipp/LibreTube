package com.github.libretube.api

import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Message
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.toID
import retrofit2.HttpException

open class PipedMediaServiceRepository : MediaServiceRepository {
    // Piped provides one uncategorized feed. A single pager key keeps Trends usable;
    // the UI hides the category selector for a one-item list and the API ignores it.
    override fun getTrendingCategories(): List<TrendingCategory> = listOf(TrendingCategory.GAMING)

    override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> =
        api.getTrending(region).map(PipedItemNormalizer::stream)

    override suspend fun getStreams(videoId: String): Streams {
        return try {
            api.getStreams(videoId.toID()).also {
                it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
                it.isShort = it.videoStreams.firstOrNull()?.let { stream ->
                    (stream.height ?: 0) > (stream.width ?: 0)
                } ?: false
            }
        } catch (e: HttpException) {
            val errorMessage = e.response()?.errorBody()?.string()?.runCatching {
                JsonHelper.json.decodeFromString<Message>(this).message
            }?.getOrNull()

            throw Exception(errorMessage)
        }
    }

    override suspend fun getComments(videoId: String): CommentsPage =
        api.getComments(videoId.toID())

    override suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>?
    ): SegmentData = api.getSegments(
        videoId.toID(),
        JsonHelper.json.encodeToString(category),
        JsonHelper.json.encodeToString(actionType)
    )

    override suspend fun getDeArrowContent(videoId: String): DeArrowContent? =
        api.getDeArrowContent(videoId.toID())[videoId.toID()]

    override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage =
        api.getCommentsNextPage(videoId.toID(), nextPage)

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult =
        api.getSearchResults(searchQuery, filter).also {
            it.items = it.items.map(PipedItemNormalizer::content)
        }

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult = api.getSearchResultsNextPage(searchQuery, filter, nextPage).also {
        it.items = it.items.map(PipedItemNormalizer::content)
    }

    override suspend fun getSuggestions(query: String): List<String> =
        api.getSuggestions(query)

    override suspend fun getChannel(channelId: String): Channel =
        api.getChannel(channelId.toID()).also {
            it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
        }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse =
        api.getChannelTab(data, nextPage).also {
            it.content = it.content.map(PipedItemNormalizer::content)
        }

    override suspend fun getChannelByName(channelName: String): Channel =
        api.getChannelByName(channelName).also {
            it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
        }

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel =
        api.getChannelNextPage(channelId.toID(), nextPage).also {
            it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
        }

    override suspend fun getPlaylist(playlistId: String): Playlist =
        api.getPlaylist(playlistId.toID()).also {
            it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
        }

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist =
        api.getPlaylistNextPage(playlistId.toID(), nextPage).also {
            it.relatedStreams = it.relatedStreams.map(PipedItemNormalizer::stream)
        }

    companion object {
        // Use the same explicitly selected instance as the Piped account and Authelia session.
        val apiUrl get() = RetrofitInstance.pipedAuthUrl

        private val api by resettableLazy(RetrofitInstance.apiLazyMgr) {
            RetrofitInstance.buildRetrofitInstance<PipedApi>(apiUrl)
        }
    }
}
