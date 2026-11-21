package com.nuvio.app.features.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalBuilderTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    private fun progress(id: String, at: Long, fraction: Float, season: Int? = null, episode: Int? = null, title: String = "Show") =
        JournalProgressSample(
            type = if (season != null) "series" else "movie", id = id, season = season, episode = episode,
            title = title, poster = "p", lastUpdatedAt = at, fraction = fraction, durationMs = 3_600_000, completed = fraction >= 0.9f,
        )

    private fun mark(id: String, at: Long, type: String = "movie", season: Int? = null, episode: Int? = null, name: String = "Name") =
        JournalWatchedSample(type, id, season, episode, name, null, at)

    @Test
    fun movieStatusFollowsTheJournalThreshold() {
        val input = JournalBuildInput(records = emptyMap(), progress = listOf(progress("tt1", t0, 0.8f, title = "Film")), watched = emptyList())
        assertEquals(JournalStatus.InProgress, buildJournalEntries(input).single().status)
        val lowered = buildJournalEntries(input.copy(settings = JournalSettings(watchedThresholdPercent = 75))).single()
        assertEquals(JournalStatus.Watched, lowered.status)
        assertEquals(t0, lowered.finishedAt)
        assertEquals(60, lowered.watchedMinutes)
    }

    @Test
    fun movieDatesCombineJournalAndNuvio() {
        val record = JournalVideoRecord(type = "movie", id = "tt1", startedAt = t0 - day, completions = listOf(t0 - day / 2), progress = 1f, progressAt = t0 - day / 2)
        val entry = buildJournalEntries(
            JournalBuildInput(records = mapOf(record.key to record), progress = emptyList(), watched = listOf(mark("tt1", t0))),
        ).single()
        assertEquals(t0 - day, entry.startedAt)
        assertEquals(t0, entry.finishedAt)
        assertEquals(t0, entry.lastWatchedAt)
        assertEquals(JournalStatus.Watched, entry.status)
    }

    @Test
    fun seriesShowsEpisodeProgressAndFinishes() {
        val watched = (1..12).map { mark("tt9", t0 + it, "series", 1, it, name = "Episode $it") }
        val meta = mapOf("series:tt9" to JournalMetaInfo(name = "My Show", releasedEpisodes = 24, runtimeMinutes = 45, genres = listOf("Drama"), year = 2020))
        val input = JournalBuildInput(
            records = emptyMap(),
            progress = listOf(progress("tt9", t0 + 100, 0.5f, 1, 13)),
            watched = watched + mark("tt9", t0, "series", 0, 1),
            meta = meta,
        )
        val entry = buildJournalEntries(input).single()
        assertEquals(JournalKind.Series, entry.kind)
        assertEquals("My Show", entry.title)
        assertEquals(12, entry.episodesWatched, "specials (season 0) are not counted")
        assertEquals(24, entry.episodesTotal)
        assertEquals(JournalStatus.InProgress, entry.status)
        assertEquals(0.5f, entry.progress)
        assertNull(entry.finishedAt)
        assertEquals(12 * 45 + 45 + 30, entry.watchedMinutes) // 12 eps + special + half of episode 13 (60 min row)

        val finished = buildJournalEntries(input.copy(fullyWatchedSeries = setOf("series:tt9"))).single()
        assertEquals(JournalStatus.Watched, finished.status)
        assertEquals(24, finished.episodesWatched)

        val allWatched = (1..24).map { mark("tt9", t0 + it, "series", 1, it) }
        val complete = buildJournalEntries(input.copy(progress = emptyList(), watched = allWatched)).single()
        assertEquals(JournalStatus.Watched, complete.status)
        assertEquals(t0 + 24, complete.finishedAt)
    }

    @Test
    fun seriesTitleIsNotAnEpisodeTitle() {
        val entry = buildJournalEntries(
            JournalBuildInput(
                records = emptyMap(),
                progress = listOf(progress("tt5", t0, 0.3f, 2, 3, title = "Real Show")),
                watched = listOf(mark("tt5", t0 - 1, "series", 2, 2, name = "The Episode")),
            ),
        ).single()
        assertEquals("Real Show", entry.title)
    }

    @Test
    fun letterboxdFilmsAreDeduplicated() {
        val input = JournalBuildInput(
            records = emptyMap(),
            progress = emptyList(),
            watched = listOf(mark("tmdb:603", t0), mark("tt0133093", t0), mark("tt777", t0, name = "Amélie")),
            meta = mapOf(
                "movie:tt0133093" to JournalMetaInfo(name = "The Matrix", year = 1999, tmdbId = null, imdbId = "tt0133093"),
                "movie:tt777" to JournalMetaInfo(name = "Amélie", year = 2001),
                "movie:tt555" to JournalMetaInfo(name = "Other", year = 2010, genres = listOf("Comedy")),
            ),
            letterboxd = listOf(
                JournalLetterboxdFilm(slug = "by-tmdb", tmdbId = 603, rating = 4.5, diaryDates = listOf("2026-11-08")),
                JournalLetterboxdFilm(slug = "the-matrix", imdbId = "tt0133093", liked = true),
                JournalLetterboxdFilm(slug = "amelie", title = "Amelie", year = 2001, rating = 3.0),
                JournalLetterboxdFilm(slug = "lb-only", title = "Other", year = 2010, imdbId = "tt555", diaryDates = listOf("2026-10-01", "2025-01-01")),
                JournalLetterboxdFilm(slug = "a-show", title = "Show", isTv = true),
            ),
            diaryDateToEpochMs = { date -> if (date == "2026-11-08") t0 + day else if (date == "2026-10-01") t0 - 30 * day else t0 - 400 * day },
        )
        val entries = buildJournalEntries(input)
        assertEquals(4, entries.size)
        val byTmdb = entries.single { it.id == "tmdb:603" }
        assertEquals(setOf(JournalSource.Nuvio, JournalSource.Letterboxd), byTmdb.sources)
        assertEquals(4.5, byTmdb.rating)
        assertEquals(t0 + day, byTmdb.lastWatchedAt)
        assertTrue(entries.single { it.id == "tt0133093" }.liked)
        assertEquals(3.0, entries.single { it.id == "tt777" }.rating, "title + year fallback, accents ignored")
        val lbOnly = entries.single { it.letterboxdSlug == "lb-only" }
        assertEquals(setOf(JournalSource.Letterboxd), lbOnly.sources)
        assertEquals("tt555", lbOnly.id)
        assertEquals(listOf("Comedy"), lbOnly.genres)
        assertEquals(t0 - 400 * day, lbOnly.startedAt)
        assertEquals(t0 - 30 * day, lbOnly.finishedAt)
        assertEquals(2, lbOnly.timesWatched)
    }

    @Test
    fun parsesYearsAndRuntimes() {
        assertEquals(2016, parseJournalYear("2016–2019"))
        assertEquals(1999, parseJournalYear("1999-03-31"))
        assertNull(parseJournalYear("n/a"))
        assertEquals(142, parseJournalRuntime("2h 22min"))
        assertEquals(45, parseJournalRuntime("45 min"))
        assertEquals(60, parseJournalRuntime("1 h"))
        assertEquals(98, parseJournalRuntime("98"))
        assertNull(parseJournalRuntime(""))
    }
}
