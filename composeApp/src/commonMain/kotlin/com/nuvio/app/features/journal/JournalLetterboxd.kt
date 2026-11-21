package com.nuvio.app.features.journal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.letterboxd.LetterboxdDiaryEntry
import com.nuvio.app.features.letterboxd.LetterboxdError
import com.nuvio.app.features.letterboxd.LetterboxdException
import com.nuvio.app.features.letterboxd.LetterboxdFilm
import com.nuvio.app.features.letterboxd.LetterboxdPlatform
import com.nuvio.app.features.letterboxd.LetterboxdRateLimitSettings
import com.nuvio.app.features.letterboxd.LetterboxdRateLimits
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.LetterboxdWatchedFilmsPage
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
import kotlinx.serialization.json.Json

/** A film the member watched on Letterboxd (watched films page and/or diary RSS). */
@Serializable
data class JournalLetterboxdFilm(
    val slug: String,
    val title: String? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val poster: String? = null,
    val rating: Double? = null,
    val liked: Boolean = false,
    /** Diary dates (`yyyy-MM-dd`), newest first; empty for films logged without a date. */
    val diaryDates: List<String> = emptyList(),
    val isTv: Boolean = false,
    /** The film page couldn't be found: don't look it up again. */
    val unresolvable: Boolean = false,
)

@Serializable
internal data class JournalLetterboxdSnapshot(
    val username: String? = null,
    val fetchedAt: Long = 0L,
    val films: List<JournalLetterboxdFilm> = emptyList(),
    /** Next watched-films page to read when the previous refresh stopped before the end (page limit / error). */
    val nextPage: Int? = null,
)

data class JournalLetterboxdState(
    val connected: Boolean = false,
    val username: String? = null,
    val films: List<JournalLetterboxdFilm> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastRefreshAt: Long = 0L,
    val error: LetterboxdError? = null,
    /** More watched-films pages remain (read by the next refreshes). */
    val incomplete: Boolean = false,
    val unresolvedCount: Int = 0,
)

/** Letterboxd calls of the journal (interface so the refresh logic can be tested against a fake). */
internal interface JournalLetterboxdRemote {
    /** True when Letterboxd cookies are saved. */
    suspend fun hasCredentials(): Boolean
    suspend fun username(): String?
    suspend fun diary(username: String): List<LetterboxdDiaryEntry>
    suspend fun watchedPage(username: String, page: Int): LetterboxdWatchedFilmsPage
    /** Cached film page (persistent index), no network. */
    suspend fun cachedFilm(slug: String): LetterboxdFilm?
    /** Film page (one request); null when Letterboxd doesn't know the slug. */
    suspend fun fetchFilm(slug: String): LetterboxdFilm?
}

internal class RepositoryJournalLetterboxdRemote : JournalLetterboxdRemote {
    override suspend fun hasCredentials(): Boolean {
        LetterboxdRepository.awaitLoaded()
        return LetterboxdRepository.isSupported && LetterboxdRepository.state.value.hasCredentials
    }

    override suspend fun username(): String? =
        LetterboxdRepository.authenticated { username -> username ?: fetchSession().username }

    override suspend fun diary(username: String): List<LetterboxdDiaryEntry> =
        LetterboxdRepository.client.fetchDiaryRss(username)

    override suspend fun watchedPage(username: String, page: Int): LetterboxdWatchedFilmsPage =
        LetterboxdRepository.authenticated { fetchWatchedFilmsPage(username, page) }

    override suspend fun cachedFilm(slug: String): LetterboxdFilm? = LetterboxdRepository.cachedFilm(slug)

    override suspend fun fetchFilm(slug: String): LetterboxdFilm? = try {
        LetterboxdRepository.film(slug, maxAgeMs = Long.MAX_VALUE)
    } catch (error: LetterboxdException) {
        if (error.error == LetterboxdError.NotFound) null else throw error
    }
}

/**
 * Letterboxd side of the Journal: the member's watched films (`/<user>/films/`, 72 per page, rating + like) and
 * diary dates (public RSS, ≈50 latest entries with TMDB ids). The HTML diary (`/films/diary/`) and the JSON
 * log-entries API answer with a Cloudflare challenge (Nov 2026), so older diary dates aren't available.
 *
 * Network use is bounded by [LetterboxdRateLimits] (Settings › Letterboxd › Network / rate limits): every read
 * `journalPageGapMs` apart, at most `journalMaxPages` watched-films pages per refresh (the next refresh continues
 * where this one stopped), at most `journalResolveBatch` film pages (slug → TMDB/IMDb ids) per refresh
 * `journalResolveGapMs` apart, automatic refreshes at most every `journalMinRefreshMin`. A challenge, a rate limit or
 * an expired session stops the refresh with a visible error; the disk cache is shown meanwhile.
 */
internal class JournalLetterboxdSource(
    private val remote: JournalLetterboxdRemote,
    private val scope: CoroutineScope,
    private val loadCache: suspend () -> String?,
    private val saveCache: suspend (String) -> Unit,
    private val limits: () -> LetterboxdRateLimits = { LetterboxdRateLimitSettings.current },
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val log = Logger.withTag("JournalLetterboxd")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(JournalLetterboxdState())
    val state: StateFlow<JournalLetterboxdState> = mutableState.asStateFlow()

    private var snapshot = JournalLetterboxdSnapshot()
    private var cacheLoaded = false
    private var job: Job? = null
    private var lastRequestAt = 0L
    private var lastAttemptAt = 0L

    /** Shows the cache, then refreshes in the background when [force] or when the cache is old enough. */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch { runRefresh(force) }
    }

    internal suspend fun runRefresh(force: Boolean) = mutex.withLock {
        ensureCacheLoaded()
        if (!remote.hasCredentials()) {
            mutableState.value = JournalLetterboxdState(connected = false)
            return@withLock
        }
        val now = nowMs()
        val minGap = limits().journalMinRefreshMin * 60_000L
        publish(connected = true, refreshing = false)
        val lastRun = maxOf(snapshot.fetchedAt, lastAttemptAt)
        if (!force && lastRun > 0L && now - lastRun < minGap) return@withLock
        lastAttemptAt = now
        mutableState.update { it.copy(isRefreshing = true, error = null) }
        var error: LetterboxdError? = null
        try {
            val username = remote.username() ?: throw LetterboxdException(LetterboxdError.NotConnected)
            if (snapshot.username != null && snapshot.username != username) snapshot = JournalLetterboxdSnapshot()
            error = refreshFrom(username)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: LetterboxdException) {
            error = failure.error
        } catch (failure: Exception) {
            log.w { "Journal Letterboxd refresh failed: ${failure::class.simpleName}" }
            error = LetterboxdError.Network
        }
        if (error != null) log.w { "Journal Letterboxd refresh stopped: $error" }
        persist()
        publish(connected = true, refreshing = false, error = error)
    }

    /** Film of [slug] with its ids, fetching its page if needed (one request, for a click). */
    suspend fun filmForOpen(slug: String): JournalLetterboxdFilm? {
        val known = snapshot.films.firstOrNull { it.slug == slug }
        if (known != null && (known.tmdbId != null || known.imdbId != null)) return known
        val film = runCatching { remote.cachedFilm(slug) ?: remote.fetchFilm(slug) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return null
        val merged = (known ?: JournalLetterboxdFilm(slug = slug)).withFilm(film)
        mutex.withLock {
            snapshot = snapshot.copy(films = snapshot.films.map { if (it.slug == slug) merged else it })
            persist()
            publish(connected = mutableState.value.connected, refreshing = mutableState.value.isRefreshing, error = mutableState.value.error)
        }
        return merged
    }

    private suspend fun refreshFrom(username: String): LetterboxdError? {
        val limits = limits()
        val diary = paced(limits.journalPageGapMs) { remote.diary(username) }

        // Watched films: page 1 (latest changes), then continue where the previous refresh stopped.
        val read = LinkedHashMap<String, JournalLetterboxdFilm>()
        var error: LetterboxdError? = null
        var nextPage: Int? = null
        var fullPass = false
        try {
            val first = paced(limits.journalPageGapMs) { remote.watchedPage(username, 1) }
            first.items.forEach { read[it.slug] = it.toJournalFilm() }
            if (first.hasNext) {
                val start = snapshot.nextPage?.takeIf { it > 2 } ?: 2
                var page: Int? = start
                var budget = limits.journalMaxPages - 1
                while (page != null && budget > 0) {
                    val result = try {
                        paced(limits.journalPageGapMs) { remote.watchedPage(username, page!!) }
                    } catch (failure: LetterboxdException) {
                        // A page past the end can be a 404: that's the end of the list.
                        if (failure.error == LetterboxdError.NotFound) LetterboxdWatchedFilmsPage(emptyList(), false) else throw failure
                    }
                    result.items.forEach { read[it.slug] = it.toJournalFilm() }
                    budget--
                    page = if (result.hasNext) page + 1 else null
                }
                nextPage = page
                fullPass = start == 2 && page == null
            } else {
                fullPass = true
            }
        } catch (failure: LetterboxdException) {
            error = failure.error
            nextPage = snapshot.nextPage
        }

        val previous = snapshot.films.associateBy { it.slug }
        val base: Map<String, JournalLetterboxdFilm> = if (fullPass && error == null) {
            read.mapValues { (slug, film) -> previous[slug]?.let(film::keepIdsFrom) ?: film }
        } else {
            val merged = LinkedHashMap(previous)
            read.forEach { (slug, film) -> merged[slug] = previous[slug]?.let(film::keepIdsFrom) ?: film }
            merged
        }
        val films = mergeDiary(base, diary)
        snapshot = snapshot.copy(
            username = username,
            fetchedAt = if (error == null) nowMs() else snapshot.fetchedAt,
            films = films,
            nextPage = nextPage,
        )
        if (error != null) return error
        return resolveIds(limits)
    }

    /** Fills TMDB/IMDb ids: persistent film index first (no network), then a few film pages. */
    private suspend fun resolveIds(limits: LetterboxdRateLimits): LetterboxdError? {
        val films = snapshot.films.toMutableList()
        for (index in films.indices) {
            val film = films[index]
            if (film.imdbId != null && film.tmdbId != null) continue
            remote.cachedFilm(film.slug)?.let { films[index] = film.withFilm(it) }
        }
        var budget = limits.journalResolveBatch
        var error: LetterboxdError? = null
        for (index in films.indices) {
            if (budget <= 0) break
            val film = films[index]
            if (film.tmdbId != null || film.unresolvable || film.isTv) continue
            budget--
            try {
                val page = paced(limits.journalResolveGapMs) { remote.fetchFilm(film.slug) }
                films[index] = if (page == null) film.copy(unresolvable = true) else film.withFilm(page)
            } catch (failure: LetterboxdException) {
                error = failure.error
                break
            }
        }
        snapshot = snapshot.copy(films = films)
        return error
    }

    private suspend fun <T> paced(gapMs: Int, block: suspend () -> T): T {
        val wait = lastRequestAt + gapMs - nowMs()
        if (lastRequestAt > 0L && wait > 0L) sleep(wait)
        try {
            return block()
        } finally {
            lastRequestAt = nowMs()
        }
    }

    private suspend fun ensureCacheLoaded() {
        if (cacheLoaded) return
        cacheLoaded = true
        snapshot = runCatching { loadCache() }.getOrNull()
            ?.let { runCatching { json.decodeFromString(JournalLetterboxdSnapshot.serializer(), it) }.getOrNull() }
            ?: JournalLetterboxdSnapshot()
    }

    private suspend fun persist() {
        runCatching { saveCache(json.encodeToString(JournalLetterboxdSnapshot.serializer(), snapshot)) }
            .onFailure { log.w { "Could not save the journal Letterboxd cache: ${it::class.simpleName}" } }
    }

    private fun publish(connected: Boolean, refreshing: Boolean, error: LetterboxdError? = mutableState.value.error) {
        mutableState.value = JournalLetterboxdState(
            connected = connected,
            username = snapshot.username,
            films = snapshot.films,
            isRefreshing = refreshing,
            lastRefreshAt = snapshot.fetchedAt,
            error = error,
            incomplete = snapshot.nextPage != null,
            unresolvedCount = snapshot.films.count { it.tmdbId == null && !it.unresolvable && !it.isTv },
        )
    }

    companion object {
        const val CACHE_NAME = "journal_letterboxd.json"

        val default: JournalLetterboxdSource by lazy {
            JournalLetterboxdSource(
                remote = RepositoryJournalLetterboxdRemote(),
                scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
                loadCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.readCache(CACHE_NAME) } },
                saveCache = { withContext(Dispatchers.Default) { LetterboxdPlatform.writeCache(CACHE_NAME, it) } },
            )
        }
    }
}

private fun com.nuvio.app.features.letterboxd.LetterboxdWatchedFilm.toJournalFilm() =
    JournalLetterboxdFilm(slug = slug, title = title, year = year, rating = rating, liked = liked)

/** Fresh page data (rating, like, title) + ids / poster / diary already known. */
private fun JournalLetterboxdFilm.keepIdsFrom(old: JournalLetterboxdFilm) = copy(
    tmdbId = tmdbId ?: old.tmdbId,
    imdbId = imdbId ?: old.imdbId,
    poster = poster ?: old.poster,
    isTv = isTv || old.isTv,
    unresolvable = unresolvable || old.unresolvable,
    title = title ?: old.title,
    year = year ?: old.year,
)

private fun JournalLetterboxdFilm.withFilm(film: LetterboxdFilm) = copy(
    tmdbId = tmdbId ?: film.tmdbId,
    imdbId = imdbId ?: film.imdbId,
    poster = poster ?: film.posterUrl,
    title = title ?: film.name,
    year = year ?: film.year,
    isTv = isTv || film.isTvShow,
)

/** Adds the RSS diary (dates, TMDB ids, posters) to the watched films, by slug. */
internal fun mergeDiary(films: Map<String, JournalLetterboxdFilm>, diary: List<LetterboxdDiaryEntry>): List<JournalLetterboxdFilm> {
    val merged = LinkedHashMap(films)
    diary.filter { it.filmSlug != null }.groupBy { it.filmSlug!! }.forEach { (slug, entries) ->
        val latest = entries.maxBy { it.watchedDate.orEmpty() }
        val current = merged[slug] ?: JournalLetterboxdFilm(slug = slug, title = latest.filmTitle, year = latest.filmYear)
        val dates = (entries.mapNotNull { it.watchedDate } + current.diaryDates).distinct().sortedDescending()
        merged[slug] = current.copy(
            title = current.title ?: latest.filmTitle,
            year = current.year ?: latest.filmYear,
            tmdbId = current.tmdbId ?: entries.firstNotNullOfOrNull { it.tmdbId },
            poster = current.poster ?: entries.firstNotNullOfOrNull { it.posterUrl },
            rating = current.rating ?: latest.rating,
            liked = current.liked || entries.any { it.liked },
            diaryDates = dates,
        )
    }
    return merged.values.toList()
}
