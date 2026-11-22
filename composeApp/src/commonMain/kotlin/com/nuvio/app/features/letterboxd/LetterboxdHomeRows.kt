package com.nuvio.app.features.letterboxd

/**
 * Pieces shared by the Letterboxd home rows ("Recently rated by friends", lists as catalogs): slug → film lookups
 * through the persistent film index, and the refresh policy of the rows.
 */
internal interface LetterboxdFilmSlugSource {
    /** Cached film, no network. */
    suspend fun cachedFilm(slug: String): LetterboxdFilm?

    /** Film page (one request); null when Letterboxd doesn't know the slug. */
    suspend fun fetchFilm(slug: String): LetterboxdFilm?
}

/** Lookups through [LetterboxdRepository] (persistent `film_index.json`; ids never change, so any cached copy is fine). */
internal object RepositoryLetterboxdFilmSlugs : LetterboxdFilmSlugSource {
    override suspend fun cachedFilm(slug: String): LetterboxdFilm? = LetterboxdRepository.cachedFilm(slug)

    override suspend fun fetchFilm(slug: String): LetterboxdFilm? = try {
        LetterboxdRepository.film(slug, maxAgeMs = Long.MAX_VALUE)
    } catch (error: LetterboxdException) {
        if (error.error == LetterboxdError.NotFound) null else throw error
    }
}

/**
 * Whether a home row is due for an automatic refresh when Home is entered ([LetterboxdFriendsRefreshMode]).
 * The minimum gap between automatic refreshes ([LetterboxdRateLimits]) is checked by the caller.
 */
internal fun letterboxdRowRefreshDue(
    mode: LetterboxdFriendsRefreshMode,
    delayMinutes: Int,
    nowMs: Long,
    fetchedAtEpochMs: Long,
    refreshedThisSession: Boolean,
): Boolean = when (mode) {
    LetterboxdFriendsRefreshMode.OnLaunch -> !refreshedThisSession
    LetterboxdFriendsRefreshMode.EveryVisit -> true
    LetterboxdFriendsRefreshMode.EveryVisitWithDelay -> nowMs - fetchedAtEpochMs >= delayMinutes.coerceAtLeast(1) * 60_000L
}

/** Errors after which a bulk job stops sending requests (Cloudflare reacts to bursts). */
internal fun LetterboxdError.stopsBulkRequests(): Boolean =
    this == LetterboxdError.CloudflareChallenge || this == LetterboxdError.RateLimited
