package com.github.libretube.api

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ProtocolException

class PipedMediaPolicyTest {
    @Test fun `direct media is blocked before DNS lookup`() {
        var resolved = false
        val client = OkHttpClient.Builder().dns(Dns {
            resolved = true
            listOf(InetAddress.getByName("127.0.0.1"))
        }).addInterceptor(PipedMediaPolicy { true }).build()
        assertThrows(ProtocolException::class.java) {
            client.newCall(Request.Builder().url("https://rr.example.googlevideo.com/videoplayback").build()).execute()
        }
        assertFalse(resolved)
    }

    @Test fun `proxy redirect to YouTube is rejected before contacting YouTube`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "https://rr.example.googlevideo.com/videoplayback"))
            val resolved = mutableListOf<String>()
            val client = OkHttpClient.Builder().dns(Dns { host ->
                resolved.add(host)
                listOf(InetAddress.getByName("127.0.0.1"))
            }).addInterceptor(PipedMediaPolicy { true }).addNetworkInterceptor(PipedMediaPolicy { true }).build()
            val url = server.url("/videoplayback").newBuilder().host("proxy.example.test").build()
            assertThrows(ProtocolException::class.java) { client.newCall(Request.Builder().url(url).build()).execute() }
            assertEquals(listOf("proxy.example.test"), resolved)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `proxied media preserves range headers and partial responses`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 2-4/10").setBody("234"))
            val client = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
                .addInterceptor(PipedMediaPolicy { true }).addNetworkInterceptor(PipedMediaPolicy { true }).build()
            val url = server.url("/videoplayback?host=rr.example.googlevideo.com&qhash=signature")
                .newBuilder().host("proxy.example.test").build()
            client.newCall(Request.Builder().url(url).header("Range", "bytes=2-4").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("234", it.body.string())
            }
            val request = server.takeRequest()
            assertEquals("bytes=2-4", request.getHeader("Range"))
            assertTrue(request.path!!.contains("qhash=signature"))
        }
    }
}
