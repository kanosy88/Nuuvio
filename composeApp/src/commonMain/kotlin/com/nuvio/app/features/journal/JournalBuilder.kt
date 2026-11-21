package com.nuvio.app.features.journal

/** Everything the journal list is built from (pure input, see [buildJournalEntries]). */
internal data class JournalBuildInput(
    val records: Map<String, JournalVideoRecord>,
    val progress: List<JournalProgressSample>,
    val watched: List<JournalWatchedSample>,
    /** Content keys (`series:<id>`) of the series Nuvio considers fully watched. */
    val fullyWatchedSeries: Set<String> = emptySet(),
    /** Catalog details by content key (`movie:<id>`, `series:<id>`). */
    val meta: Map<String, JournalMetaInfo> = emptyMap(),
    val settings: JournalSettings = JournalSettings(),
    /** Letterboxd films, empty when no account is connected. */
    val letterboxd: List<JournalLetterboxdFilm> = emptyList(),
    /** `yyyy-MM-dd` → epoch ms (local noon), injectable for tests. */
    val diaryDateToEpochMs: (String) -> Long? = { null },
)

/** One video (movie, series or episode) with Nuvio's current state and the journal's history. */
internal data class JournalVideoView(
    val type: String,
    val id: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val poster: String?,
    val startedAt: Long?,
    val lastActivityAt: Long?,
    val progress: Float?,
    val progressAt: Long?,
    val durationMs: Long?,
    /** Current watched mark in Nuvio. */
    val markedAt: Long?,
    val completions: List<Long>,
    val unmarkedAt: Long?,
    /** Title of the show (progress rows), not of the episode. */
    val showTitle: String?,
    val showPoster: String?,
) {
    private val unmark: Long get() = unmarkedAt ?: Long.MIN_VALUE

    fun isWatched(threshold: Float): Boolean =
        markedAt != null ||
            ((progress ?: 0f) >= threshold && (progressAt ?: Long.MIN_VALUE) > unmark) ||
            completions.any { it > unmark }

    fun isInProgress(threshold: Float): Boolean =
        !isWatched(threshold) && (
            ((progress ?: 0f) > 0f && (progressAt ?: Long.MIN_VALUE) > unmark) ||
                (startedAt != null && startedAt > unmark)
            )

    fun finishedAt(threshold: Float): Long? {
        if (!isWatched(threshold)) return null
        return listOfNotNull(
            markedAt,
            completions.filter { it > unmark }.maxOrNull(),
            progressAt?.takeIf { (progress ?: 0f) >= threshold && it > unmark },
        ).maxOrNull()
    }

    val lastSeenAt: Long?
        get() = listOfNotNull(lastActivityAt?.takeIf { it > 0L }, progressAt, markedAt, completions.maxOrNull()).maxOrNull()
}

internal fun buildJournalVideos(input: JournalBuildInput): List<JournalVideoView> {
    val progressByKey = input.progress.groupBy { journalVideoKey(it.type, it.id, it.season, it.episode) }
        .mapValues { (_, rows) -> rows.maxBy { it.lastUpdatedAt } }
    val watchedByKey = input.watched.groupBy { it.key }.mapValues { (_, rows) -> rows.maxBy { it.markedAt } }
    val keys = LinkedHashSet<String>().apply {
        addAll(input.records.keys)
        addAll(progressByKey.keys)
        addAll(watchedByKey.keys)
    }
    return keys.mapNotNull { key ->
        val record = input.records[key]
        val progress = progressByKey[key]
        val watched = watchedByKey[key]
        val type = JournalType.normalize(record?.type ?: progress?.type ?: watched?.type ?: return@mapNotNull null)
        val id = record?.id ?: progress?.id ?: watched?.id ?: return@mapNotNull null
        JournalVideoView(
            type = type,
            id = id,
            season = record?.season ?: progress?.season ?: watched?.season,
            episode = record?.episode ?: progress?.episode ?: watched?.episode,
            title = watched?.title?.takeIf { it.isNotBlank() } ?: progress?.title ?: record?.title.orEmpty(),
            poster = watched?.poster ?: progress?.poster ?: record?.poster,
            startedAt = record?.startedAt,
            lastActivityAt = listOfNotNull(record?.lastActivityAt, progress?.lastUpdatedAt, watched?.markedAt).maxOrNull(),
            progress = progress?.fraction ?: record?.progress,
            progressAt = progress?.lastUpdatedAt ?: record?.progressAt,
            durationMs = progress?.durationMs?.takeIf { it > 0L } ?: record?.durationMs,
            markedAt = watched?.markedAt,
            completions = record?.completions.orEmpty(),
            unmarkedAt = record?.unmarkedAt,
            showTitle = progress?.title?.takeIf { it.isNotBlank() } ?: record?.title?.takeIf { progress == null && watched == null && it.isNotBlank() },
            showPoster = progress?.poster,
        )
    }
}

/**
 * The journal list: movies and series that are watched or in progress (Nuvio + journal history), merged with
 * the films watched on Letterboxd (deduplicated by TMDB id, IMDb id, then title + year).
 */
internal fun buildJournalEntries(input: JournalBuildInput): List<JournalEntry> {
    val threshold = input.settings.sanitized().watchedThresholdPercent / 100f
    val videos = buildJournalVideos(input)
    val entries = mutableListOf<JournalEntry>()

    videos.filter { it.type == JournalType.Movie && it.season == null }.forEach { video ->
        val watched = video.isWatched(threshold)
        if (!watched && !video.isInProgress(threshold)) return@forEach
        val meta = input.meta[journalContentKey(JournalType.Movie, video.id)]
        val runtime = video.durationMs?.let { (it / 60_000L).toInt() }?.takeIf { it > 0 } ?: meta?.runtimeMinutes
        entries += JournalEntry(
            key = journalContentKey(JournalType.Movie, video.id),
            kind = JournalKind.Movie,
            id = video.id,
            title = meta?.name?.takeIf { it.isNotBlank() } ?: video.title.ifBlank { video.id },
            poster = meta?.poster ?: video.poster,
            year = meta?.year,
            genres = meta?.genres.orEmpty(),
            status = if (watched) JournalStatus.Watched else JournalStatus.InProgress,
            startedAt = video.startedAt,
            lastWatchedAt = video.lastSeenAt,
            finishedAt = video.finishedAt(threshold),
            progress = if (watched) 1f else video.progress,
            sources = setOf(JournalSource.Nuvio),
            timesWatched = maxOf(video.completions.size, if (watched) 1 else 0),
            watchedMinutes = when {
                runtime == null -> 0
                watched -> runtime
                else -> (runtime * (video.progress ?: 0f)).toInt()
            },
        )
    }

    videos.filter { it.type == JournalType.Series }.groupBy { it.id }.forEach { (seriesId, seriesVideos) ->
        val key = journalContentKey(JournalType.Series, seriesId)
        val meta = input.meta[key]
        val show = seriesVideos.firstOrNull { it.season == null }
        val episodes = seriesVideos.filter { it.season != null && it.episode != null }
        val mainEpisodes = episodes.filter { it.season != 0 }
        val watchedEpisodes = mainEpisodes.filter { it.isWatched(threshold) }
        val watchedCount = watchedEpisodes.distinctBy { it.season to it.episode }.size
        val total = meta?.releasedEpisodes?.takeIf { it > 0 }
        val markedComplete = key in input.fullyWatchedSeries || show?.isWatched(threshold) == true
        val finished = markedComplete || (total != null && watchedCount >= total)
        val active = episodes.any { it.isWatched(threshold) || it.isInProgress(threshold) } || show?.isInProgress(threshold) == true
        if (!finished && !active) return@forEach
        val shownCount = if (markedComplete && total != null) maxOf(watchedCount, total) else watchedCount
        val runtime = meta?.runtimeMinutes
        val minutes = episodes.sumOf { episode ->
            val length = episode.durationMs?.let { (it / 60_000L).toInt() }?.takeIf { it > 0 } ?: runtime ?: 0
            when {
                episode.isWatched(threshold) -> length
                episode.isInProgress(threshold) -> (length * (episode.progress ?: 0f)).toInt()
                else -> 0
            }
        }
        val finishDates = (episodes + listOfNotNull(show)).mapNotNull { it.finishedAt(threshold) }
        entries += JournalEntry(
            key = key,
            kind = JournalKind.Series,
            id = seriesId,
            title = meta?.name?.takeIf { it.isNotBlank() }
                ?: show?.title?.takeIf { it.isNotBlank() }
                ?: episodes.firstNotNullOfOrNull { it.showTitle }
                ?: seriesId,
            poster = meta?.poster ?: show?.poster ?: episodes.firstNotNullOfOrNull { it.showPoster },
            year = meta?.year,
            genres = meta?.genres.orEmpty(),
            status = if (finished) JournalStatus.Watched else JournalStatus.InProgress,
            startedAt = seriesVideos.mapNotNull { it.startedAt }.minOrNull(),
            lastWatchedAt = seriesVideos.mapNotNull { it.lastSeenAt }.maxOrNull(),
            finishedAt = if (finished) finishDates.maxOrNull() else null,
            progress = total?.let { (shownCount.toFloat() / it).coerceIn(0f, 1f) } ?: if (finished) 1f else null,
            episodesWatched = shownCount,
            episodesTotal = total,
            sources = setOf(JournalSource.Nuvio),
            watchedMinutes = minutes,
        )
    }

    return mergeLetterboxd(entries, input)
}

private fun mergeLetterboxd(entries: MutableList<JournalEntry>, input: JournalBuildInput): List<JournalEntry> {
    if (input.letterboxd.isEmpty()) return entries
    val movieIndexes = entries.indices.filter { entries[it].kind == JournalKind.Movie }
    val byTmdb = HashMap<Int, Int>()
    val byImdb = HashMap<String, Int>()
    val byTitle = HashMap<String, Int>()
    movieIndexes.forEach { index ->
        val entry = entries[index]
        val meta = input.meta[entry.key]
        val id = entry.id.orEmpty()
        (tmdbIdOf(id) ?: meta?.tmdbId)?.let { byTmdb.getOrPut(it) { index } }
        (imdbIdOf(id) ?: meta?.imdbId)?.let { byImdb.getOrPut(it) { index } }
        titleKey(entry.title, entry.year)?.let { byTitle.getOrPut(it) { index } }
    }
    val extra = mutableListOf<JournalEntry>()
    input.letterboxd.filterNot { it.isTv }.forEach { film ->
        val diaryEpochs = film.diaryDates.mapNotNull(input.diaryDateToEpochMs)
        val lastDiary = diaryEpochs.maxOrNull()
        val firstDiary = diaryEpochs.minOrNull()
        val match = film.tmdbId?.let(byTmdb::get)
            ?: film.imdbId?.let(byImdb::get)
            ?: titleKey(film.title, film.year)?.let(byTitle::get)
        if (match != null) {
            val entry = entries[match]
            entries[match] = entry.copy(
                sources = entry.sources + JournalSource.Letterboxd,
                status = JournalStatus.Watched,
                rating = film.rating ?: entry.rating,
                liked = entry.liked || film.liked,
                letterboxdSlug = film.slug,
                letterboxdTmdbId = film.tmdbId,
                year = entry.year ?: film.year,
                startedAt = entry.startedAt ?: firstDiary,
                lastWatchedAt = listOfNotNull(entry.lastWatchedAt, lastDiary).maxOrNull(),
                finishedAt = listOfNotNull(entry.finishedAt, lastDiary).maxOrNull(),
                timesWatched = maxOf(entry.timesWatched, film.diaryDates.size, 1),
                progress = 1f,
            )
            return@forEach
        }
        val id = film.imdbId ?: film.tmdbId?.let { "tmdb:$it" }
        val meta = id?.let { input.meta[journalContentKey(JournalType.Movie, it)] }
        extra += JournalEntry(
            key = "letterboxd:${film.slug}",
            kind = JournalKind.Movie,
            id = id,
            title = film.title ?: meta?.name ?: film.slug,
            poster = meta?.poster ?: film.poster,
            year = film.year ?: meta?.year,
            genres = meta?.genres.orEmpty(),
            status = JournalStatus.Watched,
            startedAt = firstDiary,
            lastWatchedAt = lastDiary,
            finishedAt = lastDiary,
            progress = 1f,
            sources = setOf(JournalSource.Letterboxd),
            rating = film.rating,
            liked = film.liked,
            timesWatched = maxOf(film.diaryDates.size, 1),
            letterboxdSlug = film.slug,
            letterboxdTmdbId = film.tmdbId,
            watchedMinutes = meta?.runtimeMinutes ?: 0,
        )
    }
    return entries + extra
}

internal fun tmdbIdOf(id: String): Int? =
    id.takeIf { it.startsWith("tmdb:", ignoreCase = true) }?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()

internal fun imdbIdOf(id: String): String? =
    id.substringBefore(':').takeIf { Regex("tt\\d+").matches(it) }

/** Lower case, accents and punctuation removed, + year: last-resort match between Nuvio and Letterboxd. */
internal fun titleKey(title: String?, year: Int?): String? {
    if (title.isNullOrBlank() || year == null) return null
    return normalizeForSearch(title).replace(Regex("[^a-z0-9]+"), "") + "#" + year
}

internal fun normalizeForSearch(text: String): String {
    val lower = text.lowercase()
    val builder = StringBuilder(lower.length)
    lower.forEach { char -> builder.append(AccentFolding[char] ?: char) }
    return builder.toString()
}

private val AccentFolding: Map<Char, Char> = buildMap {
    "àáâãäåā".forEach { put(it, 'a') }
    "çćč".forEach { put(it, 'c') }
    "èéêëēė".forEach { put(it, 'e') }
    "ìíîïī".forEach { put(it, 'i') }
    "ñń".forEach { put(it, 'n') }
    "òóôõöøō".forEach { put(it, 'o') }
    "ùúûüū".forEach { put(it, 'u') }
    "ýÿ".forEach { put(it, 'y') }
    "šś".forEach { put(it, 's') }
    "žźż".forEach { put(it, 'z') }
}

/** First 4-digit year of a release info such as `2016`, `2016–2019`, `2016-03-01`. */
internal fun parseJournalYear(releaseInfo: String?): Int? =
    releaseInfo?.let { Regex("""\b(18|19|20|21)\d{2}\b""").find(it)?.value?.toIntOrNull() }

/** `142 min`, `2h 22min`, `1 h`, `45m`, `PT2H22M`, `142` → minutes. */
internal fun parseJournalRuntime(runtime: String?): Int? {
    val text = runtime?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    text.toIntOrNull()?.let { return it.takeIf { minutes -> minutes > 0 } }
    val hours = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val minutes = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return (hours * 60 + minutes).takeIf { it > 0 }
}
