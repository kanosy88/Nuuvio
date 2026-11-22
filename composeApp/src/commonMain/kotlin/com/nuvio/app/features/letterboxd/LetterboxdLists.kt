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

// ---------------------------------------------------------------------------------------------- list identity

/** A Letterboxd list, `letterboxd.com/<owner>/list/<slug>/` (lower case: Letterboxd addresses are case-insensitive). */
@Serializable
data class LetterboxdListRef(val owner: String, val slug: String) {
    val key: String get() = "$owner/$slug"
    val path: String get() = "/$owner/list/$slug/"
    val url: String get() = "$LETTERBOXD_BASE_URL$path"
}

/**
 * List address pasted by the user: `https://letterboxd.com/<owner>/list/<slug>/` with or without scheme / `www.`,
 * trailing views (`/detail/`, `/page/2/`, `/by/rating/`…), query or fragment; also `<owner>/list/<slug>`.
 * Null when it isn't a list address (short `boxd.it` links aren't resolved).
 */
fun parseLetterboxdListUrl(input: String): LetterboxdListRef? {
    var rest = input.trim().substringBefore('#').substringBefore('?')
    rest = rest.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
    val host = Regex("^(?:www\\.)?letterboxd\\.com(?=/|$)", RegexOption.IGNORE_CASE).find(rest)
    if (host != null) {
        rest = rest.substring(host.range.last + 1)
    } else if (rest.contains('.') && rest.substringBefore('/').contains('.')) {
        return null // another host
    }
    val match = Regex("^/?([A-Za-z0-9_]+)/list/([A-Za-z0-9_-]+)(?:/.*)?$").find(rest) ?: return null
    val (owner, slug) = match.destructured
    return LetterboxdListRef(owner.lowercase(), slug.lowercase())
}

/** One film of a list (no ids: those come from the film page, resolved lazily through the persistent film index). */
@Serializable
data class LetterboxdListEntry(val slug: String, val title: String? = null, val year: Int? = null)

/** One page of a list as read from Letterboxd. */
data class LetterboxdListPage(
    val title: String?,
    val entries: List<LetterboxdListEntry>,
    val hasNext: Boolean,
    val totalFilms: Int?,
)

/** A list in a member's "Lists" page. */
data class LetterboxdListSummary(val ref: LetterboxdListRef, val title: String, val filmCount: Int?)

// ---------------------------------------------------------------------------------------------- settings

/** A list shown on Home. The order of [LetterboxdListsSettings.lists] is the order of the rows. */
@Serializable
data class LetterboxdListConfig(
    val owner: String,
    val slug: String,
    val title: String,
    val enabled: Boolean = true,
    /** Added from "My lists": only shown while a Letterboxd account is connected. */
    val mine: Boolean = false,
) {
    val ref: LetterboxdListRef get() = LetterboxdListRef(owner, slug)
    val key: String get() = ref.key
}

@Serializable
data class LetterboxdListsSettings(
    val lists: List<LetterboxdListConfig> = emptyList(),
    val postersPerRow: Int = 30,
    val refreshMode: LetterboxdFriendsRefreshMode = LetterboxdFriendsRefreshMode.OnLaunch,
    val refreshDelayMinutes: Int = 15,
) {
    /** Lists shown on Home, in order: enabled ones, minus "my lists" while signed out. */
    fun visibleLists(connected: Boolean): List<LetterboxdListConfig> = lists.filter { it.enabled && (connected || !it.mine) }

    companion object {
        val PostersPerRowRange = 5..100
    }
}

// ---------------------------------------------------------------------------------------------- cache / state

/** Disk cache of one list: the pages read so far (page 1 at each refresh, the next ones for "View all"). */
@Serializable
data class CachedLetterboxdList(
    val title: String? = null,
    val totalFilms: Int? = null,
    val entries: List<LetterboxdListEntry> = emptyList(),
    val pagesLoaded: Int = 0,
    val hasMore: Boolean = false,
    val fetchedAtEpochMs: Long = 0L,
)

@Serializable
internal data class LetterboxdListsCache(
    val version: Int = 1,
    val lists: Map<String, CachedLetterboxdList> = emptyMap(),
)

/** Why adding a list failed (besides Letterboxd errors). */
enum class LetterboxdListAddProblem { InvalidUrl, AlreadyAdded, Empty }

data class LetterboxdListsState(
    val isLoaded: Boolean = false,
    val settings: LetterboxdListsSettings = LetterboxdListsSettings(),
    val lists: Map<String, CachedLetterboxdList> = emptyMap(),
    val films: Map<String, LetterboxdFilm> = emptyMap(),
    /** Slugs that are not usable (unknown to Letterboxd, TV show, no TMDB movie id). */
    val excludedSlugs: Set<String> = emptySet(),
    /** Lists being read right now. */
    val refreshing: Set<String> = emptySet(),
    /** Last error per list, shown in its row. */
    val errors: Map<String, LetterboxdError> = emptyMap(),
    /** Slug being resolved after a click on a not-yet-resolved poster. */
    val opening: String? = null,
    val openError: LetterboxdError? = null,
    /** Settings: the connected member's lists (null = not loaded). */
    val myLists: List<LetterboxdListSummary>? = null,
    val myListsLoading: Boolean = false,
    val myListsError: LetterboxdError? = null,
    val adding: Boolean = false,
    val addError: LetterboxdError? = null,
    val addProblem: LetterboxdListAddProblem? = null,
)

/** One poster of a list row. [film] is null until the slug has been resolved (ids, poster). */
data class LetterboxdListRowEntry(
    val slug: String,
    val title: String,
    val year: Int?,
    val film: LetterboxdFilm?,
) {
    val posterUrl: String? get() = film?.posterUrl
}

/** Posters of the row of list [key] (pure): list order, unusable films removed, capped to the row size. */
internal fun buildLetterboxdListRow(state: LetterboxdListsState, key: String): List<LetterboxdListRowEntry> {
    val list = state.lists[key] ?: return emptyList()
    return list.entries.asSequence()
        .filter { it.slug !in state.excludedSlugs }
        .take(state.settings.postersPerRow.coerceIn(LetterboxdListsSettings.PostersPerRowRange))
        .map { entry ->
            val film = state.films[entry.slug]
            LetterboxdListRowEntry(entry.slug, film?.name ?: entry.title ?: entry.slug, film?.year ?: entry.year, film)
        }
        .toList()
}

/** True when "View all" shows more than the row: more usable entries than the row size, or unread pages. */
internal fun LetterboxdListsState.hasMoreThanRow(key: String): Boolean {
    val list = lists[key] ?: return false
    val usable = list.entries.count { it.slug !in excludedSlugs }
    return list.hasMore || usable > settings.postersPerRow.coerceIn(LetterboxdListsSettings.PostersPerRowRange)
}

/** A page of a list for the catalog screen ("View all"): films in list order and where the next page starts. */
internal data class LetterboxdListCatalogChunk(
    val films: List<LetterboxdFilm>,
    val consumed: Int,
    val nextSkip: Int?,
)

// ---------------------------------------------------------------------------------------------- data source

/** What the lists need from Letterboxd (swappable in tests). */
internal interface LetterboxdListsSource : LetterboxdFilmSlugSource {
    /** Connected member, null when no Letterboxd account is connected ("my lists" unavailable). */
    suspend fun username(): String?
    suspend fun listPage(ref: LetterboxdListRef, page: Int): LetterboxdListPage
    suspend fun memberListsPage(username: String, page: Int): Pair<List<LetterboxdListSummary>, Boolean>
}

internal class RepositoryLetterboxdListsSource : LetterboxdListsSource {
    override suspend fun username(): String? {
        LetterboxdRepository.awaitLoaded()
        if (!LetterboxdRepository.state.value.hasCredentials) return null
        return LetterboxdRepository.authenticated { username -> username ?: fetchSession().username }
    }

    override suspend fun listPage(ref: LetterboxdListRef, page: Int): LetterboxdListPage {
        LetterboxdRepository.awaitLoaded()
        return LetterboxdRepository.client.fetchListPage(ref, page)
    }

    override suspend fun memberListsPage(username: String, page: Int): Pair<List<LetterboxdListSummary>, Boolean> =
        LetterboxdRepository.authenticated { fetchMemberListsPage(username, page) }

    override suspend fun cachedFilm(slug: String): LetterboxdFilm? = RepositoryLetterboxdFilmSlugs.cachedFilm(slug)

    override suspend fun fetchFilm(slug: String): LetterboxdFilm? = RepositoryLetterboxdFilmSlugs.fetchFilm(slug)
}

// ---------------------------------------------------------------------------------------------- controller

/**
 * Letterboxd lists shown as Home rows (and "View all" catalogs). Same mechanics as the friends row
 * ([LetterboxdFriendsFeed]): disk cache shown at once, background refresh by [LetterboxdListsSettings.refreshMode],
 * manual refresh, ids resolved through the persistent film index then in small spaced batches.
 *
 * Network use (limits from [LetterboxdRateLimits], Settings › Letterboxd › Network / rate limits):
 * - a refresh reads page 1 (100 films) of each visible list, `listsPageGapMs` apart; later pages are read only
 *   by "View all" when the user scrolls;
 * - then at most `listsResolveBatch` film pages (rows in order), `listsResolveGapMs` apart; the rest at the next
 *   refresh, on click, or in "View all" (same batch per page);
 * - automatic refreshes never closer than `listsMinAutoRefreshSec`; a challenge / rate limit stops the job.
 * All network sequences of the feed are serialized, so a refresh and a "View all" page never add up.
 */
internal class LetterboxdListsFeed(
    private val source: LetterboxdListsSource,
    private val scope: CoroutineScope,
    private val loadCache: suspend () -> String?,
    private val saveCache: suspend (String) -> Unit,
    private val loadSettings: suspend () -> String?,
    private val saveSettings: suspend (String) -> Unit,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val limits: () -> LetterboxdRateLimits = { LetterboxdRateLimitSettings.current },
) {
    private val log = Logger.withTag("LetterboxdLists")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loadMutex = Mutex()
    private val networkMutex = Mutex()
    private val settingsMutex = Mutex()
    private val mutableState = MutableStateFlow(LetterboxdListsState())
    val state: StateFlow<LetterboxdListsState> = mutableState.asStateFlow()

    private var refreshedThisSession = false
    private var lastAutoAttemptAt = Long.MIN_VALUE / 2
    private var refreshJob: Job? = null

    suspend fun load() = loadMutex.withLock {
        if (mutableState.value.isLoaded) return@withLock
        LetterboxdRateLimitSettings.ensureLoaded()
        val settings = loadSettings()?.let { runCatching { json.decodeFromString(LetterboxdListsSettings.serializer(), it) }.getOrNull() }
            ?: LetterboxdListsSettings()
        val cache = loadCache()?.let { runCatching { json.decodeFromString(LetterboxdListsCache.serializer(), it) }.getOrNull() }
            ?: LetterboxdListsCache()
        val known = settings.lists.mapTo(HashSet()) { it.key }
        val lists = cache.lists.filterKeys { it in known }
        val films = cachedFilms(lists.values.flatMap { list -> list.entries.map { it.slug } }.distinct())
        mutableState.value = LetterboxdListsState(
            isLoaded = true,
            settings = settings,
            lists = lists,
            films = films.filterValues { it.isNuvioMovie },
            excludedSlugs = films.filterValues { !it.isNuvioMovie }.keys,
        )
    }

    /** Called each time the rows become visible (Home entered); refreshes according to the settings. */
    fun onRowsVisible(connected: Boolean) {
        scope.launch {
            load()
            val current = mutableState.value
            val visible = current.settings.visibleLists(connected)
            if (visible.isEmpty() || refreshJob?.isActive == true) return@launch
            val now = nowMs()
            if (now - lastAutoAttemptAt < limits().listsMinAutoRefreshSec * 1_000L) return@launch
            val oldest = visible.minOf { current.lists[it.key]?.fetchedAtEpochMs ?: 0L }
            val due = letterboxdRowRefreshDue(
                mode = current.settings.refreshMode,
                delayMinutes = current.settings.refreshDelayMinutes,
                nowMs = now,
                fetchedAtEpochMs = oldest,
                refreshedThisSession = refreshedThisSession,
            ) || visible.any { it.key !in current.lists }
            if (due) {
                lastAutoAttemptAt = now
                refresh(connected)
            }
        }
    }

    /** Manual or automatic refresh of the visible lists (or only [onlyKey]); no-op while one is running. */
    fun refresh(connected: Boolean, onlyKey: String? = null): Job? {
        if (refreshJob?.isActive == true) return refreshJob
        return scope.launch { refreshNow(connected, onlyKey) }.also { refreshJob = it }
    }

    internal suspend fun refreshNow(connected: Boolean, onlyKey: String? = null) {
        load()
        refreshedThisSession = true
        val targets = mutableState.value.settings.visibleLists(connected).filter { onlyKey == null || it.key == onlyKey }
        if (targets.isEmpty()) return
        networkMutex.withLock {
            var stopped = false
            for ((index, config) in targets.withIndex()) {
                if (stopped) break
                if (index > 0) pause(limits().listsPageGapMs.toLong())
                val error = readFirstPage(config)
                if (error != null && error.stopsBulkRequests()) stopped = true
            }
            persist()
            if (!stopped) resolveRows(targets)
        }
    }

    /** Reads page 1 of a list into the cache; returns the error (also stored for the row), null on success. */
    private suspend fun readFirstPage(config: LetterboxdListConfig): LetterboxdError? {
        val key = config.key
        mutableState.update { it.copy(refreshing = it.refreshing + key) }
        return try {
            val page = source.listPage(config.ref, 1)
            storeFirstPage(key, page)
            mutableState.update { it.copy(refreshing = it.refreshing - key, errors = it.errors - key) }
            null
        } catch (error: CancellationException) {
            mutableState.update { it.copy(refreshing = it.refreshing - key) }
            throw error
        } catch (error: Exception) {
            val letterboxdError = (error as? LetterboxdException)?.error ?: LetterboxdError.Network
            log.w { "List refresh failed: $letterboxdError" }
            mutableState.update { it.copy(refreshing = it.refreshing - key, errors = it.errors + (key to letterboxdError)) }
            letterboxdError
        }
    }

    private suspend fun storeFirstPage(key: String, page: LetterboxdListPage) {
        val films = cachedFilms(page.entries.map { it.slug }.filter { it !in mutableState.value.films })
        mutableState.update { state ->
            state.copy(
                lists = state.lists + (
                    key to CachedLetterboxdList(
                        title = page.title,
                        totalFilms = page.totalFilms,
                        entries = page.entries,
                        pagesLoaded = 1,
                        hasMore = page.hasNext,
                        fetchedAtEpochMs = nowMs(),
                    )
                    ),
                films = state.films + films.filterValues { it.isNuvioMovie },
                excludedSlugs = state.excludedSlugs + films.filterValues { !it.isNuvioMovie }.keys,
            )
        }
    }

    /** Resolves (with the network) the first unresolved posters of the rows, rows in order, in one spaced batch. */
    private suspend fun resolveRows(targets: List<LetterboxdListConfig>) {
        var fetched = 0
        for (config in targets) {
            while (fetched < limits().listsResolveBatch) {
                val state = mutableState.value
                val next = buildLetterboxdListRow(state, config.key).firstOrNull { it.film == null } ?: break
                if (fetched > 0) pause(limits().listsResolveGapMs.toLong())
                fetched++
                try {
                    applyResolved(next.slug, source.fetchFilm(next.slug))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LetterboxdException) {
                    mutableState.update { it.copy(errors = it.errors + (config.key to error.error)) }
                    if (error.error.stopsBulkRequests()) return
                    break
                }
            }
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
     * (unknown, TV show, no TMDB/IMDb id); the reason is in [LetterboxdListsState.openError].
     */
    suspend fun filmForOpen(slug: String): LetterboxdFilm? {
        mutableState.value.films[slug]?.let { return it.takeIf(LetterboxdFriendsFeed::isOpenable) }
        mutableState.update { it.copy(opening = slug, openError = null) }
        return try {
            val film = source.cachedFilm(slug) ?: source.fetchFilm(slug)
            applyResolved(slug, film)
            val openable = film?.takeIf(LetterboxdFriendsFeed::isOpenable)
            mutableState.update { it.copy(opening = null, openError = if (openable == null) LetterboxdError.NotFound else null) }
            openable
        } catch (error: CancellationException) {
            mutableState.update { it.copy(opening = null) }
            throw error
        } catch (error: LetterboxdException) {
            mutableState.update { it.copy(opening = null, openError = error.error) }
            null
        }
    }

    // ------------------------------------------------------------------ "View all" (catalog screen)

    /**
     * Next films of list [key] from position [skip] for the catalog screen. Reads further list pages when needed
     * and resolves at most `listsResolveBatch` unknown films; stops before the next unknown one when the batch is
     * used up (the following page continues from there). Throws [LetterboxdException] when nothing could be read.
     */
    suspend fun catalogChunk(key: String, skip: Int, pageSize: Int = CATALOG_CHUNK_SIZE): LetterboxdListCatalogChunk {
        load()
        val config = mutableState.value.settings.lists.firstOrNull { it.key == key }
            ?: throw LetterboxdException(LetterboxdError.NotFound)
        return networkMutex.withLock {
            if (mutableState.value.lists[key] == null) {
                val page = source.listPage(config.ref, 1)
                storeFirstPage(key, page)
                persist()
            }
            val films = mutableListOf<LetterboxdFilm>()
            var index = skip
            var consumed = 0
            var resolved = 0
            var networkUsed = false
            try {
                while (consumed < pageSize) {
                    var list = mutableState.value.lists[key] ?: break
                    if (index >= list.entries.size) {
                        if (!list.hasMore) break
                        if (networkUsed) pause(limits().listsPageGapMs.toLong())
                        networkUsed = true
                        appendNextPage(key, config, list)
                        list = mutableState.value.lists[key] ?: break
                        if (index >= list.entries.size) break
                    }
                    val slug = list.entries[index].slug
                    val state = mutableState.value
                    if (slug !in state.excludedSlugs && state.films[slug] == null) {
                        val cached = source.cachedFilm(slug)
                        if (cached != null) {
                            applyResolved(slug, cached)
                        } else {
                            if (resolved >= limits().listsResolveBatch) break
                            if (networkUsed) pause(limits().listsResolveGapMs.toLong())
                            networkUsed = true
                            resolved++
                            applyResolved(slug, source.fetchFilm(slug))
                        }
                    }
                    mutableState.value.films[slug]?.let(films::add)
                    index++
                    consumed++
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LetterboxdException) {
                log.w { "List page failed: ${error.error}" }
                mutableState.update { it.copy(errors = it.errors + (key to error.error)) }
                if (consumed == 0) throw error
                // Keep what was read; the next page retries from here.
            }
            val list = mutableState.value.lists[key]
            val more = list != null && (index < list.entries.size || list.hasMore)
            LetterboxdListCatalogChunk(films, consumed, if (more) index else null)
        }
    }

    private suspend fun appendNextPage(key: String, config: LetterboxdListConfig, list: CachedLetterboxdList) {
        val page = source.listPage(config.ref, list.pagesLoaded + 1)
        val known = list.entries.mapTo(HashSet()) { it.slug }
        val added = page.entries.filter { it.slug !in known }
        val films = cachedFilms(added.map { it.slug }.filter { it !in mutableState.value.films })
        mutableState.update { state ->
            val current = state.lists[key] ?: list
            state.copy(
                lists = state.lists + (
                    key to current.copy(
                        entries = current.entries + added,
                        pagesLoaded = current.pagesLoaded + 1,
                        hasMore = page.hasNext && added.isNotEmpty(),
                    )
                    ),
                films = state.films + films.filterValues { it.isNuvioMovie },
                excludedSlugs = state.excludedSlugs + films.filterValues { !it.isNuvioMovie }.keys,
            )
        }
        persist()
    }

    // ------------------------------------------------------------------ settings

    /** Loads the connected member's lists for the settings picker. */
    fun loadMyLists() {
        scope.launch {
            mutableState.update { it.copy(myListsLoading = true, myListsError = null) }
            try {
                val user = source.username() ?: throw LetterboxdException(LetterboxdError.NotConnected)
                val all = networkMutex.withLock {
                    val collected = mutableListOf<LetterboxdListSummary>()
                    for (page in 1..MAX_MEMBER_LIST_PAGES) {
                        if (page > 1) pause(limits().listsPageGapMs.toLong())
                        val (lists, hasNext) = source.memberListsPage(user, page)
                        collected += lists
                        if (!hasNext) break
                    }
                    collected.distinctBy { it.ref.key }
                }
                mutableState.update { it.copy(myLists = all, myListsLoading = false) }
            } catch (error: CancellationException) {
                mutableState.update { it.copy(myListsLoading = false) }
                throw error
            } catch (error: Exception) {
                val letterboxdError = (error as? LetterboxdException)?.error ?: LetterboxdError.Network
                mutableState.update { it.copy(myListsLoading = false, myListsError = letterboxdError) }
            }
        }
    }

    /** Adds a list by address: reads its first page (checks it exists, gets its name), then resolves a few films. */
    suspend fun addByUrl(input: String): Boolean {
        load()
        mutableState.update { it.copy(addProblem = null, addError = null) }
        val ref = parseLetterboxdListUrl(input)
        if (ref == null) {
            mutableState.update { it.copy(addProblem = LetterboxdListAddProblem.InvalidUrl) }
            return false
        }
        val user = runCatching { source.username() }.getOrNull()
        return add(ref, title = null, mine = user != null && user.equals(ref.owner, ignoreCase = true))
    }

    /** Adds one of the connected member's lists. */
    suspend fun addMine(summary: LetterboxdListSummary): Boolean {
        load()
        mutableState.update { it.copy(addProblem = null, addError = null) }
        return add(summary.ref, summary.title, mine = true)
    }

    private suspend fun add(ref: LetterboxdListRef, title: String?, mine: Boolean): Boolean {
        if (mutableState.value.settings.lists.any { it.key == ref.key }) {
            mutableState.update { it.copy(addProblem = LetterboxdListAddProblem.AlreadyAdded) }
            return false
        }
        mutableState.update { it.copy(adding = true) }
        val page = try {
            networkMutex.withLock { source.listPage(ref, 1) }
        } catch (error: CancellationException) {
            mutableState.update { it.copy(adding = false) }
            throw error
        } catch (error: Exception) {
            val letterboxdError = (error as? LetterboxdException)?.error ?: LetterboxdError.Network
            mutableState.update { it.copy(adding = false, addError = letterboxdError) }
            return false
        }
        if (page.entries.isEmpty() && (page.totalFilms ?: 0) > 0) {
            // Films only: a list holding nothing Nuvio can show.
            mutableState.update { it.copy(adding = false, addProblem = LetterboxdListAddProblem.Empty) }
            return false
        }
        val config = LetterboxdListConfig(ref.owner, ref.slug, title ?: page.title ?: ref.slug, mine = mine)
        storeFirstPage(ref.key, page)
        mutableState.update { it.copy(adding = false, errors = it.errors - ref.key) }
        updateSettingsNow { it.copy(lists = it.lists + config) }
        persist()
        // A few posters at once; the rest follows the usual refreshes.
        scope.launch { networkMutex.withLock { resolveRows(listOf(config)) } }
        return true
    }

    fun remove(key: String) = updateSettings { settings -> settings.copy(lists = settings.lists.filterNot { it.key == key }) }

    fun setEnabled(key: String, enabled: Boolean) = updateSettings { settings ->
        settings.copy(lists = settings.lists.map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    /** Moves a list up ([delta] < 0) or down in the order of the rows. */
    fun move(key: String, delta: Int) = updateSettings { settings ->
        val from = settings.lists.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in settings.lists.indices) return@updateSettings settings
        val reordered = settings.lists.toMutableList().apply { add(to, removeAt(from)) }
        settings.copy(lists = reordered)
    }

    fun updateSettings(transform: (LetterboxdListsSettings) -> LetterboxdListsSettings) {
        scope.launch { updateSettingsNow(transform) }
    }

    fun clearAddFeedback() = mutableState.update { it.copy(addProblem = null, addError = null) }

    private suspend fun updateSettingsNow(transform: (LetterboxdListsSettings) -> LetterboxdListsSettings) {
        load()
        settingsMutex.withLock {
            val before = mutableState.value.settings
            val after = transform(before)
            if (after == before) return
            val keys = after.lists.mapTo(HashSet()) { it.key }
            mutableState.update { state ->
                state.copy(
                    settings = after,
                    lists = state.lists.filterKeys { it in keys },
                    errors = state.errors.filterKeys { it in keys },
                )
            }
            runCatching { saveSettings(json.encodeToString(LetterboxdListsSettings.serializer(), after)) }
                .onFailure { log.w { "Could not save lists settings: ${it::class.simpleName}" } }
            if (after.lists.size < before.lists.size) persist()
        }
    }

    private suspend fun cachedFilms(slugs: List<String>): Map<String, LetterboxdFilm> =
        slugs.mapNotNull { slug -> source.cachedFilm(slug)?.let { slug to it } }.toMap()

    private suspend fun persist() {
        val cache = LetterboxdListsCache(lists = mutableState.value.lists)
        runCatching { saveCache(json.encodeToString(LetterboxdListsCache.serializer(), cache)) }
            .onFailure { log.w { "Could not write lists cache: ${it::class.simpleName}" } }
    }

    companion object {
        const val CATALOG_CHUNK_SIZE = 40
        private const val MAX_MEMBER_LIST_PAGES = 10
    }
}

/** App-wide lists instance (disk cache in the Letterboxd cache folder, settings with the other Letterboxd settings). */
internal object LetterboxdLists {
    private const val CACHE_FILE = "lists.json"
    private const val SETTINGS_KEY = "lists_rows"

    val feed: LetterboxdListsFeed by lazy {
        LetterboxdListsFeed(
            source = RepositoryLetterboxdListsSource(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            loadCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.readCache(CACHE_FILE) } },
            saveCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.writeCache(CACHE_FILE, it) } },
            loadSettings = { withContext(Dispatchers.Default) { LetterboxdPlatform.readPreference(SETTINGS_KEY) } },
            saveSettings = { withContext(Dispatchers.Default) { LetterboxdPlatform.writePreference(SETTINGS_KEY, it) } },
        )
    }
}
