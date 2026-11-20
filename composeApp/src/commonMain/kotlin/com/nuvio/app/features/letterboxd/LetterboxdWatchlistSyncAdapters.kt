package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.providerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-wide watchlist sync (F3): the run outlives the Library tab. */
internal object LetterboxdWatchlistSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller: LetterboxdWatchlistSyncController by lazy {
        LetterboxdWatchlistSyncController(
            engine = LetterboxdWatchlistSyncEngine(
                remote = RepositoryWatchlistRemote,
                library = NuvioWatchlistSyncLibrary,
                store = PreferenceWatchlistSnapshotStore,
            ),
            scope = scope,
        )
    }
}

/**
 * Letterboxd side over [LetterboxdRepository]: ids come from the persistent TMDB/IMDb ↔ slug index (never expire,
 * a film's ids don't change); personal reads/writes go through [ClientLetterboxdFilmSource.default] so the detail
 * block's short cache is cleared by the sync's writes.
 */
internal object RepositoryWatchlistRemote : LetterboxdWatchlistRemote {
    override suspend fun username(): String {
        LetterboxdRepository.awaitLoaded()
        val account = LetterboxdRepository.state.value
        if (!account.hasCredentials) throw LetterboxdException(LetterboxdError.NotConnected)
        if (account.reconnectRequired) throw LetterboxdException(LetterboxdError.AuthExpired)
        return account.username?.takeIf { it.isNotBlank() }
            ?: LetterboxdRepository.authenticated { fetchSession().username }
    }

    override suspend fun watchlistPage(username: String, page: Int) =
        LetterboxdRepository.authenticated { fetchWatchlistPage(username, page) }

    override suspend fun filmByTmdb(tmdbId: Int) = LetterboxdRepository.resolveFilmByTmdb(tmdbId, IDS_MAX_AGE_MS)
    override suspend fun filmByImdb(imdbId: String) = LetterboxdRepository.resolveFilmByImdb(imdbId, IDS_MAX_AGE_MS)
    override suspend fun film(slug: String) = LetterboxdRepository.film(slug, IDS_MAX_AGE_MS)
    override suspend fun isWatched(film: LetterboxdFilm) = ClientLetterboxdFilmSource.default.myState(film).watched
    override suspend fun setInWatchlist(film: LetterboxdFilm, inWatchlist: Boolean) =
        ClientLetterboxdFilmSource.default.setInWatchlist(film, inWatchlist)

    override suspend fun <T> paced(intervalMs: Long, block: suspend () -> T): T = LetterboxdRepository.paced(intervalMs, block)

    private const val IDS_MAX_AGE_MS = Long.MAX_VALUE
}

/**
 * Nuvio library side over [LibraryRepository] (same paths as the library button: local library synced with the Nuvio
 * account, or the active tracking provider). With a provider (Trakt, Simkl, MDBList) only its watchlist-kind list counts
 * as "library": personal lists are neither read nor changed. Changes made here are marked in
 * [LetterboxdLibraryBridge.ownRemovals] so the detail page's library hook doesn't mirror them onto Letterboxd again.
 */
internal object NuvioWatchlistSyncLibrary : WatchlistSyncLibrary {
    private val log = Logger.withTag("LetterboxdWatchlistSync")

    override suspend fun scope(): String? {
        LibraryRepository.ensureLoaded()
        val state = LibraryRepository.uiState.value
        if (!state.isLoaded || state.isLoading) return null
        if (state.sourceMode != LibrarySourceMode.LOCAL && watchlistKeys(state.sourceMode).isEmpty()) return null
        return "p${ProfileRepository.activeProfileId}:${state.sourceMode.name.lowercase()}"
    }

    override suspend fun movies(): List<WatchlistSyncLibraryMovie> {
        LibraryRepository.ensureLoaded()
        val state = LibraryRepository.uiState.value
        val items = if (state.sourceMode == LibrarySourceMode.LOCAL) {
            state.items
        } else {
            val keys = watchlistKeys(state.sourceMode).toSet()
            state.sections.filter { it.type in keys }.flatMap { it.items }
        }
        return items.filter { it.type.equals("movie", ignoreCase = true) }.distinctBy { it.id }.map { it.toSyncMovie() }
    }

    override suspend fun add(film: LetterboxdFilm, nuvioId: String): WatchlistSyncLibraryWrite {
        val item = LibraryItem(
            id = nuvioId,
            type = "movie",
            name = film.name ?: film.slug,
            poster = film.posterUrl,
            releaseInfo = film.year?.toString(),
            imdbId = film.imdbId,
            tmdbId = film.tmdbId,
            savedAtEpochMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        return change(item, saved = true)
    }

    override suspend fun remove(movies: List<WatchlistSyncLibraryMovie>): WatchlistSyncLibraryWrite {
        var result = WatchlistSyncLibraryWrite.Done
        movies.forEach { movie ->
            val item = LibraryRepository.savedItem(movie.nuvioId) ?: return@forEach
            val write = change(item, saved = false)
            if (write != WatchlistSyncLibraryWrite.Done && result == WatchlistSyncLibraryWrite.Done) result = write
        }
        return result
    }

    private suspend fun change(item: LibraryItem, saved: Boolean): WatchlistSyncLibraryWrite {
        val keys = echoKeys(item)
        keys.forEach { LetterboxdLibraryBridge.ownRemovals.mark(it, saved) }
        val result = try {
            val mode = LibraryRepository.uiState.value.sourceMode
            if (mode == LibrarySourceMode.LOCAL) {
                if (LibraryRepository.isSaved(item.id, item.type) != saved) LibraryRepository.toggleSaved(item)
                WatchlistSyncLibraryWrite.Done
            } else {
                changeProviderWatchlist(item, mode, saved)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Watchlist sync could not change the library: ${error::class.simpleName}" }
            WatchlistSyncLibraryWrite.Failed
        }
        if (result != WatchlistSyncLibraryWrite.Done) keys.forEach { LetterboxdLibraryBridge.ownRemovals.consume(it, saved) }
        return result
    }

    /** Sets the provider's watchlist-kind list(s); the local library and the other lists keep their membership. */
    private suspend fun changeProviderWatchlist(item: LibraryItem, mode: LibrarySourceMode, saved: Boolean): WatchlistSyncLibraryWrite {
        val watchlistKeys = watchlistKeys(mode)
        if (watchlistKeys.isEmpty()) return WatchlistSyncLibraryWrite.Failed
        val providerKeys = LibraryRepository.libraryListTabs().filter { it.providerId == mode.providerId }.mapTo(HashSet()) { it.key }
        val current = LibraryRepository.getMembershipSnapshot(item)
            .filterKeys { it == LOCAL_LIBRARY_LIST_KEY || it in providerKeys }
        val desired = current.toMutableMap().apply {
            if (saved) put(watchlistKeys.first(), true) else watchlistKeys.forEach { put(it, false) }
        }
        if (desired == current) return WatchlistSyncLibraryWrite.Done
        val applied = LibraryRepository.applyMembershipChanges(item, desired)
        return if (applied.requiresRemovalConfirmation) WatchlistSyncLibraryWrite.NeedsConfirmation else WatchlistSyncLibraryWrite.Done
    }

    private fun watchlistKeys(mode: LibrarySourceMode): List<String> {
        val providerId = mode.providerId ?: return emptyList()
        return LibraryRepository.libraryListTabs()
            .filter { it.providerId == providerId && it.kind == TrackingLibraryTabKind.WATCHLIST }
            .map { it.key }
    }

    /** Ids under which the detail page may show this film (its hook is keyed by the page's id). */
    private fun echoKeys(item: LibraryItem): Set<String> = buildSet {
        add(item.id)
        item.imdbId?.let(::add)
        item.tmdbId?.let { add("tmdb:$it") }
    }

    private fun LibraryItem.toSyncMovie(): WatchlistSyncLibraryMovie {
        val imdb = (imdbId ?: id.substringBefore(':')).takeIf { IMDB_ID.matches(it) }
        val tmdb = tmdbId ?: id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") }?.substringBefore(':')?.toIntOrNull()
        return WatchlistSyncLibraryMovie(nuvioId = id, title = name.ifBlank { id }, imdbId = imdb, tmdbId = tmdb)
    }

    private val IMDB_ID = Regex("tt\\d+")
}

/** Snapshots per scope (Nuvio profile + library source + Letterboxd member), with the app settings (not the cache). */
internal object PreferenceWatchlistSnapshotStore : WatchlistSyncSnapshotStore {
    private const val KEY = "watchlist_sync"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Stored(val snapshots: Map<String, LetterboxdWatchlistSnapshot> = emptyMap())

    override suspend fun load(scope: String): LetterboxdWatchlistSnapshot? = read().snapshots[scope]

    override suspend fun save(scope: String, snapshot: LetterboxdWatchlistSnapshot) {
        val stored = read()
        val payload = json.encodeToString(Stored.serializer(), stored.copy(snapshots = stored.snapshots + (scope to snapshot)))
        withContext(Dispatchers.Default) { LetterboxdPlatform.writePreference(KEY, payload) }
    }

    private suspend fun read(): Stored = withContext(Dispatchers.Default) {
        LetterboxdPlatform.readPreference(KEY)
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
    }
}
