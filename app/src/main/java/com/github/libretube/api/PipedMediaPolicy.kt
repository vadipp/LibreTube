package com.github.libretube.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.ProtocolException

/** Install as both an application and network interceptor: reject initial URLs and redirect targets. */
class PipedMediaPolicy(private val enabled: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (enabled() && isDirectYouTube(url)) {
            throw ProtocolException("Piped mode requires media from the instance proxy. A direct YouTube connection was blocked.")
        }
        val response = chain.proceed(chain.request())
        val target = response.header("Location")?.let(url::resolve)
        if (enabled() && response.isRedirect && target != null && isDirectYouTube(target)) {
            response.close()
            throw ProtocolException("The Piped proxy redirected media to YouTube. Direct playback is disabled in Piped mode.")
        }
        return response
    }

    companion object {
        fun isDirectYouTube(url: HttpUrl): Boolean = listOf(
            "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com"
        ).any { url.host == it || url.host.endsWith(".$it") }
    }
}
