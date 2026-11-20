package com.nuvio.app.features.letterboxd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** F3: decision logic of the watchlist sync (pure function), every rule and their combinations. */
class LetterboxdWatchlistSyncPlanTest {

    // ------------------------------------------------------------------ exhaustive table, one film

    private enum class Snap { FirstSync, Absent, Present }
    private enum class Watched { No, Yes, Unknown }

    private sealed interface Expected {
        data object Nothing : Expected
        data object InSync : Expected
        data class AddToLetterboxd(val removedOnLetterboxd: Boolean) : Expected
        data object RemoveFromLetterboxd : Expected
        data object AddToLibrary : Expected
        data object RemoveFromLibrary : Expected
        data class Skip(val reason: WatchlistSyncSkip.Reason) : Expected
    }

    private fun expected(inLibrary: Boolean, inWatchlist: Boolean, snap: Snap, watched: Watched): Expected = when {
        inLibrary && inWatchlist -> Expected.InSync
        inLibrary -> when (watched) {
            Watched.Unknown -> Expected.Skip(WatchlistSyncSkip.Reason.WatchedStateUnknown)
            Watched.Yes -> if (snap == Snap.FirstSync) Expected.Skip(WatchlistSyncSkip.Reason.WatchedKeptOnFirstSync) else Expected.RemoveFromLibrary
            Watched.No -> Expected.AddToLetterboxd(removedOnLetterboxd = snap == Snap.Present)
        }
        inWatchlist -> if (snap == Snap.Present) Expected.RemoveFromLetterboxd else Expected.AddToLibrary
        else -> Expected.Nothing
    }

    @Test
    fun everyCombinationFollowsTheRules() {
        var cases = 0
        for (inLibrary in listOf(false, true)) for (inWatchlist in listOf(false, true)) for (snap in Snap.entries) for (watched in Watched.entries) {
            cases++
            val label = "library=$inLibrary watchlist=$inWatchlist snapshot=$snap watched=$watched"
            val input = singleFilmInput(inLibrary, inWatchlist, snap, watched)
            val plan = planWatchlistSync(input)
            assertEquals(snap == Snap.FirstSync, plan.firstSync, label)
            assertTrue(plan.unmatched.isEmpty(), label)
            when (val want = expected(inLibrary, inWatchlist, snap, watched)) {
                Expected.Nothing -> assertNoChange(plan, label, inSync = false)
                Expected.InSync -> assertNoChange(plan, label, inSync = true)
                is Expected.Skip -> {
                    assertTrue(plan.actions.isEmpty(), label)
                    assertEquals(listOf(want.reason), plan.skipped.map { it.reason }, label)
                }
                else -> {
                    assertTrue(plan.skipped.isEmpty(), label)
                    val action = plan.actions.singleOrNull() ?: fail("$label: expected one action, got ${plan.actions}")
                    when (want) {
                        is Expected.AddToLetterboxd -> assertEquals(
                            want.removedOnLetterboxd,
                            assertIs<WatchlistSyncAction.AddToLetterboxd>(action, label).removedOnLetterboxd,
                            label,
                        )
                        Expected.RemoveFromLetterboxd -> assertIs<WatchlistSyncAction.RemoveFromLetterboxd>(action, label)
                        Expected.AddToLibrary -> assertIs<WatchlistSyncAction.AddToLibrary>(action, label)
                        Expected.RemoveFromLibrary -> assertIs<WatchlistSyncAction.RemoveFromLibrary>(action, label)
                        else -> error("unreachable")
                    }
                }
            }
            if (snap == Snap.FirstSync) {
                assertTrue(plan.removeFromLetterboxd.isEmpty() && plan.removeFromLibrary.isEmpty(), "$label: first sync never removes")
            }
        }
        assertEquals(36, cases)
    }

    @Test
    fun applyingThePlanConvergesAndTheSnapshotRecordsBothSides() {
        for (inLibrary in listOf(false, true)) for (inWatchlist in listOf(false, true)) for (snap in Snap.entries) for (watched in listOf(Watched.No, Watched.Yes)) {
            val label = "library=$inLibrary watchlist=$inWatchlist snapshot=$snap watched=$watched"
            val input = singleFilmInput(inLibrary, inWatchlist, snap, watched)
            val plan = planWatchlistSync(input)
            val snapshot = nextWatchlistSnapshot(input, plan, plan.actions.toSet(), nowEpochMs = 42L)
            assertEquals(42L, snapshot.syncedAtEpochMs, label)
            var library = inLibrary
            var watchlist = inWatchlist
            plan.actions.forEach { action ->
                when (action) {
                    is WatchlistSyncAction.AddToLetterboxd -> watchlist = true
                    is WatchlistSyncAction.RemoveFromLetterboxd -> watchlist = false
                    is WatchlistSyncAction.AddToLibrary -> library = true
                    is WatchlistSyncAction.RemoveFromLibrary -> library = false
                }
            }
            assertEquals(library && watchlist, "f" in snapshot.films, label)
            // Second sync from the resulting state: nothing left to do, except the film watched on Letterboxd kept by
            // the first sync, which now leaves the library.
            val again = planWatchlistSync(
                singleFilmInput(library, watchlist, if (snapshot.films.isEmpty()) Snap.Absent else Snap.Present, watched)
                    .copy(snapshot = snapshot),
            )
            if (snap == Snap.FirstSync && inLibrary && !inWatchlist && watched == Watched.Yes) {
                assertIs<WatchlistSyncAction.RemoveFromLibrary>(again.actions.single(), label)
            } else {
                assertTrue(again.actions.isEmpty(), "$label: expected no action, got ${again.actions}")
            }
        }
    }

    // ------------------------------------------------------------------ specific rules

    @Test
    fun libraryWinsOverARemovalOnLetterboxd() {
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(matched(heatMovie, heat)),
                watchlist = emptyList(),
                snapshot = snapshotOf(heat to heatMovie),
                watched = mapOf("heat" to false),
            ),
        )
        val action = assertIs<WatchlistSyncAction.AddToLetterboxd>(plan.actions.single())
        assertTrue(action.removedOnLetterboxd)
        assertEquals("Heat", action.title)
    }

    @Test
    fun watchedOnLetterboxdLeavesTheLibraryInsteadOfGoingBackToTheWatchlist() {
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(matched(heatMovie, heat)),
                watchlist = emptyList(),
                snapshot = snapshotOf(heat to heatMovie),
                watched = mapOf("heat" to true),
            ),
        )
        val action = assertIs<WatchlistSyncAction.RemoveFromLibrary>(plan.actions.single())
        assertEquals(listOf(heatMovie), action.movies)
        assertTrue(plan.addToLetterboxd.isEmpty())
    }

    @Test
    fun firstSyncIsAUnionWithoutRemovals() {
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(matched(heatMovie, heat), matched(jawsMovie, jaws), matched(alienMovie, alien)),
                watchlist = listOf(item(alien), item(dune), item(tenet)),
                snapshot = null,
                watchlistLookups = mapOf("dune-2021" to WatchlistSyncLookup.Found(dune), "tenet" to WatchlistSyncLookup.Found(tenet)),
                watched = mapOf("heat" to false, "jaws" to true),
            ),
        )
        assertTrue(plan.firstSync)
        assertEquals(listOf("heat"), plan.addToLetterboxd.map { it.slug })
        assertEquals(listOf("dune-2021", "tenet"), plan.addToLibrary.map { it.slug })
        assertTrue(plan.removeFromLetterboxd.isEmpty())
        assertTrue(plan.removeFromLibrary.isEmpty())
        assertEquals(listOf("alien"), plan.inSync)
        assertEquals(WatchlistSyncSkip.Reason.WatchedKeptOnFirstSync, plan.skipped.single().reason)
        assertEquals("jaws", plan.skipped.single().slug)
    }

    @Test
    fun emptySnapshotIsNotAFirstSync() {
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(matched(jawsMovie, jaws)),
                watchlist = emptyList(),
                snapshot = LetterboxdWatchlistSnapshot(syncedAtEpochMs = 1L),
                watched = mapOf("jaws" to true),
            ),
        )
        assertFalse(plan.firstSync)
        assertIs<WatchlistSyncAction.RemoveFromLibrary>(plan.actions.single())
    }

    @Test
    fun removedFromTheLibrarySinceTheLastSyncLeavesTheWatchlist() {
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = emptyList(),
                watchlist = listOf(item(tenet)),
                snapshot = snapshotOf(tenet to tenetMovie),
            ),
        )
        val action = assertIs<WatchlistSyncAction.RemoveFromLetterboxd>(plan.actions.single())
        assertEquals("tenet", action.slug)
        assertEquals("Ltenet", action.item.lid)
    }

    @Test
    fun noRemovalFromTheWatchlistWhileALibraryCopyCouldNotBeMatched() {
        // Tenet is still in the library but its lookup failed this time (Cloudflare on one film, parse error…).
        val failed = WatchlistSyncLibraryEntry(tenetMovie, WatchlistSyncMatch.Failed(LetterboxdError.Parse))
        val plan = planWatchlistSync(
            WatchlistSyncInput(library = listOf(failed), watchlist = listOf(item(tenet)), snapshot = snapshotOf(tenet to tenetMovie)),
        )
        assertTrue(plan.actions.isEmpty())
        assertEquals(WatchlistSyncSkip.Reason.LibraryMatchUncertain, plan.skipped.single().reason)
        assertEquals(WatchlistSyncUnmatched.Reason.LookupFailed, plan.unmatched.single().reason)
        assertEquals(LetterboxdError.Parse, plan.unmatched.single().error)
        // …and the snapshot keeps it, so the removal is decided at the next sync.
        val next = nextWatchlistSnapshot(
            WatchlistSyncInput(listOf(failed), listOf(item(tenet)), snapshotOf(tenet to tenetMovie)), plan, emptySet(), 2L,
        )
        assertTrue("tenet" in next.films)
    }

    @Test
    fun snapshotMatchingUsesAnyOfTheIds() {
        val previous = LetterboxdWatchlistSnapshot.Film(nuvioIds = listOf("tt0000001"), imdbId = "tt0000001", tmdbId = 7)
        assertTrue(WatchlistSyncLibraryMovie("tt0000001", "A", null, null).sharesIdsWith(previous))
        assertTrue(WatchlistSyncLibraryMovie("tmdb:7", "A", null, 7).sharesIdsWith(previous))
        assertTrue(WatchlistSyncLibraryMovie("x", "A", "tt0000001", null).sharesIdsWith(previous))
        assertFalse(WatchlistSyncLibraryMovie("tt0000002", "B", "tt0000002", 8).sharesIdsWith(previous))
    }

    @Test
    fun newWatchlistFilmAlreadyInTheLibraryUnderAnUnmatchedEntryIsNotDuplicated() {
        val unmatchedCopy = WatchlistSyncLibraryEntry(duneMovie, WatchlistSyncMatch.Failed(LetterboxdError.Server))
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(unmatchedCopy),
                watchlist = listOf(item(dune)),
                snapshot = null,
                watchlistLookups = mapOf("dune-2021" to WatchlistSyncLookup.Found(dune)),
            ),
        )
        assertTrue(plan.addToLibrary.isEmpty())
        assertEquals(WatchlistSyncSkip.Reason.LibraryMatchUncertain, plan.skipped.single().reason)
    }

    @Test
    fun unmatchedFilmsAreReportedOnBothSides() {
        val noIds = WatchlistSyncLibraryEntry(WatchlistSyncLibraryMovie("custom:1", "Home movie", null, null), WatchlistSyncMatch.NoExternalId)
        val unknown = WatchlistSyncLibraryEntry(WatchlistSyncLibraryMovie("tmdb:999999", "Obscure", null, 999999), WatchlistSyncMatch.NotOnLetterboxd)
        val failed = WatchlistSyncLibraryEntry(WatchlistSyncLibraryMovie("tt7", "Broken", "tt7", null), WatchlistSyncMatch.Failed(LetterboxdError.Server))
        val tvInLibrary = WatchlistSyncLibraryEntry(WatchlistSyncLibraryMovie("tt8", "Mini", "tt8", null), WatchlistSyncMatch.Matched(chernobyl))
        val noIdFilm = LetterboxdFilm(slug = "lost-film", lid = "Llost", name = "Lost Film")
        val plan = planWatchlistSync(
            WatchlistSyncInput(
                library = listOf(noIds, unknown, failed, tvInLibrary),
                watchlist = listOf(item(chernobyl), item(noIdFilm), LetterboxdWatchlistItem("broken-page", "Lb", "film:9", "Broken Page")),
                snapshot = LetterboxdWatchlistSnapshot(),
                watchlistLookups = mapOf(
                    "chernobyl" to WatchlistSyncLookup.Found(chernobyl),
                    "lost-film" to WatchlistSyncLookup.Found(noIdFilm),
                    "broken-page" to WatchlistSyncLookup.Failed(LetterboxdError.NotFound),
                ),
            ),
        )
        assertTrue(plan.actions.isEmpty(), "unmatched films never produce a change: ${plan.actions}")
        val library = plan.unmatched.filter { it.side == WatchlistSyncUnmatched.Side.Library }
        assertEquals(
            listOf(
                "Home movie" to WatchlistSyncUnmatched.Reason.NoExternalId,
                "Obscure" to WatchlistSyncUnmatched.Reason.NotOnLetterboxd,
                "Broken" to WatchlistSyncUnmatched.Reason.LookupFailed,
                "Mini" to WatchlistSyncUnmatched.Reason.TvShow,
            ),
            library.map { it.title to it.reason },
        )
        val letterboxd = plan.unmatched.filter { it.side == WatchlistSyncUnmatched.Side.Letterboxd }
        assertEquals(
            listOf(
                "Chernobyl (2019)" to WatchlistSyncUnmatched.Reason.TvShow,
                "Lost Film" to WatchlistSyncUnmatched.Reason.NoExternalId,
                "Broken Page" to WatchlistSyncUnmatched.Reason.LookupFailed,
            ),
            letterboxd.map { it.title to it.reason },
        )
        assertEquals(LetterboxdError.NotFound, letterboxd.last().error)
    }

    @Test
    fun duplicateLibraryEntriesOfOneFilmAreHandledTogether() {
        val cinemeta = WatchlistSyncLibraryMovie("tt0113277", "Heat", "tt0113277", null)
        val tmdbCopy = WatchlistSyncLibraryMovie("tmdb:949", "Heat", null, 949)
        val library = listOf(matched(cinemeta, heat), matched(tmdbCopy, heat))
        val add = planWatchlistSync(WatchlistSyncInput(library, emptyList(), null, watched = mapOf("heat" to false)))
        assertEquals(listOf(cinemeta, tmdbCopy), assertIs<WatchlistSyncAction.AddToLetterboxd>(add.actions.single()).movies)
        val remove = planWatchlistSync(WatchlistSyncInput(library, emptyList(), LetterboxdWatchlistSnapshot(), watched = mapOf("heat" to true)))
        assertEquals(listOf(cinemeta, tmdbCopy), assertIs<WatchlistSyncAction.RemoveFromLibrary>(remove.actions.single()).movies)
        val inSync = planWatchlistSync(WatchlistSyncInput(library, listOf(item(heat), item(heat)), null))
        assertEquals(listOf("heat"), inSync.inSync)
        val snapshot = nextWatchlistSnapshot(WatchlistSyncInput(library, listOf(item(heat)), null), inSync, emptySet(), 1L)
        assertEquals(listOf("tt0113277", "tmdb:949"), snapshot.films.getValue("heat").nuvioIds)
    }

    @Test
    fun lookupsAskOnlyForWhatTheDecisionNeeds() {
        val lookups = watchlistSyncLookups(
            library = listOf(matched(alienMovie, alien), matched(heatMovie, heat), matched(jawsMovie, jaws)),
            watchlist = listOf(item(alien), item(dune), item(tenet)),
            snapshot = snapshotOf(tenet to tenetMovie),
        )
        // Tenet is in the snapshot: its removal needs no lookup. Alien is on both sides.
        assertEquals(listOf("dune-2021"), lookups.watchlistDetails.map { it.slug })
        assertEquals(listOf("heat", "jaws"), lookups.watchedChecks.map { it.slug })
    }

    @Test
    fun aFailedOrSkippedChangeKeepsItsPreviousSnapshotEntry() {
        val input = WatchlistSyncInput(
            library = listOf(matched(heatMovie, heat), matched(alienMovie, alien)),
            watchlist = listOf(item(tenet), item(dune), item(alien)),
            snapshot = snapshotOf(heat to heatMovie, tenet to tenetMovie, alien to alienMovie, jaws to jawsMovie),
            watchlistLookups = mapOf("dune-2021" to WatchlistSyncLookup.Found(dune)),
            watched = mapOf("heat" to false),
        )
        val plan = planWatchlistSync(input)
        assertEquals(setOf("heat", "tenet", "dune-2021"), plan.actions.map { it.slug }.toSet())
        // Nothing succeeded: heat and tenet keep their entry (retried next time), dune stays out, jaws (gone from both
        // sides) is dropped, alien (on both sides) stays.
        val none = nextWatchlistSnapshot(input, plan, emptySet(), 5L)
        assertEquals(setOf("heat", "tenet", "alien"), none.films.keys)
        // Everything succeeded.
        val all = nextWatchlistSnapshot(input, plan, plan.actions.toSet(), 5L)
        assertEquals(setOf("heat", "dune-2021", "alien"), all.films.keys)
        val duneEntry = all.films.getValue("dune-2021")
        assertEquals(listOf("tt1160419"), duneEntry.nuvioIds)
        assertEquals(438631, duneEntry.tmdbId)
    }

    @Test
    fun nuvioIdOfAFilmAddedFromLetterboxd() {
        assertEquals("tt1160419", watchlistSyncNuvioId(dune))
        assertEquals("tmdb:577922", watchlistSyncNuvioId(tenet.copy(imdbId = null)))
        assertEquals("Dune (2021)", WatchlistSyncAction.AddToLibrary(dune).title)
    }

    // ------------------------------------------------------------------ helpers

    private fun singleFilmInput(inLibrary: Boolean, inWatchlist: Boolean, snap: Snap, watched: Watched): WatchlistSyncInput {
        val film = LetterboxdFilm(slug = "f", lid = "Lf", uid = "film:1", tmdbId = 1, imdbId = "tt0000001", name = "F", year = 2000)
        val movie = WatchlistSyncLibraryMovie("tt0000001", "F", "tt0000001", 1)
        return WatchlistSyncInput(
            library = if (inLibrary) listOf(matched(movie, film)) else emptyList(),
            watchlist = if (inWatchlist) listOf(item(film)) else emptyList(),
            snapshot = when (snap) {
                Snap.FirstSync -> null
                Snap.Absent -> LetterboxdWatchlistSnapshot(syncedAtEpochMs = 1L)
                Snap.Present -> snapshotOf(film to movie)
            },
            watchlistLookups = mapOf("f" to WatchlistSyncLookup.Found(film)),
            watched = when (watched) {
                Watched.No -> mapOf("f" to false)
                Watched.Yes -> mapOf("f" to true)
                Watched.Unknown -> emptyMap()
            },
        )
    }

    private fun assertNoChange(plan: WatchlistSyncPlan, label: String, inSync: Boolean) {
        assertTrue(plan.actions.isEmpty(), "$label: ${plan.actions}")
        assertTrue(plan.skipped.isEmpty(), "$label: ${plan.skipped}")
        assertEquals(if (inSync) listOf("f") else emptyList(), plan.inSync, label)
    }

    private fun matched(movie: WatchlistSyncLibraryMovie, film: LetterboxdFilm) =
        WatchlistSyncLibraryEntry(movie, WatchlistSyncMatch.Matched(film))

    private fun item(film: LetterboxdFilm) = LetterboxdWatchlistItem(film.slug, film.lid, film.uid, film.name)

    private fun snapshotOf(vararg films: Pair<LetterboxdFilm, WatchlistSyncLibraryMovie>) = LetterboxdWatchlistSnapshot(
        syncedAtEpochMs = 1L,
        films = films.associate { (film, movie) ->
            film.slug to LetterboxdWatchlistSnapshot.Film(listOf(movie.nuvioId), film.imdbId, film.tmdbId, movie.title)
        },
    )

    private companion object {
        fun film(slug: String, tmdb: Int, imdb: String, name: String, year: Int) =
            LetterboxdFilm(slug = slug, lid = "L$slug", uid = "film:$tmdb", tmdbId = tmdb, imdbId = imdb, name = name, year = year, tmdbType = "movie")

        val alien = film("alien", 348, "tt0078748", "Alien", 1979)
        val heat = film("heat", 949, "tt0113277", "Heat", 1995)
        val jaws = film("jaws", 578, "tt0073195", "Jaws", 1975)
        val dune = film("dune-2021", 438631, "tt1160419", "Dune", 2021)
        val tenet = film("tenet", 577922, "tt6723592", "Tenet", 2020)
        val chernobyl = LetterboxdFilm(slug = "chernobyl", lid = "Lch", uid = "film:1", tmdbId = null, imdbId = "tt7366338", name = "Chernobyl", year = 2019, tmdbType = "tv")

        val alienMovie = WatchlistSyncLibraryMovie("tt0078748", "Alien", "tt0078748", 348)
        val heatMovie = WatchlistSyncLibraryMovie("tt0113277", "Heat", "tt0113277", 949)
        val jawsMovie = WatchlistSyncLibraryMovie("tt0073195", "Jaws", "tt0073195", 578)
        val duneMovie = WatchlistSyncLibraryMovie("tt1160419", "Dune", "tt1160419", 438631)
        val tenetMovie = WatchlistSyncLibraryMovie("tt6723592", "Tenet", "tt6723592", 577922)
    }
}
