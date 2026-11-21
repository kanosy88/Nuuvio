package com.nuvio.app.features.journal

import com.nuvio.app.features.letterboxd.LetterboxdDiaryEntry
import com.nuvio.app.features.letterboxd.LetterboxdError
import com.nuvio.app.features.letterboxd.LetterboxdException
import com.nuvio.app.features.letterboxd.LetterboxdFilm
import com.nuvio.app.features.letterboxd.LetterboxdParsers
import com.nuvio.app.features.letterboxd.LetterboxdRateLimits
import com.nuvio.app.features.letterboxd.LetterboxdWatchedFilm
import com.nuvio.app.features.letterboxd.LetterboxdWatchedFilmsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalLetterboxdSourceTest {
    private class FakeRemote(val pages: Int, val perPage: Int = 3) : JournalLetterboxdRemote {
        val calls = mutableListOf<String>()
        var failOnPage: Int? = null
        var connected = true
        val known = mutableMapOf<String, LetterboxdFilm>()

        override suspend fun hasCredentials() = connected
        override suspend fun username() = "member"
        override suspend fun diary(username: String): List<LetterboxdDiaryEntry> {
            calls += "rss"
            return listOf(
                LetterboxdDiaryEntry(
                    member = username, filmSlug = "f1-1", filmTitle = "Film 1-1", filmYear = 2001, tmdbId = 11,
                    watchedDate = "2026-11-08", rating = 3.5, liked = true, rewatch = false, publishedAt = null,
                    link = null, isReview = false, posterUrl = "https://poster/f1-1.jpg",
                ),
            )
        }

        override suspend fun watchedPage(username: String, page: Int): LetterboxdWatchedFilmsPage {
            calls += "page$page"
            if (page == failOnPage) throw LetterboxdException(LetterboxdError.CloudflareChallenge)
            val items = (1..perPage).map { LetterboxdWatchedFilm(slug = "f$page-$it", title = "Film $page-$it", year = 2000 + page, rating = 4.0) }
            return LetterboxdWatchedFilmsPage(items, hasNext = page < pages)
        }

        override suspend fun cachedFilm(slug: String): LetterboxdFilm? = known[slug]
        override suspend fun fetchFilm(slug: String): LetterboxdFilm? {
            calls += "film:$slug"
            return LetterboxdFilm(slug = slug, tmdbId = slug.hashCode() and 0xffff, imdbId = "tt${slug.length}", tmdbType = "movie")
        }
    }

    private class Clock {
        var now = 1_000_000L
        val sleeps = mutableListOf<Long>()
    }

    private fun source(remote: FakeRemote, clock: Clock, limits: LetterboxdRateLimits, cache: MutableList<String>, scope: CoroutineScope) =
        JournalLetterboxdSource(
            remote = remote,
            scope = scope,
            loadCache = { cache.lastOrNull() },
            saveCache = { cache += it },
            limits = { limits },
            nowMs = { clock.now },
            sleep = { clock.sleeps += it; clock.now += it },
        )

    @Test
    fun readsPagesWithinLimitsAndContinuesNextTime() = runTest {
        val remote = FakeRemote(pages = 5)
        val clock = Clock()
        val cache = mutableListOf<String>()
        val limits = LetterboxdRateLimits(journalMaxPages = 3, journalPageGapMs = 1_500, journalResolveBatch = 2, journalResolveGapMs = 2_000)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val lb = source(remote, clock, limits, cache, scope)

        lb.runRefresh(force = false)
        assertEquals(listOf("rss", "page1", "page2", "page3", "film:f1-2", "film:f1-3"), remote.calls)
        assertEquals(listOf(1_500L, 1_500L, 1_500L, 2_000L, 2_000L), clock.sleeps, "every request is spaced, no burst")
        var state = lb.state.value
        assertTrue(state.incomplete)
        assertEquals(9, state.films.size)
        val diaryFilm = state.films.single { it.slug == "f1-1" }
        assertEquals(11, diaryFilm.tmdbId)
        assertEquals(listOf("2026-11-08"), diaryFilm.diaryDates)
        assertEquals(4.0, diaryFilm.rating, "watched-films page rating wins")
        assertEquals("https://poster/f1-1.jpg", diaryFilm.poster)
        assertEquals(6, state.unresolvedCount)

        // Within the minimum refresh interval: nothing is requested.
        remote.calls.clear()
        lb.runRefresh(force = false)
        assertTrue(remote.calls.isEmpty())

        // Forced: page 1 again, then continues at page 4.
        lb.runRefresh(force = true)
        assertEquals(listOf("rss", "page1", "page4", "page5", "film:f2-1", "film:f2-2"), remote.calls)
        state = lb.state.value
        assertTrue(!state.incomplete)
        assertEquals(15, state.films.size)

        // A fresh instance shows the disk cache.
        val reloaded = source(remote, clock, limits, cache, scope)
        remote.connected = true
        remote.calls.clear()
        reloaded.runRefresh(force = false)
        assertEquals(15, reloaded.state.value.films.size)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun cloudflareStopsTheRefreshWithAVisibleError() = runTest {
        val remote = FakeRemote(pages = 3).apply { failOnPage = 2 }
        val clock = Clock()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val lb = source(remote, clock, LetterboxdRateLimits(journalMaxPages = 5), mutableListOf(), scope)
        lb.runRefresh(force = true)
        assertEquals(listOf("rss", "page1", "page2"), remote.calls, "no film lookups after a challenge")
        val state = lb.state.value
        assertEquals(LetterboxdError.CloudflareChallenge, state.error)
        assertEquals(3, state.films.size, "what was read is kept")
    }

    @Test
    fun disconnectedShowsNothing() = runTest {
        val remote = FakeRemote(pages = 1).apply { connected = false }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val lb = source(remote, Clock(), LetterboxdRateLimits(), mutableListOf(), scope)
        lb.runRefresh(force = true)
        assertTrue(!lb.state.value.connected)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun parsesWatchedFilmsPageAndRssPoster() {
        val html = """
            <ul class="grid -p70">
            <li class="griditem">
              <div class="react-component" data-item-name="Clayface (2026)" data-item-slug="clayface"
                data-postered-identifier='{&quot;lid&quot;:&quot;RTVm&quot;,&quot;uid&quot;:&quot;film:1284636&quot;}'> </div>
              <p class="poster-viewingdata" data-item-uid="film:1284636"> <span class="rating -micro -darker rated-7">★★★½</span>
              <span class="like liked-micro has-icon icon-liked icon-16"><span class="icon"></span></span> </p>
            </li>
            <li class="griditem">
              <div class="react-component" data-item-name="Amélie &amp; co" data-item-slug="amelie"> </div>
              <p class="poster-viewingdata" data-item-uid="film:2"> </p>
            </li>
            </ul>
        """.trimIndent()
        val films = LetterboxdParsers.parseWatchedFilmsPage(html)
        assertEquals(2, films.size)
        assertEquals(LetterboxdWatchedFilm("clayface", "RTVm", "film:1284636", "Clayface", 2026, 3.5, true), films[0])
        assertEquals("Amélie & co", films[1].title)
        assertNull(films[1].year)
        assertNull(films[1].rating)
        assertTrue(!films[1].liked)

        val rss = """<item> <title>Train to Busan, 2016 - ★★★</title> <link>https://letterboxd.com/u/film/train-to-busan/</link>
            <guid isPermaLink="false">letterboxd-watch-1</guid> <letterboxd:watchedDate>2026-11-08</letterboxd:watchedDate>
            <letterboxd:filmTitle>Train to Busan</letterboxd:filmTitle> <letterboxd:filmYear>2016</letterboxd:filmYear>
            <tmdb:movieId>396535</tmdb:movieId> <description><![CDATA[ <p><img src="https://a.ltrbxd.com/p.jpg?v=1"/></p> ]]></description> </item>"""
        val entry = LetterboxdParsers.parseDiaryRss(rss, "u").single()
        assertEquals("https://a.ltrbxd.com/p.jpg?v=1", entry.posterUrl)
        assertEquals("train-to-busan", entry.filmSlug)
    }
}
