package com.nuvio.app.features.journal

import kotlin.math.abs

/** A playback progress row as Nuvio currently exposes it (one movie or episode). */
internal data class JournalProgressSample(
    val type: String,
    val id: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val poster: String?,
    val lastUpdatedAt: Long,
    val fraction: Float,
    val durationMs: Long,
    /** Nuvio's own completion (90 %). */
    val completed: Boolean,
)

/** A watched mark as Nuvio currently exposes it (movie, whole series or episode). */
internal data class JournalWatchedSample(
    val type: String,
    val id: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val poster: String?,
    val markedAt: Long,
) {
    val key: String get() = journalVideoKey(type, id, season, episode)
}

/** Nuvio's state at one point in time, for the active profile. */
internal data class JournalObservation(
    val progress: List<JournalProgressSample>,
    val watched: List<JournalWatchedSample>,
    val watchedLoaded: Boolean,
    /** Active watch source (Nuvio sync, Trakt…): marks that vanish because the source changed aren't "unmarks". */
    val source: String,
)

private const val CompletionDedupMs = 60L * 60 * 1000
private const val MaxCompletionsPerVideo = 100

/** A vanishing that large is a reload (sign-out, cache reset), not the user unmarking things one by one. */
private const val MassUnmarkMinCount = 20

/**
 * Folds one [observation] of Nuvio's state into the journal [payload] (pure, so it can be tested and replayed).
 * - a progress row that appears after the journal was created gives the start date; a progress row reaching
 *   Nuvio's completion, or a new watched mark, adds a completion date (rewatches add more);
 * - a watched mark that disappears between two loaded observations of the same source = marked "not watched".
 * Older data (before [JournalProfilePayload.createdAt]) is taken as is: its dates stay Nuvio's.
 */
internal fun recordJournalObservation(
    payload: JournalProfilePayload,
    observation: JournalObservation,
    previous: JournalObservation?,
    nowMs: Long,
): JournalProfilePayload {
    val createdAt = if (payload.createdAt > 0L) payload.createdAt else nowMs
    val records = payload.records.toMutableMap()

    observation.progress
        .groupBy { journalVideoKey(it.type, it.id, it.season, it.episode) }
        .forEach { (key, samples) ->
            val sample = samples.maxBy { it.lastUpdatedAt }
            records[key] = recordProgress(records[key], sample, createdAt)
        }

    observation.watched
        .groupBy { it.key }
        .forEach { (key, samples) ->
            val sample = samples.maxBy { it.markedAt }
            records[key] = recordWatched(records[key], sample, createdAt)
        }

    if (
        previous != null &&
        previous.watchedLoaded &&
        observation.watchedLoaded &&
        previous.source == observation.source
    ) {
        val currentKeys = observation.watched.mapTo(HashSet()) { it.key }
        val vanished = previous.watched.filter { it.key !in currentKeys }.distinctBy { it.key }
        val massVanishing = vanished.size >= MassUnmarkMinCount && vanished.size * 2 > previous.watched.size
        if (vanished.isNotEmpty() && !massVanishing) {
            vanished.forEach { sample ->
                val base = records[sample.key] ?: newRecord(sample.type, sample.id, sample.season, sample.episode, sample.title, sample.poster)
                records[sample.key] = base.copy(unmarkedAt = nowMs, markedAt = null)
            }
        }
    }

    return payload.copy(createdAt = createdAt, records = records)
}

/** Marks videos as explicitly "not watched" from now (Journal action), so older progress no longer counts. */
internal fun markJournalVideosUnwatched(
    payload: JournalProfilePayload,
    videos: List<JournalVideoRecord>,
    nowMs: Long,
): JournalProfilePayload {
    if (videos.isEmpty()) return payload
    val records = payload.records.toMutableMap()
    videos.forEach { video ->
        val base = records[video.key] ?: video
        records[video.key] = base.copy(unmarkedAt = nowMs, markedAt = null)
    }
    return payload.copy(records = records)
}

private fun newRecord(type: String, id: String, season: Int?, episode: Int?, title: String, poster: String?) =
    JournalVideoRecord(
        type = JournalType.normalize(type),
        id = id.trim(),
        season = season,
        episode = episode,
        title = title,
        poster = poster,
    )

private fun recordProgress(existing: JournalVideoRecord?, sample: JournalProgressSample, createdAt: Long): JournalVideoRecord {
    val at = sample.lastUpdatedAt
    var record = existing ?: newRecord(sample.type, sample.id, sample.season, sample.episode, sample.title, sample.poster)
    record = record.copy(
        title = record.title.ifBlank { sample.title },
        poster = record.poster ?: sample.poster,
    )
    // First playback known to the journal. A row that already existed before the journal keeps an unknown start.
    if (record.startedAt == null && at >= createdAt && existing?.progressAt == null) {
        record = record.copy(startedAt = at)
    }
    if (record.progressAt == null || at >= record.progressAt) {
        record = record.copy(progress = sample.fraction.coerceIn(0f, 1f), progressAt = at)
    }
    if (sample.durationMs > 0L) record = record.copy(durationMs = sample.durationMs)
    if (sample.completed && !record.completed && at >= createdAt) {
        record = record.withCompletion(at)
    }
    return record.copy(
        completed = sample.completed,
        lastActivityAt = maxOf(record.lastActivityAt, at),
    )
}

private fun recordWatched(existing: JournalVideoRecord?, sample: JournalWatchedSample, createdAt: Long): JournalVideoRecord {
    var record = existing ?: newRecord(sample.type, sample.id, sample.season, sample.episode, sample.title, sample.poster)
    record = record.copy(
        title = record.title.ifBlank { sample.title },
        poster = record.poster ?: sample.poster,
    )
    val markedAt = sample.markedAt.takeIf { it > 0L } ?: return record
    if (record.markedAt != markedAt) {
        if (markedAt >= createdAt) record = record.withCompletion(markedAt)
        record = record.copy(markedAt = markedAt)
    }
    return record.copy(lastActivityAt = maxOf(record.lastActivityAt, markedAt))
}

private fun JournalVideoRecord.withCompletion(at: Long): JournalVideoRecord =
    if (completions.any { abs(it - at) < CompletionDedupMs }) {
        this
    } else {
        copy(completions = (completions + at).sorted().takeLast(MaxCompletionsPerVideo))
    }
