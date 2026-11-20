package com.nuvio.app.features.letterboxd

import kotlinx.serialization.Serializable

/*
 * F3 — Letterboxd watchlist ↔ Nuvio library sync: decision logic (pure, no I/O, exhaustively tested).
 * Films only. Rules (SPEC F3):
 * - in both → nothing;
 * - library only → add to the Letterboxd watchlist (the library wins, even when it was removed on Letterboxd);
 *   EXCEPTION: watched on Letterboxd → remove it from the library (and don't put it back in the watchlist);
 * - Letterboxd only, absent from the snapshot → add to the library;
 * - Letterboxd only, in the snapshot (on both sides after the last sync) → removed from the library since → remove it
 *   from the Letterboxd watchlist;
 * - first sync (no snapshot): union, nothing is removed anywhere. A library film already watched on Letterboxd is kept
 *   in the library and not put in the watchlist (the next sync removes it from the library).
 * The snapshot holds the films that were on both sides after the last sync, keyed by Letterboxd slug. Only successful
 * changes update it; a film whose change failed keeps its previous snapshot entry, so the next sync retries it.
 * Safety: a film is never removed from the watchlist while a library film that could be it couldn't be matched.
 */

/** A movie of the Nuvio library. Several entries (different Nuvio ids) can stand for the same Letterboxd film. */
internal data class WatchlistSyncLibraryMovie(
    val nuvioId: String,
    val title: String,
    val imdbId: String?,
    val tmdbId: Int?,
)

/** How a library movie was matched with Letterboxd. */
internal sealed interface WatchlistSyncMatch {
    data class Matched(val film: LetterboxdFilm) : WatchlistSyncMatch

    /** Neither TMDB nor IMDb id: it can't be looked up on Letterboxd. */
    data object NoExternalId : WatchlistSyncMatch

    data object NotOnLetterboxd : WatchlistSyncMatch

    /** The lookup failed this time: the film is left out of every decision. */
    data class Failed(val error: LetterboxdError) : WatchlistSyncMatch
}

internal data class WatchlistSyncLibraryEntry(val movie: WatchlistSyncLibraryMovie, val match: WatchlistSyncMatch)

/** Details (TMDB/IMDb ids) of a watchlist film, looked up only when it may be added to the library. */
internal sealed interface WatchlistSyncLookup {
    data class Found(val film: LetterboxdFilm) : WatchlistSyncLookup
    data class Failed(val error: LetterboxdError) : WatchlistSyncLookup
}

/** State of the last sync: the films on both sides afterwards, by Letterboxd slug. */
@Serializable
internal data class LetterboxdWatchlistSnapshot(
    val syncedAtEpochMs: Long = 0L,
    val films: Map<String, Film> = emptyMap(),
) {
    @Serializable
    data class Film(
        val nuvioIds: List<String> = emptyList(),
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val title: String? = null,
    )
}

internal data class WatchlistSyncInput(
    val library: List<WatchlistSyncLibraryEntry>,
    val watchlist: List<LetterboxdWatchlistItem>,
    /** Null = never synced (first sync). */
    val snapshot: LetterboxdWatchlistSnapshot?,
    /** Details of the watchlist films listed by [watchlistSyncLookups]. */
    val watchlistLookups: Map<String, WatchlistSyncLookup> = emptyMap(),
    /** Watched state on Letterboxd (slug → watched) of the films found only in the library; missing = unknown. */
    val watched: Map<String, Boolean> = emptyMap(),
)

internal sealed interface WatchlistSyncAction {
    val slug: String
    val title: String

    /** Only in the library. [removedOnLetterboxd]: it was on both sides at the last sync (the library wins). */
    data class AddToLetterboxd(
        val film: LetterboxdFilm,
        val movies: List<WatchlistSyncLibraryMovie>,
        val removedOnLetterboxd: Boolean,
    ) : WatchlistSyncAction {
        override val slug get() = film.slug
        override val title get() = movies.first().title
    }

    /** Was on both sides at the last sync and has been removed from the library since. */
    data class RemoveFromLetterboxd(
        val item: LetterboxdWatchlistItem,
        val previous: LetterboxdWatchlistSnapshot.Film,
    ) : WatchlistSyncAction {
        override val slug get() = item.slug
        override val title get() = item.name ?: previous.title ?: item.slug
    }

    /** New in the Letterboxd watchlist. */
    data class AddToLibrary(val film: LetterboxdFilm) : WatchlistSyncAction {
        override val slug get() = film.slug
        override val title get() = film.displayTitle()
        val nuvioId: String get() = watchlistSyncNuvioId(film)
    }

    /** Only in the library and already watched on Letterboxd (exception). */
    data class RemoveFromLibrary(
        val film: LetterboxdFilm,
        val movies: List<WatchlistSyncLibraryMovie>,
    ) : WatchlistSyncAction {
        override val slug get() = film.slug
        override val title get() = movies.first().title
    }
}

/** A film left as it is this time, and why. */
internal data class WatchlistSyncSkip(val slug: String, val title: String, val reason: Reason) {
    enum class Reason {
        /** First sync: watched on Letterboxd, kept in the library and not put back in the watchlist. */
        WatchedKeptOnFirstSync,

        /** Its watched state on Letterboxd couldn't be read. */
        WatchedStateUnknown,

        /** A library film that could be this one couldn't be matched (or matched another Letterboxd film). */
        LibraryMatchUncertain,
    }
}

/** A film with no counterpart on the other side, for the report. */
internal data class WatchlistSyncUnmatched(
    val side: Side,
    val title: String,
    val reason: Reason,
    val error: LetterboxdError? = null,
) {
    enum class Side { Library, Letterboxd }
    enum class Reason {
        /** Library film without TMDB/IMDb id, or Letterboxd film without either. */
        NoExternalId,
        NotOnLetterboxd,
        /** Letterboxd lists it as a film but it's a TV show: films only. */
        TvShow,
        LookupFailed,
    }
}

internal data class WatchlistSyncPlan(
    val firstSync: Boolean,
    val actions: List<WatchlistSyncAction>,
    /** Slugs already on both sides. */
    val inSync: List<String>,
    val skipped: List<WatchlistSyncSkip>,
    val unmatched: List<WatchlistSyncUnmatched>,
) {
    val addToLetterboxd get() = actions.filterIsInstance<WatchlistSyncAction.AddToLetterboxd>()
    val removeFromLetterboxd get() = actions.filterIsInstance<WatchlistSyncAction.RemoveFromLetterboxd>()
    val addToLibrary get() = actions.filterIsInstance<WatchlistSyncAction.AddToLibrary>()
    val removeFromLibrary get() = actions.filterIsInstance<WatchlistSyncAction.RemoveFromLibrary>()
}

/** What has to be looked up on Letterboxd before [planWatchlistSync] can decide. */
internal data class WatchlistSyncLookups(
    /** Only on Letterboxd and not in the snapshot: their ids are needed to add them to the library. */
    val watchlistDetails: List<LetterboxdWatchlistItem>,
    /** Only in the library: their watched state decides between "add to Letterboxd" and "remove from library". */
    val watchedChecks: List<LetterboxdFilm>,
)

internal fun watchlistSyncLookups(
    library: List<WatchlistSyncLibraryEntry>,
    watchlist: List<LetterboxdWatchlistItem>,
    snapshot: LetterboxdWatchlistSnapshot?,
): WatchlistSyncLookups {
    val sides = WatchlistSyncSides(library, watchlist)
    return WatchlistSyncLookups(
        watchlistDetails = sides.watchlistOnly().filter { item -> snapshot?.films?.get(item.slug) == null },
        watchedChecks = sides.libraryOnly().map { (film, _) -> film },
    )
}

internal fun planWatchlistSync(input: WatchlistSyncInput): WatchlistSyncPlan {
    val sides = WatchlistSyncSides(input.library, input.watchlist)
    val firstSync = input.snapshot == null
    val actions = mutableListOf<WatchlistSyncAction>()
    val skipped = mutableListOf<WatchlistSyncSkip>()
    val unmatched = mutableListOf<WatchlistSyncUnmatched>()

    input.library.forEach { entry ->
        val reason = when (val match = entry.match) {
            is WatchlistSyncMatch.Matched -> if (match.film.isTvShow) WatchlistSyncUnmatched.Reason.TvShow else null
            WatchlistSyncMatch.NoExternalId -> WatchlistSyncUnmatched.Reason.NoExternalId
            WatchlistSyncMatch.NotOnLetterboxd -> WatchlistSyncUnmatched.Reason.NotOnLetterboxd
            is WatchlistSyncMatch.Failed -> WatchlistSyncUnmatched.Reason.LookupFailed
        } ?: return@forEach
        unmatched += WatchlistSyncUnmatched(
            side = WatchlistSyncUnmatched.Side.Library,
            title = entry.movie.title,
            reason = reason,
            error = (entry.match as? WatchlistSyncMatch.Failed)?.error,
        )
    }

    // Library only: add to Letterboxd, or remove from the library when watched there.
    sides.libraryOnly().forEach { (film, movies) ->
        val title = movies.first().title
        when (input.watched[film.slug]) {
            null -> skipped += WatchlistSyncSkip(film.slug, title, WatchlistSyncSkip.Reason.WatchedStateUnknown)
            true -> if (firstSync) {
                skipped += WatchlistSyncSkip(film.slug, title, WatchlistSyncSkip.Reason.WatchedKeptOnFirstSync)
            } else {
                actions += WatchlistSyncAction.RemoveFromLibrary(film, movies)
            }
            false -> actions += WatchlistSyncAction.AddToLetterboxd(
                film = film,
                movies = movies,
                removedOnLetterboxd = input.snapshot?.films?.containsKey(film.slug) == true,
            )
        }
    }

    // Letterboxd only: removed from the library since the last sync, or new on Letterboxd.
    sides.watchlistOnly().forEach { item ->
        val title = item.name ?: item.slug
        val previous = input.snapshot?.films?.get(item.slug)
        if (previous != null) {
            if (input.library.any { it.movie.sharesIdsWith(previous) }) {
                skipped += WatchlistSyncSkip(item.slug, title, WatchlistSyncSkip.Reason.LibraryMatchUncertain)
            } else {
                actions += WatchlistSyncAction.RemoveFromLetterboxd(item, previous)
            }
            return@forEach
        }
        when (val lookup = input.watchlistLookups[item.slug]) {
            null -> unmatched += WatchlistSyncUnmatched(
                WatchlistSyncUnmatched.Side.Letterboxd, title, WatchlistSyncUnmatched.Reason.LookupFailed,
            )
            is WatchlistSyncLookup.Failed -> unmatched += WatchlistSyncUnmatched(
                WatchlistSyncUnmatched.Side.Letterboxd, title, WatchlistSyncUnmatched.Reason.LookupFailed, lookup.error,
            )
            is WatchlistSyncLookup.Found -> {
                val film = lookup.film
                when {
                    film.isTvShow -> unmatched += WatchlistSyncUnmatched(
                        WatchlistSyncUnmatched.Side.Letterboxd, film.displayTitle(), WatchlistSyncUnmatched.Reason.TvShow,
                    )
                    film.tmdbId == null && film.imdbId == null -> unmatched += WatchlistSyncUnmatched(
                        WatchlistSyncUnmatched.Side.Letterboxd, film.displayTitle(), WatchlistSyncUnmatched.Reason.NoExternalId,
                    )
                    // Already in the library under an entry that couldn't be matched: don't add a duplicate.
                    input.library.any { it.movie.sharesIdsWith(film) } ->
                        skipped += WatchlistSyncSkip(item.slug, film.displayTitle(), WatchlistSyncSkip.Reason.LibraryMatchUncertain)
                    else -> actions += WatchlistSyncAction.AddToLibrary(film)
                }
            }
        }
    }

    return WatchlistSyncPlan(
        firstSync = firstSync,
        actions = actions,
        inSync = sides.inBoth(),
        skipped = skipped,
        unmatched = unmatched,
    )
}

/**
 * Snapshot after applying [plan]: films on both sides (already, or thanks to a successful change). A failed or
 * skipped film keeps its previous entry; films removed from either side successfully leave it.
 */
internal fun nextWatchlistSnapshot(
    input: WatchlistSyncInput,
    plan: WatchlistSyncPlan,
    succeeded: Set<WatchlistSyncAction>,
    nowEpochMs: Long,
): LetterboxdWatchlistSnapshot {
    val sides = WatchlistSyncSides(input.library, input.watchlist)
    val previous = input.snapshot?.films.orEmpty()
    val films = LinkedHashMap<String, LetterboxdWatchlistSnapshot.Film>()
    plan.inSync.forEach { slug ->
        val (film, movies) = sides.librarySlug(slug) ?: return@forEach
        films[slug] = snapshotFilm(film, movies)
    }
    plan.actions.forEach { action ->
        if (action !in succeeded) {
            previous[action.slug]?.let { films[action.slug] = it }
            return@forEach
        }
        when (action) {
            is WatchlistSyncAction.AddToLetterboxd -> films[action.slug] = snapshotFilm(action.film, action.movies)
            is WatchlistSyncAction.AddToLibrary -> films[action.slug] = LetterboxdWatchlistSnapshot.Film(
                nuvioIds = listOf(action.nuvioId),
                imdbId = action.film.imdbId,
                tmdbId = action.film.tmdbId,
                title = action.title,
            )
            is WatchlistSyncAction.RemoveFromLetterboxd, is WatchlistSyncAction.RemoveFromLibrary -> films.remove(action.slug)
        }
    }
    plan.skipped.forEach { skip -> previous[skip.slug]?.let { films[skip.slug] = it } }
    return LetterboxdWatchlistSnapshot(syncedAtEpochMs = nowEpochMs, films = films)
}

/** Nuvio id given to a film added from Letterboxd: IMDb id (Cinemeta), else `tmdb:<id>` (same as the F2 row). */
internal fun watchlistSyncNuvioId(film: LetterboxdFilm): String =
    film.imdbId?.takeIf { it.startsWith("tt") } ?: "tmdb:${film.tmdbId ?: error("Film without TMDB/IMDb id")}"

internal fun LetterboxdFilm.displayTitle(): String =
    (name ?: slug).let { title -> year?.let { "$title ($it)" } ?: title }

internal fun WatchlistSyncLibraryMovie.sharesIdsWith(film: LetterboxdWatchlistSnapshot.Film): Boolean =
    nuvioId in film.nuvioIds ||
        (imdbId != null && imdbId == film.imdbId) ||
        (tmdbId != null && tmdbId == film.tmdbId)

internal fun WatchlistSyncLibraryMovie.sharesIdsWith(film: LetterboxdFilm): Boolean =
    (imdbId != null && imdbId == film.imdbId) ||
        (tmdbId != null && tmdbId == film.tmdbId) ||
        nuvioId == film.imdbId || (film.tmdbId != null && nuvioId == "tmdb:${film.tmdbId}")

private fun snapshotFilm(film: LetterboxdFilm, movies: List<WatchlistSyncLibraryMovie>) = LetterboxdWatchlistSnapshot.Film(
    nuvioIds = movies.map { it.nuvioId },
    imdbId = film.imdbId ?: movies.firstNotNullOfOrNull { it.imdbId },
    tmdbId = film.tmdbId ?: movies.firstNotNullOfOrNull { it.tmdbId },
    title = movies.first().title,
)

/** Both sides indexed by Letterboxd slug (library: matched films only, TV entries excluded). */
private class WatchlistSyncSides(
    library: List<WatchlistSyncLibraryEntry>,
    watchlist: List<LetterboxdWatchlistItem>,
) {
    private val librarySlugs = LinkedHashMap<String, Pair<LetterboxdFilm, MutableList<WatchlistSyncLibraryMovie>>>()
    private val watchlistSlugs = LinkedHashMap<String, LetterboxdWatchlistItem>()

    init {
        library.forEach { entry ->
            val film = (entry.match as? WatchlistSyncMatch.Matched)?.film?.takeUnless { it.isTvShow } ?: return@forEach
            librarySlugs.getOrPut(film.slug) { film to mutableListOf() }.second += entry.movie
        }
        watchlist.forEach { item -> watchlistSlugs.getOrPut(item.slug) { item } }
    }

    fun librarySlug(slug: String): Pair<LetterboxdFilm, List<WatchlistSyncLibraryMovie>>? = librarySlugs[slug]

    fun inBoth(): List<String> = librarySlugs.keys.filter { it in watchlistSlugs }

    fun libraryOnly(): List<Pair<LetterboxdFilm, List<WatchlistSyncLibraryMovie>>> =
        librarySlugs.filterKeys { it !in watchlistSlugs }.values.toList()

    fun watchlistOnly(): List<LetterboxdWatchlistItem> = watchlistSlugs.values.filter { it.slug !in librarySlugs }
}
