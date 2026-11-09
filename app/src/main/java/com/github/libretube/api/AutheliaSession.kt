package com.github.libretube.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException

/** A single, explicitly configured instance session; its snapshot must be encrypted at rest. */
class AutheliaSession(
    initial: Snapshot? = null,
    private val persist: (Snapshot?) -> Unit = {},
    private val clientBuilder: () -> OkHttpClient.Builder = { OkHttpClient.Builder() },
    private val updatePipedToken: (String) -> Unit = {}
) : CookieJar, Interceptor {
    data class Snapshot(
        val api: String,
        val portal: String,
        val origins: Set<String>,
        val cookies: List<String>,
        val username: String? = null,
        val password: String? = null
    ) {
        override fun toString() = "AutheliaSession.Snapshot(api=$api, authenticated=${cookies.isNotEmpty()})"
    }

    @Volatile private var state = initial
    @Volatile private var generation = 0L
    private val loginLock = Any()
    private var automaticLoginFailed = false

    private class AuthenticationRequired : ProtocolException(
        "Instance authentication expired or access was denied. Sign in again in Account settings."
    )

    /** Application interceptor: at most one reauthentication and one replay per request. */
    val retryInterceptor = Interceptor { chain ->
        val observedGeneration = generation
        try {
            chain.proceed(chain.request())
        } catch (e: AuthenticationRequired) {
            synchronized(loginLock) {
                val current = state ?: throw e
                if (generation == observedGeneration) {
                    if (automaticLoginFailed || current.username == null || current.password == null) throw e
                    try {
                        performLogin(current.api, current.portal, current.username, current.password)
                        refreshPipedToken()
                    } catch (error: IOException) {
                        // Do not repeatedly retry rejected credentials and trigger server lockout.
                        automaticLoginFailed = true
                        throw IOException("Automatic instance sign-in failed. Sign in again in Account settings.", error)
                    }
                }
            }
            chain.proceed(chain.request())
        }
    }

    fun portalFor(api: String): String = state?.takeIf { it.api == api.toHttpUrl().toString() }
        ?.portal.orEmpty()

    @Synchronized
    fun clear() {
        generation++
        state = null
        persist(null)
    }

    private fun HttpUrl.origin() = newBuilder().encodedPath("/").query(null).fragment(null).build().toString()

    private fun decodeCookie(value: String): Cookie? =
        Cookie.parse("https://${value.substringBefore('\n')}/".toHttpUrl(), value.substringAfter('\n'))

    private fun trusted(url: HttpUrl, snapshot: Snapshot) =
        url.isHttps && url.origin() in snapshot.origins

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val snapshot = state ?: return emptyList()
        if (!trusted(url, snapshot)) return emptyList()
        return snapshot.cookies.mapNotNull { decodeCookie(it) }
            .filter { it.secure && it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val snapshot = state ?: return
        if (!trusted(url, snapshot)) return
        val existing = snapshot.cookies.mapNotNull { decodeCookie(it) }
            .toMutableList()
        for (cookie in cookies.filter { it.secure && it.httpOnly }) {
            existing.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) existing.add(cookie)
        }
        state = snapshot.copy(cookies = existing.map { it.domain + "\n" + it.toString() }).also(persist)
    }

    /** Network interceptor: catch login redirects before they turn into HTML/JSON errors. */
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val snapshot = state ?: return response
        if (!trusted(chain.request().url, snapshot)) return response
        val destination = response.header("Location")?.let { chain.request().url.resolve(it) }
        val portal = snapshot.portal.toHttpUrl()
        if (response.code == 401 || response.code == 403 ||
            (response.isRedirect && destination != null && destination.origin() == portal.origin() &&
                destination.encodedPath.startsWith(portal.encodedPath))) {
            response.close()
            throw AuthenticationRequired()
        }
        return response
    }

    /** Callers perform the normal Piped login after this establishes the gateway session. */
    fun login(apiUrl: String, portalUrl: String, username: String, password: String) {
        synchronized(loginLock) {
            performLogin(apiUrl, portalUrl, username, password)
            automaticLoginFailed = false
        }
    }

    private fun performLogin(apiUrl: String, portalUrl: String, username: String, password: String) {
        val api = apiUrl.toHttpUrl()
        val portal = portalUrl.toHttpUrlOrNull()
            ?: throw IOException("Enter a valid Authelia portal URL.")
        if (!api.isHttps || api.username.isNotEmpty() || api.password.isNotEmpty() ||
            api.query != null || api.fragment != null || !portal.isHttps || portal.username.isNotEmpty() ||
            portal.password.isNotEmpty() || portal.query != null || portal.fragment != null) {
            throw IOException("Authelia and instance URLs must use HTTPS without credentials or query parameters.")
        }
        val base = portal.newBuilder().encodedPath(portal.encodedPath.trimEnd('/') + "/").build()
        val previous: Snapshot?
        val expectedGeneration: Long
        synchronized(this) {
            previous = state
            expectedGeneration = generation
            state = Snapshot(api.toString(), base.toString(), setOf(api.origin(), base.origin()), emptyList(), username, password)
        }
        val client = clientBuilder().cookieJar(this)
            .followRedirects(false).followSslRedirects(false).build()
        try {
            val body = buildJsonObject {
                put("username", username)
                put("password", password)
                put("keepMeLoggedIn", true)
                put("targetURL", api.resolve("config").toString())
            }.toString().toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url(base.resolve("api/firstfactor")!!).post(body).build())
                .execute().use { response ->
                    if (!response.isSuccessful || runCatching {
                        Json.parseToJsonElement(response.body.string()).jsonObject["status"]?.jsonPrimitive?.content
                    }.getOrNull() != "OK") {
                        throw IOException("Authelia sign-in failed. Check the portal URL, username and password.")
                    }
                }
            // A first-factor success alone does not prove that the protected resource is accessible.
            client.newCall(Request.Builder().url(api.resolve("config")!!).build()).execute().use { response ->
                if (!response.isSuccessful || loadForRequest(api).isEmpty()) {
                    throw IOException("Authelia did not grant instance access. Two-factor login is not supported by background sign-in.")
                }
                val config = runCatching { Json.parseToJsonElement(response.body.string()).jsonObject }
                    .getOrElse { throw IOException("The protected instance did not return its configuration.") }
                // Trust only an explicitly configured origin or a proxy advertised by the authenticated API.
                val proxy = config["imageProxyUrl"]?.jsonPrimitive?.content?.toHttpUrlOrNull()
                synchronized(this) {
                    if (generation != expectedGeneration) throw IOException("Instance sign-in was cancelled.")
                    val current = state ?: throw IOException("Instance sign-in was cancelled.")
                    state = current.copy(origins = current.origins + listOfNotNull(proxy?.takeIf { it.isHttps }?.origin()))
                        .also(persist)
                    generation++
                }
            }
        } catch (e: Exception) {
            synchronized(this) {
                if (generation == expectedGeneration) {
                    state = previous
                    persist(previous)
                }
            }
            if (e is IOException) throw e
            throw IOException("Authelia sign-in could not be completed.")
        }
    }

    private fun refreshPipedToken() {
        val current = state ?: throw IOException("Instance sign-in was cancelled.")
        val expectedGeneration = generation
        val body = buildJsonObject {
            put("username", current.username)
            put("password", current.password)
        }.toString().toRequestBody("application/json".toMediaType())
        val client = clientBuilder().cookieJar(this).followRedirects(false).followSslRedirects(false).build()
        client.newCall(Request.Builder().url(current.api.toHttpUrl().resolve("login")!!).post(body).build())
            .execute().use { response ->
                val token = if (response.isSuccessful) runCatching {
                    Json.parseToJsonElement(response.body.string()).jsonObject["token"]?.jsonPrimitive?.content
                }.getOrNull() else null
                if (token.isNullOrBlank() || token == "null") throw IOException("Piped account sign-in failed.")
                synchronized(this) {
                    if (generation != expectedGeneration) throw IOException("Instance sign-in was cancelled.")
                    updatePipedToken(token)
                }
            }
    }
}
