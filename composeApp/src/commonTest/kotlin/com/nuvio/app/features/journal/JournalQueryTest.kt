package com.nuvio.app.features.journal

import kotlin.test.Test
import kotlin.test.assertEquals

class JournalQueryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun entry(
        key: String,
        kind: JournalKind = JournalKind.Movie,
        status: JournalStatus = JournalStatus.Watched,
        last: Long? = now,
        rating: Double? = null,
        year: Int? = 2000,
        genres: List<String> = emptyList(),
        sources: Set<JournalSource> = setOf(JournalSource.Nuvio),
        episodes: Int = 0,
        minutes: Int = 0,
    ) = JournalEntry(
        key = key, kind = kind, id = key, title = key, poster = null, year = year, genres = genres, status = status,
        startedAt = null, lastWatchedAt = last, finishedAt = null, progress = null, episodesWatched = episodes,
        sources = sources, rating = rating, watchedMinutes = minutes,
    )

    /** Fake local calendar: day number → "yyyy-MM-dd" with 30-day months starting at 2027-01-01 for [now]. */
    private val localDate: (Long) -> String? = { epoch ->
        val days = ((epoch - now) / day).toInt() + 400
        val year = 2026 + days / 360
        val month = (days % 360) / 30 + 1
        "$year-${month.toString().padStart(2, '0')}-01"
    }

    private val entries = listOf(
        entry("Émile", rating = 4.0, year = 1990, genres = listOf("Drama"), minutes = 100),
        entry("alpha", last = now - 10 * day, rating = 2.5, year = 2020, sources = setOf(JournalSource.Letterboxd)),
        entry("Zed", kind = JournalKind.Series, status = JournalStatus.InProgress, last = now - 100 * day, episodes = 5, minutes = 200),
        entry("undated", last = null, year = null),
    )

    @Test
    fun filtersAndSearch() {
        fun keys(query: JournalQuery) = applyJournalQuery(entries, query, now, localDate).map { it.key }
        assertEquals(listOf("Zed"), keys(JournalQuery(type = JournalTypeFilter.Series)))
        assertEquals(listOf("Zed"), keys(JournalQuery(status = JournalStatusFilter.InProgress)))
        assertEquals(listOf("alpha"), keys(JournalQuery(source = JournalSourceFilter.Letterboxd)))
        assertEquals(listOf("Émile"), keys(JournalQuery(genre = "drama")))
        assertEquals(listOf("alpha"), keys(JournalQuery(year = 2020)))
        assertEquals(listOf("Émile", "alpha"), keys(JournalQuery(period = JournalPeriod.Last30Days)))
        assertEquals(listOf("Émile"), keys(JournalQuery(search = "emi")))
    }

    @Test
    fun sorting() {
        fun keys(sort: JournalSort) = applyJournalQuery(entries, JournalQuery(sort = sort), now, localDate).map { it.key }
        assertEquals(listOf("Émile", "alpha", "Zed", "undated"), keys(JournalSort.Date))
        assertEquals(listOf("alpha", "Émile", "undated", "Zed"), keys(JournalSort.Title))
        assertEquals(listOf("Émile", "alpha", "Zed", "undated"), keys(JournalSort.Rating))
        assertEquals(listOf("alpha", "Zed", "Émile", "undated"), keys(JournalSort.Year))
    }

    @Test
    fun monthGroupsAndStats() {
        val sorted = applyJournalQuery(entries, JournalQuery(), now, localDate)
        val groups = groupJournalByMonth(sorted, localDate)
        assertEquals(listOf("Émile", "alpha"), groups.first().entries.map { it.key })
        assertEquals(null, groups.last().yearMonth)
        assertEquals(listOf("undated"), groups.last().entries.map { it.key })
        val stats = journalStats(entries)
        assertEquals(JournalStats(entries = 4, movies = 3, series = 1, episodes = 5, minutes = 300), stats)
        assertEquals(listOf("Drama"), journalGenres(entries))
        assertEquals(listOf(2020, 2000, 1990), journalYears(entries))
    }
}
