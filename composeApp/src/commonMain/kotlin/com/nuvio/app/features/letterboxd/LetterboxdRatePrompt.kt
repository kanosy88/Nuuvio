package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * F5 — "Rate on Letterboxd?" at the end of a film.
 *
 * Order of events when a film reaches the threshold / ends in Nuvio's player (see NOTES.md):
 *  1. While playing, Nuvio persists the progress (60 s tick, pause, seek, end, exit). At ≥ 90 % or "ended",
 *     upstream marks the film watched in Nuvio (WatchingActions.onProgressEntryUpdated). F5 doesn't touch that.
 *  2. The player hook ([LetterboxdViewingSession]) only watches the playback position and latches "reached"
 *     when the F5 threshold is crossed or the film ends.
 *  3. When the player is left (+ a short grace so the exit flush lands), the coordinator decides; if the
 *     prompt is due it makes sure the film is watched on Letterboxd with ONE write at most
 *     ([LetterboxdNativeSync.ensureWatched], serialized with the detail-page observer: whichever runs first
 *     writes, the other skips), which also drops it from the watchlist and the Nuvio library (no echo).
 *  4. Only then the dialog opens (rating, like, review / diary entry), over the page under the player.
 */

/** Settings › Letterboxd › "Rate after a film". */
@Serializable
internal data class LetterboxdRatePromptSettings(
    val enabled: Boolean = true,
    /** Null = same as the Journal's "counts as watched" threshold (90 % by default). */
    val thresholdPercent: Int? = null,
) {
    fun sanitized() = copy(thresholdPercent = thresholdPercent?.coerceIn(ThresholdRange))

    fun effectiveThreshold(journalThreshold: Int?): Int =
        (thresholdPercent ?: journalThreshold ?: FallbackThresholdPercent).coerceIn(ThresholdRange)

    companion object {
        const val FallbackThresholdPercent = 90
        val ThresholdRange = 50..100
    }
}

internal object LetterboxdRatePromptSettingsStore {
    private const val PREFERENCE = "rate_prompt"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loadMutex = Mutex()
    private val mutableState = MutableStateFlow(LetterboxdRatePromptSettings())
    private var loaded = false

    val state: StateFlow<LetterboxdRatePromptSettings> = mutableState.asStateFlow()

    suspend fun ensureLoaded() = loadMutex.withLock {
        if (loaded) return@withLock
        runCatching { withContext(Dispatchers.Default) { LetterboxdPlatform.readPreference(PREFERENCE) } }.getOrNull()
            ?.let { runCatching { json.decodeFromString(LetterboxdRatePromptSettings.serializer(), it) }.getOrNull() }
            ?.let { mutableState.value = it.sanitized() }
        loaded = true
    }

    suspend fun update(transform: (LetterboxdRatePromptSettings) -> LetterboxdRatePromptSettings) {
        ensureLoaded()
        val after = transform(mutableState.value).sanitized()
        if (after == mutableState.value) return
        mutableState.value = after
        runCatching {
            withContext(Dispatchers.Default) {
                LetterboxdPlatform.writePreference(PREFERENCE, json.encodeToString(LetterboxdRatePromptSettings.serializer(), after))
            }
        }
    }
}

/** Ratings / diary entries written from Nuuvio (detail block, prompt), by slug: "already rated during this viewing". */
internal object LetterboxdRatingActivity {
    private val lock = SynchronizedObject()
    private val ratedAt = LinkedHashMap<String, Long>()

    fun record(slug: String, atMs: Long) = synchronized(lock) {
        ratedAt.remove(slug)
        ratedAt[slug] = atMs
        while (ratedAt.size > 100) ratedAt.remove(ratedAt.keys.first())
    }

    fun ratedSince(slug: String, sinceMs: Long): Boolean = synchronized(lock) { (ratedAt[slug] ?: Long.MIN_VALUE) >= sinceMs }
}

/** When the prompt was last shown per film (key = Nuvio id): one proposal per viewing, across restarts. */
internal interface LetterboxdPromptLog {
    suspend fun last(key: String): Long?
    suspend fun record(key: String, atMs: Long)
}

internal class MemoryPromptLog : LetterboxdPromptLog {
    private val entries = HashMap<String, Long>()
    override suspend fun last(key: String) = entries[key]
    override suspend fun record(key: String, atMs: Long) { entries[key] = atMs }
}

/** Kept with the other Letterboxd preferences; entries older than 30 days are dropped. */
internal class PreferencePromptLog(private val nowMs: () -> Long) : LetterboxdPromptLog {
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val mutex = Mutex()
    private var cache: MutableMap<String, Long>? = null

    private suspend fun entries(): MutableMap<String, Long> = cache ?: withContext(Dispatchers.Default) {
        runCatching { LetterboxdPlatform.readPreference(PREFERENCE)?.let { Json.decodeFromString(serializer, it) } }.getOrNull()
    }.orEmpty().toMutableMap().also { cache = it }

    override suspend fun last(key: String): Long? = mutex.withLock { entries()[key] }

    override suspend fun record(key: String, atMs: Long) = mutex.withLock {
        val map = entries()
        map[key] = atMs
        map.entries.removeAll { nowMs() - it.value > 30L * DAY_MS }
        runCatching { withContext(Dispatchers.Default) { LetterboxdPlatform.writePreference(PREFERENCE, Json.encodeToString(serializer, map)) } }
        Unit
    }

    private companion object {
        const val PREFERENCE = "rate_prompt_log"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** The film playing, as the player knows it. */
internal data class LetterboxdViewingRef(
    /** Nuvio id of the film (`tt…`, `tmdb:…` or an addon id). */
    val id: String,
    val title: String,
    val poster: String?,
    val initialPositionMs: Long = 0L,
    /** 0..1, when the player resumes by fraction. */
    val initialProgressFraction: Float? = null,
)

/**
 * Latches "reached" when the playback crosses [thresholdPercent] or ends. Clips shorter than ~2 min
 * (trailers, error placeholders — same rule as Nuvio's progress) never count.
 */
internal class LetterboxdViewingTracker(private val ref: LetterboxdViewingRef) {
    var reached: Boolean = false
        private set
    /** Where the viewing started, in percent (known once the duration is). */
    var startPercent: Double? = null
        private set

    fun observe(positionMs: Long, durationMs: Long, isEnded: Boolean, thresholdPercent: Int) {
        if (durationMs < MinFilmDurationMs) return
        if (startPercent == null) {
            startPercent = ref.initialProgressFraction?.takeIf { ref.initialPositionMs <= 0L }?.let { it.coerceIn(0f, 1f) * 100.0 }
                ?: (ref.initialPositionMs.coerceAtLeast(0L) * 100.0 / durationMs)
        }
        if (isEnded || positionMs * 100.0 / durationMs >= thresholdPercent) reached = true
    }

    companion object {
        const val MinFilmDurationMs = 121_000L
    }
}

/**
 * One proposal per viewing: never twice within [windowMs], and not again for a viewing resumed past the
 * threshold after a proposal (the end of the same viewing).
 */
internal fun letterboxdShouldPrompt(
    reached: Boolean,
    startPercent: Double,
    thresholdPercent: Int,
    lastPromptAtMs: Long?,
    nowMs: Long,
    windowMs: Long = LetterboxdRatePromptCoordinator.SameViewingWindowMs,
): Boolean {
    if (!reached) return false
    if (lastPromptAtMs == null) return true
    if (nowMs - lastPromptAtMs < windowMs) return false
    return startPercent < thresholdPercent
}

/** What the dialog needs: the Letterboxd film (already watched there) and the Nuvio film. */
internal data class LetterboxdRatePromptRequest(
    val id: Long,
    val meta: MetaDetails,
    val film: LetterboxdFilm,
)

/**
 * Decides when to show the end-of-film prompt and prepares it. Platform pieces are injected
 * (production wiring in `ui/LetterboxdRatePromptHost.kt`).
 */
internal class LetterboxdRatePromptCoordinator(
    private val scope: CoroutineScope,
    private val settings: () -> LetterboxdRatePromptSettings,
    private val journalThreshold: () -> Int?,
    private val account: () -> LetterboxdAccountState,
    /** Loads the account / settings before deciding. */
    private val ensureReady: suspend () -> Unit = {},
    /** Makes sure the film is watched on Letterboxd (one write at most); null = no prompt (reported elsewhere). */
    private val prepare: suspend (LetterboxdViewingRef) -> Pair<MetaDetails, LetterboxdFilm>?,
    private val ratedSince: (slug: String, sinceMs: Long) -> Boolean = LetterboxdRatingActivity::ratedSince,
    private val log: LetterboxdPromptLog = MemoryPromptLog(),
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val exitGraceMs: Long = 1_500L,
) {
    private val logger = Logger.withTag("LetterboxdPrompt")
    private val lock = SynchronizedObject()
    private val pendingExits = HashMap<String, Pair<LetterboxdViewingSession, Job>>()
    private val mutableActive = MutableStateFlow(0)
    private val mutablePending = MutableStateFlow<LetterboxdRatePromptRequest?>(null)
    private var nextId = 1L

    /** Players currently open: the dialog waits until none is (never over the video). */
    val activePlayers: StateFlow<Int> = mutableActive.asStateFlow()

    /** The prompt to show, null when none. */
    val pending: StateFlow<LetterboxdRatePromptRequest?> = mutablePending.asStateFlow()

    /** The player opened a film. A reopening during the exit grace (recomposition) continues the same viewing. */
    fun startSession(ref: LetterboxdViewingRef): LetterboxdViewingSession {
        mutableActive.update { it + 1 }
        val resumed = synchronized(lock) { pendingExits.remove(ref.id) }
        resumed?.second?.cancel()
        return resumed?.first?.takeIf { it.ref.id == ref.id } ?: LetterboxdViewingSession(ref, nowMs(), this)
    }

    fun thresholdPercent(): Int = settings().effectiveThreshold(journalThreshold())

    internal fun sessionClosed(session: LetterboxdViewingSession) {
        mutableActive.update { (it - 1).coerceAtLeast(0) }
        val job = scope.launch {
            delay(exitGraceMs)
            synchronized(lock) { if (pendingExits[session.ref.id]?.first === session) pendingExits.remove(session.ref.id) }
            runCatching { evaluate(session) }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.w { "End-of-film prompt failed: ${error::class.simpleName}" }
            }
        }
        synchronized(lock) { pendingExits[session.ref.id] = session to job }
    }

    fun dismiss(request: LetterboxdRatePromptRequest) {
        mutablePending.update { if (it?.id == request.id) null else it }
    }

    private suspend fun evaluate(session: LetterboxdViewingSession) {
        if (!session.tracker.reached) return
        ensureReady()
        if (!settings().enabled || !account().hasCredentials) return
        val threshold = thresholdPercent()
        val now = nowMs()
        if (!letterboxdShouldPrompt(true, session.tracker.startPercent ?: 0.0, threshold, log.last(session.ref.id), now)) {
            logger.i { "End-of-film prompt skipped: already proposed for this viewing" }
            return
        }
        log.record(session.ref.id, now)
        val (meta, film) = prepare(session.ref) ?: return
        if (ratedSince(film.slug, session.startedAtMs)) {
            logger.i { "End-of-film prompt skipped: rated during this viewing" }
            return
        }
        mutablePending.value = LetterboxdRatePromptRequest(id = nextId++, meta = meta, film = film)
    }

    companion object {
        /** Two proposals for the same film need at least this much time in between. */
        const val SameViewingWindowMs = 12L * 60 * 60 * 1000
    }
}

/** A film open in the player. Feed it the playback position; [close] when the player goes away. */
internal class LetterboxdViewingSession(
    val ref: LetterboxdViewingRef,
    val startedAtMs: Long,
    private val coordinator: LetterboxdRatePromptCoordinator,
) {
    val tracker = LetterboxdViewingTracker(ref)

    fun observe(positionMs: Long, durationMs: Long, isEnded: Boolean) =
        tracker.observe(positionMs, durationMs, isEnded, coordinator.thresholdPercent())

    fun close() = coordinator.sessionClosed(this)
}
