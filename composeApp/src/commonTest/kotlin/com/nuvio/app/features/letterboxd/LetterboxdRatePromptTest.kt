package com.nuvio.app.features.letterboxd

import com.nuvio.app.features.details.MetaDetails
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

/** F5: when the end-of-film prompt is due (pure logic + coordinator with fakes; Letterboxd itself in LetterboxdFilmPanelTest). */
class LetterboxdRatePromptTest {
    private val film = LetterboxdFilm(slug = "midsommar", lid = "jhxe", uid = "film:459564", tmdbId = 530385)
    private val ref = LetterboxdViewingRef(id = "tt8772262", title = "Midsommar", poster = null)
    private val duration = 8_880_000L // 2 h 28

    @Test
    fun trackerLatchesAtTheThresholdOrAtTheEnd() {
        val tracker = LetterboxdViewingTracker(ref)
        tracker.observe(positionMs = duration * 80 / 100, durationMs = duration, isEnded = false, thresholdPercent = 90)
        assertFalse(tracker.reached)
        tracker.observe(positionMs = duration * 90 / 100, durationMs = duration, isEnded = false, thresholdPercent = 90)
        assertTrue(tracker.reached)
        tracker.observe(positionMs = 0, durationMs = duration, isEnded = false, thresholdPercent = 90)
        assertTrue(tracker.reached, "seeking back afterwards doesn't undo it")

        val ended = LetterboxdViewingTracker(ref)
        ended.observe(positionMs = duration / 2, durationMs = duration, isEnded = true, thresholdPercent = 95)
        assertTrue(ended.reached)

        val custom = LetterboxdViewingTracker(ref)
        custom.observe(positionMs = duration * 92 / 100, durationMs = duration, isEnded = false, thresholdPercent = 95)
        assertFalse(custom.reached, "a threshold above Nuvio's 90 % is honoured")
    }

    @Test
    fun trackerIgnoresShortClipsAndUnknownDurations() {
        val tracker = LetterboxdViewingTracker(ref)
        tracker.observe(positionMs = 100_000, durationMs = 110_000, isEnded = true, thresholdPercent = 90)
        tracker.observe(positionMs = 0, durationMs = 0, isEnded = true, thresholdPercent = 90)
        assertFalse(tracker.reached)
        assertNull(tracker.startPercent)
    }

    @Test
    fun trackerKnowsWhereTheViewingStarted() {
        val byPosition = LetterboxdViewingTracker(ref.copy(initialPositionMs = duration / 4))
        byPosition.observe(positionMs = duration / 4, durationMs = duration, isEnded = false, thresholdPercent = 90)
        assertEquals(25.0, byPosition.startPercent)
        val byFraction = LetterboxdViewingTracker(ref.copy(initialProgressFraction = 0.95f))
        byFraction.observe(positionMs = 0, durationMs = duration, isEnded = false, thresholdPercent = 90)
        assertEquals(95.0, byFraction.startPercent!!, 0.01)
    }

    @Test
    fun onePromptPerViewing() {
        val hour = 60L * 60 * 1000
        assertFalse(letterboxdShouldPrompt(reached = false, startPercent = 0.0, thresholdPercent = 90, lastPromptAtMs = null, nowMs = 0))
        assertTrue(letterboxdShouldPrompt(reached = true, startPercent = 0.0, thresholdPercent = 90, lastPromptAtMs = null, nowMs = 0))
        // Rewinding to rewatch a scene the same evening: same viewing.
        assertFalse(letterboxdShouldPrompt(true, 10.0, 90, lastPromptAtMs = 0, nowMs = 2 * hour))
        // Resumed past the threshold days later (end credits of an already proposed viewing).
        assertFalse(letterboxdShouldPrompt(true, 93.0, 90, lastPromptAtMs = 0, nowMs = 72 * hour))
        // A real rewatch, days later.
        assertTrue(letterboxdShouldPrompt(true, 0.0, 90, lastPromptAtMs = 0, nowMs = 72 * hour))
        // Never proposed (e.g. Letterboxd connected since): even resumed past the threshold.
        assertTrue(letterboxdShouldPrompt(true, 93.0, 90, lastPromptAtMs = null, nowMs = 72 * hour))
    }

    @Test
    fun thresholdFollowsTheJournalUnlessSet() {
        assertEquals(90, LetterboxdRatePromptSettings().effectiveThreshold(null))
        assertEquals(80, LetterboxdRatePromptSettings().effectiveThreshold(80))
        assertEquals(70, LetterboxdRatePromptSettings(thresholdPercent = 70).effectiveThreshold(80))
        assertEquals(50, LetterboxdRatePromptSettings(thresholdPercent = 10).sanitized().thresholdPercent)
    }

    @Test
    fun promptAfterThePlayerIsLeft() = runTest {
        val h = Harness(this)
        val session = h.coordinator.startSession(ref)
        assertEquals(1, h.coordinator.activePlayers.value)
        session.observe(duration * 95 / 100, duration, false)
        testScheduler.advanceUntilIdle()
        assertNull(h.coordinator.pending.value, "never while the player is open")
        assertEquals(0, h.prepared)
        session.close()
        assertEquals(0, h.coordinator.activePlayers.value)
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.prepared)
        val request = assertNotNull(h.coordinator.pending.value)
        assertEquals("midsommar", request.film.slug)
        h.coordinator.dismiss(request)
        assertNull(h.coordinator.pending.value)

        // Same film again the same evening: no second proposal (and no second "watched" write).
        val again = h.coordinator.startSession(ref)
        again.observe(duration, duration, true)
        again.close()
        testScheduler.advanceUntilIdle()
        assertNull(h.coordinator.pending.value)
        assertEquals(1, h.prepared)
    }

    @Test
    fun noPromptBelowTheThresholdDisabledOrWithoutAccount() = runTest {
        val below = Harness(this)
        below.coordinator.startSession(ref).apply { observe(duration * 60 / 100, duration, false); close() }
        testScheduler.advanceUntilIdle()
        assertNull(below.coordinator.pending.value)
        assertEquals(0, below.prepared)

        val disabled = Harness(this, settings = LetterboxdRatePromptSettings(enabled = false))
        disabled.coordinator.startSession(ref).apply { observe(duration, duration, true); close() }
        testScheduler.advanceUntilIdle()
        assertNull(disabled.coordinator.pending.value)
        assertEquals(0, disabled.prepared, "disabled: Letterboxd untouched")

        val offline = Harness(this, account = LetterboxdAccountState(isLoaded = true))
        offline.coordinator.startSession(ref).apply { observe(duration, duration, true); close() }
        testScheduler.advanceUntilIdle()
        assertNull(offline.coordinator.pending.value)
        assertEquals(0, offline.prepared)
    }

    @Test
    fun noPromptWhenRatedDuringTheViewingOrWhenPreparationFails() = runTest {
        val rated = Harness(this, ratedSince = { slug, since -> slug == "midsommar" && since <= 1_000 })
        rated.coordinator.startSession(ref).apply { observe(duration, duration, true); close() }
        testScheduler.advanceUntilIdle()
        assertNull(rated.coordinator.pending.value)
        assertEquals(1, rated.prepared, "still marked watched (a rating already did it on Letterboxd)")

        val failing = Harness(this, prepareResult = null)
        failing.coordinator.startSession(ref).apply { observe(duration, duration, true); close() }
        testScheduler.advanceUntilIdle()
        assertNull(failing.coordinator.pending.value)
    }

    @Test
    fun reopeningDuringTheExitGraceContinuesTheSameViewing() = runTest {
        val h = Harness(this)
        val first = h.coordinator.startSession(ref)
        first.observe(duration * 95 / 100, duration, false)
        first.close()
        testScheduler.advanceTimeBy(500)
        val second = h.coordinator.startSession(ref) // recomposition / back in the player
        assertTrue(first === second)
        testScheduler.advanceUntilIdle()
        assertNull(h.coordinator.pending.value, "still in the player")
        second.close()
        testScheduler.advanceUntilIdle()
        assertNotNull(h.coordinator.pending.value)
        assertEquals(1, h.prepared)
    }

    private inner class Harness(
        test: TestScope,
        settings: LetterboxdRatePromptSettings = LetterboxdRatePromptSettings(),
        account: LetterboxdAccountState = LetterboxdAccountState(isLoaded = true, status = LetterboxdConnectionStatus.Connected, cookieNames = listOf("letterboxd.user.CURRENT")),
        prepareResult: LetterboxdFilm? = film,
        ratedSince: (String, Long) -> Boolean = { _, _ -> false },
    ) {
        var prepared = 0
        private var clock = 1_000L
        val coordinator = LetterboxdRatePromptCoordinator(
            scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob()),
            settings = { settings },
            journalThreshold = { 90 },
            account = { account },
            prepare = { viewing ->
                prepared++
                prepareResult?.let { MetaDetails(id = viewing.id, type = "movie", name = viewing.title) to it }
            },
            ratedSince = ratedSince,
            nowMs = { clock.also { clock += 1_000 } },
        )
    }
}
