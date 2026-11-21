package com.nuvio.app.features.journal

import kotlinx.serialization.Serializable

/**
 * Journal (F4) data model.
 *
 * Nuvio itself only keeps the *current* state (see `Nuuvio-spec/NOTES.md`, F4): one watched mark per item with a
 * single date, playback progress per video with its last update. The journal adds its own per-profile store that
 * records, from the moment it is installed, what Nuvio forgets: when a video was started, every time it was
 * finished, and when it was explicitly marked "not watched". Dates older than the journal come from Nuvio.
 */

/** Normalized content type of a journal entry. */
internal object JournalType {
    const val Movie = "movie"
    const val Series = "series"

    fun normalize(type: String): String = when (type.trim().lowercase()) {
        "movie", "film" -> Movie
        "series", "show", "tv", "tvshow", "anime" -> Series
        else -> type.trim().lowercase()
    }
}

/** Key of one video (a movie, a whole series when [season] is null, or an episode). */
internal fun journalVideoKey(type: String, id: String, season: Int? = null, episode: Int? = null): String =
    "${JournalType.normalize(type)}:${id.trim()}:${season ?: -1}:${episode ?: -1}"

/** Key of a journal entry (a movie or a series): `type:id`. */
internal fun journalContentKey(type: String, id: String): String = "${JournalType.normalize(type)}:${id.trim()}"

/** What the journal knows about one video beyond Nuvio's current state. */
@Serializable
internal data class JournalVideoRecord(
    val type: String,
    val id: String,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    val poster: String? = null,
    /** First playback seen by the journal (null = started before the journal existed, or never played). */
    val startedAt: Long? = null,
    val lastActivityAt: Long = 0L,
    /** Last playback progress seen (0..1) and when it was saved; kept when Nuvio drops the progress row. */
    val progress: Float? = null,
    val progressAt: Long? = null,
    val durationMs: Long? = null,
    /** Last completion state seen in Nuvio's progress (to detect a new completion, i.e. a rewatch). */
    val completed: Boolean = false,
    /** Every time the video was finished (playback completed or marked watched), oldest first. */
    val completions: List<Long> = emptyList(),
    /** Last watched-mark date seen in Nuvio (to detect a new mark). */
    val markedAt: Long? = null,
    /** When the video was explicitly marked "not watched" (overrides older progress and completions). */
    val unmarkedAt: Long? = null,
) {
    val key: String get() = journalVideoKey(type, id, season, episode)

    /** Records that only mirror Nuvio's state aren't stored (Nuvio already keeps them). */
    val hasJournalData: Boolean
        get() = startedAt != null || completions.isNotEmpty() || unmarkedAt != null || progress != null
}

@Serializable
internal data class JournalProfilePayload(
    val version: Int = 1,
    /** When the journal started recording for this profile (0 = never ran). */
    val createdAt: Long = 0L,
    val records: Map<String, JournalVideoRecord> = emptyMap(),
)

/** Catalog details used for filters, stats and series progress (cached, shared by all profiles). */
@Serializable
internal data class JournalMetaInfo(
    val name: String? = null,
    val poster: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    /** Released episodes of the main seasons (specials excluded), null when unknown / not a series. */
    val releasedEpisodes: Int? = null,
    val fetchedAt: Long = 0L,
    /** True when the catalog had no details for this item (retried after a while). */
    val missing: Boolean = false,
)

@Serializable
internal data class JournalMetaCache(val entries: Map<String, JournalMetaInfo> = emptyMap())

@Serializable
data class JournalSettings(
    /** Progress from which an item counts as watched in the journal (Nuvio's own threshold stays at 90 %). */
    val watchedThresholdPercent: Int = DefaultWatchedThresholdPercent,
) {
    fun sanitized(): JournalSettings = copy(watchedThresholdPercent = watchedThresholdPercent.coerceIn(ThresholdRange))

    companion object {
        const val DefaultWatchedThresholdPercent = 90
        val ThresholdRange = 50..100
    }
}

enum class JournalStatus { InProgress, Watched }

enum class JournalSource { Nuvio, Letterboxd }

enum class JournalKind { Movie, Series }

/** One line of the Journal screen: a movie or a series. */
data class JournalEntry(
    val key: String,
    val kind: JournalKind,
    /** Nuvio id to open the detail page (IMDb `tt…` or `tmdb:N`); null for a Letterboxd film not identified yet. */
    val id: String?,
    val title: String,
    val poster: String?,
    val year: Int?,
    val genres: List<String>,
    val status: JournalStatus,
    val startedAt: Long?,
    val lastWatchedAt: Long?,
    val finishedAt: Long?,
    /** Movie: playback progress (0..1). Series: watched / released episodes. */
    val progress: Float?,
    val episodesWatched: Int = 0,
    val episodesTotal: Int? = null,
    val sources: Set<JournalSource>,
    /** My Letterboxd rating out of 5. */
    val rating: Double? = null,
    val liked: Boolean = false,
    val timesWatched: Int = 0,
    val letterboxdSlug: String? = null,
    val letterboxdTmdbId: Int? = null,
    /** Time spent watching (movie: runtime when watched, else progress × runtime; series: watched episodes). */
    val watchedMinutes: Int = 0,
)
