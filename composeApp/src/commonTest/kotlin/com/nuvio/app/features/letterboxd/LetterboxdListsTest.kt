package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetterboxdListsTest {
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------------ addresses

    @Test
    fun parsesListAddresses() {
        val expected = LetterboxdListRef("dave", "imdb-top-250")
        listOf(
            "https://letterboxd.com/dave/list/imdb-top-250/",
            "http://www.letterboxd.com/Dave/list/imdb-top-250",
            "letterboxd.com/dave/list/imdb-top-250/detail/",
            "https://letterboxd.com/dave/list/imdb-top-250/page/3/?x=1#top",
            "https://letterboxd.com/dave/list/imdb-top-250/by/rating/",
            "  dave/list/imdb-top-250  ",
            "/dave/list/imdb-top-250/",
        ).forEach { assertEquals(expected, parseLetterboxdListUrl(it), it) }
        listOf(
            "https://letterboxd.com/dave/",
            "https://letterboxd.com/film/heat/",
            "https://example.com/dave/list/imdb-top-250/",
            "https://boxd.it/abc",
            "",
            "dave/list/",
        ).forEach { assertNull(parseLetterboxdListUrl(it), it) }
        assertEquals("https://letterboxd.com/dave/list/imdb-top-250/", expected.url)
    }

    // ------------------------------------------------------------------ parsers (structure of the real pages, Nov 2026)

    private fun poster(slug: String, name: String, index: Int?, type: String = "film") =
        """<li class="posteritem" data-owner-rating="8" data-object-id="filmListEntry:1"> <div class="react-component" """ +
            """data-component-class="LazyPoster" data-item-name="$name" data-item-slug="$slug" data-item-link="/film/$slug/" """ +
            """data-postered-identifier='{&quot;lid&quot;:&quot;28dA&quot;,&quot;uid&quot;:&quot;film:1&quot;,&quot;type&quot;:&quot;$type&quot;,&quot;typeName&quot;:&quot;$type&quot;}' """ +
            (index?.let { """data-list-index="$it" """ } ?: "") +
            """data-target-link="/film/$slug/"> <div class="poster film-poster"><img src="x" alt="$name"/></div> </div> </li>"""

    @Test
    fun parsesListPage() {
        val html = """
            <meta property="og:title" content="IMDb Top 250 &amp; more" />
            <meta name="description" content="A list of 1,250 films compiled on Letterboxd, including …" />
            <ul class="poster-list -p125 -grid">
            ${poster("the-shawshank-redemption", "The Shawshank Redemption (1994)", 0)}
            ${poster("some-show", "Some Show (2020)", 1, type = "tv")}
            ${poster("the-godfather", "The Godfather (1972)", 2)}
            </ul>
            <section class="related">${poster("unrelated", "Unrelated (2000)", null)}</section>
            <a class="next" href="/dave/list/imdb-top-250/page/2/">Older</a>
        """.trimIndent()
        val page = LetterboxdParsers.parseListPage(html)
        assertEquals("IMDb Top 250 & more", page.title)
        assertEquals(1250, page.totalFilms)
        assertTrue(page.hasNext)
        assertEquals(
            listOf(
                LetterboxdListEntry("the-shawshank-redemption", "The Shawshank Redemption", 1994),
                LetterboxdListEntry("the-godfather", "The Godfather", 1972),
            ),
            page.entries,
        )
        val last = LetterboxdParsers.parseListPage("""${poster("a", "A (2001)", 200)}<span class="next">Older</span>""")
        assertFalse(last.hasNext)
        assertNull(last.totalFilms)
    }

    @Test
    fun parsesMemberLists() {
        fun summary(owner: String, slug: String, name: String, count: String) = """
            <div class="listitem js-listitem">
            <article class="list-summary js-list-summary js-list" data-film-list-id="80764790" data-person="$owner">
              <a href="/$owner/list/$slug/" class="poster-list-link"><ul class="posterlist">${poster("iron-man-2008", "Iron Man (2008)", null)}</ul></a>
              <div class="body"><div class="masthead">
                <h2 class="name prettify">
                  <a href="/$owner/list/$slug/">$name</a>
                </h2>
                <div class="content-reactions-strip -filmlist"> <span class="value">$count</span> <a href="/$owner/list/$slug/edit/">Edit</a></div>
              </div></div>
            </article></div>
        """.trimIndent()
        val html = summary("me", "back", "back", "1&nbsp;film") + summary("me", "best-of", "Best &amp; worst", "1,204&nbsp;films")
        val lists = LetterboxdParsers.parseMemberLists(html)
        assertEquals(
            listOf(
                LetterboxdListSummary(LetterboxdListRef("me", "back"), "back", 1),
                LetterboxdListSummary(LetterboxdListRef("me", "best-of"), "Best & worst", 1204),
            ),
            lists,
        )
    }

    // ------------------------------------------------------------------ feed

    private fun film(slug: String, tmdb: Int? = slug.hashCode().and(0xffff), type: String? = "movie") =
        LetterboxdFilm(slug = slug, tmdbId = tmdb, imdbId = null, name = slug.uppercase(), posterUrl = "https://p/$slug.jpg", tmdbType = type)

    private class FakeSource(
        var user: String? = "me",
        val lists: MutableMap<String, List<LetterboxdListPage>> = mutableMapOf(),
        val films: Map<String, LetterboxdFilm?> = emptyMap(),
        val cached: MutableMap<String, LetterboxdFilm> = mutableMapOf(),
        var failWith: LetterboxdError? = null,
        var failFilmsWith: LetterboxdError? = null,
        val mine: List<LetterboxdListSummary> = emptyList(),
    ) : LetterboxdListsSource {
        val pageReads = mutableListOf<String>()
        val fetchedFilms = mutableListOf<String>()

        override suspend fun username(): String? = user
        override suspend fun listPage(ref: LetterboxdListRef, page: Int): LetterboxdListPage {
            pageReads += "${ref.key}#$page"
            failWith?.let { throw LetterboxdException(it) }
            return lists[ref.key]?.getOrNull(page - 1) ?: throw LetterboxdException(LetterboxdError.NotFound)
        }
        override suspend fun memberListsPage(username: String, page: Int) = mine to false
        override suspend fun cachedFilm(slug: String): LetterboxdFilm? = cached[slug]
        override suspend fun fetchFilm(slug: String): LetterboxdFilm? {
            fetchedFilms += slug
            failFilmsWith?.let { throw LetterboxdException(it) }
            return films[slug]?.also { cached[slug] = it }
        }
    }

    private class Store {
        var cache: String? = null
        var settings: String? = null
    }

    private fun TestScope.feed(
        source: FakeSource,
        store: Store = Store(),
        clock: () -> Long = { now },
        pauses: MutableList<Long> = mutableListOf(),
        batch: Int = 10,
    ) = LetterboxdListsFeed(
        source = source,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        loadCache = { store.cache },
        saveCache = { store.cache = it },
        loadSettings = { store.settings },
        saveSettings = { store.settings = it },
        nowMs = clock,
        pause = { pauses += it },
        limits = { LetterboxdRateLimits(listsResolveBatch = batch) },
    )

    private fun page(vararg slugs: String, hasNext: Boolean = false, title: String = "T") =
        LetterboxdListPage(title, slugs.map { LetterboxdListEntry(it, it, 2000) }, hasNext, null)

    @Test
    fun addsByAddressThenRefreshesVisibleListsAndResolvesInOneSmallBatch() = runTest {
        val source = FakeSource(
            lists = mutableMapOf(
                "ann/top" to listOf(page("a", "b", "c", title = "Ann's top")),
                "me/back" to listOf(page("d", "show", "e")),
            ),
            films = mapOf("a" to film("a"), "b" to null, "c" to film("c"), "d" to film("d"), "e" to film("e"), "show" to film("show", tmdb = null, type = "tv")),
            cached = mutableMapOf("c" to film("c")),
        )
        val store = Store()
        val pauses = mutableListOf<Long>()
        val feed = feed(source, store, pauses = pauses, batch = 3)

        assertFalse(feed.addByUrl("not a list"))
        assertEquals(LetterboxdListAddProblem.InvalidUrl, feed.state.value.addProblem)
        assertTrue(feed.addByUrl("https://letterboxd.com/Ann/list/top/"))
        assertTrue(feed.addByUrl("letterboxd.com/me/list/back/"))
        assertFalse(feed.addByUrl("ann/list/top"))
        assertEquals(LetterboxdListAddProblem.AlreadyAdded, feed.state.value.addProblem)
        assertFalse(feed.addByUrl("ann/list/missing"))
        assertEquals(LetterboxdError.NotFound, feed.state.value.addError)
        testScheduler.advanceUntilIdle()

        val settings = feed.state.value.settings
        assertEquals(listOf("ann/top", "me/back"), settings.lists.map { it.key })
        assertEquals("Ann's top", settings.lists[0].title)
        assertFalse(settings.lists[0].mine)
        assertTrue(settings.lists[1].mine) // owner = connected member
        assertNotNull(store.settings)

        source.pageReads.clear()
        source.fetchedFilms.clear()
        pauses.clear()
        feed.refreshNow(connected = true)
        assertEquals(listOf("ann/top#1", "me/back#1"), source.pageReads)
        val state = feed.state.value
        // Row order, unresolved first posters only, cached "c" needs no request; batch of 3 across rows.
        assertTrue(state.films.keys.containsAll(listOf("a", "c", "d")))
        assertTrue("b" in state.excludedSlugs)
        assertTrue("show" in state.excludedSlugs) // TV show
        assertEquals(listOf("d", "e"), buildLetterboxdListRow(state, "me/back").map { it.slug })
        assertTrue(pauses.isNotEmpty() && pauses.all { it == 1_500L }) // gaps between pages / lookups
        assertTrue(source.fetchedFilms.size <= 3)

        // Signed out: "my lists" rows are hidden, public lists still refresh.
        source.pageReads.clear()
        feed.refreshNow(connected = false)
        assertEquals(listOf("ann/top#1"), source.pageReads)

        // Cached data shows at once in a new session, without any request.
        val reloaded = feed(FakeSource(cached = mutableMapOf("a" to film("a"))), store)
        reloaded.load()
        assertEquals(listOf("a", "b", "c"), reloaded.state.value.lists["ann/top"]?.entries?.map { it.slug })
        assertNotNull(reloaded.state.value.films["a"])
    }

    @Test
    fun challengeStopsTheRefreshAndShowsInTheRow() = runTest {
        val source = FakeSource(lists = mutableMapOf("a/x" to listOf(page("f1")), "b/y" to listOf(page("f2"))), films = mapOf("f1" to film("f1"), "f2" to film("f2")))
        val feed = feed(source)
        assertTrue(feed.addByUrl("a/list/x"))
        assertTrue(feed.addByUrl("b/list/y"))
        testScheduler.advanceUntilIdle()
        source.pageReads.clear()
        source.fetchedFilms.clear()
        source.failWith = LetterboxdError.CloudflareChallenge
        feed.refreshNow(connected = true)
        assertEquals(listOf("a/x#1"), source.pageReads) // stopped after the first challenge
        assertEquals(LetterboxdError.CloudflareChallenge, feed.state.value.errors["a/x"])
        assertTrue(source.fetchedFilms.isEmpty())
        // Old data kept.
        assertEquals(listOf("f1"), feed.state.value.lists["a/x"]?.entries?.map { it.slug })

        source.failWith = null
        feed.refreshNow(connected = true)
        assertNull(feed.state.value.errors["a/x"])
    }

    @Test
    fun viewAllPagesThroughListPagesWithLimitedLookups() = runTest {
        val slugs1 = (1..5).map { "p1-$it" }.toTypedArray()
        val slugs2 = (1..3).map { "p2-$it" }.toTypedArray()
        val films = (slugs1 + slugs2).associateWith { film(it) } + ("p1-2" to film("p1-2", tmdb = null, type = "tv"))
        val source = FakeSource(
            lists = mutableMapOf("a/x" to listOf(page(*slugs1, hasNext = true), page(*slugs2))),
            films = films,
            cached = mutableMapOf("p1-1" to films.getValue("p1-1")),
        )
        val pauses = mutableListOf<Long>()
        val feed = feed(source, pauses = pauses, batch = 2)
        assertTrue(feed.addByUrl("a/list/x"))
        testScheduler.advanceUntilIdle() // background batch after adding: p1-2 (tv), p1-3
        source.fetchedFilms.clear()

        val first = feed.catalogChunk("a/x", 0, pageSize = 40)
        // p1-1 cached, p1-2 excluded (TV), p1-3 resolved after adding, then 2 lookups (p1-4, p1-5), stop before p2-1.
        assertEquals(listOf("p1-1", "p1-3", "p1-4", "p1-5"), first.films.map { it.slug })
        assertEquals(listOf("p1-4", "p1-5"), source.fetchedFilms)
        assertEquals(5, first.nextSkip)

        source.fetchedFilms.clear()
        val second = feed.catalogChunk("a/x", first.nextSkip!!, pageSize = 40)
        assertTrue("a/x#2" in source.pageReads) // next list page read only when needed
        assertEquals(listOf("p2-1", "p2-2"), second.films.map { it.slug })
        assertEquals(7, second.nextSkip)
        val third = feed.catalogChunk("a/x", second.nextSkip!!, pageSize = 40)
        assertEquals(listOf("p2-3"), third.films.map { it.slug })
        assertNull(third.nextSkip)

        // Nothing readable at all → error for the catalog screen.
        val other = feed(FakeSource(lists = mutableMapOf("a/x" to listOf(page("z"))), failFilmsWith = LetterboxdError.CloudflareChallenge))
        assertTrue(other.addByUrl("a/list/x"))
        assertFailsWith<LetterboxdException> { other.catalogChunk("a/x", 0) }
    }

    @Test
    fun enableOrderRemoveAndRefreshModes() = runTest {
        var clock = now
        val source = FakeSource(
            lists = mutableMapOf("a/x" to listOf(page("1")), "b/y" to listOf(page("2")), "c/z" to listOf(page("3"))),
            mine = listOf(LetterboxdListSummary(LetterboxdListRef("me", "mine"), "Mine", 3)),
        )
        source.lists["me/mine"] = listOf(page("4"))
        val store = Store()
        val feed = feed(source, store, clock = { clock })
        listOf("a/list/x", "b/list/y", "c/list/z").forEach { assertTrue(feed.addByUrl(it)) }
        feed.loadMyLists()
        testScheduler.advanceUntilIdle()
        assertEquals(1, feed.state.value.myLists?.size)
        assertTrue(feed.addMine(feed.state.value.myLists!!.single()))

        feed.move("c/z", -1)
        feed.setEnabled("a/x", false)
        feed.remove("b/y")
        testScheduler.advanceUntilIdle()
        val settings = feed.state.value.settings
        assertEquals(listOf("a/x", "c/z", "me/mine"), settings.lists.map { it.key })
        assertFalse(settings.lists[0].enabled)
        assertNull(feed.state.value.lists["b/y"])
        assertEquals(listOf("c/z", "me/mine"), settings.visibleLists(connected = true).map { it.key })
        assertEquals(listOf("c/z"), settings.visibleLists(connected = false).map { it.key })

        // Once per launch: first visit refreshes, the next ones don't.
        source.pageReads.clear()
        feed.onRowsVisible(connected = true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("c/z#1", "me/mine#1"), source.pageReads)
        clock += 10 * 60_000
        feed.onRowsVisible(connected = true)
        testScheduler.advanceUntilIdle()
        assertEquals(2, source.pageReads.size)

        // Every visit, but never closer than the minimum automatic interval (60 s).
        feed.updateSettings { it.copy(refreshMode = LetterboxdFriendsRefreshMode.EveryVisit) }
        testScheduler.advanceUntilIdle()
        feed.onRowsVisible(connected = true)
        testScheduler.advanceUntilIdle()
        assertEquals(4, source.pageReads.size)
        clock += 30_000
        feed.onRowsVisible(connected = true)
        testScheduler.advanceUntilIdle()
        assertEquals(4, source.pageReads.size)

        // Settings survive a restart.
        val reloaded = feed(FakeSource(), store)
        reloaded.load()
        assertEquals(listOf("a/x", "c/z", "me/mine"), reloaded.state.value.settings.lists.map { it.key })
        assertEquals(LetterboxdFriendsRefreshMode.EveryVisit, reloaded.state.value.settings.refreshMode)
    }

    @Test
    fun rowCapsPostersAndKnowsWhenViewAllIsUseful() {
        val entries = (1..12).map { LetterboxdListEntry("f$it", "F$it", null) }
        val state = LetterboxdListsState(
            settings = LetterboxdListsSettings(postersPerRow = 5),
            lists = mapOf("a/x" to CachedLetterboxdList(entries = entries, pagesLoaded = 1)),
            films = mapOf("f2" to film("f2")),
            excludedSlugs = setOf("f1"),
        )
        val row = buildLetterboxdListRow(state, "a/x")
        assertEquals(listOf("f2", "f3", "f4", "f5", "f6"), row.map { it.slug })
        assertEquals("F2", row[0].title)
        assertNotNull(row[0].film)
        assertTrue(state.hasMoreThanRow("a/x"))
        val small = state.copy(settings = LetterboxdListsSettings(postersPerRow = 20))
        assertFalse(small.hasMoreThanRow("a/x"))
        assertTrue(small.copy(lists = mapOf("a/x" to CachedLetterboxdList(entries = entries, hasMore = true))).hasMoreThanRow("a/x"))
    }
}
