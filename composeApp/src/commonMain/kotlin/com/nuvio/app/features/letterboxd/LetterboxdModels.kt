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
    /** `movie` or `tv` (`data-tmdb-type`); Letterboxd also lists miniseries, which Nuvio features skip. */
    val tmdbType: String? = null,
) {
    val url: String get() = "$LETTERBOXD_BASE_URL/film/$slug/"
    val isTvShow: Boolean get() = tmdbType == "tv"

    /** A real film for Nuvio: Letterboxd films always have a TMDB movie id (TV entries don't, even in old caches). */
    val isNuvioMovie: Boolean get() = !isTvShow && tmdbId != null
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
    /** Log entry ids (e.g. `hdhvZL`) of the member's reviews of this film, readable via `production-log-entry/{id}`. */
    val reviewIds: List<String> = emptyList(),
    val diaryEntryIds: List<String> = emptyList(),
    /** ISO instant of the first "watched" mark, when known. */
    val whenWatched: String? = null,
)

/** One of the member's log entries (diary entry and/or review), from `GET /api/v0/production-log-entry/{id}`. */
data class LetterboxdLogEntry(
    val id: String,
    /** `yyyy-MM-dd`, null when the entry isn't in the diary. */
    val diaryDate: String?,
    val rewatch: Boolean,
    val rating: Double?,
    val liked: Boolean,
    /** Plain text of the review (markup stripped), null when the entry has no review. */
    val reviewText: String?,
    val containsSpoilers: Boolean,
    val whenCreated: String?,
    val url: String?,
)

/** What a followed member did with a film (`/csi/film/<slug>/friend-activity/` fragment). */
data class LetterboxdFriendActivity(
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    /** Out of 5, null when not rated. */
    val rating: Double?,
    val liked: Boolean,
    val reviewed: Boolean,
    val watched: Boolean,
    /** Only in their watchlist (not watched). */
    val wantsToWatch: Boolean,
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

/** One page of a member's watchlist; [totalEntries] is the count Letterboxd announces for the whole watchlist. */
data class LetterboxdWatchlistPage(
    val items: List<LetterboxdWatchlistItem>,
    val hasNext: Boolean,
    val totalEntries: Int?,
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
    val posterUrl: String? = null,
)

/** A film of the member's "Films" page (watched films): rating out of 5 and like, no date nor TMDB id. */
@Serializable
data class LetterboxdWatchedFilm(
    val slug: String,
    val lid: String? = null,
    val uid: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val liked: Boolean = false,
)

/** One page (72 films) of `/<user>/films/`. */
data class LetterboxdWatchedFilmsPage(
    val items: List<LetterboxdWatchedFilm>,
    val hasNext: Boolean,
)

enum class LetterboxdActivityType {
    Rating, Watch, Review, Like, Watchlist,

    /** The member liked someone else's review (the film's rating is the author's, not the member's). */
    ReviewLike,
    Comment,
    Other,
}

/** One row of the "following" activity stream (best-effort HTML parsing). */
@Serializable
data class LetterboxdActivityItem(
    val member: String,
    val filmSlug: String,
    val filmName: String?,
    val type: LetterboxdActivityType,
    val rating: Double?,
    val timestamp: String?,
    /** `data-activity-id`, also the pagination cursor (`?after=<id>`). */
    val activityId: String? = null,
    /** Display name (avatar `alt` / `<strong>`), falls back to [member]. */
    val memberName: String? = null,
    val avatarUrl: String? = null,
    /** The row says the member also liked the film ("watched, liked and rated"). */
    val liked: Boolean = false,
    val rewatch: Boolean = false,
)

/** One page of the "following" activity stream; [hasMore] is false once Letterboxd prints "End of recent activity". */
data class LetterboxdActivityPage(
    val items: List<LetterboxdActivityItem>,
    val hasMore: Boolean,
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
