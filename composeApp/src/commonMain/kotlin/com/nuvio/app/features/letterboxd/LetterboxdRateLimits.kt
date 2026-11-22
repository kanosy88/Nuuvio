package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Advanced "Network / rate limits" settings (Settings › Letterboxd). Every delay and limit of the Letterboxd module
 * lives here, with the historical values as defaults. Lower delays are faster but raise the risk of a Cloudflare
 * challenge or an HTTP 429. Durations are milliseconds unless the name says otherwise.
 */
@Serializable
data class LetterboxdRateLimits(
    /** Minimum gap between two Letterboxd requests, whatever the feature. */
    val requestGapMs: Int = 350,
    /** Watchlist sync: gap between two read requests (watchlist pages, film pages, ids). */
    val syncReadGapMs: Int = 1_500,
    /** Watchlist sync: gap between two write requests. */
    val syncWriteGapMs: Int = 2_000,
    /** Friends row: activity pages read per refresh. */
    val friendsMaxPages: Int = 5,
    /** Friends row: gap between two activity pages. */
    val friendsPageGapMs: Int = 1_500,
    /** Friends row: films whose ids are resolved per refresh (the others on click or at the next refresh). */
    val friendsResolveBatch: Int = 10,
    /** Friends row: gap between two film resolutions. */
    val friendsResolveGapMs: Int = 1_500,
    /** Friends row: minimum time between two automatic refreshes, in seconds. */
    val friendsMinAutoRefreshSec: Int = 60,
    /** Own diary RSS used by "hide films I've seen": re-read at most this often, in minutes. */
    val ownDiaryCacheMin: Int = 360,
    /** Film detail block: personal state kept in memory, in seconds. 0 = no cache. */
    val personalCacheSec: Int = 90,
    /** Journal: pages of the member's watched films (72 films each) read per refresh. */
    val journalMaxPages: Int = 3,
    /** Journal: gap between two Letterboxd reads (RSS, watched films pages). */
    val journalPageGapMs: Int = 1_500,
    /** Journal: films whose ids are looked up per refresh (the others at the next refresh or on click). */
    val journalResolveBatch: Int = 10,
    /** Journal: gap between two film lookups. */
    val journalResolveGapMs: Int = 1_500,
    /** Journal: minimum time between two automatic refreshes, in minutes. */
    val journalMinRefreshMin: Int = 30,
    /** Lists rows: gap between two list pages (one per list at each refresh, more for "View all"). */
    val listsPageGapMs: Int = 1_500,
    /** Lists rows: films whose ids are resolved per refresh, and per "View all" page (the others later or on click). */
    val listsResolveBatch: Int = 10,
    /** Lists rows: gap between two film resolutions. */
    val listsResolveGapMs: Int = 1_500,
    /** Lists rows: minimum time between two automatic refreshes, in seconds. */
    val listsMinAutoRefreshSec: Int = 60,
) {
    /** Clamps every value to its allowed range (applied to what is read from or written to storage). */
    fun sanitized(): LetterboxdRateLimits = copy(
        requestGapMs = requestGapMs.coerceIn(RequestGapRangeMs),
        syncReadGapMs = syncReadGapMs.coerceIn(BulkGapRangeMs),
        syncWriteGapMs = syncWriteGapMs.coerceIn(BulkGapRangeMs),
        friendsMaxPages = friendsMaxPages.coerceIn(FriendsMaxPagesRange),
        friendsPageGapMs = friendsPageGapMs.coerceIn(BulkGapRangeMs),
        friendsResolveBatch = friendsResolveBatch.coerceIn(FriendsResolveBatchRange),
        friendsResolveGapMs = friendsResolveGapMs.coerceIn(BulkGapRangeMs),
        friendsMinAutoRefreshSec = friendsMinAutoRefreshSec.coerceIn(MinAutoRefreshRangeSec),
        ownDiaryCacheMin = ownDiaryCacheMin.coerceIn(OwnDiaryCacheRangeMin),
        personalCacheSec = personalCacheSec.coerceIn(PersonalCacheRangeSec),
        journalMaxPages = journalMaxPages.coerceIn(JournalMaxPagesRange),
        journalPageGapMs = journalPageGapMs.coerceIn(BulkGapRangeMs),
        journalResolveBatch = journalResolveBatch.coerceIn(FriendsResolveBatchRange),
        journalResolveGapMs = journalResolveGapMs.coerceIn(BulkGapRangeMs),
        journalMinRefreshMin = journalMinRefreshMin.coerceIn(JournalMinRefreshRangeMin),
        listsPageGapMs = listsPageGapMs.coerceIn(BulkGapRangeMs),
        listsResolveBatch = listsResolveBatch.coerceIn(FriendsResolveBatchRange),
        listsResolveGapMs = listsResolveGapMs.coerceIn(BulkGapRangeMs),
        listsMinAutoRefreshSec = listsMinAutoRefreshSec.coerceIn(MinAutoRefreshRangeSec),
    )

    companion object {
        val RequestGapRangeMs = 100..5_000
        val BulkGapRangeMs = 500..30_000
        val FriendsMaxPagesRange = 1..20
        val FriendsResolveBatchRange = 1..50
        val MinAutoRefreshRangeSec = 10..600
        val OwnDiaryCacheRangeMin = 30..1_440
        val PersonalCacheRangeSec = 0..600
        val JournalMaxPagesRange = 1..20
        val JournalMinRefreshRangeMin = 5..1_440
    }
}

/**
 * Current [LetterboxdRateLimits], shared by the transport, the sync, the friends row and the film block.
 * Read at each use, so a change applies from the next request. Stored as a non-secret preference (like the other
 * Letterboxd settings). Defaults until [ensureLoaded] has run.
 */
internal object LetterboxdRateLimitSettings {
    private const val PREFERENCE = "rate_limits"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loadMutex = Mutex()
    private val mutableState = MutableStateFlow(LetterboxdRateLimits())
    private var loaded = false

    val state: StateFlow<LetterboxdRateLimits> = mutableState.asStateFlow()

    val current: LetterboxdRateLimits get() = mutableState.value

    suspend fun ensureLoaded() = loadMutex.withLock {
        if (loaded) return@withLock
        val stored = runCatching {
            withContext(Dispatchers.Default) { LetterboxdPlatform.readPreference(PREFERENCE) }
        }.getOrNull()
        stored
            ?.let { runCatching { json.decodeFromString(LetterboxdRateLimits.serializer(), it) }.getOrNull() }
            ?.let { mutableState.value = it.sanitized() }
        loaded = true
    }

    suspend fun update(transform: (LetterboxdRateLimits) -> LetterboxdRateLimits) {
        ensureLoaded()
        val after = transform(mutableState.value).sanitized()
        if (after == mutableState.value) return
        mutableState.value = after
        runCatching {
            withContext(Dispatchers.Default) {
                LetterboxdPlatform.writePreference(PREFERENCE, json.encodeToString(LetterboxdRateLimits.serializer(), after))
            }
        }
    }

    suspend fun restoreDefaults() = update { LetterboxdRateLimits() }
}
