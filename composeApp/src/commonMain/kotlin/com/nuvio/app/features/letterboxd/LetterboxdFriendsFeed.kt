package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// ---------------------------------------------------------------------------------------------- settings

enum class LetterboxdFriendsRefreshMode {
    /** Once per app launch (first time the row is shown). */
    OnLaunch,

    /** Every time the home screen is entered. */
    EveryVisit,

    /** Every time the home screen is entered, if the data is older than [LetterboxdFriendsSettings.refreshDelayMinutes]. */
    EveryVisitWithDelay,
}

/** "Recently rated by friends" row settings (defaults from the spec). */
@Serializable
data class LetterboxdFriendsSettings(
    val enabled: Boolean = true,
    val includeRatings: Boolean = true,
    val includeWatches: Boolean = true,
    val includeReviews: Boolean = true,
    val includeLikes: Boolean = false,
    val maxPosters: Int = 30,
    val periodDays: Int = 30,
    val groupByFilm: Boolean = true,
    /** Out of 5; null = no minimum. Only applies to activities that carry a rating. */
    val minRating: Double? = null,
    val hideWatchedByMe: Boolean = false,
    val refreshMode: LetterboxdFriendsRefreshMode = LetterboxdFriendsRefreshMode.OnLaunch,
    val refreshDelayMinutes: Int = 15,
) {
    companion object {
        val PeriodOptions = listOf(7, 14, 30, 90, 365)
        val MinRatingOptions = listOf(null, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0)
        val MaxPostersRange = 5..60
        val RefreshDelayRange = 5..240
    }
}

// ---------------------------------------------------------------------------------------------- row model

/** One followed member on a poster of the row. */
data class LetterboxdFriendOnFilm(
    val member: String,
    val name: String,
    val avatarUrl: String?,
    /** Out of 5, null when not rated. */
    val rating: Double?,
    val liked: Boolean,
    val reviewed: Boolean,
    val atEpochMs: Long,
)

/** One poster of the row. [film] is null until the slug has been resolved (ids, poster). */
data class LetterboxdFriendsEntry(
    val key: String,
    val slug: String,
    val title: String,
    val film: LetterboxdFilm?,
    val friends: List<LetterboxdFriendOnFilm>,
    val latestAtEpochMs: Long,
) {
    val posterUrl: String? get() = film?.posterUrl
}

/** Persisted raw feed (disk cache): rows are filtered at display time, so settings changes need no refetch. */
@Serializable
internal data class LetterboxdFriendsCache(
    val version: Int = 1,
    val username: String? = null,
    val fetchedAtEpochMs: Long = 0L,
    val items: List<LetterboxdActivityItem> = emptyList(),
    /** Slugs of the member's own recent diary entries (RSS), for "hide films I've seen". */
    val myRecentSlugs: List<String> = emptyList(),
    val myRecentFetchedAtEpochMs: Long = 0L,
)

data class LetterboxdFriendsState(
    val isLoaded: Boolean = false,
    val settings: LetterboxdFriendsSettings = LetterboxdFriendsSettings(),
    val items: List<LetterboxdActivityItem> = emptyList(),
    val films: Map<String, LetterboxdFilm> = emptyMap(),
    /** Slugs that are not usable (unknown to Letterboxd, TV show, no TMDB movie id). */
    val excludedSlugs: Set<String> = emptySet(),
    val myRecentSlugs: Set<String> = emptySet(),
    val fetchedAtEpochMs: Long = 0L,
    val isRefreshing: Boolean = false,
    val error: LetterboxdError? = null,
    /** Slug being resolved after a click on a not-yet-resolved poster. */
    val opening: String? = null,
)

/**
 * Builds the posters of the row from the raw activity (pure, unit-tested).
 * [isWatchedInNuvio] tells whether a resolved film is marked watched in Nuvio.
 */
internal fun buildLetterboxdFriendsEntries(
    state: LetterboxdFriendsState,
    nowMs: Long,
    isWatchedInNuvio: (LetterboxdFilm) -> Boolean = { false },
): List<LetterboxdFriendsEntry> {
    val settings = state.settings
    val since = nowMs - settings.periodDays * DAY_MS
    val inPeriod = state.items.mapNotNull { item ->
        val at = parseActivityInstant(item.timestamp) ?: return@mapNotNull null
        if (at < since || item.filmSlug in state.excludedSlugs) return@mapNotNull null
        item to at
    }
    // A like shows as a heart even when like rows alone don't put a film in the row.
    val likedPairs = inPeriod
        .filter { (item, _) -> item.liked || item.type == LetterboxdActivityType.Like }
        .mapTo(HashSet()) { (item, _) -> item.filmSlug to item.member }
    val accepted = inPeriod.mapNotNull { (item, at) ->
        val wanted = when (item.type) {
            LetterboxdActivityType.Review -> settings.includeReviews
            LetterboxdActivityType.Rating -> settings.includeRatings
            LetterboxdActivityType.Watch -> if (item.rating != null) settings.includeRatings else settings.includeWatches
            LetterboxdActivityType.Like -> settings.includeLikes
            else -> false
        }
        if (!wanted) return@mapNotNull null
        val min = settings.minRating
        if (min != null && item.rating != null && item.rating < min) return@mapNotNull null
        item to at
    }
    if (accepted.isEmpty()) return emptyList()

    // One line per (film, member): latest rating, liked/reviewed if any row says so.
    val perMember = accepted.groupBy { (item, _) -> item.filmSlug to item.member }.map { (key, rows) ->
        val sorted = rows.sortedByDescending { it.second }
        val latest = sorted.first().first
        key to LetterboxdFriendOnFilm(
            member = latest.member,
            name = sorted.firstNotNullOfOrNull { it.first.memberName } ?: latest.member,
            avatarUrl = sorted.firstNotNullOfOrNull { it.first.avatarUrl },
            rating = sorted.firstNotNullOfOrNull { it.first.rating },
            liked = key in likedPairs,
            reviewed = sorted.any { it.first.type == LetterboxdActivityType.Review },
            atEpochMs = sorted.first().second,
        )
    }
    val titles = accepted.associate { (item, _) -> item.filmSlug to item.filmName }

    fun title(slug: String): String {
        val film = state.films[slug]
        return film?.name ?: titles[slug]?.replace(Regex("""\s*\(\d{4}\)$"""), "") ?: slug
    }

    val entries = if (settings.groupByFilm) {
        perMember.groupBy { it.first.first }.map { (slug, members) ->
            val friends = members.map { it.second }.sortedByDescending { it.atEpochMs }
            LetterboxdFriendsEntry(slug, slug, title(slug), state.films[slug], friends, friends.first().atEpochMs)
        }
    } else {
        perMember.map { (key, friend) ->
            val slug = key.first
            LetterboxdFriendsEntry("$slug|${friend.member}", slug, title(slug), state.films[slug], listOf(friend), friend.atEpochMs)
        }
    }
    return entries
        .asSequence()
        .filter { entry ->
            if (!settings.hideWatchedByMe) return@filter true
            if (entry.slug in state.myRecentSlugs) return@filter false
            entry.film?.let { !isWatchedInNuvio(it) } ?: true
        }
        .sortedByDescending { it.latestAtEpochMs }
        .take(settings.maxPosters.coerceIn(LetterboxdFriendsSettings.MaxPostersRange))
        .toList()
}

internal fun parseActivityInstant(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

// ---------------------------------------------------------------------------------------------- data source

/** What the feed needs from Letterboxd (swappable in tests). */
internal interface LetterboxdFriendsSource {
    /** Null when no Letterboxd account is connected (row hidden). */
    suspend fun username(): String?
    suspend fun activityPage(username: String, after: String?): LetterboxdActivityPage
    /** Cached film, no network. */
    suspend fun cachedFilm(slug: String): LetterboxdFilm?
    /** Film page (one request); null when Letterboxd doesn't know the slug. */
    suspend fun fetchFilm(slug: String): LetterboxdFilm?
    /** Slugs of the member's own latest diary entries (public RSS, one request). */
    suspend fun myRecentSlugs(username: String): List<String>
}

internal class RepositoryLetterboxdFriendsSource : LetterboxdFriendsSource {
    override suspend fun username(): String? {
        LetterboxdRepository.awaitLoaded()
        val state = LetterboxdRepository.state.value
        if (!state.hasCredentials) return null
        return LetterboxdRepository.authenticated { username -> username ?: fetchSession().username }
    }

    override suspend fun activityPage(username: String, after: String?): LetterboxdActivityPage =
        LetterboxdRepository.authenticated { fetchFollowingActivityPage(username, after) }

    override suspend fun cachedFilm(slug: String): LetterboxdFilm? = LetterboxdRepository.cachedFilm(slug)

    override suspend fun fetchFilm(slug: String): LetterboxdFilm? = try {
        LetterboxdRepository.film(slug, maxAgeMs = Long.MAX_VALUE)
    } catch (error: LetterboxdException) {
        if (error.error == LetterboxdError.NotFound) null else throw error
    }

    override suspend fun myRecentSlugs(username: String): List<String> =
        LetterboxdRepository.client.fetchDiaryRss(username).mapNotNull { it.filmSlug }
}

// ---------------------------------------------------------------------------------------------- controller

/**
 * Feed of the "Recently rated by friends" row: disk cache shown at once, background refresh according to
 * [LetterboxdFriendsSettings.refreshMode], manual refresh.
 *
 * Cloudflare reacts to bursts, so the network use is bounded on purpose:
 * All the limits come from [LetterboxdRateLimits] (Settings › Letterboxd › Network / rate limits):
 * - activity: one request per page (`?after=` cursor), at most `friendsMaxPages` pages, `friendsPageGapMs` apart,
 *   stopping at the end of the period or of the stream;
 * - films (slug → TMDB/IMDb ids + poster): persistent cache first, then at most `friendsResolveBatch` film pages per
 *   refresh, `friendsResolveGapMs` apart; the rest is resolved at the next refresh or when a poster is clicked;
 * - automatic refreshes never closer than `friendsMinAutoRefreshSec`; any challenge / rate limit stops the refresh.
 */
internal class LetterboxdFriendsFeed(
    private val source: LetterboxdFriendsSource,
    private val scope: CoroutineScope,
    private val loadCache: suspend () -> String?,
    private val saveCache: suspend (String) -> Unit,
    private val loadSettings: suspend () -> String?,
    private val saveSettings: suspend (String) -> Unit,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val limits: () -> LetterboxdRateLimits = { LetterboxdRateLimitSettings.current },
) {
    private val log = Logger.withTag("LetterboxdFriends")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loadMutex = Mutex()
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(LetterboxdFriendsState())
    val state: StateFlow<LetterboxdFriendsState> = mutableState.asStateFlow()

    private var username: String? = null
    private var myRecentFetchedAt = 0L
    private var refreshedThisSession = false
    private var lastAttemptAt = Long.MIN_VALUE / 2
    private var refreshJob: Job? = null

    suspend fun load() = loadMutex.withLock {
        if (mutableState.value.isLoaded) return@withLock
        LetterboxdRateLimitSettings.ensureLoaded()
        val settings = loadSettings()?.let { runCatching { json.decodeFromString(LetterboxdFriendsSettings.serializer(), it) }.getOrNull() }
            ?: LetterboxdFriendsSettings()
        val cache = loadCache()?.let { runCatching { json.decodeFromString(LetterboxdFriendsCache.serializer(), it) }.getOrNull() }
            ?: LetterboxdFriendsCache()
        username = cache.username
        myRecentFetchedAt = cache.myRecentFetchedAtEpochMs
        val films = cachedFilms(cache.items.map { it.filmSlug }.distinct())
        mutableState.value = LetterboxdFriendsState(
            isLoaded = true,
            settings = settings,
            items = cache.items,
            films = films.filterValues { it.isNuvioMovie },
            excludedSlugs = films.filterValues { !it.isNuvioMovie }.keys,
            myRecentSlugs = cache.myRecentSlugs.toSet(),
            fetchedAtEpochMs = cache.fetchedAtEpochMs,
        )
    }

    /** Called each time the row becomes visible (home screen entered); refreshes according to the settings. */
    fun onRowVisible() {
        scope.launch {
            load()
            val current = mutableState.value
            if (!current.settings.enabled || current.isRefreshing) return@launch
            val now = nowMs()
            if (now - lastAttemptAt < limits().friendsMinAutoRefreshSec * 1_000L) return@launch
            val due = when (current.settings.refreshMode) {
                LetterboxdFriendsRefreshMode.OnLaunch -> !refreshedThisSession
                LetterboxdFriendsRefreshMode.EveryVisit -> true
                LetterboxdFriendsRefreshMode.EveryVisitWithDelay ->
                    now - current.fetchedAtEpochMs >= current.settings.refreshDelayMinutes.coerceAtLeast(1) * MINUTE_MS
            }
            if (due) refresh()
        }
    }

    /** Manual or automatic refresh; no-op while one is running. */
    fun refresh(): Job? {
        if (refreshJob?.isActive == true) return refreshJob
        return scope.launch { refreshNow() }.also { refreshJob = it }
    }

    internal suspend fun refreshNow() = refreshMutex.withLock {
        load()
        refreshedThisSession = true
        lastAttemptAt = nowMs()
        mutableState.update { it.copy(isRefreshing = true, error = null) }
        try {
            val user = source.username()
            if (user == null) {
                mutableState.update { it.copy(isRefreshing = false) }
                return@withLock
            }
            val settings = mutableState.value.settings
            val items = fetchActivity(user, settings.periodDays)
            val accountChanged = username != null && username != user
            username = user
            val myRecent = if (settings.hideWatchedByMe && (accountChanged || nowMs() - myRecentFetchedAt >= limits().ownDiaryCacheMin * MINUTE_MS)) {
                try {
                    source.myRecentSlugs(user).also { myRecentFetchedAt = nowMs() }
                } catch (error: LetterboxdException) {
                    // Best effort: the row still works, "hide films I've seen" then relies on Nuvio's watched state.
                    log.w { "Own diary RSS failed: ${error.error}" }
                    null
                }
            } else {
                null
            }
            val films = cachedFilms(items.map { it.filmSlug }.distinct().filter { it !in mutableState.value.films })
            mutableState.update { state ->
                state.copy(
                    items = items,
                    films = state.films + films.filterValues { it.isNuvioMovie },
                    excludedSlugs = state.excludedSlugs + films.filterValues { !it.isNuvioMovie }.keys,
                    myRecentSlugs = myRecent?.toSet() ?: if (accountChanged) emptySet() else state.myRecentSlugs,
                    fetchedAtEpochMs = nowMs(),
                )
            }
            persist()
            resolveVisibleFilms()
            mutableState.update { it.copy(isRefreshing = false) }
        } catch (error: CancellationException) {
            mutableState.update { it.copy(isRefreshing = false) }
            throw error
        } catch (error: LetterboxdException) {
            log.w { "Friends activity refresh failed: ${error.error}${error.status?.let { " (HTTP $it)" }.orEmpty()}" }
            mutableState.update { it.copy(isRefreshing = false, error = error.error) }
        } catch (error: Exception) {
            log.w { "Friends activity refresh failed: ${error::class.simpleName}" }
            mutableState.update { it.copy(isRefreshing = false, error = LetterboxdError.Network) }
        }
    }

    private suspend fun fetchActivity(user: String, periodDays: Int): List<LetterboxdActivityItem> {
        val since = nowMs() - periodDays * DAY_MS
        val collected = mutableListOf<LetterboxdActivityItem>()
        var after: String? = null
        for (page in 1..limits().friendsMaxPages) {
            if (page > 1) pause(limits().friendsPageGapMs.toLong())
            val result = source.activityPage(user, after)
            collected += result.items
            val oldest = result.items.mapNotNull { parseActivityInstant(it.timestamp) }.minOrNull()
            if (!result.hasMore || result.items.isEmpty() || (oldest != null && oldest < since)) break
            after = result.items.lastOrNull()?.activityId ?: break
        }
        return collected
            .filter { it.member != user }
            .distinctBy { it.activityId ?: "${it.member}|${it.filmSlug}|${it.type}|${it.timestamp}" }
    }

    /** Resolves (with the network) the first unresolved posters of the row, in a small spaced batch. */
    private suspend fun resolveVisibleFilms() {
        var fetched = 0
        while (fetched < limits().friendsResolveBatch) {
            val state = mutableState.value
            val next = buildLetterboxdFriendsEntries(state, nowMs())
                .firstOrNull { it.film == null && it.slug !in state.excludedSlugs } ?: return
            if (fetched > 0) pause(limits().friendsResolveGapMs.toLong())
            fetched++
            val film = source.fetchFilm(next.slug)
            applyResolved(next.slug, film)
        }
    }

    private fun applyResolved(slug: String, film: LetterboxdFilm?) {
        mutableState.update { state ->
            if (film == null || !film.isNuvioMovie) {
                state.copy(excludedSlugs = state.excludedSlugs + slug)
            } else {
                state.copy(films = state.films + (slug to film))
            }
        }
    }

    /**
     * Film to open for a clicked poster: from memory, else one request. Null when it can't be opened
     * (unknown, TV show, no TMDB/IMDb id); the error is exposed in [state].
     */
    suspend fun filmForOpen(slug: String): LetterboxdFilm? {
        mutableState.value.films[slug]?.let { return it.takeIf(::isOpenable) }
        mutableState.update { it.copy(opening = slug, error = null) }
        return try {
            val film = source.cachedFilm(slug) ?: source.fetchFilm(slug)
            applyResolved(slug, film)
            val openable = film?.takeIf(::isOpenable)
            mutableState.update { it.copy(opening = null, error = if (openable == null) LetterboxdError.NotFound else null) }
            openable
        } catch (error: CancellationException) {
            mutableState.update { it.copy(opening = null) }
            throw error
        } catch (error: LetterboxdException) {
            mutableState.update { it.copy(opening = null, error = error.error) }
            null
        }
    }

    fun updateSettings(transform: (LetterboxdFriendsSettings) -> LetterboxdFriendsSettings) {
        scope.launch {
            load()
            val before = mutableState.value.settings
            val after = transform(before)
            if (after == before) return@launch
            mutableState.update { it.copy(settings = after) }
            runCatching { saveSettings(json.encodeToString(LetterboxdFriendsSettings.serializer(), after)) }
                .onFailure { log.w { "Could not save friends row settings: ${it::class.simpleName}" } }
            // A longer period or "hide my films" needs data the cache may not have: refresh once.
            val needsData = after.enabled && (
                after.periodDays > before.periodDays ||
                    (after.hideWatchedByMe && !before.hideWatchedByMe) ||
                    (!before.enabled && mutableState.value.items.isEmpty())
                )
            if (needsData) refresh()
        }
    }

    private suspend fun cachedFilms(slugs: List<String>): Map<String, LetterboxdFilm> =
        slugs.mapNotNull { slug -> source.cachedFilm(slug)?.let { slug to it } }.toMap()

    private suspend fun persist() {
        val state = mutableState.value
        val cache = LetterboxdFriendsCache(
            username = username,
            fetchedAtEpochMs = state.fetchedAtEpochMs,
            items = state.items,
            myRecentSlugs = state.myRecentSlugs.toList(),
            myRecentFetchedAtEpochMs = myRecentFetchedAt,
        )
        runCatching { saveCache(json.encodeToString(LetterboxdFriendsCache.serializer(), cache)) }
            .onFailure { log.w { "Could not write friends activity cache: ${it::class.simpleName}" } }
    }

    companion object {
        fun isOpenable(film: LetterboxdFilm): Boolean = film.isNuvioMovie
    }
}

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24L * 60 * 60 * 1000

/** App-wide feed instance (disk cache in the Letterboxd cache folder, settings with the app settings). */
internal object LetterboxdFriends {
    private const val CACHE_FILE = "friends_activity.json"
    private const val SETTINGS_KEY = "friends_row"

    val feed: LetterboxdFriendsFeed by lazy {
        LetterboxdFriendsFeed(
            source = RepositoryLetterboxdFriendsSource(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            loadCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.readCache(CACHE_FILE) } },
            saveCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.writeCache(CACHE_FILE, it) } },
            loadSettings = { withContext(Dispatchers.Default) { LetterboxdPlatform.readPreference(SETTINGS_KEY) } },
            saveSettings = { withContext(Dispatchers.Default) { LetterboxdPlatform.writePreference(SETTINGS_KEY, it) } },
        )
    }
}
