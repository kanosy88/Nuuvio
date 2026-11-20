package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F3: the watchlist sync end to end — the real [LetterboxdClient] / [LetterboxdTransport] / [LetterboxdFilmResolver]
 * against [FakeLetterboxd] (simulated pages and endpoints, formats verified on the real site for reads), with a fake
 * Nuvio library. No real account or library is touched.
 */
class LetterboxdWatchlistSyncEngineTest {

    @Test
    fun firstSyncThenFollowingSyncsApplyEveryRule() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("alien", "dune-2021", "tenet", "chernobyl")
        server.watched += "jaws"
        val library = FakeLibrary()
        library.put("tt0078748", "Alien", "tt0078748", null) // on both sides
        library.put("tt0113277", "Heat", "tt0113277", null) // library only, not watched
        library.put("tmdb:578", "Jaws", null, 578) // library only, watched on Letterboxd
        library.put("custom:home", "Home movie", null, null) // no ids
        library.put("tmdb:999999", "Obscure", null, 999999) // not on Letterboxd
        val store = MemoryStore()
        val engine = server.engine(library, store)

        // 1) First sync: union, nothing removed.
        val first = engine.analyze()
        val plan = first.plan
        assertTrue(plan.firstSync)
        assertEquals(listOf("alien"), plan.inSync)
        assertEquals(listOf("heat"), plan.addToLetterboxd.map { it.slug })
        assertEquals(listOf("dune-2021", "tenet"), plan.addToLibrary.map { it.slug })
        assertTrue(plan.removeFromLetterboxd.isEmpty() && plan.removeFromLibrary.isEmpty())
        assertEquals(WatchlistSyncSkip.Reason.WatchedKeptOnFirstSync, plan.skipped.single().reason)
        assertEquals(
            setOf("Home movie" to WatchlistSyncUnmatched.Reason.NoExternalId, "Obscure" to WatchlistSyncUnmatched.Reason.NotOnLetterboxd,
                "Chernobyl (2019)" to WatchlistSyncUnmatched.Reason.TvShow),
            plan.unmatched.map { it.title to it.reason }.toSet(),
        )
        assertEquals(4, first.watchlistAnnounced)
        assertEquals(4, first.watchlistRead)
        assertTrue(server.writes.isEmpty(), "analysis never writes")
        // Watchlist read page by page (2 per page here), every request spaced by the read interval.
        assertEquals(listOf("/member/watchlist/", "/member/watchlist/page/2/"), server.paths().filter { "/watchlist/" in it })
        assertTrue(server.sleeps.isNotEmpty() && server.sleeps.all { it == READ_MS }, "reads spaced: ${server.sleeps}")

        server.sleeps.clear()
        val report = engine.apply(first)
        assertTrue(report.failures.isEmpty(), "${report.failures}")
        assertTrue(report.snapshotSaved)
        assertEquals(listOf("+heat"), server.writes)
        assertEquals(listOf("tt1160419", "tt6723592"), library.added)
        assertTrue("heat" in server.watchlist)
        assertEquals(setOf("alien", "heat", "dune-2021", "tenet"), store.snapshots.values.single().films.keys)
        val addedDune = library.items.getValue("tt1160419")
        assertEquals(438631, addedDune.tmdbId)

        // 2) Nothing changed: Jaws (watched, kept by the first sync) now leaves the library. Ids come from the cache.
        server.requests.clear()
        val second = engine.analyze()
        assertEquals(listOf("jaws"), second.plan.removeFromLibrary.map { it.slug })
        assertEquals(1, second.plan.actions.size)
        assertTrue(server.paths().none { it.startsWith("/tmdb/") || it.startsWith("/imdb/") || it.startsWith("/film/") }, "${server.paths()}")
        engine.apply(second)
        assertEquals(listOf("tmdb:578"), library.removed)
        assertTrue(server.writes == listOf("+heat"), "removing from the library writes nothing on Letterboxd")

        // 3) The user removes Tenet from the library, and Alien from the watchlist on Letterboxd; adds a new film there.
        library.items.remove("tt6723592")
        server.watchlist.remove("alien")
        server.watchlist += "past-lives"
        val third = engine.analyze()
        assertEquals(listOf("tenet"), third.plan.removeFromLetterboxd.map { it.slug })
        val alien = third.plan.addToLetterboxd.single()
        assertEquals("alien", alien.slug)
        assertTrue(alien.removedOnLetterboxd, "the library wins")
        assertEquals(listOf("past-lives"), third.plan.addToLibrary.map { it.slug })

        // Letterboxd refuses the Tenet removal: the rest is applied, Tenet stays in the snapshot and is retried.
        server.rejectWatchlistWrites += "tenet"
        val partial = engine.apply(third)
        val failure = partial.failures.single()
        assertEquals("tenet", failure.action.slug)
        assertEquals(LetterboxdError.Rejected, assertIs<WatchlistSyncResult.Outcome.Failed>(failure.outcome).error)
        assertTrue("alien" in server.watchlist && "tenet" in server.watchlist)
        assertTrue("tt27766216" in library.items)
        assertTrue("tenet" in store.snapshots.values.single().films)

        server.rejectWatchlistWrites.clear()
        val retry = engine.analyze()
        assertEquals(listOf("tenet"), retry.plan.actions.map { it.slug })
        assertTrue(engine.apply(retry).failures.isEmpty())
        assertTrue("tenet" !in server.watchlist)
        assertTrue("tenet" !in store.snapshots.values.single().films)
        assertTrue(engine.analyze().plan.actions.isEmpty(), "converged")
    }

    @Test
    fun writesAreSpacedAndCarryTheCsrfToken() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021")
        val library = FakeLibrary()
        library.put("tt0113277", "Heat", "tt0113277", null)
        library.put("tt0078748", "Alien", "tt0078748", null)
        val engine = server.engine(library, MemoryStore())
        val analysis = engine.analyze()
        server.sleeps.clear()
        server.requests.clear()
        engine.apply(analysis)
        val patches = server.requests.filter { it.method == "PATCH" }
        assertEquals(listOf("/api/v0/me/watchlist/Lheat", "/api/v0/me/watchlist/Lalien"), patches.map { it.path })
        assertTrue(patches.all { it.headers["X-CSRF-TOKEN"] == "tok" })
        assertEquals(listOf(WRITE_MS, WRITE_MS), server.sleeps, "spaced from the last read and from each other")
    }

    @Test
    fun cloudflareDuringApplyStopsTheLetterboxdWritesButNotTheLibrary() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021")
        val library = FakeLibrary()
        library.put("tt0113277", "Heat", "tt0113277", null)
        library.put("tt0078748", "Alien", "tt0078748", null)
        val store = MemoryStore()
        val engine = server.engine(library, store)
        val analysis = engine.analyze()
        server.challengeWrites = true
        val report = engine.apply(analysis)
        assertEquals(listOf("tt1160419"), library.added, "library change applied")
        val outcomes = report.results.associate { it.action.slug to it.outcome }
        assertIs<WatchlistSyncResult.Outcome.Done>(outcomes.getValue("dune-2021"))
        assertEquals(LetterboxdError.CloudflareChallenge, assertIs<WatchlistSyncResult.Outcome.Failed>(outcomes.getValue("heat")).error)
        assertEquals(LetterboxdError.CloudflareChallenge, assertIs<WatchlistSyncResult.Outcome.NotAttempted>(outcomes.getValue("alien")).error)
        assertEquals(1, server.requests.count { it.method == "PATCH" }, "stopped after the challenge")
        assertEquals(setOf("dune-2021"), store.snapshots.values.single().films.keys, "only successes recorded")
    }

    @Test
    fun cloudflareDuringAnalysisFailsClearlyWithoutWriting() = runTest {
        val server = FakeLetterboxd()
        server.challengeReads = true
        val library = FakeLibrary()
        library.put("tt0113277", "Heat", "tt0113277", null)
        val store = MemoryStore()
        val failure = assertFailsWith<WatchlistSyncException> { server.engine(library, store).analyze() }
        assertEquals(WatchlistSyncException.Failure.Letterboxd, failure.failure)
        assertEquals(LetterboxdError.CloudflareChallenge, failure.error)
        assertTrue(server.writes.isEmpty() && library.added.isEmpty() && store.snapshots.isEmpty())
    }

    @Test
    fun libraryChangesAfterThePreviewAreNotOverwritten() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021")
        val library = FakeLibrary()
        library.put("tt0113277", "Heat", "tt0113277", null)
        val engine = server.engine(library, MemoryStore())
        val analysis = engine.analyze()
        library.items.remove("tt0113277") // removed by the user while the preview was open
        library.put("tt1160419", "Dune", "tt1160419", null) // added by the user too
        val report = engine.apply(analysis)
        val outcomes = report.results.associate { it.action.slug to it.outcome }
        assertEquals(WatchlistSyncResult.Outcome.ChangedSincePreview, outcomes["heat"])
        assertEquals(WatchlistSyncResult.Outcome.AlreadyDone, outcomes["dune-2021"])
        assertTrue(server.writes.isEmpty())
        assertTrue(library.added.isEmpty())
    }

    @Test
    fun aProfileOrSourceSwitchCancelsTheApply() = runTest {
        val server = FakeLetterboxd()
        val library = FakeLibrary()
        library.put("tt0113277", "Heat", "tt0113277", null)
        val engine = server.engine(library, MemoryStore())
        val analysis = engine.analyze()
        library.scopeValue = "p2:local"
        val failure = assertFailsWith<WatchlistSyncException> { engine.apply(analysis) }
        assertEquals(WatchlistSyncException.Failure.LibraryChanged, failure.failure)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun snapshotsAreKeptPerLibraryAndAccount() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021")
        val library = FakeLibrary()
        val store = MemoryStore()
        val engine = server.engine(library, store)
        engine.apply(engine.analyze())
        assertEquals(setOf("p1:local|lb:member"), store.snapshots.keys)
        library.scopeValue = "p1:trakt"
        assertTrue(engine.analyze().plan.firstSync, "another library source starts with a first sync")
    }

    @Test
    fun libraryNotLoadedYetIsReported() = runTest {
        val library = FakeLibrary().apply { scopeValue = null }
        val failure = assertFailsWith<WatchlistSyncException> { FakeLetterboxd().engine(library, MemoryStore()).analyze() }
        assertEquals(WatchlistSyncException.Failure.LibraryNotReady, failure.failure)
    }

    @Test
    fun incompleteWatchlistAndMassRemovalAreFlagged() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021", "tenet", "alien", "heat", "jaws", "past-lives")
        val library = FakeLibrary()
        val store = MemoryStore()
        val engine = server.engine(library, store)
        engine.apply(engine.analyze())
        assertEquals(6, library.items.size)
        // The library is emptied (e.g. signed out of the Nuvio account): the preview warns before removing everything.
        library.items.clear()
        server.announcedExtra = 3
        val analysis = engine.analyze()
        assertEquals(6, analysis.plan.removeFromLetterboxd.size)
        assertTrue(analysis.massRemoval)
        assertTrue(analysis.incompleteWatchlist)
        assertEquals(9, analysis.watchlistAnnounced)
    }

    @Test
    fun controllerRunsPreviewThenApplyAndCanCancel() = runTest {
        val server = FakeLetterboxd()
        server.watchlist += listOf("dune-2021")
        val library = FakeLibrary()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val controller = LetterboxdWatchlistSyncController(server.engine(library, MemoryStore()), scope)

        controller.analyze()
        assertIs<LetterboxdWatchlistSyncController.State.Analyzing>(controller.state.value)
        controller.dismiss()
        testScheduler.advanceUntilIdle()
        assertEquals(LetterboxdWatchlistSyncController.State.Idle, controller.state.value, "cancelled analysis stays closed")

        controller.analyze()
        testScheduler.advanceUntilIdle()
        val preview = assertIs<LetterboxdWatchlistSyncController.State.Preview>(controller.state.value)
        assertEquals(1, preview.analysis.plan.addToLibrary.size)
        assertTrue(library.added.isEmpty(), "nothing applied before confirmation")
        controller.apply()
        testScheduler.advanceUntilIdle()
        val done = assertIs<LetterboxdWatchlistSyncController.State.Done>(controller.state.value)
        assertTrue(done.report.failures.isEmpty())
        assertEquals(listOf("tt1160419"), library.added)
        controller.dismiss()
        assertEquals(LetterboxdWatchlistSyncController.State.Idle, controller.state.value)
    }

    // ------------------------------------------------------------------ interaction with the native library hook (F1 revision)

    @Test
    fun libraryChangesMadeByTheSyncAreNotMirroredBackByTheDetailPageHook() = runTest {
        val guard = LetterboxdLibraryEchoGuard(nowMs = { 0L })
        val source = RecordingSource()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val native = LetterboxdNativeSync(source, scope, notify = {}, ownRemovals = guard)
        val target = LetterboxdNativeSync.Target("tt1160419", resolveFilm = { dune }, removeFromLibrary = { LetterboxdLibraryBridge.Result.Removed })

        guard.mark("tt1160419", saved = true) // the sync added Dune to the library
        native.libraryChanged(target, saved = true)
        testScheduler.advanceUntilIdle()
        assertTrue(source.watchlistWrites.isEmpty(), "no second write for the sync's own change")

        native.libraryChanged(target, saved = false) // then the user removes it: mirrored as usual
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), source.watchlistWrites)

        guard.mark("tt1160419", saved = false) // the sync removed it (watched on Letterboxd)…
        native.libraryChanged(target, saved = true) // …a user re-add in the other direction still counts
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true), source.watchlistWrites)
        assertTrue(guard.consume("tt1160419", saved = false))
    }

    // ------------------------------------------------------------------ fakes

    private class FakeLibrary : WatchlistSyncLibrary {
        var scopeValue: String? = "p1:local"
        val items = LinkedHashMap<String, WatchlistSyncLibraryMovie>()
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()

        fun put(id: String, title: String, imdb: String?, tmdb: Int?) {
            items[id] = WatchlistSyncLibraryMovie(id, title, imdb, tmdb)
        }

        override suspend fun scope() = scopeValue
        override suspend fun movies() = items.values.toList()
        override suspend fun add(film: LetterboxdFilm, nuvioId: String): WatchlistSyncLibraryWrite {
            items[nuvioId] = WatchlistSyncLibraryMovie(nuvioId, film.name ?: film.slug, film.imdbId, film.tmdbId)
            added += nuvioId
            return WatchlistSyncLibraryWrite.Done
        }

        override suspend fun remove(movies: List<WatchlistSyncLibraryMovie>): WatchlistSyncLibraryWrite {
            movies.forEach { items.remove(it.nuvioId); removed += it.nuvioId }
            return WatchlistSyncLibraryWrite.Done
        }
    }

    private class MemoryStore : WatchlistSyncSnapshotStore {
        val snapshots = LinkedHashMap<String, LetterboxdWatchlistSnapshot>()
        override suspend fun load(scope: String) = snapshots[scope]
        override suspend fun save(scope: String, snapshot: LetterboxdWatchlistSnapshot) {
            snapshots[scope] = snapshot
        }
    }

    /** Remote over the real client, transport and resolver (same code as [RepositoryWatchlistRemote] uses). */
    private class ClientRemote(
        private val client: LetterboxdClient,
        private val transport: LetterboxdTransport,
        private val resolver: LetterboxdFilmResolver,
    ) : LetterboxdWatchlistRemote {
        override suspend fun username() = "member"
        override suspend fun watchlistPage(username: String, page: Int) = client.fetchWatchlistPage(username, page)
        override suspend fun filmByTmdb(tmdbId: Int) = resolver.byTmdb(tmdbId, Long.MAX_VALUE)
        override suspend fun filmByImdb(imdbId: String) = resolver.byImdb(imdbId, Long.MAX_VALUE)
        override suspend fun film(slug: String) = resolver.film(slug, Long.MAX_VALUE)
        override suspend fun isWatched(film: LetterboxdFilm) = client.fetchMyFilmState(film).watched
        override suspend fun setInWatchlist(film: LetterboxdFilm, inWatchlist: Boolean) = client.setInWatchlist(film, inWatchlist)
        override suspend fun <T> paced(intervalMs: Long, block: suspend () -> T): T = transport.withPacing(intervalMs, block)
    }

    /** Simulated letterboxd.com: watchlist pages, `/tmdb/` + `/imdb/` redirects, film pages, `/me` and watchlist PATCH. */
    private class FakeLetterboxd {
        data class Film(val slug: String, val lid: String, val tmdb: Int?, val imdb: String?, val name: String, val year: Int, val tv: Boolean = false)

        val films = listOf(
            Film("alien", "Lalien", 348, "tt0078748", "Alien", 1979),
            Film("heat", "Lheat", 949, "tt0113277", "Heat", 1995),
            Film("jaws", "Ljaws", 578, "tt0073195", "Jaws", 1975),
            Film("dune-2021", "Ldune", 438631, "tt1160419", "Dune", 2021),
            Film("tenet", "Ltenet", 577922, "tt6723592", "Tenet", 2020),
            Film("past-lives", "Lpast", 666277, "tt27766216", "Past Lives", 2023),
            Film("chernobyl", "Lcher", null, "tt7366338", "Chernobyl", 2019, tv = true),
        ).associateBy { it.slug }

        val watchlist = mutableListOf<String>()
        val watched = mutableSetOf<String>()
        val rejectWatchlistWrites = mutableSetOf<String>()
        var challengeWrites = false
        var challengeReads = false
        var announcedExtra = 0
        val requests = mutableListOf<LetterboxdHttpRequest>()
        val writes = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        private var clock = 1_000_000L
        private val pageSize = 2
        private val json = Json { ignoreUnknownKeys = true }

        fun paths() = requests.map { it.path }

        private val challenge = LetterboxdHttpResponse(403, emptyMap(), "<html><title>Just a moment...</title></html>")

        private fun handle(request: LetterboxdHttpRequest): LetterboxdHttpResponse {
            requests += request
            val path = request.path
            if (challengeReads && request.method == "GET") return challenge
            Regex("""^/member/watchlist/(?:page/(\d+)/)?$""").find(path)?.let { match ->
                val page = match.groupValues[1].toIntOrNull() ?: 1
                val slice = watchlist.drop((page - 1) * pageSize).take(pageSize)
                val next = watchlist.size > page * pageSize
                return ok(buildString {
                    append("""<div class="cols-2 js-watchlist-content" data-num-entries="${watchlist.size + announcedExtra}"><ul>""")
                    slice.forEach { slug ->
                        val film = films.getValue(slug)
                        append("""<li class="griditem"><div class="react-component" data-item-name="${film.name} (${film.year})" """)
                        append("""data-item-slug="$slug" data-postered-identifier='{&quot;lid&quot;:&quot;${film.lid}&quot;,&quot;uid&quot;:&quot;film:${film.tmdb ?: 1}&quot;}'></div></li>""")
                    }
                    append("</ul>")
                    if (next) append("""<div class="pagination"><a class="next" href="/member/watchlist/page/${page + 1}/">Older</a></div>""")
                    append("</div>")
                })
            }
            Regex("""^/tmdb/(\d+)/$""").find(path)?.let { match ->
                val film = films.values.firstOrNull { it.tmdb == match.groupValues[1].toInt() } ?: return LetterboxdHttpResponse(404, emptyMap(), "")
                return LetterboxdHttpResponse(302, mapOf("Location" to listOf("$LETTERBOXD_BASE_URL/film/${film.slug}/")), "")
            }
            Regex("""^/imdb/(tt\d+)/$""").find(path)?.let { match ->
                val film = films.values.firstOrNull { it.imdb == match.groupValues[1] } ?: return LetterboxdHttpResponse(404, emptyMap(), "")
                return LetterboxdHttpResponse(302, mapOf("Location" to listOf("$LETTERBOXD_BASE_URL/film/${film.slug}/")), "")
            }
            Regex("""^/film/([^/]+)/$""").find(path)?.let { match ->
                val film = films[match.groupValues[1]] ?: return LetterboxdHttpResponse(404, emptyMap(), "")
                return ok(filmPage(film))
            }
            Regex("""^/api/v0/production/([^/]+)/me$""").find(path)?.let { match ->
                val film = films.values.first { it.lid == match.groupValues[1] }
                return ok("""{"watched":${film.slug in watched},"liked":false,"inWatchlist":${film.slug in watchlist},"reviews":[],"diaryEntries":[]}""")
            }
            Regex("""^/api/v0/me/watchlist/([^/]+)$""").find(path)?.let { match ->
                if (request.method != "PATCH") return LetterboxdHttpResponse(405, emptyMap(), "")
                if (challengeWrites) return challenge
                if (request.headers["X-CSRF-TOKEN"] != "tok") return LetterboxdHttpResponse(403, emptyMap(), "")
                val film = films.values.first { it.lid == match.groupValues[1] }
                if (film.slug in rejectWatchlistWrites) return LetterboxdHttpResponse(400, emptyMap(), """{"messages":["Not allowed"]}""")
                val inWatchlist = json.parseToJsonElement(request.body!!).jsonObject["inWatchlist"]!!.jsonPrimitive.booleanOrNull!!
                if (inWatchlist) { if (film.slug !in watchlist) watchlist += film.slug } else watchlist.remove(film.slug)
                writes += (if (inWatchlist) "+" else "-") + film.slug
                return LetterboxdHttpResponse(204, emptyMap(), "")
            }
            return LetterboxdHttpResponse(500, emptyMap(), "unexpected ${request.method} $path")
        }

        private fun filmPage(film: Film) = buildString {
            append("""<html><head><meta name="production:identifier" content="{&quot;lid&quot;:&quot;${film.lid}&quot;,&quot;uid&quot;:&quot;film:${film.tmdb ?: 1}&quot;}">""")
            append("""<meta name="production:name-and-year" content="${film.name} (${film.year})"></head>""")
            if (film.tv) {
                append("""<body data-tmdb-id="" data-tmdb-type="movie"><a href="https://www.themoviedb.org/tv/87108/">TMDB</a>""")
            } else {
                append("""<body data-tmdb-id="${film.tmdb}" data-tmdb-type="movie"><a href="https://www.themoviedb.org/movie/${film.tmdb}/">TMDB</a>""")
            }
            film.imdb?.let { append("""<a href="http://www.imdb.com/title/$it/maindetails">IMDb</a>""") }
            append("</body></html>")
        }

        private fun ok(body: String) = LetterboxdHttpResponse(200, emptyMap(), body)

        suspend fun engine(library: WatchlistSyncLibrary, store: WatchlistSyncSnapshotStore): LetterboxdWatchlistSyncEngine {
            val transport = LetterboxdTransport(
                engine = { handle(it) },
                limits = { LetterboxdRateLimits(requestGapMs = 0) },
                nowMs = { clock },
                sleep = { sleeps += it; clock += it },
            )
            transport.configure(linkedMapOf("letterboxd.user.CURRENT" to "session", LETTERBOXD_CSRF_COOKIE to "tok"), "UA/1.0")
            val client = LetterboxdClient(transport)
            val index = LetterboxdFilmIndex(load = { null }, save = {}, nowMs = { clock })
            return LetterboxdWatchlistSyncEngine(
                remote = ClientRemote(client, transport, LetterboxdFilmResolver(client, index)),
                library = library,
                store = store,
                limits = { LetterboxdRateLimits(syncReadGapMs = READ_MS.toInt(), syncWriteGapMs = WRITE_MS.toInt()) },
                nowMs = { clock },
            )
        }
    }

    private class RecordingSource : LetterboxdFilmSource {
        val watchlistWrites = mutableListOf<Boolean>()
        override val account: StateFlow<LetterboxdAccountState> =
            MutableStateFlow(LetterboxdAccountState(isLoaded = true, status = LetterboxdConnectionStatus.Connected, username = "member", cookieNames = listOf("letterboxd.user.CURRENT")))
        override suspend fun resolveFilm(tmdbId: Int?, imdbId: String?) = dune
        override suspend fun histogram(film: LetterboxdFilm) = null
        override suspend fun myState(film: LetterboxdFilm) = LetterboxdFilmState(watched = false, liked = false, inWatchlist = false, rating = null)
        override suspend fun logEntry(id: String) = error("unused")
        override suspend fun friends(film: LetterboxdFilm) = emptyList<LetterboxdFriendActivity>()
        override suspend fun setWatched(film: LetterboxdFilm, watched: Boolean) = error("unused")
        override suspend fun setInWatchlist(film: LetterboxdFilm, inWatchlist: Boolean) {
            watchlistWrites += inWatchlist
        }
        override suspend fun setLiked(film: LetterboxdFilm, liked: Boolean) = error("unused")
        override suspend fun setRating(film: LetterboxdFilm, rating: Double?) = error("unused")
        override suspend fun createLogEntry(film: LetterboxdFilm, draft: LetterboxdLogEntryDraft) = error("unused")
        override suspend fun updateLogEntry(film: LetterboxdFilm, id: String, draft: LetterboxdLogEntryDraft) = error("unused")
    }

    private companion object {
        const val READ_MS = 1_500L
        const val WRITE_MS = 2_000L
        val dune = LetterboxdFilm(slug = "dune-2021", lid = "Ldune", uid = "film:438631", tmdbId = 438631, imdbId = "tt1160419", name = "Dune", year = 2021)
    }
}
