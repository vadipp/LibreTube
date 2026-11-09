package com.github.libretube.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutheliaSessionTest {
    private val server = MockWebServer()
    private lateinit var certificates: HandshakeCertificates
    private lateinit var api: HttpUrl
    private lateinit var portal: HttpUrl
    private var saved: AutheliaSession.Snapshot? = null

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("*.example.test").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        certificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.start()
        api = server.url("/").newBuilder().host("api.example.test").build()
        portal = server.url("/authelia/").newBuilder().host("login.example.test").build()
    }

    @After fun teardown() { server.shutdown() }

    private fun builder() = OkHttpClient.Builder()
        .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)

    private fun session() = AutheliaSession(persist = { saved = it }, clientBuilder = ::builder)

    private fun enqueueLogin(configCode: Int = 200) {
        server.enqueue(MockResponse().setBody("""{"status":"OK"}""")
            .addHeader("Set-Cookie", "session=secret; Domain=example.test; Path=/; Secure; HttpOnly; Max-Age=3600"))
        server.enqueue(MockResponse().setResponseCode(configCode)
            .setBody("""{"imageProxyUrl":"https://proxy.example.test:${api.port}/"}"""))
    }

    @Test fun `background login reuses credentials and keeps secrets out of diagnostics`() {
        enqueueLogin()
        val session = session()
        val password = "p@ss\"word"
        session.login(api.toString(), portal.toString(), "user", password)
        val login = server.takeRequest()
        assertEquals("/authelia/api/firstfactor", login.path)
        val body = Json.parseToJsonElement(login.body.readUtf8()).jsonObject
        assertEquals(password, body["password"]!!.jsonPrimitive.content)
        assertEquals("true", body["keepMeLoggedIn"]!!.jsonPrimitive.content)
        val config = server.takeRequest()
        assertEquals("/config", config.path)
        assertEquals("session=secret", config.getHeader("Cookie"))
        assertFalse(saved.toString().contains(password))
        assertEquals(password, saved!!.password)
        assertEquals("secret", AutheliaSession(saved).loadForRequest(api).single().value)
    }

    @Test fun `cookies are restricted to trusted exact origins even with a broad cookie domain`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        assertEquals(1, session.loadForRequest(api).size)
        assertEquals(1, session.loadForRequest("https://proxy.example.test:${api.port}/image".toHttpUrl()).size)
        assertTrue(session.loadForRequest("https://unrelated.example.test:${api.port}/".toHttpUrl()).isEmpty())
        assertTrue(session.loadForRequest(api.newBuilder().scheme("http").build()).isEmpty())
        assertTrue(session.loadForRequest(api.newBuilder().port(api.port + 1).build()).isEmpty())
        assertTrue(session.loadForRequest("https://www.youtube.com/".toHttpUrl()).isEmpty())
    }

    @Test fun `login never follows a redirect carrying credentials to another service`() {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "https://other.example.test/"))
        val session = session()
        assertThrows(IOException::class.java) { session.login(api.toString(), portal.toString(), "user", "password") }
        assertEquals(1, server.requestCount)
        assertNull(saved)
        assertTrue(session.loadForRequest(api).isEmpty())
    }

    @Test fun `first factor alone does not count as successful authorization`() {
        enqueueLogin(configCode = 302)
        val session = session()
        assertThrows(IOException::class.java) { session.login(api.toString(), portal.toString(), "user", "password") }
        assertNull(saved)
        assertTrue(session.loadForRequest(api).isEmpty())
    }

    @Test fun `host only refreshed cookie stays host only after persistence`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        val cookie = okhttp3.Cookie.parse(api, "hostsession=renewed; Path=/; Secure; HttpOnly")!!
        session.saveFromResponse(api, listOf(cookie))
        val restored = AutheliaSession(saved)
        assertTrue(restored.loadForRequest(api).any { it.name == "hostsession" })
        assertFalse(restored.loadForRequest(portal).any { it.name == "hostsession" })
        restored.clear()
        assertTrue(restored.loadForRequest(api).isEmpty())
    }

    @Test fun `expired session produces actionable error before following portal redirect`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", portal.toString()))
        val client = builder().cookieJar(session).addNetworkInterceptor(session).build()
        val error = assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url(api.resolve("subscriptions")!!).build()).execute().close()
        }
        assertTrue(error.message!!.contains("Sign in again"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `plain HTTP portals are rejected before credentials are sent`() {
        val session = session()
        assertThrows(IOException::class.java) {
            session.login(api.toString(), portal.newBuilder().scheme("http").build().toString(), "user", "password")
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `server session renewal and deletion replace persisted cookies`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        session.saveFromResponse(api, listOf(okhttp3.Cookie.parse(api,
            "session=renewed; Domain=example.test; Path=/; Secure; HttpOnly; Max-Age=3600")!!))
        assertEquals("renewed", AutheliaSession(saved).loadForRequest(api).single().value)
        session.saveFromResponse(api, listOf(okhttp3.Cookie.parse(api,
            "session=deleted; Domain=example.test; Path=/; Secure; HttpOnly; Max-Age=0")!!))
        assertTrue(AutheliaSession(saved).loadForRequest(api).isEmpty())
    }

    @Test fun `unconfigured client passes ordinary instance responses through`() {
        val session = session()
        server.enqueue(MockResponse().setBody("""{"registrationDisabled":true}"""))
        builder().cookieJar(session).addNetworkInterceptor(session).build()
            .newCall(Request.Builder().url(api.resolve("config")!!).build()).execute().use {
                assertEquals(200, it.code)
            }
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertNull(saved)
    }

    @Test fun `expired gateway session automatically renews both logins and retries once`() {
        var token = "old-token"
        val session = AutheliaSession(persist = { saved = it }, clientBuilder = ::builder,
            updatePipedToken = { token = it })
        enqueueLogin()
        session.login(api.toString(), portal.toString(), "user", "password")
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", portal.toString()))
        enqueueLogin()
        server.enqueue(MockResponse().setBody("""{"token":"new-token"}"""))
        server.enqueue(MockResponse().setBody("[]"))
        val client = builder().cookieJar(session).addInterceptor(session.retryInterceptor)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", token).build()) }
            .addNetworkInterceptor(session).build()
        client.newCall(Request.Builder().url(api.resolve("feed?authToken=old-token")!!).build()).execute().use {
            assertEquals(200, it.code)
        }
        val requests = (1..7).map { server.takeRequest() }
        assertEquals(2, requests.count { it.path == "/authelia/api/firstfactor" })
        assertEquals("/login", requests[5].path)
        assertEquals("new-token", requests.last().getHeader("Authorization"))
        assertEquals("/feed?authToken=new-token", requests.last().path)
        assertNull(requests[3].getHeader("Authorization"))
    }

    @Test fun `failed automatic login stops rather than retrying bad credentials repeatedly`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", portal.toString()))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"KO"}"""))
        val client = builder().cookieJar(session).addInterceptor(session.retryInterceptor)
            .addNetworkInterceptor(session).build()
        repeat(2) { index ->
            if (index == 1) server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", portal.toString()))
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(api.resolve("subscriptions")!!).build()).execute().close()
            }
            assertTrue(error.message!!.contains("Sign in again"))
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun `simultaneous expired requests share one login`() {
        var token = "old-token"
        enqueueLogin()
        val session = AutheliaSession(clientBuilder = ::builder, updatePipedToken = { token = it })
        session.login(api.toString(), portal.toString(), "user", "password")
        val oldRequests = CountDownLatch(2)
        val logins = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/authelia/api/firstfactor" -> {
                    logins.incrementAndGet()
                    MockResponse().setBody("""{"status":"OK"}""").addHeader("Set-Cookie",
                        "session=renewed; Domain=example.test; Path=/; Secure; HttpOnly; Max-Age=3600")
                }
                "/config" -> MockResponse().setBody("{}")
                "/login" -> MockResponse().setBody("""{"token":"new-token"}""")
                else -> if (request.getHeader("Authorization") == "new-token") MockResponse().setBody("[]")
                else {
                    oldRequests.countDown()
                    oldRequests.await(5, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(302).addHeader("Location", portal.toString())
                }
            }
        }
        val client = builder().cookieJar(session).addInterceptor(session.retryInterceptor)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", token).build()) }
            .addNetworkInterceptor(session).build()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<Int> {
                    client.newCall(Request.Builder().url(api.resolve("subscriptions")!!).build()).execute().use { it.code }
                }
            }
            results.forEach { assertEquals(200, it.get(15, TimeUnit.SECONDS)) }
            assertEquals(1, logins.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `proxy media denial is not mistaken for an expired Authelia session`() {
        enqueueLogin()
        val session = session()
        session.login(api.toString(), portal.toString(), "user", "password")
        server.enqueue(MockResponse().setResponseCode(403).setBody("Media unavailable"))
        val client = builder().cookieJar(session).addInterceptor(session.retryInterceptor)
            .addNetworkInterceptor(session).build()
        val media = api.newBuilder().host("proxy.example.test").encodedPath("/videoplayback").build()
        client.newCall(Request.Builder().url(media).build()).execute().use { assertEquals(403, it.code) }
        assertEquals(3, server.requestCount)
    }
}
