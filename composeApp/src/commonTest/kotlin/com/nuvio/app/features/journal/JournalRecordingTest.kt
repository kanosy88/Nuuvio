package com.nuvio.app.features.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalRecordingTest {
    private val hour = 60L * 60 * 1000
    private val t0 = 1_000_000_000_000L

    private fun progress(id: String, at: Long, fraction: Float, completed: Boolean = false, season: Int? = null, episode: Int? = null) =
        JournalProgressSample(
            type = if (season != null) "series" else "movie", id = id, season = season, episode = episode,
            title = "T", poster = null, lastUpdatedAt = at, fraction = fraction, durationMs = 7_200_000, completed = completed,
        )

    private fun mark(id: String, at: Long, type: String = "movie", season: Int? = null, episode: Int? = null) =
        JournalWatchedSample(type, id, season, episode, "T", null, at)

    private fun obs(progress: List<JournalProgressSample> = emptyList(), watched: List<JournalWatchedSample> = emptyList(), source: String = "NUVIO_SYNC|false") =
        JournalObservation(progress, watched, watchedLoaded = true, source = source)

    @Test
    fun baselineKeepsNuvioDatesAndUnknownStart() {
        val payload = recordJournalObservation(
            JournalProfilePayload(),
            obs(progress = listOf(progress("tt1", t0 - 10 * hour, 0.4f)), watched = listOf(mark("tt2", t0 - 5 * hour))),
            previous = null,
            nowMs = t0,
        )
        assertEquals(t0, payload.createdAt)
        val inProgress = payload.records.getValue(journalVideoKey("movie", "tt1"))
        assertNull(inProgress.startedAt)
        assertTrue(inProgress.completions.isEmpty())
        assertEquals(0.4f, inProgress.progress)
        val watched = payload.records.getValue(journalVideoKey("movie", "tt2"))
        assertTrue(watched.completions.isEmpty())
        assertTrue(!watched.hasJournalData, "a baseline watched mark is not stored (Nuvio keeps it)")
    }

    @Test
    fun newPlaybackRecordsStartCompletionAndRewatch() {
        var payload = recordJournalObservation(JournalProfilePayload(), obs(), null, t0)
        payload = recordJournalObservation(payload, obs(progress = listOf(progress("tt1", t0 + hour, 0.05f))), null, t0 + hour)
        assertEquals(t0 + hour, payload.records.getValue(journalVideoKey("movie", "tt1")).startedAt)
        payload = recordJournalObservation(payload, obs(progress = listOf(progress("tt1", t0 + 3 * hour, 0.95f, completed = true))), null, t0 + 3 * hour)
        // Nuvio marks it watched at completion: same moment → one completion only.
        payload = recordJournalObservation(
            payload,
            obs(progress = listOf(progress("tt1", t0 + 3 * hour, 0.95f, completed = true)), watched = listOf(mark("tt1", t0 + 3 * hour + 1_000))),
            null,
            t0 + 3 * hour,
        )
        var record = payload.records.getValue(journalVideoKey("movie", "tt1"))
        assertEquals(listOf(t0 + 3 * hour), record.completions)
        assertEquals(t0 + hour, record.startedAt)
        // Rewatch two days later.
        val later = t0 + 48 * hour
        payload = recordJournalObservation(payload, obs(progress = listOf(progress("tt1", later, 0.1f))), null, later)
        payload = recordJournalObservation(payload, obs(progress = listOf(progress("tt1", later + 2 * hour, 0.92f, completed = true))), null, later)
        record = payload.records.getValue(journalVideoKey("movie", "tt1"))
        assertEquals(listOf(t0 + 3 * hour, later + 2 * hour), record.completions)
        assertEquals(t0 + hour, record.startedAt, "start = first playback")
    }

    @Test
    fun resumingAnOldProgressKeepsUnknownStart() {
        var payload = recordJournalObservation(JournalProfilePayload(), obs(progress = listOf(progress("tt1", t0 - hour, 0.3f))), null, t0)
        payload = recordJournalObservation(payload, obs(progress = listOf(progress("tt1", t0 + hour, 0.5f))), null, t0 + hour)
        val record = payload.records.getValue(journalVideoKey("movie", "tt1"))
        assertNull(record.startedAt)
        assertEquals(0.5f, record.progress)
        assertEquals(t0 + hour, record.lastActivityAt)
    }

    @Test
    fun manualMarkAddsCompletionOnce() {
        var payload = recordJournalObservation(JournalProfilePayload(), obs(), null, t0)
        val marked = obs(watched = listOf(mark("tt3", t0 + hour)))
        payload = recordJournalObservation(payload, marked, null, t0 + hour)
        payload = recordJournalObservation(payload, marked, marked, t0 + 2 * hour)
        val record = payload.records.getValue(journalVideoKey("movie", "tt3"))
        assertEquals(listOf(t0 + hour), record.completions)
        assertNull(record.startedAt, "marked without playing: no start date")
    }

    @Test
    fun vanishedMarkIsAnUnmarkOnlyForTheSameSource() {
        val before = obs(watched = listOf(mark("tt1", t0 - hour), mark("tt2", t0 - hour)))
        var payload = recordJournalObservation(JournalProfilePayload(), before, null, t0)
        val after = obs(watched = listOf(mark("tt2", t0 - hour)))
        payload = recordJournalObservation(payload, after, before, t0 + hour)
        assertEquals(t0 + hour, payload.records.getValue(journalVideoKey("movie", "tt1")).unmarkedAt)

        // Source switch (Nuvio → Trakt): marks disappear but nothing was unmarked.
        val otherSource = obs(watched = emptyList(), source = "TRAKT|false")
        val switched = recordJournalObservation(payload, otherSource, after, t0 + 2 * hour)
        assertNull(switched.records.getValue(journalVideoKey("movie", "tt2")).unmarkedAt)
    }

    @Test
    fun massVanishingIsIgnored() {
        val many = obs(watched = (1..30).map { mark("tt$it", t0 - hour) })
        val payload = recordJournalObservation(JournalProfilePayload(), many, null, t0)
        val emptied = recordJournalObservation(payload, obs(), many, t0 + hour)
        assertTrue(emptied.records.values.none { it.unmarkedAt != null })
    }

    @Test
    fun journalUnmarkOverridesOlderProgress() {
        var payload = recordJournalObservation(JournalProfilePayload(), obs(progress = listOf(progress("tt1", t0 - hour, 0.95f, completed = true))), null, t0)
        val record = payload.records.getValue(journalVideoKey("movie", "tt1"))
        payload = markJournalVideosUnwatched(payload, listOf(record), t0 + hour)
        assertEquals(t0 + hour, payload.records.getValue(record.key).unmarkedAt)
        val entries = buildJournalEntries(JournalBuildInput(records = payload.records, progress = listOf(progress("tt1", t0 - hour, 0.95f, true)), watched = emptyList()))
        assertTrue(entries.isEmpty(), "unmarked and no newer playback: not watched nor in progress")
    }
}
