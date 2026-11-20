package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F1 revision: mirrors Nuvio's own "watched" and "library" buttons (detail page of a film) onto Letterboxd.
 * - watched in Nuvio → watched on Letterboxd (Letterboxd drops it from the watchlist) → removed from the Nuvio library;
 * - not watched in Nuvio → not watched on Letterboxd;
 * - added to / removed from the Nuvio library → added to / removed from the Letterboxd watchlist.
 * Films only. Nothing happens without a Letterboxd account. The Nuvio action is never undone: a Letterboxd
 * failure is only reported through [notify] (non-blocking message).
 */
internal class LetterboxdNativeSync(
    private val source: LetterboxdFilmSource,
    private val scope: CoroutineScope,
    private val notify: suspend (Notice) -> Unit,
    /** Library removals made by the Letterboxd integration itself (not to be mirrored back onto the watchlist). */
    private val ownRemovals: LetterboxdLibraryEchoGuard = LetterboxdLibraryBridge.ownRemovals,
) {
    private val log = Logger.withTag("LetterboxdNative")
    private val mutex = Mutex()
    private val mutableChanges = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Slug of a film whose Letterboxd state was just written (the detail block reloads it). */
    val changes: SharedFlow<String> = mutableChanges.asSharedFlow()

    /** A film of the Nuvio catalog. [removeFromLibrary] removes it from the Nuvio library (existing F1 logic). */
    class Target(
        val key: String,
        val resolveFilm: suspend () -> LetterboxdFilm?,
        val removeFromLibrary: suspend () -> LetterboxdLibraryBridge.Result,
    )

    enum class Operation { Watched, Unwatched, WatchlistAdd, WatchlistRemove }

    sealed interface Notice {
        data class Failed(val operation: Operation, val error: LetterboxdError, val detail: String? = null) : Notice
        data class NotOnLetterboxd(val operation: Operation) : Notice
        data class ReconnectRequired(val operation: Operation) : Notice
        data class LibraryRemoval(val result: LetterboxdLibraryBridge.Result) : Notice
    }

    /** The native "watched" state of [target] changed (user action in Nuvio). */
    fun watchedChanged(target: Target, watched: Boolean) =
        run(target, if (watched) Operation.Watched else Operation.Unwatched) { film ->
            source.setWatched(film, watched)
            if (watched) {
                // Re-read (the write cleared the cache): Letterboxd should have dropped it from the watchlist itself.
                val state = source.myState(film)
                if (state.watched && state.inWatchlist) source.setInWatchlist(film, false)
                ownRemovals.mark(target.key)
                val removal = runCatching { target.removeFromLibrary() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(LetterboxdLibraryBridge.Result.Failed)
                if (removal != LetterboxdLibraryBridge.Result.Removed) ownRemovals.consume(target.key)
                if (removal != LetterboxdLibraryBridge.Result.NotInLibrary) notify(Notice.LibraryRemoval(removal))
            }
        }

    /** [target] was added to / removed from the Nuvio library (user action in Nuvio). */
    fun libraryChanged(target: Target, saved: Boolean) {
        // Change made by the integration itself (removal after "watched", watchlist sync F3): Letterboxd is
        // already in that state, mirroring it would be a second write.
        if (ownRemovals.consume(target.key, saved)) return
        run(target, if (saved) Operation.WatchlistAdd else Operation.WatchlistRemove) { film ->
            source.setInWatchlist(film, saved)
        }
    }

    private fun run(target: Target, operation: Operation, block: suspend (LetterboxdFilm) -> Unit) {
        val account = source.account.value
        if (!account.hasCredentials) return
        scope.launch {
            mutex.withLock {
                if (source.account.value.status == LetterboxdConnectionStatus.ReconnectRequired) {
                    notify(Notice.ReconnectRequired(operation))
                    return@withLock
                }
                var film: LetterboxdFilm? = null
                try {
                    film = target.resolveFilm()
                    if (film == null) {
                        notify(Notice.NotOnLetterboxd(operation))
                        return@withLock
                    }
                    block(film)
                    log.i { "Letterboxd ${operation.name} mirrored" }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LetterboxdException) {
                    if (error.error == LetterboxdError.NotConnected) return@withLock
                    log.w { "Letterboxd ${operation.name} failed: ${error.error}${error.status?.let { " (HTTP $it)" }.orEmpty()}" }
                    notify(
                        Notice.Failed(
                            operation = operation,
                            error = error.error,
                            detail = error.message?.takeIf { error.error == LetterboxdError.Rejected && it != error.error.name },
                        ),
                    )
                } catch (error: IllegalArgumentException) {
                    notify(Notice.Failed(operation, LetterboxdError.Rejected))
                } finally {
                    film?.let { mutableChanges.tryEmit(it.slug) }
                }
            }
        }
    }
}

/**
 * Remembers library changes made by the integration for a short while, so they aren't mirrored back.
 * [saved] = the direction of the change (false = removal, the default), so a later opposite click still counts.
 */
internal class LetterboxdLibraryEchoGuard(
    private val ttlMs: Long = 60_000L,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val marks = LinkedHashMap<String, Long>()

    fun mark(key: String, saved: Boolean = false) = synchronizedMarks { marks[markKey(key, saved)] = nowMs() }

    /** True (and forgets it) when [key] was changed that way by the integration less than [ttlMs] ago. */
    fun consume(key: String, saved: Boolean = false): Boolean = synchronizedMarks {
        val at = marks.remove(markKey(key, saved)) ?: return@synchronizedMarks false
        nowMs() - at < ttlMs
    }

    private fun markKey(key: String, saved: Boolean) = if (saved) "+$key" else "-$key"

    private val lock = SynchronizedObject()
    private inline fun <T> synchronizedMarks(block: () -> T): T = synchronized(lock, block)
}

/**
 * Turns successive observations of the native buttons' state into user changes. A change is only reported
 * between two observations taken while the repositories are loaded, and not when it comes with a (re)load
 * (first remote pull, profile switch, library source change), so a sync never passes for a click.
 */
internal class LetterboxdNativeStateTracker {
    data class Observation(
        val watchedLoaded: Boolean,
        /** Changes when the remote watched items arrive: such a change isn't a user action. */
        val watchedRemoteLoaded: Boolean,
        val watched: Boolean,
        val libraryLoaded: Boolean,
        /** Library source (local / provider): switching it isn't a user action. */
        val librarySource: String,
        val saved: Boolean,
    )

    sealed interface Change {
        data class Watched(val watched: Boolean) : Change
        data class Library(val saved: Boolean) : Change
    }

    private var last: Observation? = null

    fun next(observation: Observation): List<Change> {
        val previous = last
        last = observation
        if (previous == null) return emptyList()
        return buildList {
            if (previous.watchedLoaded && observation.watchedLoaded &&
                previous.watchedRemoteLoaded == observation.watchedRemoteLoaded &&
                previous.watched != observation.watched
            ) {
                add(Change.Watched(observation.watched))
            }
            if (previous.libraryLoaded && observation.libraryLoaded &&
                previous.librarySource == observation.librarySource &&
                previous.saved != observation.saved
            ) {
                add(Change.Library(observation.saved))
            }
        }
    }
}
