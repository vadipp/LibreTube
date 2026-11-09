package com.github.libretube.repo

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpDownloadChunkTest {
    @Test fun unknownSizeLearnsTotalAndResumesWithInclusiveBoundedRanges() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/7").setBody("ABCD"))
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 4-6/7").setBody("EFG"))
            val downloader = HttpDownloadChunk(OkHttpClient(), 4)
            val sink = Buffer()
            val first = downloader.fetch(server.url("/media"), 0, -1, sink)
            assertEquals(7L, first.totalSize)
            assertFalse(first.complete)
            assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
            val last = downloader.fetch(server.url("/media"), 4, first.totalSize, sink)
            assertTrue(last.complete)
            assertEquals(7L, last.totalSize)
            assertEquals("bytes=4-6", server.takeRequest().getHeader("Range"))
            assertEquals("ABCDEFG", sink.readUtf8())
        }
    }

    @Test fun fullChunkedResponseAtZeroRecordsActualSize() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("complete file", 3))
            val sink = Buffer()
            val result = HttpDownloadChunk(OkHttpClient(), 4).fetch(server.url("/media"), 0, -1, sink)
            assertTrue(result.complete)
            assertEquals(13L, result.totalSize)
            assertEquals("complete file", sink.readUtf8())
        }
    }

    @Test fun ignoredResumeRangeCannotAppendDuplicateFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ABCDEFG"))
            val sink = Buffer().writeUtf8("ABC")
            assertThrows(IOException::class.java) {
                HttpDownloadChunk(OkHttpClient(), 4).fetch(server.url("/media"), 3, -1, sink)
            }
            assertEquals("ABC", sink.readUtf8())
        }
    }

    @Test fun wrongRangeStartCannotCorruptExistingBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-2/7").setBody("ABC"))
            val sink = Buffer().writeUtf8("ABC")
            assertThrows(IOException::class.java) {
                HttpDownloadChunk(OkHttpClient(), 4).fetch(server.url("/media"), 3, 7, sink)
            }
            assertEquals("ABC", sink.readUtf8())
        }
    }

    @Test fun unknownPartialRangeWaitsForDefinitiveEndOfFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/*").setBody("ABCD"))
            server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */4"))
            val downloader = HttpDownloadChunk(OkHttpClient(), 4)
            val sink = Buffer()
            assertFalse(downloader.fetch(server.url("/media"), 0, -1, sink).complete)
            val eof = downloader.fetch(server.url("/media"), 4, -1, sink)
            assertTrue(eof.complete)
            assertEquals(4L, eof.totalSize)
            assertEquals("ABCD", sink.readUtf8())
        }
    }

    @Test fun proxyErrorDoesNotWriteErrorPageAsMedia() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            val sink = Buffer()
            assertThrows(IOException::class.java) {
                HttpDownloadChunk(OkHttpClient()).fetch(server.url("/media"), 0, -1, sink)
            }
            assertEquals(0L, sink.size)
        }
    }
}
