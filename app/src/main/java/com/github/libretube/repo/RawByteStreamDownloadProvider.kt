package com.github.libretube.repo

import android.util.Log
import com.github.libretube.api.AutheliaAuth
import com.github.libretube.api.PipedMediaPolicy
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.extensions.TAG
import com.github.libretube.helpers.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.BufferedSink
import okio.IOException
import java.time.Duration
import kotlin.io.path.fileSize

/**
 * Download from RAW HTTP stream.
 */
class RawByteStreamDownloadProvider(val url: HttpUrl) : DownloadProvider {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(AutheliaAuth.session)
            .addInterceptor(AutheliaAuth.session.retryInterceptor)
            .addInterceptor(PipedMediaPolicy { PlayerHelper.usePipedPlayback })
            .addNetworkInterceptor(AutheliaAuth.session)
            .addNetworkInterceptor(PipedMediaPolicy { PlayerHelper.usePipedPlayback })
            .connectTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .readTimeout(Duration.ofMillis(DownloadHelper.DEFAULT_TIMEOUT.toLong()))
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun downloadNextChunk(
        item: DownloadItem,
        sink: BufferedSink,
    ): DownloadProgressResult {
        return withContext(Dispatchers.IO) {
            try {
                val result = HttpDownloadChunk(httpClient).fetch(
                    url, item.path.fileSize(), item.downloadSize, sink
                )
                result.totalSize?.let { item.downloadSize = it }
                if (result.complete) DownloadProgressResult.DownloadComplete
                else DownloadProgressResult.Progressed(result.bytes)
            } catch (e: IOException) {
                Log.e(TAG(), "Media download failed", e)
                DownloadProgressResult.Failed
            }
        }
    }
}
