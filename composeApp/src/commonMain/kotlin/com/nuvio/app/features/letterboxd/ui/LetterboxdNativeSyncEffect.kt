package com.nuvio.app.features.letterboxd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.letterboxd.ClientLetterboxdFilmSource
import com.nuvio.app.features.letterboxd.LetterboxdFilm
import com.nuvio.app.features.letterboxd.LetterboxdLibraryBridge
import com.nuvio.app.features.letterboxd.LetterboxdNativeStateTracker
import com.nuvio.app.features.letterboxd.LetterboxdNativeSync
import com.nuvio.app.features.letterboxd.LetterboxdNativeSync.Notice
import com.nuvio.app.features.letterboxd.LetterboxdNativeSync.Operation
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/** App-wide [LetterboxdNativeSync]: its writes outlive the detail page (leaving right after a click is fine). */
internal object LetterboxdNativeActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sync: LetterboxdNativeSync by lazy {
        LetterboxdNativeSync(source = ClientLetterboxdFilmSource.default, scope = scope, notify = ::show)
    }

    private suspend fun show(notice: Notice) {
        val message = when (notice) {
            is Notice.Failed -> {
                val reason = listOfNotNull(getString(notice.error.messageResource()), notice.detail).joinToString(" ")
                getString(notice.operation.failureMessage(), reason)
            }
            is Notice.NotOnLetterboxd -> getString(Res.string.letterboxd_sync_not_on_letterboxd)
            is Notice.ReconnectRequired -> getString(Res.string.letterboxd_sync_reconnect)
            is Notice.LibraryRemoval -> when (notice.result) {
                LetterboxdLibraryBridge.Result.Removed -> getString(Res.string.letterboxd_film_removed_from_library)
                LetterboxdLibraryBridge.Result.NeedsConfirmation -> getString(Res.string.letterboxd_film_library_confirm)
                LetterboxdLibraryBridge.Result.Failed -> getString(Res.string.letterboxd_film_library_failed)
                LetterboxdLibraryBridge.Result.NotInLibrary -> return
            }
        }
        NuvioToastController.show(message, if (notice is Notice.LibraryRemoval && notice.result == LetterboxdLibraryBridge.Result.Removed) 2500L else 5000L)
    }

    private fun Operation.failureMessage() = when (this) {
        Operation.Watched -> Res.string.letterboxd_sync_failed_watched
        Operation.Unwatched -> Res.string.letterboxd_sync_failed_unwatched
        Operation.WatchlistAdd -> Res.string.letterboxd_sync_failed_watchlist_add
        Operation.WatchlistRemove -> Res.string.letterboxd_sync_failed_watchlist_remove
    }
}

/**
 * F1 revision hook (detail page of a film, outside the lazy list so it stays active while scrolling):
 * observes the state shown by Nuvio's own "watched" and "library" buttons for this film and mirrors the
 * user's changes onto Letterboxd. The buttons themselves are untouched. Films only, desktop only.
 */
@Composable
internal fun LetterboxdNativeSyncEffect(meta: MetaDetails) {
    if (!LetterboxdRepository.isSupported || !meta.type.equals("movie", ignoreCase = true)) return
    val currentMeta by rememberUpdatedState(meta)
    LaunchedEffect(meta.id, meta.type) {
        LetterboxdRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        LibraryRepository.ensureLoaded()
        val target = LetterboxdNativeSync.Target(
            key = meta.id,
            resolveFilm = { resolveLetterboxdFilm(currentMeta) },
            removeFromLibrary = {
                val tmdbId = runCatching { TmdbService.ensureTmdbId(currentMeta.id, "movie", currentMeta.imdbId)?.toIntOrNull() }.getOrNull()
                LetterboxdLibraryBridge.removeWatchedMovie(currentMeta, tmdbId)
            },
        )
        val tracker = LetterboxdNativeStateTracker()
        combine(WatchedRepository.uiState, LibraryRepository.uiState) { watched, library ->
            LetterboxdNativeStateTracker.Observation(
                watchedLoaded = watched.isLoaded,
                watchedRemoteLoaded = watched.hasLoadedRemoteItems,
                watched = WatchedRepository.isWatched(id = meta.id, type = meta.type),
                libraryLoaded = library.isLoaded,
                librarySource = library.sourceMode.name,
                saved = LibraryRepository.isSaved(meta.id, meta.type),
            )
        }
            .distinctUntilChanged()
            .collect { observation ->
                tracker.next(observation).forEach { change ->
                    when (change) {
                        is LetterboxdNativeStateTracker.Change.Watched -> LetterboxdNativeActions.sync.watchedChanged(target, change.watched)
                        is LetterboxdNativeStateTracker.Change.Library -> LetterboxdNativeActions.sync.libraryChanged(target, change.saved)
                    }
                }
            }
    }
}

/**
 * Mirrors a change of Nuvio's native "watched" state made outside the detail page (Journal) exactly like
 * [LetterboxdNativeSyncEffect] does for the detail button: same writes, library removal and notices.
 * Films only; no-op without Letterboxd cookies or on unsupported platforms.
 */
internal fun mirrorNativeWatchedToLetterboxd(meta: MetaDetails, watched: Boolean) {
    if (!LetterboxdRepository.isSupported || !meta.type.equals("movie", ignoreCase = true)) return
    val target = LetterboxdNativeSync.Target(
        key = meta.id,
        resolveFilm = { resolveLetterboxdFilm(meta) },
        removeFromLibrary = {
            val tmdbId = runCatching { TmdbService.ensureTmdbId(meta.id, "movie", meta.imdbId)?.toIntOrNull() }.getOrNull()
            LetterboxdLibraryBridge.removeWatchedMovie(meta, tmdbId)
        },
    )
    LetterboxdNativeActions.sync.watchedChanged(target, watched)
}

/** Same matching as the detail block: TMDB id, IMDb id as fallback. */
internal suspend fun resolveLetterboxdFilm(meta: MetaDetails): LetterboxdFilm? {
    val tmdbId = runCatching { TmdbService.ensureTmdbId(meta.id, "movie", meta.imdbId)?.toIntOrNull() }.getOrNull()
    val imdbId = (meta.imdbId ?: meta.id.substringBefore(':')).takeIf { Regex("tt\\d+").matches(it) }
    return ClientLetterboxdFilmSource.default.resolveFilm(tmdbId, imdbId)
}
