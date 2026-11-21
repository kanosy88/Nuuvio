package com.nuvio.app.features.journal

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.letterboxd.ui.mirrorNativeWatchedToLetterboxd
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

/**
 * Journal (F4) entry point.
 * - [start] (desktop launch): records Nuvio's playback / watched changes into the journal's own per-profile store.
 *   It only observes Nuvio's state flows: no Nuvio behavior changes.
 * - [entries]: the list shown by the Journal screen (Nuvio + journal history + Letterboxd).
 * - [setWatched]: Nuvio's own "mark watched" actions (+ the Letterboxd mirror of F1 for films).
 */
internal object JournalRepository {
    private val log = Logger.withTag("Journal")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()

    private const val SETTINGS = "settings"
    private const val META = "meta"
    private const val META_WORKERS = 2
    private const val META_TIMEOUT_MS = 20_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private var started = false
    private var profileId: Int = -1
    private var previous: JournalObservation? = null
    private var saveJob: Job? = null
    private var metaSaveJob: Job? = null
    private var sharedLoaded = false

    private val payload = MutableStateFlow(JournalProfilePayload())
    private val mutableSettings = MutableStateFlow(JournalSettings())
    private val meta = MutableStateFlow<Map<String, JournalMetaInfo>>(emptyMap())
    private val metaLock = SynchronizedObject()
    private val metaQueue = LinkedHashSet<String>()
    private val metaInFlight = HashSet<String>()
    private var metaWorkers = 0

    val settings: StateFlow<JournalSettings> = mutableSettings.asStateFlow()
    val letterboxd: JournalLetterboxdSource get() = JournalLetterboxdSource.default

    /** Starts recording (idempotent). Waits for the active profile so Nuvio's repositories load in their usual order. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            loadShared()
            ProfileRepository.state.first { it.isLoaded && it.activeProfile != null }
            delay(5_000)
            observe()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun observe() {
        combine(ProfileRepository.state, WatchProgressRepository.uiState, WatchedRepository.uiState) { _, progress, watched ->
            Triple(ProfileRepository.activeProfileId, progress, watched)
        }
            .debounce(1_000)
            .collect { (activeProfile, progress, watched) ->
                runCatching {
                    mutex.withLock {
                        if (activeProfile != profileId) switchProfile(activeProfile)
                        val observation = JournalObservation(
                            progress = progress.entries.mapNotNull(WatchProgressEntry::toJournalSample),
                            watched = watched.items.mapNotNull(WatchedItem::toJournalSample),
                            watchedLoaded = watched.isLoaded,
                            source = "${progress.source.name}|${watched.hasLoadedRemoteItems}",
                        )
                        val before = payload.value
                        val after = recordJournalObservation(before, observation, previous, nowMs())
                        previous = observation
                        if (after != before) {
                            payload.value = after
                            scheduleSave(immediate = before.createdAt == 0L)
                        }
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Journal recording failed: ${error::class.simpleName}" }
                }
            }
    }

    private suspend fun switchProfile(newProfile: Int) {
        if (profileId >= 0) flushSave()
        profileId = newProfile
        previous = null
        payload.value = withContext(Dispatchers.Default) {
            JournalStorage.load(profileKey(newProfile))
                ?.let { runCatching { json.decodeFromString(JournalProfilePayload.serializer(), it) }.getOrNull() }
                ?: JournalProfilePayload()
        }
        log.i { "Journal recording for profile $newProfile (${payload.value.records.size} stored records)" }
    }

    private suspend fun loadShared() {
        if (sharedLoaded) return
        sharedLoaded = true
        withContext(Dispatchers.Default) {
            JournalStorage.load(SETTINGS)
                ?.let { runCatching { json.decodeFromString(JournalSettings.serializer(), it) }.getOrNull() }
                ?.let { mutableSettings.value = it.sanitized() }
            JournalStorage.load(META)
                ?.let { runCatching { json.decodeFromString(JournalMetaCache.serializer(), it) }.getOrNull() }
                ?.let { meta.value = it.entries }
        }
    }

    private fun scheduleSave(immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!immediate) delay(2_000)
            mutex.withLock { writePayload() }
        }
    }

    private suspend fun flushSave() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            writePayload()
        }
    }

    private suspend fun writePayload() {
        val id = profileId.takeIf { it >= 0 } ?: return
        val current = payload.value
        val stored = current.copy(records = current.records.filterValues { it.hasJournalData })
        runCatching {
            withContext(Dispatchers.Default) {
                JournalStorage.save(profileKey(id), json.encodeToString(JournalProfilePayload.serializer(), stored))
            }
        }.onFailure { log.w { "Could not save the journal: ${it::class.simpleName}" } }
    }

    private fun profileKey(id: Int) = "profile_$id"

    // ------------------------------------------------------------------ screen

    /** The journal list (recomputed when Nuvio, the journal, the catalog details or Letterboxd change). */
    fun entries(): Flow<List<JournalEntry>> {
        val nuvio = combine(
            payload,
            WatchProgressRepository.uiState,
            WatchedRepository.uiState,
            WatchedRepository.fullyWatchedSeriesKeys,
        ) { journal, progress, watched, fullyWatched -> NuvioSnapshot(journal, progress.entries, watched.items, fullyWatched) }
        return combine(nuvio, meta, mutableSettings, letterboxd.state) { snapshot, details, settings, lb ->
            val progressSamples = snapshot.progress.mapNotNull(WatchProgressEntry::toJournalSample)
            val watchedSamples = snapshot.watched.mapNotNull(WatchedItem::toJournalSample)
            val seriesIds = (progressSamples.map { it.type to it.id } + watchedSamples.map { it.type to it.id } +
                snapshot.journal.records.values.map { it.type to it.id })
                .filter { (type, _) -> type == JournalType.Series }
                .mapTo(HashSet()) { it.second }
            val fullyWatched = seriesIds
                .filter { id -> watchedItemKeys("series", id).any(snapshot.fullyWatched::contains) }
                .mapTo(HashSet()) { journalContentKey(JournalType.Series, it) }
            buildJournalEntries(
                JournalBuildInput(
                    records = snapshot.journal.records,
                    progress = progressSamples,
                    watched = watchedSamples,
                    fullyWatchedSeries = fullyWatched,
                    meta = details,
                    settings = settings,
                    letterboxd = if (lb.connected) lb.films else emptyList(),
                    diaryDateToEpochMs = ::diaryDateToEpochMs,
                ),
            )
        }.flowOn(Dispatchers.Default)
    }

    private data class NuvioSnapshot(
        val journal: JournalProfilePayload,
        val progress: List<WatchProgressEntry>,
        val watched: List<WatchedItem>,
        val fullyWatched: Set<String>,
    )

    /** True until the recorder has seen Nuvio's state once for the active profile. */
    val journalStartedAt: Long get() = payload.value.createdAt

    fun setWatchedThreshold(percent: Int) {
        val updated = mutableSettings.value.copy(watchedThresholdPercent = percent).sanitized()
        if (updated == mutableSettings.value) return
        mutableSettings.value = updated
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    JournalStorage.save(SETTINGS, json.encodeToString(JournalSettings.serializer(), updated))
                }
            }
        }
    }

    /** Fetches the catalog details missing for [entries] (a couple at a time, cached on disk). */
    fun requestMeta(entries: List<JournalEntry>) {
        val now = nowMs()
        val current = meta.value
        val wanted = entries.mapNotNull { entry ->
            val id = entry.id ?: return@mapNotNull null
            val type = if (entry.kind == JournalKind.Series) JournalType.Series else JournalType.Movie
            val key = journalContentKey(type, id)
            val info = current[key]
            val stale = when {
                info == null -> true
                info.missing -> now - info.fetchedAt > DAY_MS
                type == JournalType.Series -> now - info.fetchedAt > DAY_MS
                else -> now - info.fetchedAt > 30 * DAY_MS
            }
            key.takeIf { stale }
        }
        if (wanted.isEmpty()) return
        synchronized(metaLock) {
            wanted.forEach { if (it !in metaInFlight) metaQueue += it }
            while (metaWorkers < META_WORKERS && metaQueue.isNotEmpty()) {
                metaWorkers++
                scope.launch { metaWorker() }
            }
        }
    }

    private suspend fun metaWorker() {
        try {
            while (true) {
                val key = synchronized(metaLock) {
                    metaQueue.firstOrNull()?.also { metaQueue.remove(it); metaInFlight += it }
                } ?: break
                val info = runCatching { fetchMeta(key) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: JournalMetaInfo(fetchedAt = nowMs(), missing = true)
                meta.value = meta.value + (key to info)
                synchronized(metaLock) { metaInFlight -= key }
                scheduleMetaSave()
            }
        } finally {
            synchronized(metaLock) { metaWorkers-- }
        }
    }

    private suspend fun fetchMeta(key: String): JournalMetaInfo? {
        val type = key.substringBefore(':')
        val id = key.substringAfter(':')
        val details = withTimeoutOrNull(META_TIMEOUT_MS) {
            MetaDetailsRepository.fetch(type = type, id = id, cacheResult = false)
        } ?: return null
        val tmdbId = tmdbIdOf(id) ?: if (type == JournalType.Movie) {
            runCatching { withTimeoutOrNull(META_TIMEOUT_MS) { TmdbService.ensureTmdbId(id, "movie", details.imdbId) } }
                .getOrNull()?.toIntOrNull()
        } else {
            null
        }
        return JournalMetaInfo(
            name = details.name.takeIf { it.isNotBlank() },
            poster = details.poster,
            imdbId = details.imdbId ?: imdbIdOf(id),
            tmdbId = tmdbId,
            year = parseJournalYear(details.releaseInfo),
            genres = details.genres,
            runtimeMinutes = parseJournalRuntime(details.runtime),
            releasedEpisodes = if (type == JournalType.Series) {
                details.releasedMainSeasonEpisodes(CurrentDateProvider.todayIsoDate()).size
            } else {
                null
            },
            fetchedAt = nowMs(),
        )
    }

    private fun scheduleMetaSave() {
        metaSaveJob?.cancel()
        metaSaveJob = scope.launch {
            delay(3_000)
            val snapshot = JournalMetaCache(meta.value)
            runCatching {
                withContext(Dispatchers.Default) { JournalStorage.save(META, json.encodeToString(JournalMetaCache.serializer(), snapshot)) }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * Marks [entry] watched / not watched with Nuvio's own actions (same as the detail page button, so trackers and,
     * for films, Letterboxd follow). "Not watched" is also remembered by the journal so older progress no longer
     * counts as watched.
     */
    fun setWatched(entry: JournalEntry, watched: Boolean) {
        val id = entry.id ?: return
        scope.launch {
            runCatching {
                if (entry.kind == JournalKind.Movie) setMovieWatched(entry, id, watched) else setSeriesWatched(entry, id, watched)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Journal mark ${if (watched) "watched" else "unwatched"} failed: ${error::class.simpleName}" }
            }
        }
    }

    private suspend fun setMovieWatched(entry: JournalEntry, id: String, watched: Boolean) {
        val preview = MetaPreview(id = id, type = JournalType.Movie, name = entry.title, poster = entry.poster)
        val current = WatchedRepository.isWatched(id = id, type = JournalType.Movie)
        if (current != watched) WatchingActions.togglePosterWatched(preview)
        if (!watched) rememberUnwatched { record -> record.type == JournalType.Movie && record.id == id && record.season == null }
        val details = runCatching { withTimeoutOrNull(META_TIMEOUT_MS) { MetaDetailsRepository.fetch(JournalType.Movie, id) } }.getOrNull()
            ?: MetaDetails(id = id, type = JournalType.Movie, name = entry.title, imdbId = imdbIdOf(id), poster = entry.poster)
        mirrorNativeWatchedToLetterboxd(details, watched)
    }

    private suspend fun setSeriesWatched(entry: JournalEntry, id: String, watched: Boolean) {
        val preview = MetaPreview(id = id, type = JournalType.Series, name = entry.title, poster = entry.poster)
        val marked = WatchedRepository.isWatched(id = id, type = JournalType.Series) ||
            WatchedRepository.isFullyWatchedSeries(id = id, type = JournalType.Series)
        if (watched) {
            if (!marked) WatchingActions.togglePosterWatched(preview)
            return
        }
        if (marked) {
            WatchingActions.togglePosterWatched(preview)
        } else {
            // Episodes marked one by one: unmark them with Nuvio's repository.
            val episodes = WatchedRepository.uiState.value.items.filter { item ->
                JournalType.normalize(item.type) == JournalType.Series && item.id == id && item.season != null
            }
            if (episodes.isNotEmpty()) WatchedRepository.unmarkWatched(episodes)
        }
        rememberUnwatched { record -> record.type == JournalType.Series && record.id == id }
    }

    private suspend fun rememberUnwatched(predicate: (JournalVideoRecord) -> Boolean) {
        mutex.withLock {
            val before = payload.value
            val videos = before.records.values.filter(predicate).ifEmpty { return@withLock }
            payload.value = markJournalVideosUnwatched(before, videos, nowMs())
            scheduleSave()
        }
    }

    /** Detail page target of [entry]; for a Letterboxd film not identified yet, looks its page up (one request). */
    suspend fun previewFor(entry: JournalEntry): MetaPreview? {
        val type = if (entry.kind == JournalKind.Series) JournalType.Series else JournalType.Movie
        val id = entry.id ?: entry.letterboxdSlug?.let { slug ->
            letterboxd.filmForOpen(slug)?.let { film -> film.imdbId ?: film.tmdbId?.let { "tmdb:$it" } }
        } ?: return null
        return MetaPreview(id = id, type = type, name = entry.title, poster = entry.poster, releaseInfo = entry.year?.toString())
    }

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun diaryDateToEpochMs(date: String): Long? =
        runCatching { EpisodeReleaseDatePlatform.localDateTimeToEpochMs("${date}T12:00:00") }.getOrNull()
}

private fun WatchProgressEntry.toJournalSample(): JournalProgressSample? {
    val type = JournalType.normalize(parentMetaType)
    if (type != JournalType.Movie && type != JournalType.Series) return null
    return JournalProgressSample(
        type = type,
        id = parentMetaId,
        season = seasonNumber,
        episode = episodeNumber,
        title = title,
        poster = poster,
        lastUpdatedAt = lastUpdatedEpochMs,
        fraction = progressFraction,
        durationMs = durationMs,
        completed = isEffectivelyCompleted,
    )
}

private fun WatchedItem.toJournalSample(): JournalWatchedSample? {
    val normalized = JournalType.normalize(type)
    if (normalized != JournalType.Movie && normalized != JournalType.Series) return null
    return JournalWatchedSample(
        type = normalized,
        id = id,
        season = season,
        episode = episode,
        title = name,
        poster = poster,
        markedAt = markedAtEpochMs,
    )
}
