package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetterboxdClientTest {
    private val film = LetterboxdFilm(slug = "the-matrix", lid = "2a1m", uid = "film:51518")

    private class FakeEngine(val handler: (LetterboxdHttpRequest) -> LetterboxdHttpResponse) : LetterboxdHttpEngine {
        val requests = mutableListOf<LetterboxdHttpRequest>()
        override suspend fun execute(request: LetterboxdHttpRequest): LetterboxdHttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun response(status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
        LetterboxdHttpResponse(status, headers, body)

    private suspend fun client(
        engine: FakeEngine,
        cookies: Map<String, String> = linkedMapOf("letterboxd.user.CURRENT" to "session", LETTERBOXD_CSRF_COOKIE to "tok", LETTERBOXD_CLEARANCE_COOKIE to "cf"),
        onAuthExpired: suspend () -> Unit = {},
    ): LetterboxdClient {
        val transport = LetterboxdTransport(engine, minIntervalMs = 0)
        transport.configure(cookies, "UA/1.0")
        return LetterboxdClient(transport, onAuthExpired)
    }

    @Test
    fun resolvesTmdbRedirectWithoutSessionCookies() = runTest {
        val engine = FakeEngine { response(302, headers = mapOf("Location" to listOf("/film/the-matrix/"))) }
        assertEquals("the-matrix", client(engine).resolveSlugForTmdb(603))
        val request = engine.requests.single()
        assertEquals("/tmdb/603/", request.path)
        assertEquals("cf_clearance=cf", request.headers["Cookie"])
        assertEquals("UA/1.0", request.headers["User-Agent"])
    }

    @Test
    fun unknownTmdbIdIsNull() = runTest {
        assertNull(client(FakeEngine { response(404) }).resolveSlugForTmdb(1))
    }

    @Test
    fun ratingUsesJsonApiWithCsrfHeader() = runTest {
        val engine = FakeEngine { response(204) }
        client(engine).setRating(film, 3.5)
        val request = engine.requests.single()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v0/me/rate/2a1m", request.path)
        assertEquals("tok", request.headers["X-CSRF-TOKEN"])
        assertEquals("""{"rating":3.5}""", request.body)
        assertTrue(request.headers["Cookie"]!!.contains("letterboxd.user.CURRENT=session"))

        client(engine).setRating(film, null)
        assertEquals("""{"rating":null}""", engine.requests.last().body)
        assertFailsWith<IllegalArgumentException> { client(engine).setRating(film, 3.3) }
    }

    @Test
    fun watchlistAndLogEntryPayloads() = runTest {
        val engine = FakeEngine { request ->
            if (request.path == "/api/v0/production-log-entries") response(200, """{"id":"abc"}""") else response(200, "{}")
        }
        val client = client(engine)
        client.setInWatchlist(film, false)
        assertEquals("""{"inWatchlist":false}""", engine.requests.last().body)

        val id = client.createLogEntry(
            film,
            LetterboxdLogEntryDraft(diaryDate = "2026-11-19", rewatch = true, review = " Great ", containsSpoilers = true, rating = 4.0, liked = true),
        )
        assertEquals("abc", id)
        val body = Json.parseToJsonElement(engine.requests.last().body!!).jsonObject
        assertEquals("2a1m", body["productionId"]!!.jsonPrimitive.content)
        assertEquals("2026-11-19", body["diaryDetails"]!!.jsonObject["diaryDate"]!!.jsonPrimitive.content)
        assertEquals("Great", body["review"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("4.0", body["rating"]!!.jsonPrimitive.content)
    }

    @Test
    fun watchedUsesFormEndpointAndRetriesOnStaleCsrf() = runTest {
        var watchCalls = 0
        val engine = FakeEngine { request ->
            when {
                request.path == "/s/film:51518/watch/" -> {
                    watchCalls++
                    if (watchCalls == 1) response(403, """{"result":false,"messages":["expired"]}""")
                    else response(200, """{"result":true,"watched":true,"csrf":"tok3"}""")
                }
                request.path == LetterboxdClient.SESSION_PROBE_PATH ->
                    response(200, "person = { username: \"jane\", loggedIn: true } supermodelCSRF = 'abc2';")
                else -> response(500)
            }
        }
        val client = client(engine)
        assertTrue(client.setWatched(film, true))
        val posts = engine.requests.filter { it.path.startsWith("/s/") }
        assertEquals("watched=true&__csrf=tok", posts[0].body)
        assertEquals("watched=true&__csrf=abc2", posts[1].body)
        assertEquals("https://letterboxd.com/film/the-matrix/", posts[1].headers["Referer"])
    }

    @Test
    fun guestPageMeansAuthExpired() = runTest {
        var expired = false
        val engine = FakeEngine { response(200, "person = {\n username: \"\"\n , loggedIn: false") }
        val error = assertFailsWith<LetterboxdException> { client(engine, onAuthExpired = { expired = true }).fetchSession() }
        assertEquals(LetterboxdError.AuthExpired, error.error)
        assertTrue(expired)
    }

    @Test
    fun signedInPageWithCachedGuestLiteralIsAccepted() = runTest {
        var expired = false
        val page = "person = {\n username: \"\"\n , loggedIn: false\n , role: \"guest\" };\n" +
            "supermodelCSRF = 'abc123'; analytic_params['user_type'] = 'Member'; person.username = \"jane_doe\"; person.loggedIn = true;"
        val engine = FakeEngine { response(200, page) }
        val session = client(engine, onAuthExpired = { expired = true }).fetchSession()
        assertEquals("jane_doe", session.username)
        assertEquals("abc123", session.csrfToken)
        assertFalse(expired)
    }

    @Test
    fun guestPageWithoutSessionCookieIsReportedAsMissingCookie() = runTest {
        val engine = FakeEngine { response(200, "person = {\n username: \"\"\n , loggedIn: false") }
        val error = assertFailsWith<LetterboxdException> {
            client(engine, cookies = mapOf(LETTERBOXD_CSRF_COOKIE to "tok", "useMobileSite" to "no")).fetchSession()
        }
        assertEquals(LetterboxdError.SessionCookieMissing, error.error)
    }

    @Test
    fun unexpectedSessionPageIsAParseErrorNotAnExpiredSession() = runTest {
        var expired = false
        val engine = FakeEngine { response(200, "<html><body>maintenance</body></html>") }
        val error = assertFailsWith<LetterboxdException> { client(engine, onAuthExpired = { expired = true }).fetchSession() }
        assertEquals(LetterboxdError.Parse, error.error)
        assertFalse(expired)
    }

    @Test
    fun cloudflareChallengeIsReported() = runTest {
        val engine = FakeEngine { response(403, "<html><head><title>Just a moment...</title></head></html>") }
        val error = assertFailsWith<LetterboxdException> { client(engine).setInWatchlist(film, true) }
        assertEquals(LetterboxdError.CloudflareChallenge, error.error)
        assertEquals(LetterboxdProbeResult.CloudflareChallenge, client(engine).probeApi())
    }

    @Test
    fun unauthorizedApiCallFlagsReconnect() = runTest {
        var expired = false
        val engine = FakeEngine { response(401) }
        val error = assertFailsWith<LetterboxdException> { client(engine, onAuthExpired = { expired = true }).fetchMyFilmState(film) }
        assertEquals(LetterboxdError.AuthExpired, error.error)
        assertTrue(expired)
    }

    @Test
    fun filmIndexCachesMappings() = runTest {
        var saved: String? = null
        var now = 0L
        val index = LetterboxdFilmIndex(load = { saved }, save = { saved = it }, nowMs = { now }, negativeTtlMs = 100)
        index.putFilm(LetterboxdFilm(slug = "the-matrix", tmdbId = 603, averageRating = 4.2))
        index.putTmdb(1, null)
        val reloaded = LetterboxdFilmIndex(load = { saved }, save = { saved = it }, nowMs = { now }, negativeTtlMs = 100)
        assertEquals(LetterboxdFilmIndex.Lookup.Found("the-matrix"), reloaded.lookupTmdb(603))
        assertEquals(LetterboxdFilmIndex.Lookup.NotOnLetterboxd, reloaded.lookupTmdb(1))
        assertEquals(4.2, reloaded.film("the-matrix", 10)?.averageRating)
        now = 200
        assertEquals(LetterboxdFilmIndex.Lookup.Miss, reloaded.lookupTmdb(1))
        assertNull(reloaded.film("the-matrix", 10))
        assertEquals("the-matrix", reloaded.staleFilm("the-matrix")?.slug)
    }

    @Test
    fun followingActivityPageUsesAfterCursorAndDetectsEnd() = runTest {
        val row = """<section data-activity-id="42" class="activity-row -basic" > <a class="avatar -a24" href="/ann/" ><img alt="Ann" src="a.jpg" /></a> <p class="activity-summary"> <a href="/ann/" class="name"><strong>Ann</strong></a> <a href="/ann/film/heat/" class="target"> <span class="context"> watched </span> Heat </a> </p"> <time datetime="2026-11-19T03:49:01.847Z" class="time">x</time> </section>"""
        val engine = FakeEngine { request ->
            if (request.path.contains("after=")) response(200, row + """<section class="activity-row js-pagination-end"><p class='end-of-activity'>End</p></section>""")
            else response(200, row)
        }
        val first = client(engine).fetchFollowingActivityPage("me", after = null)
        assertTrue(first.hasMore)
        assertEquals("42", first.items.single().activityId)
        val second = client(engine).fetchFollowingActivityPage("me", after = "42")
        assertFalse(second.hasMore)
        assertEquals(
            listOf("/ajax/activity-pagination/me/following/", "/ajax/activity-pagination/me/following/?after=42"),
            engine.requests.map { it.path },
        )
        assertFailsWith<IllegalArgumentException> { client(engine).fetchFollowingActivityPage("me", after = "1&x=2") }
    }
}
