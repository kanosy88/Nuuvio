package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F1: film detail block. Writes are exercised against [FakeLetterboxdServer], a stateful simulation of the
 * Letterboxd endpoints (formats verified with a real session for reads, Nov 2026); no real account is touched.
 */
class LetterboxdFilmPanelTest {
    private val film = LetterboxdFilm(slug = "midsommar", lid = "jhxe", uid = "film:459564", tmdbId = 530385, averageRating = 3.7, ratingCount = 1_200_000)


    @Test
    fun parsesFriendActivity() {
        val friends = LetterboxdParsers.parseFriendActivity(friendFragment)
        assertEquals(listOf("alice_b", "bob", "carol"), friends.map { it.username })
        val alice = friends[0]
        assertEquals("Alice B", alice.displayName)
        assertEquals(3.5, alice.rating)
        assertEquals("https://a.ltrbxd.com/avatar/alice.jpg?v=1", alice.avatarUrl)
        assertTrue(alice.watched)
        assertFalse(alice.liked)
        assertEquals("Bob & Co", friends[1].displayName)
        assertTrue(friends[1].liked)
        assertNull(friends[1].rating)
        assertTrue(friends[2].wantsToWatch)
        assertFalse(friends[2].watched)
        assertTrue(LetterboxdParsers.parseFriendActivity("\n\n  \n").isEmpty())
    }

    @Test
    fun readsLogEntryWithReview() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.entries["e1"] = FakeLetterboxdServer.Entry(
            diaryDate = "2026-11-08", rewatch = true, rating = 4.0, like = true,
            review = "Great <b>film</b>.<br/>Second line &amp; more", spoilers = true,
        )
        val entry = server.client().fetchLogEntry("e1")
        assertEquals("e1", entry.id)
        assertEquals("2026-11-08", entry.diaryDate)
        assertTrue(entry.rewatch)
        assertEquals(4.0, entry.rating)
        assertTrue(entry.liked)
        assertEquals("Great film.\nSecond line & more", entry.reviewText)
        assertTrue(entry.containsSpoilers)
        assertEquals("/api/v0/production-log-entry/e1", server.requests.last().path)
    }

    @Test
    fun filmStateExposesLogEntryIds() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.watched = true
        server.rating = 4.0
        server.entries["hdhvZL"] = FakeLetterboxdServer.Entry(diaryDate = "2026-11-08")
        server.entries["rev1"] = FakeLetterboxdServer.Entry(diaryDate = null, review = "Nice")
        val state = server.client().fetchMyFilmState(film)
        assertEquals(listOf("rev1"), state.reviewIds)
        assertEquals(listOf("hdhvZL", "rev1"), state.diaryEntryIds)
        assertEquals(4.0, state.rating)
        assertTrue(state.watched)
    }

    @Test
    fun updateLogEntryPatchesAndClearsRemovedFields() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.entries["e1"] = FakeLetterboxdServer.Entry(diaryDate = "2026-11-08", review = "Old", rating = 3.0)
        server.client().updateLogEntry(film, "e1", LetterboxdLogEntryDraft(diaryDate = null, review = "  New text ", rating = null, liked = true))
        val request = server.requests.last()
        assertEquals("PATCH", request.method)
        assertEquals("tok", request.headers["X-CSRF-TOKEN"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(JsonNull, body["diaryDetails"])
        assertEquals(JsonNull, body["rating"])
        assertEquals("New text", body["review"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(body["productionId"])
        assertEquals("New text", server.entries["e1"]!!.review)
    }

    @Test
    fun loadsPublicAndPersonalData() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.rating = 4.0
        server.watched = true
        server.entries["e1"] = FakeLetterboxdServer.Entry(diaryDate = "2026-11-08", review = "Loved it", rating = 4.0)
        val controller = server.controller()
        controller.load(530385)
        testScheduler.advanceUntilIdle()
        val state = controller.state.value
        assertEquals(LetterboxdFilmPanelState.Phase.Ready, state.phase)
        assertEquals(LetterboxdFilmPanelState.Connection.Connected, state.connection)
        assertEquals(LetterboxdFilmPanelState.PersonalPhase.Ready, state.personal)
        assertEquals(4.0, state.myState?.rating)
        assertEquals("Loved it", state.review?.reviewText)
        assertEquals(listOf("alice_b", "bob", "carol"), state.friends.map { it.username })
        assertEquals(10, state.histogram?.counts?.size)
        assertEquals(55, state.histogram?.total)
        assertNull(state.actionError)
    }

    @Test
    fun notConnectedShowsPublicDataOnly() = runTest {
        val server = FakeLetterboxdServer(this, film, account = LetterboxdAccountState(isLoaded = true))
        val controller = server.controller()
        controller.load(530385)
        testScheduler.advanceUntilIdle()
        val state = controller.state.value
        assertEquals(LetterboxdFilmPanelState.Phase.Ready, state.phase)
        assertEquals(LetterboxdFilmPanelState.Connection.NotConnected, state.connection)
        assertNull(state.myState)
        assertTrue(server.requests.none { it.path.startsWith("/api/") || it.path.contains("friend-activity") })
        // Writes are ignored without an account.
        controller.toggleLike()
        testScheduler.advanceUntilIdle()
        assertTrue(server.requests.none { it.method != "GET" })
    }

    @Test
    fun unknownFilmIsNotOnLetterboxd() = runTest {
        val server = FakeLetterboxdServer(this, film)
        val controller = server.controller(resolve = { null })
        controller.load(1)
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.NotOnLetterboxd, controller.state.value.phase)
        controller.load(null)
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.NotOnLetterboxd, controller.state.value.phase)
    }

    @Test
    fun fallsBackToImdbWhenTmdbIdIsUnknown() = runTest {
        val server = FakeLetterboxdServer(this, film)
        val asked = mutableListOf<String>()
        val controller = server.controller(resolve = { null }, resolveImdb = { asked += it; film })
        controller.load(null, "tt8772262")
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.Ready, controller.state.value.phase)
        assertEquals(listOf("tt8772262"), asked)
        // TMDB id known but not on Letterboxd: IMDb is tried too.
        controller.load(999, "tt8772262")
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.Ready, controller.state.value.phase)
        controller.load(null, "not-an-imdb-id")
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.NotOnLetterboxd, controller.state.value.phase)
    }

    @Test
    fun resolvesImdbRedirect() = runTest {
        val engine = LetterboxdHttpEngine { request ->
            if (request.path == "/imdb/tt8772262/") LetterboxdHttpResponse(302, mapOf("Location" to listOf("https://letterboxd.com/film/midsommar/")), "")
            else LetterboxdHttpResponse(404, emptyMap(), "")
        }
        val client = LetterboxdClient(LetterboxdTransport(engine, minIntervalMs = 0))
        assertEquals("midsommar", client.resolveSlugForImdb("tt8772262"))
        assertNull(client.resolveSlugForImdb("tt0000001"))
    }

    @Test
    fun networkFailureIsVisible() = runTest {
        val server = FakeLetterboxdServer(this, film)
        val controller = server.controller(resolve = { throw LetterboxdException(LetterboxdError.Network) })
        controller.load(530385)
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Phase.Failed, controller.state.value.phase)
        assertEquals(LetterboxdError.Network, controller.state.value.error)
    }

    @Test
    fun ratingLikeAndRemoval() = runTest {
        val server = FakeLetterboxdServer(this, film)
        var removals = 0
        val controller = server.controller(onBecameWatched = { removals++ })
        controller.load(530385)
        testScheduler.advanceUntilIdle()

        controller.setRating(3.5)
        testScheduler.advanceUntilIdle()
        val patch = server.requests.last { it.method == "PATCH" }
        assertEquals("/api/v0/me/rate/jhxe", patch.path)
        assertEquals("""{"rating":3.5}""", patch.body)
        assertEquals(3.5, controller.state.value.myState?.rating)
        assertTrue(controller.state.value.myState!!.watched, "rating marks the film as watched")
        assertEquals(1, removals)

        controller.setRating(null)
        testScheduler.advanceUntilIdle()
        assertEquals("""{"rating":null}""", server.requests.last { it.method == "PATCH" }.body)
        assertNull(controller.state.value.myState?.rating)

        controller.toggleLike()
        testScheduler.advanceUntilIdle()
        assertEquals("/s/film:459564/like/", server.requests.last { it.method == "POST" }.path)
        assertTrue(controller.state.value.myState!!.liked)
        assertEquals(1, removals)
    }

    @Test
    fun writeReviewThenEditIt() = runTest {
        val server = FakeLetterboxdServer(this, film)
        var removals = 0
        val controller = server.controller(onBecameWatched = { removals++ })
        controller.load(530385)
        testScheduler.advanceUntilIdle()

        controller.saveReview(
            LetterboxdLogEntryDraft(diaryDate = "2026-11-19", rewatch = false, review = "First take", containsSpoilers = true, rating = 4.5, liked = true),
        )
        testScheduler.advanceUntilIdle()
        val create = server.requests.last { it.method == "POST" }
        assertEquals("/api/v0/production-log-entries", create.path)
        val body = Json.parseToJsonElement(create.body!!).jsonObject
        assertEquals("jhxe", body["productionId"]!!.jsonPrimitive.content)
        assertEquals("2026-11-19", body["diaryDetails"]!!.jsonObject["diaryDate"]!!.jsonPrimitive.content)
        assertEquals(true, body["review"]!!.jsonObject["containsSpoilers"]!!.jsonPrimitive.booleanOrNull)
        val state = controller.state.value
        assertEquals("First take", state.review?.reviewText)
        assertEquals(4.5, state.myState?.rating)
        assertTrue(state.myState!!.watched)
        assertEquals(1, removals)

        val entryId = state.review!!.id
        controller.saveReview(LetterboxdLogEntryDraft(diaryDate = "2026-11-19", review = "Second take", rating = 4.5, liked = true), entryId)
        testScheduler.advanceUntilIdle()
        val patch = server.requests.last { it.method == "PATCH" }
        assertEquals("/api/v0/production-log-entry/$entryId", patch.path)
        assertEquals("Second take", controller.state.value.review?.reviewText)
        assertEquals(1, server.entries.size)
        assertEquals(1, removals)
    }

    // ------------------------------------------------------------------ Nuvio's native buttons → Letterboxd (F1 revision)

    @Test
    fun nativeWatchedMarksWatchedOnLetterboxdThenLeavesTheLibraryWithoutEcho() = runTest {
        val server = FakeLetterboxdServer(this, film)
        val native = server.nativeSync()
        native.sync.watchedChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        val post = server.requests.single { it.method == "POST" }
        assertEquals("/s/film:459564/watch/", post.path)
        assertEquals("watched=true&__csrf=tok", post.body)
        assertTrue(server.watched)
        assertFalse(server.inWatchlist)
        assertTrue(server.requests.none { it.method == "PATCH" }, "Letterboxd already dropped it from the watchlist")
        assertEquals(1, native.removals)
        assertEquals(listOf<LetterboxdNativeSync.Notice>(LetterboxdNativeSync.Notice.LibraryRemoval(LetterboxdLibraryBridge.Result.Removed)), native.notices)
        assertEquals(listOf("midsommar"), native.changes)

        // The library removal it caused is seen by the observer: not mirrored back onto the watchlist.
        native.sync.libraryChanged(native.target, false)
        testScheduler.advanceUntilIdle()
        assertTrue(server.requests.none { it.method == "PATCH" })

        // Not watched in Nuvio → not watched on Letterboxd; the library isn't touched.
        native.sync.watchedChanged(native.target, false)
        testScheduler.advanceUntilIdle()
        assertEquals("watched=false&__csrf=tok", server.requests.last { it.method == "POST" }.body)
        assertFalse(server.watched)
        assertEquals(1, native.removals)
    }

    @Test
    fun nativeWatchedDropsFromTheWatchlistWhenLetterboxdKeptIt() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.watchKeepsWatchlist = true
        val native = server.nativeSync(removal = LetterboxdLibraryBridge.Result.NotInLibrary)
        native.sync.watchedChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        val patch = server.requests.single { it.method == "PATCH" }
        assertEquals("/api/v0/me/watchlist/jhxe", patch.path)
        assertEquals("""{"inWatchlist":false}""", patch.body)
        assertFalse(server.inWatchlist)
        assertTrue(native.notices.isEmpty(), "nothing to report when the film wasn't in the library")
    }

    @Test
    fun nativeLibraryButtonDrivesTheWatchlist() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.inWatchlist = false
        val native = server.nativeSync()
        native.sync.libraryChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        assertEquals("""{"inWatchlist":true}""", server.requests.last { it.method == "PATCH" }.body)
        assertTrue(server.inWatchlist)
        native.sync.libraryChanged(native.target, false)
        testScheduler.advanceUntilIdle()
        assertEquals("/api/v0/me/watchlist/jhxe", server.requests.last { it.method == "PATCH" }.path)
        assertEquals("""{"inWatchlist":false}""", server.requests.last { it.method == "PATCH" }.body)
        assertFalse(server.inWatchlist)
        assertEquals(0, native.removals)
        assertTrue(native.notices.isEmpty())
    }

    @Test
    fun nativeButtonsDoNothingWithoutAccount() = runTest {
        val server = FakeLetterboxdServer(this, film, account = LetterboxdAccountState(isLoaded = true))
        val native = server.nativeSync()
        native.sync.watchedChanged(native.target, true)
        native.sync.libraryChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        assertTrue(server.requests.isEmpty())
        assertTrue(native.notices.isEmpty())
        assertEquals(0, native.removals)
    }

    @Test
    fun letterboxdFailureIsReportedAndNuvioIsLeftAlone() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.watched = true
        server.entries["e1"] = FakeLetterboxdServer.Entry(diaryDate = "2026-11-08")
        val native = server.nativeSync()
        native.sync.watchedChanged(native.target, false) // refused: there is diary activity
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf<LetterboxdNativeSync.Notice>(
                LetterboxdNativeSync.Notice.Failed(LetterboxdNativeSync.Operation.Unwatched, LetterboxdError.Rejected, "There is activity on this film."),
            ),
            native.notices,
        )
        assertTrue(server.watched)

        server.sessionValid = false
        native.sync.watchedChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        val failure = native.notices.last() as LetterboxdNativeSync.Notice.Failed
        assertEquals(LetterboxdNativeSync.Operation.Watched, failure.operation)
        assertEquals(0, native.removals, "no library removal when Letterboxd wasn't updated")
    }

    @Test
    fun expiredOrUnknownFilmIsReported() = runTest {
        val expired = connectedAccount.copy(status = LetterboxdConnectionStatus.ReconnectRequired)
        val server = FakeLetterboxdServer(this, film, account = expired)
        val native = server.nativeSync()
        native.sync.libraryChanged(native.target, true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<LetterboxdNativeSync.Notice>(LetterboxdNativeSync.Notice.ReconnectRequired(LetterboxdNativeSync.Operation.WatchlistAdd)), native.notices)
        assertTrue(server.requests.none { it.method != "GET" })

        val other = FakeLetterboxdServer(this, film).nativeSync(film = null)
        other.sync.watchedChanged(other.target, true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<LetterboxdNativeSync.Notice>(LetterboxdNativeSync.Notice.NotOnLetterboxd(LetterboxdNativeSync.Operation.Watched)), other.notices)
        assertEquals(0, other.removals)
    }

    @Test
    fun trackerOnlyReportsChangesBetweenSettledObservations() {
        val tracker = LetterboxdNativeStateTracker()
        val base = LetterboxdNativeStateTracker.Observation(
            watchedLoaded = false, watchedRemoteLoaded = false, watched = false,
            libraryLoaded = false, librarySource = "LOCAL", saved = false,
        )
        assertTrue(tracker.next(base).isEmpty())
        // Loading the repositories isn't a click.
        assertTrue(tracker.next(base.copy(watchedLoaded = true, watched = true, libraryLoaded = true, saved = true)).isEmpty())
        val loaded = base.copy(watchedLoaded = true, watched = true, libraryLoaded = true, saved = true)
        // Remote watched items arriving, or another library source, aren't clicks either.
        assertTrue(tracker.next(loaded.copy(watchedRemoteLoaded = true, watched = false)).isEmpty())
        assertTrue(tracker.next(loaded.copy(watchedRemoteLoaded = true, watched = false, librarySource = "TRAKT", saved = false)).isEmpty())
        val settled = loaded.copy(watchedRemoteLoaded = true, watched = false, librarySource = "TRAKT", saved = false)
        assertEquals(listOf<LetterboxdNativeStateTracker.Change>(LetterboxdNativeStateTracker.Change.Watched(true)), tracker.next(settled.copy(watched = true)))
        assertEquals(listOf<LetterboxdNativeStateTracker.Change>(LetterboxdNativeStateTracker.Change.Library(true)), tracker.next(settled.copy(watched = true, saved = true)))
        assertTrue(tracker.next(settled.copy(watched = true, saved = true)).isEmpty())
        assertEquals(
            listOf(LetterboxdNativeStateTracker.Change.Watched(false), LetterboxdNativeStateTracker.Change.Library(false)),
            tracker.next(settled),
        )
        // Profile switch: unloaded, then reloaded with other values → nothing.
        assertTrue(tracker.next(settled.copy(watchedLoaded = false, libraryLoaded = false)).isEmpty())
        assertTrue(tracker.next(settled.copy(watched = true, saved = true)).isEmpty())
    }

    @Test
    fun echoGuardForgetsAfterTtl() {
        var now = 0L
        val guard = LetterboxdLibraryEchoGuard(ttlMs = 1_000, nowMs = { now })
        assertFalse(guard.consume("a"))
        guard.mark("a")
        assertTrue(guard.consume("a"))
        assertFalse(guard.consume("a"), "consumed once")
        guard.mark("b")
        now = 5_000
        assertFalse(guard.consume("b"))
    }

    @Test
    fun expiredSessionAsksToReconnect() = runTest {
        val server = FakeLetterboxdServer(this, film)
        server.sessionValid = false
        val controller = server.controller()
        controller.load(530385)
        testScheduler.advanceUntilIdle()
        val state = controller.state.value
        assertEquals(LetterboxdFilmPanelState.Phase.Ready, state.phase)
        assertEquals(LetterboxdFilmPanelState.Connection.ReconnectRequired, state.connection)
        assertEquals(LetterboxdError.AuthExpired, state.personalError)
    }

    @Test
    fun connectingLaterLoadsPersonalData() = runTest {
        val account = MutableStateFlow(LetterboxdAccountState(isLoaded = true))
        val server = FakeLetterboxdServer(this, film, account = account.value)
        val controller = server.controller(account = account)
        controller.load(530385)
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Connection.NotConnected, controller.state.value.connection)
        account.value = connectedAccount
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdFilmPanelState.Connection.Connected, controller.state.value.connection)
        assertEquals(LetterboxdFilmPanelState.PersonalPhase.Ready, controller.state.value.personal)
    }

    @Test
    fun formatting() {
        assertEquals("★★★½", com.nuvio.app.features.letterboxd.ui.starsText(3.5))
        assertEquals("½", com.nuvio.app.features.letterboxd.ui.starsText(0.5))
        assertEquals("3.7", com.nuvio.app.features.letterboxd.ui.formatOneDecimal(3.7316))
        assertEquals("4", com.nuvio.app.features.letterboxd.ui.formatRating(4.0))
        assertEquals("950", com.nuvio.app.features.letterboxd.ui.formatCompactCount(950))
        assertEquals("12.3K", com.nuvio.app.features.letterboxd.ui.formatCompactCount(12_345))
        assertEquals("1M", com.nuvio.app.features.letterboxd.ui.formatCompactCount(1_000_000))
    }

    private companion object {
    /** Friend activity fragment, same markup as the real one (member names are made up). */
            val friendFragment = """
            <section class="section activity-from-friends -clear -friends-watched -friends-want-to-watch">
              <ul class="avatar-list -watchedstate">
                <li class="listitem -rated">
                  <a href="/alice_b/film/midsommar/activity/" class="watchedstate-avatar tooltip" title="Rated by Alice B">
                    <span class="avatar -a40" > <img src="https://a.ltrbxd.com/avatar/alice.jpg?v=1" alt="Alice B" width="40" height="40" /> </span>
                    <span class="rating -nano rated-7"> ★★★½ </span>
                  </a>
                </li>
                <li class="listitem -watched -liked">
                  <a href="/bob/film/midsommar/activity/" class="watchedstate-avatar tooltip" title="Watched by Bob">
                    <span class="avatar -a40" > <img src="https://a.ltrbxd.com/avatar/bob.jpg" alt="Bob &amp; Co" /> </span>
                  </a>
                </li>
                <li class="listitem -watchlisted">
                  <a href="/carol/watchlist/" class="watchedstate-avatar tooltip" title="Carol wants to watch">
                    <span class="avatar -a40" > <img src="https://a.ltrbxd.com/avatar/carol.jpg" alt="Carol" /> </span>
                    <span class="watchlist">Carol wants to watch</span>
                  </a>
                </li>
              </ul>
            </section>
        """.trimIndent()
        val connectedAccount = LetterboxdAccountState(
            isLoaded = true,
            status = LetterboxdConnectionStatus.Connected,
            username = "member",
            cookieNames = listOf("letterboxd.user.CURRENT"),
        )
    }

    /** Stateful simulation of the Letterboxd endpoints used by F1. */
    private class FakeLetterboxdServer(
        private val testScope: TestScope,
        private val film: LetterboxdFilm,
        private val account: LetterboxdAccountState = connectedAccount,
    ) {
        data class Entry(
            var diaryDate: String? = null,
            var rewatch: Boolean = false,
            var rating: Double? = null,
            var like: Boolean = false,
            var review: String? = null,
            var spoilers: Boolean = false,
        )

        var watched = false
        var liked = false
        var rating: Double? = null
        var inWatchlist = true
        /** Simulates Letterboxd keeping a film in the watchlist after "watched" (not expected). */
        var watchKeepsWatchlist = false
        var sessionValid = true
        val entries = LinkedHashMap<String, Entry>()
        val requests = mutableListOf<LetterboxdHttpRequest>()
        private var nextId = 1
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + SupervisorJob())
        private val json = Json { ignoreUnknownKeys = true }

        private fun handle(request: LetterboxdHttpRequest): LetterboxdHttpResponse {
            requests += request
            val lid = film.lid
            val uid = film.uid
            val path = request.path
            if (!sessionValid && (path.startsWith("/api/") || path.startsWith("/s/"))) return LetterboxdHttpResponse(401, emptyMap(), "")
            return when {
                path == LetterboxdClient.SESSION_PROBE_PATH -> ok(
                    "person = { username: \"\", loggedIn: false }; supermodelCSRF = 'tok'; person.username = \"member\"; person.loggedIn = true;",
                )
                path == "/csi/film/${film.slug}/rating-histogram/" -> ok(
                    (1..10).joinToString("") { """<tr class="column"><td title="$it&nbsp;★ ratings (1%)"></td></tr>""" },
                )
                path.startsWith("/csi/film/${film.slug}/friend-activity/") -> ok(friendFragment)
                path == "/api/v0/production/$lid/me" && request.method == "GET" -> ok(meJson())
                path == "/s/$uid/watch/" -> {
                    val target = form(request)["watched"] == "true"
                    if (!target && entries.isNotEmpty()) {
                        LetterboxdHttpResponse(200, emptyMap(), """{"result":false,"messages":["There is activity on this film."]}""")
                    } else {
                        watched = target
                        if (target && !watchKeepsWatchlist) inWatchlist = false
                        ok("""{"result":true,"watched":$target}""")
                    }
                }
                path == "/s/$uid/like/" -> {
                    liked = form(request)["liked"] == "true"
                    ok("""{"result":true,"liked":$liked,"count":1}""")
                }
                path == "/api/v0/me/rate/$lid" && request.method == "PATCH" -> {
                    rating = body(request)["rating"]?.jsonPrimitive?.doubleOrNull
                    if (rating != null) { watched = true; inWatchlist = false }
                    LetterboxdHttpResponse(204, emptyMap(), "")
                }
                path == "/api/v0/me/watchlist/$lid" && request.method == "PATCH" -> {
                    inWatchlist = body(request)["inWatchlist"]?.jsonPrimitive?.booleanOrNull ?: error("inWatchlist expected")
                    LetterboxdHttpResponse(204, emptyMap(), "")
                }
                path == "/api/v0/production-log-entries" && request.method == "POST" -> {
                    val id = "e${nextId++}"
                    entries[id] = Entry().also { apply(it, body(request)) }
                    watched = true
                    inWatchlist = false
                    ok("""{"id":"$id"}""")
                }
                path.startsWith("/api/v0/production-log-entry/") -> {
                    val id = path.substringAfterLast('/')
                    val entry = entries[id] ?: return LetterboxdHttpResponse(404, emptyMap(), """{"error":true}""")
                    when (request.method) {
                        "PATCH" -> { apply(entry, body(request)); ok("{}") }
                        else -> ok(entryJson(id, entry))
                    }
                }
                else -> LetterboxdHttpResponse(500, emptyMap(), "unexpected ${request.method} $path")
            }
        }

        private fun apply(entry: Entry, body: JsonObject) {
            val diary = body["diaryDetails"] as? JsonObject
            entry.diaryDate = diary?.get("diaryDate")?.jsonPrimitive?.contentOrNull
            entry.rewatch = diary?.get("rewatch")?.jsonPrimitive?.booleanOrNull ?: false
            val review = body["review"] as? JsonObject
            entry.review = review?.get("text")?.jsonPrimitive?.contentOrNull
            entry.spoilers = review?.get("containsSpoilers")?.jsonPrimitive?.booleanOrNull ?: false
            entry.rating = body["rating"]?.jsonPrimitive?.doubleOrNull
            entry.like = body["like"]?.jsonPrimitive?.booleanOrNull ?: false
            entry.rating?.let { rating = it }
            if (entry.like) liked = true
        }

        private fun meJson(): String {
            val reviews = entries.filterValues { it.review != null }.keys.joinToString(",") { "\"$it\"" }
            val diary = entries.keys.joinToString(",") { "\"$it\"" }
            return """{"type":"Film","productionId":"${film.lid}","watched":$watched,"liked":$liked,"inWatchlist":$inWatchlist,""" +
                (rating?.let { "\"rating\":$it," } ?: "") +
                """"reviews":[$reviews],"diaryEntries":[$diary],"drafts":[]}"""
        }

        private fun entryJson(id: String, entry: Entry): String = buildString {
            append("""{"id":"$id","production":{"id":"${film.lid}"},""")
            entry.diaryDate?.let { append(""""diaryDetails":{"diaryDate":"$it","rewatch":${entry.rewatch}},""") }
            entry.review?.let { append(""""review":{"text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(it))},"containsSpoilers":${entry.spoilers}},""") }
            entry.rating?.let { append(""""rating":$it,""") }
            append(""""like":${entry.like},"whenCreated":"2026-11-19T10:00:0${id.last()}Z","links":[{"type":"letterboxd","url":"https://letterboxd.com/member/film/${film.slug}/"}]}""")
        }

        private fun ok(body: String) = LetterboxdHttpResponse(200, emptyMap(), body)
        private fun body(request: LetterboxdHttpRequest) = json.parseToJsonElement(request.body!!).jsonObject
        private fun form(request: LetterboxdHttpRequest) = request.body!!.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

        suspend fun client(): LetterboxdClient {
            val transport = LetterboxdTransport({ handle(it) }, minIntervalMs = 0)
            if (account.hasCredentials) {
                transport.configure(linkedMapOf("letterboxd.user.CURRENT" to "session", LETTERBOXD_CSRF_COOKIE to "tok"), "UA/1.0")
            }
            return LetterboxdClient(transport)
        }

        suspend fun controller(
            resolve: suspend (Int) -> LetterboxdFilm? = { film },
            resolveImdb: suspend (String) -> LetterboxdFilm? = { null },
            account: MutableStateFlow<LetterboxdAccountState> = MutableStateFlow(this.account),
            onBecameWatched: suspend () -> Unit = {},
        ): LetterboxdFilmPanelController {
            val source = ClientLetterboxdFilmSource(client(), account, resolve, resolveImdb)
            // Not backgroundScope: the test scheduler's advanceUntilIdle() skips background work.
            return LetterboxdFilmPanelController(source, controllerScope, onBecameWatched)
        }

        class Native(val sync: LetterboxdNativeSync, val target: LetterboxdNativeSync.Target) {
            var removals = 0
            val notices = mutableListOf<LetterboxdNativeSync.Notice>()
            val changes = mutableListOf<String>()
        }

        suspend fun nativeSync(
            film: LetterboxdFilm? = this.film,
            removal: LetterboxdLibraryBridge.Result = LetterboxdLibraryBridge.Result.Removed,
        ): Native {
            val source = ClientLetterboxdFilmSource(client(), MutableStateFlow(account), { film })
            lateinit var native: Native
            val sync = LetterboxdNativeSync(
                source = source,
                scope = controllerScope,
                notify = { native.notices += it },
                ownRemovals = LetterboxdLibraryEchoGuard(),
            )
            val target = LetterboxdNativeSync.Target(
                key = "tt8772262",
                resolveFilm = { film },
                removeFromLibrary = { native.removals++; removal },
            )
            native = Native(sync, target)
            controllerScope.launch { sync.changes.collect { native.changes += it } }
            return native
        }
    }
}
