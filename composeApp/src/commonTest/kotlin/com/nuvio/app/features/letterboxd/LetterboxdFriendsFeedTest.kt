package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetterboxdFriendsFeedTest {
    private val now = parseActivityInstant("2026-11-20T12:00:00Z")!!

    private fun item(
        member: String,
        slug: String,
        type: LetterboxdActivityType,
        rating: Double? = null,
        daysAgo: Double = 1.0,
        id: String? = null,
        liked: Boolean = false,
    ) = LetterboxdActivityItem(
        member = member,
        filmSlug = slug,
        filmName = slug.replaceFirstChar { it.uppercase() },
        type = type,
        rating = rating,
        timestamp = kotlin.time.Instant.fromEpochMilliseconds(now - (daysAgo * DAY).toLong()).toString(),
        activityId = id,
        memberName = member.uppercase(),
        liked = liked,
    )

    private fun film(slug: String, tmdb: Int? = 1, imdb: String? = "tt1", type: String? = "movie") =
        LetterboxdFilm(slug = slug, tmdbId = tmdb, imdbId = imdb, name = slug, posterUrl = "https://p/$slug.jpg", tmdbType = type)

    // ------------------------------------------------------------------ pure row building

    @Test
    fun groupsByFilmWithOneLinePerFriendAndAppliesDefaults() {
        val state = LetterboxdFriendsState(
            isLoaded = true,
            items = listOf(
                item("ann", "heat", LetterboxdActivityType.Rating, 4.5, daysAgo = 1.0),
                item("bob", "heat", LetterboxdActivityType.Rating, 3.0, daysAgo = 2.0),
                item("ann", "heat", LetterboxdActivityType.Like, daysAgo = 1.5),
                item("bob", "alien", LetterboxdActivityType.Watch, daysAgo = 0.5),
                item("cid", "brazil", LetterboxdActivityType.Like, daysAgo = 0.2), // likes off by default
                item("cid", "dune", LetterboxdActivityType.Watchlist, daysAgo = 0.1), // never shown
                item("dan", "old", LetterboxdActivityType.Rating, 5.0, daysAgo = 45.0), // outside 30 days
                item("eve", "jaws", LetterboxdActivityType.Review, 4.0, daysAgo = 3.0),
            ),
        )
        val entries = buildLetterboxdFriendsEntries(state, now)
        assertEquals(listOf("alien", "heat", "jaws"), entries.map { it.slug })
        val heat = entries[1]
        assertEquals(listOf("ann", "bob"), heat.friends.map { it.member })
        assertEquals(4.5, heat.friends[0].rating)
        assertTrue(heat.friends[0].liked)
        assertEquals("ANN", heat.friends[0].name)
        assertNull(entries[0].friends.single().rating)
        assertTrue(entries[2].friends.single().reviewed)
    }

    @Test
    fun ungroupedGivesOnePosterPerFriendAndFiltersApply() {
        val items = listOf(
            item("ann", "heat", LetterboxdActivityType.Rating, 4.5),
            item("bob", "heat", LetterboxdActivityType.Rating, 2.0),
            item("bob", "alien", LetterboxdActivityType.Watch),
            item("cid", "brazil", LetterboxdActivityType.Like),
        )
        val ungrouped = buildLetterboxdFriendsEntries(
            LetterboxdFriendsState(items = items, settings = LetterboxdFriendsSettings(groupByFilm = false)),
            now,
        )
        assertEquals(3, ungrouped.size)
        assertEquals(2, ungrouped.count { it.slug == "heat" })
        assertEquals(ungrouped.size, ungrouped.map { it.key }.toSet().size)

        val filtered = buildLetterboxdFriendsEntries(
            LetterboxdFriendsState(
                items = items,
                settings = LetterboxdFriendsSettings(minRating = 3.0, includeWatches = false, includeLikes = true, maxPosters = 5),
            ),
            now,
        )
        assertEquals(setOf("heat", "brazil"), filtered.map { it.slug }.toSet())
        assertEquals(listOf("ann"), filtered.first { it.slug == "heat" }.friends.map { it.member })
    }

    @Test
    fun excludesTvShowsHidesMyFilmsAndCapsPosters() {
        val items = (1..40).map { item("ann", "film$it", LetterboxdActivityType.Rating, 4.0, daysAgo = it / 10.0) }
        val state = LetterboxdFriendsState(
            items = items,
            films = mapOf("film2" to film("film2", imdb = "tt2")),
            excludedSlugs = setOf("film1"),
            myRecentSlugs = setOf("film3"),
            settings = LetterboxdFriendsSettings(hideWatchedByMe = true),
        )
        val entries = buildLetterboxdFriendsEntries(state, now) { it.imdbId == "tt2" }
        assertEquals(30, entries.size)
        assertEquals("film4", entries.first().slug)
        assertFalse(entries.any { it.slug in setOf("film1", "film2", "film3") })
        assertEquals(
            listOf("film1", "film2", "film3"),
            buildLetterboxdFriendsEntries(state.copy(settings = LetterboxdFriendsSettings(), excludedSlugs = emptySet()), now)
                .take(3).map { it.slug },
        )
    }

    // ------------------------------------------------------------------ controller

    private class FakeSource(
        var user: String? = "me",
        val pages: MutableList<LetterboxdActivityPage> = mutableListOf(),
        val films: Map<String, LetterboxdFilm?> = emptyMap(),
        val cached: MutableMap<String, LetterboxdFilm> = mutableMapOf(),
        var failWith: LetterboxdError? = null,
    ) : LetterboxdFriendsSource {
        val cursors = mutableListOf<String?>()
        val fetchedFilms = mutableListOf<String>()
        var rssCalls = 0

        override suspend fun username(): String? = user
        override suspend fun activityPage(username: String, after: String?): LetterboxdActivityPage {
            failWith?.let { throw LetterboxdException(it) }
            cursors += after
            return pages.removeFirstOrNull() ?: LetterboxdActivityPage(emptyList(), false)
        }
        override suspend fun cachedFilm(slug: String): LetterboxdFilm? = cached[slug]
        override suspend fun fetchFilm(slug: String): LetterboxdFilm? {
            fetchedFilms += slug
            failWith?.let { throw LetterboxdException(it) }
            return films[slug]?.also { cached[slug] = it }
        }
        override suspend fun myRecentSlugs(username: String): List<String> {
            rssCalls++
            return listOf("mine")
        }
    }

    private class Store {
        var cache: String? = null
        var settings: String? = null
    }

    private fun TestScope.feed(
        source: FakeSource,
        store: Store = Store(),
        clock: () -> Long,
        pauses: MutableList<Long> = mutableListOf(),
        resolveBatch: Int = 10,
    ) = LetterboxdFriendsFeed(
        source = source,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        loadCache = { store.cache },
        saveCache = { store.cache = it },
        loadSettings = { store.settings },
        saveSettings = { store.settings = it },
        nowMs = clock,
        pause = { pauses += it },
        limits = { LetterboxdRateLimits(friendsResolveBatch = resolveBatch) },
    )

    @Test
    fun paginatesUntilPeriodEndThenCachesAndResolvesInSmallBatch() = runTest {
        val source = FakeSource(
            pages = mutableListOf(
                LetterboxdActivityPage(
                    listOf(
                        item("ann", "a", LetterboxdActivityType.Rating, 4.0, daysAgo = 1.0, id = "30"),
                        item("me", "mine-own", LetterboxdActivityType.Rating, 4.0, daysAgo = 1.0, id = "29"),
                        item("bob", "b", LetterboxdActivityType.Rating, 3.0, daysAgo = 10.0, id = "20"),
                    ),
                    hasMore = true,
                ),
                LetterboxdActivityPage(
                    listOf(
                        item("ann", "c", LetterboxdActivityType.Rating, 4.0, daysAgo = 20.0, id = "10"),
                        item("ann", "d", LetterboxdActivityType.Rating, 4.0, daysAgo = 40.0, id = "5"),
                    ),
                    hasMore = true,
                ),
            ),
            films = mapOf("a" to film("a"), "b" to null, "c" to film("c", type = "tv", tmdb = null)),
        )
        val store = Store()
        val pauses = mutableListOf<Long>()
        val feed = feed(source, store, { now }, pauses, resolveBatch = 2)
        feed.refreshNow()

        assertEquals(listOf(null, "20"), source.cursors) // stopped: page 2 reached the end of the 30-day period
        val state = feed.state.value
        assertFalse(state.items.any { it.member == "me" })
        assertNull(state.error)
        assertEquals(listOf("a", "b"), source.fetchedFilms) // batch of 2, newest first
        assertNotNull(state.films["a"])
        assertTrue("b" in state.excludedSlugs) // unknown to Letterboxd
        assertEquals(listOf(1_500L, 1_500L), pauses) // one gap between pages, one between film pages
        assertNotNull(store.cache)

        // Cached data shows at once in a new session, without any request.
        val reloaded = feed(FakeSource(cached = mutableMapOf("a" to film("a"))), store, { now })
        reloaded.load()
        assertEquals(state.items, reloaded.state.value.items)
        assertNotNull(reloaded.state.value.films["a"])
        assertEquals(now, reloaded.state.value.fetchedAtEpochMs)
    }

    @Test
    fun refreshModesAndMinimumInterval() = runTest {
        var clock = now
        val source = FakeSource()
        val store = Store()
        val feed = feed(source, store, { clock })

        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(1, source.cursors.size) // OnLaunch: first display
        clock += 10 * MINUTE
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(1, source.cursors.size) // OnLaunch: not again in this session

        feed.updateSettings { it.copy(refreshMode = LetterboxdFriendsRefreshMode.EveryVisit) }; testScheduler.advanceUntilIdle()
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(2, source.cursors.size)
        clock += 20_000
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(2, source.cursors.size) // anti-burst floor (60 s)

        feed.updateSettings { it.copy(refreshMode = LetterboxdFriendsRefreshMode.EveryVisitWithDelay, refreshDelayMinutes = 15) }
        testScheduler.advanceUntilIdle()
        clock += 5 * MINUTE
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(2, source.cursors.size) // data younger than 15 min
        clock += 11 * MINUTE
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(3, source.cursors.size)

        assertTrue(store.settings!!.contains("EveryVisitWithDelay"))
        feed.updateSettings { it.copy(enabled = false) }; testScheduler.advanceUntilIdle()
        clock += 60 * MINUTE
        feed.onRowVisible(); testScheduler.advanceUntilIdle()
        assertEquals(3, source.cursors.size) // disabled: no request
    }

    @Test
    fun errorsAreExposedAndKeepCachedItems() = runTest {
        val source = FakeSource(
            pages = mutableListOf(LetterboxdActivityPage(listOf(item("ann", "a", LetterboxdActivityType.Rating, 4.0, id = "1")), false)),
        )
        val feed = feed(source, clock = { now })
        feed.refreshNow()
        assertEquals(1, feed.state.value.items.size)
        source.failWith = LetterboxdError.CloudflareChallenge
        feed.refreshNow()
        assertEquals(LetterboxdError.CloudflareChallenge, feed.state.value.error)
        assertEquals(1, feed.state.value.items.size)
        assertFalse(feed.state.value.isRefreshing)
    }

    @Test
    fun notConnectedDoesNothing() = runTest {
        val source = FakeSource(user = null)
        val feed = feed(source, clock = { now })
        feed.refreshNow()
        assertTrue(source.cursors.isEmpty())
        assertNull(feed.state.value.error)
    }

    @Test
    fun clickOnUnresolvedPosterResolvesOnceAndRejectsTvShows() = runTest {
        val source = FakeSource(films = mapOf("heat" to film("heat", imdb = "tt0113277"), "show" to film("show", tmdb = null, type = "tv")))
        val feed = feed(source, clock = { now })
        assertEquals("tt0113277", feed.filmForOpen("heat")?.imdbId)
        assertEquals("tt0113277", feed.filmForOpen("heat")?.imdbId)
        assertEquals(listOf("heat"), source.fetchedFilms)
        assertNull(feed.filmForOpen("show"))
        assertFalse(film("stale-tv-cache", tmdb = null, type = "movie").isNuvioMovie)
        assertEquals(LetterboxdError.NotFound, feed.state.value.error)
    }

    @Test
    fun hideWatchedFetchesOwnRssOnce() = runTest {
        val source = FakeSource()
        val store = Store()
        val feed = feed(source, store, { now })
        feed.updateSettings { it.copy(hideWatchedByMe = true) }
        testScheduler.advanceUntilIdle()
        assertEquals(1, source.rssCalls)
        assertEquals(setOf("mine"), feed.state.value.myRecentSlugs)
        feed.refreshNow()
        assertEquals(1, source.rssCalls) // RSS reused for 6 h
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24.0 * 60 * 60 * 1000
    }
}
