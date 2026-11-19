package com.nuvio.app.features.letterboxd

import kotlinx.serialization.Serializable

/** Why a Letterboxd call failed. Mapped to user-facing strings by the UI. */
enum class LetterboxdError {
    /** No cookies saved. */
    NotConnected,

    /** Session cookies were sent but Letterboxd rendered a guest page: expired/invalid session, paste fresh cookies. */
    AuthExpired,

    /** Letterboxd rendered a guest page and no session cookie (`letterboxd.user.CURRENT` / `letterboxd.user`) was pasted. */
    SessionCookieMissing,

    /** Cloudflare answered with a challenge page ("Just a moment…"). */
    CloudflareChallenge,

    /** Letterboxd refused the write (usually an invalid/expired CSRF token). */
    Rejected,

    NotFound,
    RateLimited,
    Network,
    Server,
    Parse,

    /** The cookies could not be written to the secure storage. */
    Storage,
}

class LetterboxdException(
    val error: LetterboxdError,
    message: String? = null,
    val status: Int? = null,
) : Exception(message ?: error.name)

/** A film as identified on Letterboxd. `lid` is used by the JSON API, `uid` (`film:123`) by the `/s/` form endpoints. */
@Serializable
data class LetterboxdFilm(
    val slug: String,
    val lid: String? = null,
    val uid: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val name: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    /** Weighted average out of 5, null when the film has too few ratings. */
    val averageRating: Double? = null,
    val ratingCount: Int? = null,
    val reviewCount: Int? = null,
) {
    val url: String get() = "$LETTERBOXD_BASE_URL/film/$slug/"
}

/** Histogram of ratings, index 0 = ½★ … index 9 = 5★. */
data class LetterboxdRatingHistogram(
    val counts: List<Int>,
) {
    val total: Int get() = counts.sum()
}

/** The signed-in member's relationship with a film (from the internal JSON API). */
data class LetterboxdFilmState(
    val watched: Boolean,
    val liked: Boolean,
    val inWatchlist: Boolean,
    /** Out of 5 (0.5 steps), null when not rated. */
    val rating: Double?,
    val reviewCount: Int = 0,
    val diaryEntryCount: Int = 0,
)

/** Who a Letterboxd page was rendered for, see [LetterboxdParsers.detectSession]. */
internal data class LetterboxdPageSession(val state: State, val username: String?) {
    enum class State { SignedIn, Guest, Unknown }
}

data class LetterboxdSessionInfo(
    val username: String,
    val csrfToken: String?,
)

/** A film entry in a member's watchlist. */
data class LetterboxdWatchlistItem(
    val slug: String,
    val lid: String?,
    val uid: String?,
    val name: String?,
)

/** One diary/watch entry from a member's public RSS feed (carries the TMDB id). */
data class LetterboxdDiaryEntry(
    val member: String?,
    val filmSlug: String?,
    val filmTitle: String?,
    val filmYear: Int?,
    val tmdbId: Int?,
    val watchedDate: String?,
    val rating: Double?,
    val liked: Boolean,
    val rewatch: Boolean,
    val publishedAt: String?,
    val link: String?,
    val isReview: Boolean,
)

enum class LetterboxdActivityType {
    Rating, Watch, Review, Like, Watchlist,

    /** The member liked someone else's review (the film's rating is the author's, not the member's). */
    ReviewLike,
    Comment,
    Other,
}

/** One row of the "following" activity stream (best-effort HTML parsing). */
data class LetterboxdActivityItem(
    val member: String,
    val filmSlug: String,
    val filmName: String?,
    val type: LetterboxdActivityType,
    val rating: Double?,
    val timestamp: String?,
)

/** A diary entry / review to create. Dates are ISO `yyyy-MM-dd`. */
data class LetterboxdLogEntryDraft(
    /** Null = not added to the diary (no viewing date). */
    val diaryDate: String?,
    val rewatch: Boolean = false,
    val review: String? = null,
    val containsSpoilers: Boolean = false,
    /** Out of 5 (0.5 steps). */
    val rating: Double? = null,
    val liked: Boolean = false,
    val tags: List<String> = emptyList(),
)

enum class LetterboxdConnectionStatus { Disconnected, Unverified, Connected, ReconnectRequired }

enum class LetterboxdProbeResult { Ok, CloudflareChallenge, Unauthorized, Failed, Skipped }

/** Result of the "Test connection" button. */
data class LetterboxdConnectionReport(
    val username: String?,
    /** Public pages with the user's cookies (identity check). */
    val identity: LetterboxdProbeResult,
    /** Authenticated JSON API (used by writes: rating, watchlist, diary). */
    val api: LetterboxdProbeResult,
    val csrfTokenFound: Boolean,
    val hasClearanceCookie: Boolean,
    val customUserAgent: Boolean,
    val error: LetterboxdError?,
    val checkedAtEpochMs: Long,
    /** HTTP status of the failed identity check, when relevant. */
    val httpStatus: Int? = null,
)

data class LetterboxdAccountState(
    val isLoaded: Boolean = false,
    val status: LetterboxdConnectionStatus = LetterboxdConnectionStatus.Disconnected,
    val username: String? = null,
    val userAgent: String = "",
    val cookieNames: List<String> = emptyList(),
    val isBusy: Boolean = false,
    val lastError: LetterboxdError? = null,
    val lastReport: LetterboxdConnectionReport? = null,
    val storageKind: LetterboxdSecretStorageKind = LetterboxdSecretStorageKind.Unavailable,
) {
    val hasCredentials: Boolean get() = cookieNames.isNotEmpty()
    val reconnectRequired: Boolean get() = status == LetterboxdConnectionStatus.ReconnectRequired
}

enum class LetterboxdSecretStorageKind { MacKeychain, WindowsDpapi, LinuxSecretService, RestrictedFile, Unavailable }

internal const val LETTERBOXD_BASE_URL = "https://letterboxd.com"
