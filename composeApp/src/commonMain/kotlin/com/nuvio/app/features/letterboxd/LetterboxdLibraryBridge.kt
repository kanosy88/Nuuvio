package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import kotlinx.coroutines.CancellationException

/**
 * Keeps the Nuvio library in line with Letterboxd: a film marked as watched on Letterboxd leaves the
 * Letterboxd watchlist, so it also leaves the Nuvio library (SPEC F1/F3). Goes through [LibraryRepository]
 * (local library or the active tracking provider, same path as the detail page's "saved" button).
 */
internal object LetterboxdLibraryBridge {
    private val log = Logger.withTag("LetterboxdLibrary")

    enum class Result { NotInLibrary, Removed, NeedsConfirmation, Failed }

    /** Removals made here, so the native library observer doesn't mirror them onto the watchlist again. */
    val ownRemovals = LetterboxdLibraryEchoGuard()

    suspend fun removeWatchedMovie(meta: MetaDetails, tmdbId: Int?): Result {
        if (!meta.type.equals("movie", ignoreCase = true)) return Result.NotInLibrary
        LibraryRepository.ensureLoaded()
        val candidates = savedCopies(meta, tmdbId)
        if (candidates.isEmpty()) return Result.NotInLibrary
        ownRemovals.mark(meta.id)
        var result = Result.Removed
        for (item in candidates) {
            try {
                val applied = LibraryRepository.toggleSaved(item)
                if (applied.requiresRemovalConfirmation) result = Result.NeedsConfirmation
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w { "Could not remove a watched film from the library: ${error::class.simpleName}" }
                result = Result.Failed
            }
        }
        if (result != Result.Removed) ownRemovals.consume(meta.id)
        return result
    }

    /** The library entries that stand for this film (same id, or same IMDb/TMDB id under another addon id). */
    private fun savedCopies(meta: MetaDetails, tmdbId: Int?): List<LibraryItem> {
        val copies = LinkedHashMap<String, LibraryItem>()
        if (LibraryRepository.isSaved(meta.id, meta.type)) {
            copies[meta.id] = LibraryRepository.savedItem(meta.id) ?: meta.toLibraryItem(savedAtEpochMs = 0L)
        }
        val imdbId = meta.imdbId?.takeIf { it.startsWith("tt") }
        LibraryRepository.uiState.value.items
            .filter { it.type.equals("movie", ignoreCase = true) && it.id !in copies }
            .filter { item ->
                (imdbId != null && (item.imdbId == imdbId || item.id == imdbId)) ||
                    (tmdbId != null && (item.tmdbId == tmdbId || item.id == "tmdb:$tmdbId"))
            }
            .filter { LibraryRepository.isSaved(it.id, it.type) }
            .forEach { copies[it.id] = it }
        return copies.values.toList()
    }
}
