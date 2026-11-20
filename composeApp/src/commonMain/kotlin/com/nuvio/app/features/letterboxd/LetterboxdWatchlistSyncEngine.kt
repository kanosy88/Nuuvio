package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Letterboxd side of the watchlist sync (F3). Swappable in tests (fake server). */
internal interface LetterboxdWatchlistRemote {
    /** The signed-in member; throws [LetterboxdException] (NotConnected, AuthExpired…). */
    suspend fun username(): String
    suspend fun watchlistPage(username: String, page: Int): LetterboxdWatchlistPage
    /** From the persistent TMDB/IMDb ↔ slug index first; null when Letterboxd doesn't know the film. */
    suspend fun filmByTmdb(tmdbId: Int): LetterboxdFilm?
    suspend fun filmByImdb(imdbId: String): LetterboxdFilm?
    suspend fun film(slug: String): LetterboxdFilm
    suspend fun isWatched(film: LetterboxdFilm): Boolean
    suspend fun setInWatchlist(film: LetterboxdFilm, inWatchlist: Boolean)
    /** Runs [block] with at least [intervalMs] between two Letterboxd requests. */
    suspend fun <T> paced(intervalMs: Long, block: suspend () -> T): T
}

/** Nuvio library side of the watchlist sync. */
internal interface WatchlistSyncLibrary {
    /** Identifies this library (profile + source) for the snapshot; null while it isn't loaded. */
    suspend fun scope(): String?
    suspend fun movies(): List<WatchlistSyncLibraryMovie>
    suspend fun add(film: LetterboxdFilm, nuvioId: String): WatchlistSyncLibraryWrite
    suspend fun remove(movies: List<WatchlistSyncLibraryMovie>): WatchlistSyncLibraryWrite
}

internal enum class WatchlistSyncLibraryWrite { Done, NeedsConfirmation, Failed }

internal interface WatchlistSyncSnapshotStore {
    suspend fun load(scope: String): LetterboxdWatchlistSnapshot?
    suspend fun save(scope: String, snapshot: LetterboxdWatchlistSnapshot)
}

internal sealed interface WatchlistSyncProgress {
    data class ReadingWatchlist(val page: Int) : WatchlistSyncProgress
    data class MatchingLibrary(val done: Int, val total: Int) : WatchlistSyncProgress
    data class LookingUpWatchlist(val done: Int, val total: Int) : WatchlistSyncProgress
    data class CheckingWatched(val done: Int, val total: Int) : WatchlistSyncProgress
    data class Applying(val done: Int, val total: Int) : WatchlistSyncProgress
}

/** Why the sync couldn't run. [error] is set for Letterboxd failures. */
internal class WatchlistSyncException(val failure: Failure, val error: LetterboxdError? = null) : Exception(failure.name) {
    enum class Failure {
        Letterboxd,
        /** The library is still loading (or its source is switching). */
        LibraryNotReady,
        /** Profile or library source changed between the preview and the confirmation. */
        LibraryChanged,
    }
}

internal data class WatchlistSyncAnalysis(
    val scope: String,
    val input: WatchlistSyncInput,
    val plan: WatchlistSyncPlan,
    /** Number of films Letterboxd announces for the watchlist (null when unknown) vs read. */
    val watchlistAnnounced: Int?,
    val watchlistRead: Int,
) {
    val incompleteWatchlist: Boolean get() = watchlistAnnounced != null && watchlistRead < watchlistAnnounced

    /** Many removals from the watchlist at once: likely a library that isn't the one synced last time. */
    val massRemoval: Boolean
        get() = plan.removeFromLetterboxd.size >= MASS_REMOVAL_MIN &&
            plan.removeFromLetterboxd.size * 2 > (input.snapshot?.films?.size ?: 0)
}

internal data class WatchlistSyncResult(val action: WatchlistSyncAction, val outcome: Outcome) {
    sealed interface Outcome {
        data object Done : Outcome

        /** Already in the wanted state (nothing written). */
        data object AlreadyDone : Outcome

        /** The film changed in the library after the preview: left as is. */
        data object ChangedSincePreview : Outcome

        /** Removal needs a confirmation of the library provider (e.g. Simkl history): left as is. */
        data object NeedsConfirmation : Outcome

        /** [error] null = the Nuvio library refused the change. */
        data class Failed(val error: LetterboxdError?, val detail: String? = null) : Outcome

        /** Not attempted: Letterboxd stopped answering ([error]) earlier in the run. */
        data class NotAttempted(val error: LetterboxdError) : Outcome
    }

    val succeeded: Boolean get() = outcome == Outcome.Done || outcome == Outcome.AlreadyDone
}

internal data class WatchlistSyncReport(
    val plan: WatchlistSyncPlan,
    val results: List<WatchlistSyncResult>,
    val snapshotSaved: Boolean,
) {
    val failures: List<WatchlistSyncResult> get() = results.filterNot { it.succeeded }
}

/**
 * Runs the watchlist sync: [analyze] reads both sides (watchlist pages, TMDB/IMDb ↔ slug matching, watched state)
 * and computes the plan shown in the preview; [apply] performs it after confirmation, tolerating partial failures.
 * All Letterboxd requests are spaced ([readIntervalMs], [writeIntervalMs]) and mappings are cached persistently by
 * the remote, so a retry after a Cloudflare challenge resumes cheaply.
 */
internal class LetterboxdWatchlistSyncEngine(
    private val remote: LetterboxdWatchlistRemote,
    private val library: WatchlistSyncLibrary,
    private val store: WatchlistSyncSnapshotStore,
    private val readIntervalMs: Long = 1_500L,
    private val writeIntervalMs: Long = 2_000L,
    private val maxPages: Int = 200,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val log = Logger.withTag("LetterboxdWatchlistSync")

    suspend fun analyze(onProgress: (WatchlistSyncProgress) -> Unit = {}): WatchlistSyncAnalysis = letterboxdFailures {
        val username = remote.username()
        val scope = library.scope() ?: throw WatchlistSyncException(WatchlistSyncException.Failure.LibraryNotReady)
        val snapshotScope = snapshotScope(scope, username)
        val snapshot = store.load(snapshotScope)
        val movies = library.movies()
        remote.paced(readIntervalMs) {
            // 1. Watchlist, page by page.
            val watchlist = LinkedHashMap<String, LetterboxdWatchlistItem>()
            var announced: Int? = null
            for (page in 1..maxPages) {
                onProgress(WatchlistSyncProgress.ReadingWatchlist(page))
                val result = remote.watchlistPage(username, page)
                if (page == 1) announced = result.totalEntries
                result.items.forEach { item -> watchlist.getOrPut(item.slug) { item } }
                if (!result.hasNext) break
            }
            // 2. Library films → Letterboxd (persistent index first).
            val entries = movies.mapIndexed { index, movie ->
                onProgress(WatchlistSyncProgress.MatchingLibrary(index, movies.size))
                WatchlistSyncLibraryEntry(movie, match(movie))
            }
            onProgress(WatchlistSyncProgress.MatchingLibrary(movies.size, movies.size))
            val items = watchlist.values.toList()
            val lookups = watchlistSyncLookups(entries, items, snapshot)
            // 3. Ids of the watchlist films that may be added to the library.
            val details = LinkedHashMap<String, WatchlistSyncLookup>()
            lookups.watchlistDetails.forEachIndexed { index, item ->
                onProgress(WatchlistSyncProgress.LookingUpWatchlist(index, lookups.watchlistDetails.size))
                details[item.slug] = perFilm({ WatchlistSyncLookup.Found(remote.film(item.slug)) }) { WatchlistSyncLookup.Failed(it) }
            }
            // 4. Watched state of the films found only in the library (exception rule).
            val watched = LinkedHashMap<String, Boolean>()
            lookups.watchedChecks.forEachIndexed { index, film ->
                onProgress(WatchlistSyncProgress.CheckingWatched(index, lookups.watchedChecks.size))
                perFilm({ remote.isWatched(film) }) { null }?.let { watched[film.slug] = it }
            }
            val input = WatchlistSyncInput(entries, items, snapshot, details, watched)
            WatchlistSyncAnalysis(
                scope = snapshotScope,
                input = input,
                plan = planWatchlistSync(input),
                watchlistAnnounced = announced,
                watchlistRead = items.size,
            )
        }
    }

    suspend fun apply(analysis: WatchlistSyncAnalysis, onProgress: (WatchlistSyncProgress) -> Unit = {}): WatchlistSyncReport {
        val libraryScope = library.scope()
        val username = try {
            remote.username()
        } catch (error: LetterboxdException) {
            throw WatchlistSyncException(WatchlistSyncException.Failure.Letterboxd, error.error)
        }
        if (libraryScope == null || snapshotScope(libraryScope, username) != analysis.scope) {
            throw WatchlistSyncException(WatchlistSyncException.Failure.LibraryChanged)
        }
        val plan = analysis.plan
        val total = plan.actions.size
        val results = mutableListOf<WatchlistSyncResult>()
        fun progress() = onProgress(WatchlistSyncProgress.Applying(results.size, total))
        progress()

        // Library first (local, quick), then Letterboxd (spaced writes).
        val current = library.movies()
        plan.actions.filter { it is WatchlistSyncAction.AddToLibrary || it is WatchlistSyncAction.RemoveFromLibrary }.forEach { action ->
            results += WatchlistSyncResult(action, applyLibrary(action, current))
            progress()
        }
        var stoppedBy: LetterboxdError? = null
        val letterboxdActions = plan.actions.filter { it is WatchlistSyncAction.AddToLetterboxd || it is WatchlistSyncAction.RemoveFromLetterboxd }
        if (letterboxdActions.isNotEmpty()) {
            remote.paced(writeIntervalMs) {
                letterboxdActions.forEach { action ->
                    val outcome = stoppedBy?.let { WatchlistSyncResult.Outcome.NotAttempted(it) } ?: try {
                        applyLetterboxd(action, current)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: LetterboxdException) {
                        log.w { "Watchlist sync write failed: ${error.error}${error.status?.let { " (HTTP $it)" }.orEmpty()}" }
                        if (error.error.stopsRun) stoppedBy = error.error
                        WatchlistSyncResult.Outcome.Failed(
                            error.error,
                            error.message?.takeIf { error.error == LetterboxdError.Rejected && it != error.error.name },
                        )
                    }
                    results += WatchlistSyncResult(action, outcome)
                    progress()
                }
            }
        }

        val succeeded = results.filter { it.succeeded }.mapTo(HashSet()) { it.action }
        val snapshot = nextWatchlistSnapshot(analysis.input, plan, succeeded, nowMs())
        val saved = try {
            store.save(analysis.scope, snapshot)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e { "Could not save the watchlist sync snapshot: ${error::class.simpleName}" }
            false
        }
        log.i { "Watchlist sync applied: ${succeeded.size}/$total, snapshot ${snapshot.films.size} films" }
        return WatchlistSyncReport(plan, results, saved)
    }

    private suspend fun applyLibrary(action: WatchlistSyncAction, current: List<WatchlistSyncLibraryMovie>): WatchlistSyncResult.Outcome {
        val write = try {
            when (action) {
                is WatchlistSyncAction.AddToLibrary -> {
                    if (current.any { it.sharesIdsWith(action.film) }) return WatchlistSyncResult.Outcome.AlreadyDone
                    library.add(action.film, action.nuvioId)
                }
                is WatchlistSyncAction.RemoveFromLibrary -> {
                    val ids = action.movies.mapTo(HashSet()) { it.nuvioId }
                    val still = current.filter { it.nuvioId in ids }
                    if (still.isEmpty()) return WatchlistSyncResult.Outcome.AlreadyDone
                    library.remove(still)
                }
                else -> error("Not a library action")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Watchlist sync library change failed: ${error::class.simpleName}" }
            WatchlistSyncLibraryWrite.Failed
        }
        return when (write) {
            WatchlistSyncLibraryWrite.Done -> WatchlistSyncResult.Outcome.Done
            WatchlistSyncLibraryWrite.NeedsConfirmation -> WatchlistSyncResult.Outcome.NeedsConfirmation
            WatchlistSyncLibraryWrite.Failed -> WatchlistSyncResult.Outcome.Failed(null)
        }
    }

    private suspend fun applyLetterboxd(action: WatchlistSyncAction, current: List<WatchlistSyncLibraryMovie>): WatchlistSyncResult.Outcome {
        when (action) {
            is WatchlistSyncAction.AddToLetterboxd -> {
                val ids = action.movies.mapTo(HashSet()) { it.nuvioId }
                if (current.none { it.nuvioId in ids }) return WatchlistSyncResult.Outcome.ChangedSincePreview
                remote.setInWatchlist(action.film, true)
            }
            is WatchlistSyncAction.RemoveFromLetterboxd -> {
                if (current.any { it.sharesIdsWith(action.previous) }) return WatchlistSyncResult.Outcome.ChangedSincePreview
                val item = action.item
                val film = item.lid?.let { LetterboxdFilm(slug = item.slug, lid = it, uid = item.uid, name = item.name) }
                    ?: remote.film(item.slug)
                remote.setInWatchlist(film, false)
            }
            else -> error("Not a Letterboxd action")
        }
        return WatchlistSyncResult.Outcome.Done
    }

    private suspend fun match(movie: WatchlistSyncLibraryMovie): WatchlistSyncMatch {
        if (movie.tmdbId == null && movie.imdbId == null) return WatchlistSyncMatch.NoExternalId
        return perFilm({
            val film = movie.tmdbId?.let { remote.filmByTmdb(it) } ?: movie.imdbId?.let { remote.filmByImdb(it) }
            if (film == null) WatchlistSyncMatch.NotOnLetterboxd else WatchlistSyncMatch.Matched(film)
        }) { error -> if (error == LetterboxdError.NotFound) WatchlistSyncMatch.NotOnLetterboxd else WatchlistSyncMatch.Failed(error) }
    }

    /** Per-film failures don't stop the analysis; session / Cloudflare / network failures do (see [stopsRun]). */
    private suspend fun <T> perFilm(block: suspend () -> T, onFailure: (LetterboxdError) -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: LetterboxdException) {
        if (error.error.stopsRun) throw error
        log.w { "Watchlist sync lookup failed: ${error.error}" }
        onFailure(error.error)
    } catch (error: IllegalArgumentException) {
        onFailure(LetterboxdError.Parse)
    }

    private suspend fun <T> letterboxdFailures(block: suspend () -> T): T = try {
        block()
    } catch (error: LetterboxdException) {
        log.w { "Watchlist sync analysis stopped: ${error.error}${error.status?.let { " (HTTP $it)" }.orEmpty()}" }
        throw WatchlistSyncException(WatchlistSyncException.Failure.Letterboxd, error.error)
    }

    private fun snapshotScope(libraryScope: String, username: String) = "$libraryScope|lb:${username.lowercase()}"
}

/** Errors that make every following request pointless (session, Cloudflare, rate limit, network). */
internal val LetterboxdError.stopsRun: Boolean
    get() = when (this) {
        LetterboxdError.NotConnected, LetterboxdError.AuthExpired, LetterboxdError.SessionCookieMissing,
        LetterboxdError.CloudflareChallenge, LetterboxdError.RateLimited, LetterboxdError.Network,
        -> true
        else -> false
    }

/** UI state of the sync dialog: analysis → preview (confirmation) → apply → report. */
internal class LetterboxdWatchlistSyncController(
    private val engine: LetterboxdWatchlistSyncEngine,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Analyzing(val progress: WatchlistSyncProgress? = null) : State
        data class Preview(val analysis: WatchlistSyncAnalysis) : State
        data class Applying(val analysis: WatchlistSyncAnalysis, val progress: WatchlistSyncProgress.Applying? = null) : State
        data class Done(val report: WatchlistSyncReport) : State
        data class Failed(val failure: WatchlistSyncException.Failure, val error: LetterboxdError? = null) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var job: Job? = null

    val isRunning: Boolean get() = state.value.let { it is State.Analyzing || it is State.Applying }

    /** Reads both sides and shows the preview. Ignored while a run is in progress. */
    fun analyze() {
        if (isRunning) return
        mutableState.value = State.Analyzing()
        job = scope.launch {
            mutex.withLock {
                val next = try {
                    State.Preview(engine.analyze { progress -> if (isActive) mutableState.value = State.Analyzing(progress) })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WatchlistSyncException) {
                    State.Failed(error.failure, error.error)
                } catch (error: Exception) {
                    State.Failed(WatchlistSyncException.Failure.Letterboxd, LetterboxdError.Parse)
                }
                // Cancelled meanwhile (dialog closed): don't bring the preview back.
                ensureActive()
                mutableState.value = next
            }
        }
    }

    /** Confirmation of the preview: applies the plan. */
    fun apply() {
        val preview = state.value as? State.Preview ?: return
        mutableState.value = State.Applying(preview.analysis)
        job = scope.launch {
            mutex.withLock {
                mutableState.value = try {
                    State.Done(engine.apply(preview.analysis) { progress ->
                        if (progress is WatchlistSyncProgress.Applying) mutableState.value = State.Applying(preview.analysis, progress)
                    })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WatchlistSyncException) {
                    State.Failed(error.failure, error.error)
                } catch (error: Exception) {
                    State.Failed(WatchlistSyncException.Failure.Letterboxd, LetterboxdError.Parse)
                }
            }
        }
    }

    /** Cancels an analysis, or closes the preview / report. An apply in progress isn't interrupted. */
    fun dismiss() {
        when (state.value) {
            is State.Applying -> return
            is State.Analyzing -> job?.cancel()
            else -> Unit
        }
        mutableState.value = State.Idle
    }
}

internal const val MASS_REMOVAL_MIN = 5
