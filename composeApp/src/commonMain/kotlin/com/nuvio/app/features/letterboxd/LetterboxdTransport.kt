package com.nuvio.app.features.letterboxd

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class LetterboxdHttpRequest(
    val method: String,
    /** Path starting with `/`, relative to https://letterboxd.com. */
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
)

internal data class LetterboxdHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun headerValues(name: String): List<String> =
        headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
}

/** Raw HTTP engine, swappable in tests. */
internal fun interface LetterboxdHttpEngine {
    suspend fun execute(request: LetterboxdHttpRequest): LetterboxdHttpResponse
}

internal class KtorLetterboxdHttpEngine(
    private val clientProvider: () -> HttpClient,
) : LetterboxdHttpEngine {
    private val client by lazy(clientProvider)

    override suspend fun execute(request: LetterboxdHttpRequest): LetterboxdHttpResponse {
        val response = client.request(LETTERBOXD_BASE_URL + request.path) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            if (request.body != null) {
                val type = request.contentType?.let(ContentType::parse) ?: ContentType.Text.Plain
                setBody(TextContent(request.body, type))
            }
        }
        val headers = response.headers.names().associateWith { response.headers.getAll(it).orEmpty() }
        return LetterboxdHttpResponse(response.status.value, headers, response.bodyAsText())
    }
}

/**
 * Letterboxd HTTP session: attaches cookies + browser-like headers, keeps the cookie jar up to date
 * from `Set-Cookie`, throttles requests (low volume on purpose) and maps failures to [LetterboxdError].
 * Cookie values are never logged.
 */
internal class LetterboxdTransport(
    private val engine: LetterboxdHttpEngine,
    private val minIntervalMs: Long = 350L,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val throttle = Mutex()
    private var lastRequestAt = 0L
    private val jarLock = Mutex()
    private val jar = LinkedHashMap<String, String>()

    @kotlin.concurrent.Volatile
    var userAgent: String = DEFAULT_LETTERBOXD_USER_AGENT
        private set

    /** Called when cookies change server-side (e.g. CSRF rotation) so they can be persisted. */
    var onCookiesChanged: (suspend (Map<String, String>) -> Unit)? = null

    suspend fun configure(cookies: Map<String, String>, userAgent: String?) = jarLock.withLock {
        jar.clear()
        jar.putAll(cookies)
        this.userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LETTERBOXD_USER_AGENT
    }

    suspend fun clear() = jarLock.withLock { jar.clear() }

    suspend fun cookieSnapshot(): Map<String, String> = jarLock.withLock { LinkedHashMap(jar) }

    suspend fun csrfToken(): String? = jarLock.withLock { jar[LETTERBOXD_CSRF_COOKIE] }

    suspend fun setCsrfToken(token: String) {
        val changed = jarLock.withLock {
            (jar[LETTERBOXD_CSRF_COOKIE] != token).also { if (it) jar[LETTERBOXD_CSRF_COOKIE] = token }
        }
        if (changed) onCookiesChanged?.invoke(cookieSnapshot())
    }

    /**
     * Executes [request]. With [authenticated] = false no cookie is sent (public reads), except the
     * Cloudflare clearance cookie when present.
     * Throws [LetterboxdException] for network errors, Cloudflare challenges and 429.
     */
    suspend fun execute(
        request: LetterboxdHttpRequest,
        authenticated: Boolean,
        referer: String? = null,
    ): LetterboxdHttpResponse {
        val cookies = jarLock.withLock {
            if (authenticated) LinkedHashMap(jar) else jar.filterKeys { it == LETTERBOXD_CLEARANCE_COOKIE || it.startsWith("__cf") }
        }
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = userAgent
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Accept"] = request.headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies.toCookieHeader()
        if (request.method != "GET") {
            headers["Origin"] = LETTERBOXD_BASE_URL
            headers["Referer"] = referer ?: "$LETTERBOXD_BASE_URL/"
        } else if (referer != null) {
            headers["Referer"] = referer
        }
        headers.putAll(request.headers)

        throttle.withLock {
            val wait = lastRequestAt + minIntervalMs - nowMs()
            if (wait > 0) sleep(wait)
            lastRequestAt = nowMs()
        }

        val response = try {
            engine.execute(request.copy(headers = headers))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw LetterboxdException(LetterboxdError.Network, error.message)
        } catch (error: Exception) {
            // Ktor engines surface TLS/DNS/timeouts with various exception types.
            throw LetterboxdException(LetterboxdError.Network, error::class.simpleName)
        }

        val setCookies = response.headerValues("Set-Cookie")
        if (setCookies.isNotEmpty() && authenticated) {
            val changed = jarLock.withLock { applySetCookieHeaders(jar, setCookies) }
            if (changed) onCookiesChanged?.invoke(cookieSnapshot())
        }

        if (LetterboxdParsers.isCloudflareChallenge(response.status, response.body, response.headers)) {
            throw LetterboxdException(LetterboxdError.CloudflareChallenge, status = response.status)
        }
        if (response.status == 429) throw LetterboxdException(LetterboxdError.RateLimited, status = 429)
        return response
    }
}

internal fun formEncode(fields: List<Pair<String, String>>): String =
    fields.joinToString("&") { (key, value) -> "${key.encodeURLParameter()}=${value.encodeURLParameter()}" }

/** Used when the user doesn't provide one. `cf_clearance` only works with the exact UA of the browser it came from. */
internal const val DEFAULT_LETTERBOXD_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
