package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Data + actions needed by the film detail block (F1). Swappable in tests. */
internal interface LetterboxdFilmSource {
    val account: StateFlow<LetterboxdAccountState>
    /** By TMDB id, or by IMDb id when the TMDB id is unknown. */
    suspend fun resolveFilm(tmdbId: Int?, imdbId: String?): LetterboxdFilm?
    suspend fun histogram(film: LetterboxdFilm): LetterboxdRatingHistogram?
    suspend fun myState(film: LetterboxdFilm): LetterboxdFilmState
    suspend fun logEntry(id: String): LetterboxdLogEntry
    suspend fun friends(film: LetterboxdFilm): List<LetterboxdFriendActivity>
    suspend fun setWatched(film: LetterboxdFilm, watched: Boolean)
    suspend fun setLiked(film: LetterboxdFilm, liked: Boolean)
    suspend fun setRating(film: LetterboxdFilm, rating: Double?)
    suspend fun createLogEntry(film: LetterboxdFilm, draft: LetterboxdLogEntryDraft)
    suspend fun updateLogEntry(film: LetterboxdFilm, id: String, draft: LetterboxdLogEntryDraft)
}

/**
 * [LetterboxdFilmSource] over a [LetterboxdClient]. The histogram (public, changes slowly) is cached in memory;
 * film details come from the persistent index of [LetterboxdRepository] through [resolve].
 */
internal class ClientLetterboxdFilmSource(
    private val client: LetterboxdClient,
    override val account: StateFlow<LetterboxdAccountState>,
    private val resolve: suspend (Int) -> LetterboxdFilm?,
    private val resolveImdb: suspend (String) -> LetterboxdFilm? = { null },
    private val requireCredentials: suspend () -> Unit = {},
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val histogramTtlMs: Long = 6L * 60 * 60 * 1000,
    private val personalTtlMs: Long = 90_000L,
) : LetterboxdFilmSource {
    private val histogramCache = LinkedHashMap<String, Pair<Long, LetterboxdRatingHistogram?>>()
    private val cacheLock = Mutex()

    override suspend fun resolveFilm(tmdbId: Int?, imdbId: String?): LetterboxdFilm? = when {
        tmdbId != null -> resolve(tmdbId) ?: imdbId?.let { resolveImdb(it) }
        imdbId != null -> resolveImdb(imdbId)
        else -> null
    }

    override suspend fun histogram(film: LetterboxdFilm): LetterboxdRatingHistogram? {
        cacheLock.withLock {
            histogramCache[film.slug]?.takeIf { nowMs() - it.first < histogramTtlMs }?.let { return it.second }
        }
        val histogram = client.fetchRatingHistogram(film.slug)
        cacheLock.withLock {
            histogramCache[film.slug] = nowMs() to histogram
            while (histogramCache.size > 200) histogramCache.remove(histogramCache.keys.first())
        }
        return histogram
    }

    // Personal reads are cached briefly: the block is recomposed when scrolled back into view and Cloudflare
    // challenges bursts of requests. Any write clears the cache so the state re-read after it is fresh.
    override suspend fun myState(film: LetterboxdFilm) = cached("me:${film.slug}") { client.fetchMyFilmState(film) }
    override suspend fun logEntry(id: String) = cached("entry:$id") { client.fetchLogEntry(id) }
    override suspend fun friends(film: LetterboxdFilm) = cached("friends:${film.slug}") { client.fetchFriendActivity(film) }
    override suspend fun setWatched(film: LetterboxdFilm, watched: Boolean) = write { client.setWatched(film, watched); Unit }
    override suspend fun setLiked(film: LetterboxdFilm, liked: Boolean) = write { client.setLiked(film, liked); Unit }
    override suspend fun setRating(film: LetterboxdFilm, rating: Double?) = write { client.setRating(film, rating) }
    override suspend fun createLogEntry(film: LetterboxdFilm, draft: LetterboxdLogEntryDraft) =
        write { client.createLogEntry(film, draft); Unit }
    override suspend fun updateLogEntry(film: LetterboxdFilm, id: String, draft: LetterboxdLogEntryDraft) =
        write { client.updateLogEntry(film, id, draft) }

    private val personalCache = LinkedHashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, block: suspend () -> T): T {
        cacheLock.withLock {
            personalCache[key]?.takeIf { nowMs() - it.first < personalTtlMs }?.let { return it.second as T }
        }
        val value = auth(block)
        cacheLock.withLock {
            personalCache[key] = nowMs() to value
            while (personalCache.size > 100) personalCache.remove(personalCache.keys.first())
        }
        return value
    }

    private suspend fun <T> write(block: suspend () -> T): T {
        cacheLock.withLock { personalCache.clear() }
        return try {
            auth(block)
        } finally {
            cacheLock.withLock { personalCache.clear() }
        }
    }

    private suspend fun <T> auth(block: suspend () -> T): T {
        requireCredentials()
        return block()
    }

    companion object {
        /** Production source, backed by [LetterboxdRepository]. */
        val default: LetterboxdFilmSource by lazy {
            ClientLetterboxdFilmSource(
                client = LetterboxdRepository.client,
                account = LetterboxdRepository.state,
                resolve = { LetterboxdRepository.resolveFilmByTmdb(it) },
                resolveImdb = { LetterboxdRepository.resolveFilmByImdb(it) },
                requireCredentials = { LetterboxdRepository.authenticated { } },
            )
        }
    }
}

internal data class LetterboxdFilmPanelState(
    val phase: Phase = Phase.Idle,
    /** Why [Phase.Failed]. */
    val error: LetterboxdError? = null,
    val film: LetterboxdFilm? = null,
    val histogram: LetterboxdRatingHistogram? = null,
    val connection: Connection = Connection.NotConnected,
    val personal: PersonalPhase = PersonalPhase.Idle,
    val personalError: LetterboxdError? = null,
    val myState: LetterboxdFilmState? = null,
    /** The member's most recent review of the film. */
    val review: LetterboxdLogEntry? = null,
    val friends: List<LetterboxdFriendActivity> = emptyList(),
    val friendsError: LetterboxdError? = null,
    val busy: Action? = null,
    val actionError: LetterboxdError? = null,
    /** Letterboxd's own message for a refused write (English, from the server). */
    val actionErrorDetail: String? = null,
) {
    enum class Phase { Idle, Loading, NotOnLetterboxd, Ready, Failed }
    enum class Connection { NotConnected, ReconnectRequired, Connected }
    enum class PersonalPhase { Idle, Loading, Ready, Failed }
    enum class Action { Watched, Like, Rating, Review }
}

/**
 * State holder of the Letterboxd block on a film's detail page: resolves the film, loads public data
 * (average, histogram) and, when an account is connected, the member's state, latest review and friends' activity.
 * Every write re-reads the member's state afterwards (the source of truth), and [onBecameWatched] runs when
 * a write turned the film from "not watched" into "watched" (Letterboxd then drops it from the watchlist).
 */
internal class LetterboxdFilmPanelController(
    private val source: LetterboxdFilmSource,
    private val scope: CoroutineScope,
    private val onBecameWatched: suspend () -> Unit = {},
) {
    private val log = Logger.withTag("LetterboxdFilm")
    private val mutableState = MutableStateFlow(LetterboxdFilmPanelState())
    val state: StateFlow<LetterboxdFilmPanelState> = mutableState.asStateFlow()

    private var tmdbId: Int? = null
    private var imdbId: String? = null
    private var loadJob: Job? = null
    private var personalJob: Job? = null
    private var accountJob: Job? = null

    /** Both ids null = the film can't be matched (shown as "not on Letterboxd"). [imdbId] is the fallback. */
    fun load(tmdbId: Int?, imdbId: String? = null) {
        this.tmdbId = tmdbId
        this.imdbId = imdbId?.takeIf { Regex("tt\\d+").matches(it) }
        loadJob?.cancel()
        personalJob?.cancel()
        mutableState.value = LetterboxdFilmPanelState(phase = LetterboxdFilmPanelState.Phase.Loading, connection = connection())
        if (accountJob == null) {
            // Reload the personal part when the user connects / reconnects from the settings.
            accountJob = scope.launch {
                source.account
                    .map(::connectionOf)
                    .distinctUntilChanged()
                    .collect { connection ->
                        if (connection == mutableState.value.connection) return@collect
                        mutableState.update { it.copy(connection = connection) }
                        if (mutableState.value.phase == LetterboxdFilmPanelState.Phase.Ready) loadPersonal()
                    }
            }
        }
        loadJob = scope.launch {
            val imdbId = this@LetterboxdFilmPanelController.imdbId
            if (tmdbId == null && imdbId == null) {
                mutableState.update { it.copy(phase = LetterboxdFilmPanelState.Phase.NotOnLetterboxd) }
                return@launch
            }
            val film = try {
                source.resolveFilm(tmdbId, imdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: LetterboxdException) {
                log.w { "Letterboxd film lookup failed: ${error.error}" }
                mutableState.update { it.copy(phase = LetterboxdFilmPanelState.Phase.Failed, error = error.error) }
                return@launch
            }
            if (film == null) {
                mutableState.update { it.copy(phase = LetterboxdFilmPanelState.Phase.NotOnLetterboxd) }
                return@launch
            }
            mutableState.update { it.copy(phase = LetterboxdFilmPanelState.Phase.Ready, film = film) }
            loadPersonal()
            val histogram = runCatchingLetterboxd { source.histogram(film) }
            mutableState.update { it.copy(histogram = histogram.getOrNull()) }
        }
    }

    fun retry() = load(tmdbId, imdbId)

    /** Reloads the member's state, review and friends (e.g. after reconnecting). */
    fun loadPersonal() {
        val film = mutableState.value.film ?: return
        personalJob?.cancel()
        val connection = connection()
        if (connection != LetterboxdFilmPanelState.Connection.Connected) {
            mutableState.update {
                it.copy(connection = connection, personal = LetterboxdFilmPanelState.PersonalPhase.Idle, myState = null, review = null, friends = emptyList())
            }
            return
        }
        mutableState.update {
            it.copy(connection = connection, personal = LetterboxdFilmPanelState.PersonalPhase.Loading, personalError = null)
        }
        personalJob = scope.launch {
            coroutineScope {
                val friendsCall = async { runCatchingLetterboxd { source.friends(film) } }
                val myState = runCatchingLetterboxd { source.myState(film) }
                myState.exceptionOrNull()?.let { error ->
                    val code = (error as LetterboxdException).error
                    mutableState.update {
                        it.copy(
                            personal = LetterboxdFilmPanelState.PersonalPhase.Failed,
                            personalError = code,
                            connection = if (code == LetterboxdError.AuthExpired) LetterboxdFilmPanelState.Connection.ReconnectRequired else it.connection,
                        )
                    }
                    friendsCall.cancel()
                    return@coroutineScope
                }
                val current = myState.getOrThrow()
                val review = latestReview(current)
                val friends = friendsCall.await()
                mutableState.update {
                    it.copy(
                        personal = LetterboxdFilmPanelState.PersonalPhase.Ready,
                        myState = current,
                        review = review,
                        friends = friends.getOrNull().orEmpty(),
                        friendsError = (friends.exceptionOrNull() as? LetterboxdException)?.error,
                    )
                }
            }
        }
    }

    fun toggleWatched() {
        val watched = mutableState.value.myState?.watched ?: false
        act(LetterboxdFilmPanelState.Action.Watched) { film -> source.setWatched(film, !watched) }
    }

    fun toggleLike() {
        val liked = mutableState.value.myState?.liked ?: false
        act(LetterboxdFilmPanelState.Action.Like) { film -> source.setLiked(film, !liked) }
    }

    /** [rating] 0.5–5 in 0.5 steps, null removes the rating. */
    fun setRating(rating: Double?) {
        if (rating == mutableState.value.myState?.rating) return
        act(LetterboxdFilmPanelState.Action.Rating) { film -> source.setRating(film, rating) }
    }

    /** Creates a diary entry / review, or edits [editingEntryId]. */
    fun saveReview(draft: LetterboxdLogEntryDraft, editingEntryId: String? = null) {
        act(LetterboxdFilmPanelState.Action.Review, reloadReview = true) { film ->
            if (editingEntryId != null) source.updateLogEntry(film, editingEntryId, draft) else source.createLogEntry(film, draft)
        }
    }

    fun clearActionError() = mutableState.update { it.copy(actionError = null, actionErrorDetail = null) }

    private fun act(
        action: LetterboxdFilmPanelState.Action,
        reloadReview: Boolean = false,
        block: suspend (LetterboxdFilm) -> Unit,
    ) {
        val current = mutableState.value
        val film = current.film ?: return
        if (current.busy != null || current.connection != LetterboxdFilmPanelState.Connection.Connected) return
        val wasWatched = current.myState?.watched ?: false
        mutableState.update { it.copy(busy = action, actionError = null, actionErrorDetail = null) }
        scope.launch {
            try {
                block(film)
                val fresh = source.myState(film)
                val review = if (reloadReview || fresh.reviewIds != current.myState?.reviewIds) latestReview(fresh) else current.review
                mutableState.update { it.copy(myState = fresh, review = review, personal = LetterboxdFilmPanelState.PersonalPhase.Ready) }
                if (!wasWatched && fresh.watched) {
                    runCatching { onBecameWatched() }.onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Post-watch hook failed: ${error::class.simpleName}" }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LetterboxdException) {
                log.w { "Letterboxd ${action.name} failed: ${error.error}${error.status?.let { " (HTTP $it)" }.orEmpty()}" }
                mutableState.update {
                    it.copy(
                        actionError = error.error,
                        actionErrorDetail = error.message?.takeIf { error.error == LetterboxdError.Rejected && it != error.error.name },
                        connection = if (error.error == LetterboxdError.AuthExpired) LetterboxdFilmPanelState.Connection.ReconnectRequired else it.connection,
                    )
                }
            } catch (error: IllegalArgumentException) {
                mutableState.update { it.copy(actionError = LetterboxdError.Rejected) }
            } finally {
                mutableState.update { it.copy(busy = null) }
            }
        }
    }

    /** Most recent review (by creation date) among the member's last few reviews of the film. */
    private suspend fun latestReview(state: LetterboxdFilmState): LetterboxdLogEntry? {
        val ids = state.reviewIds.takeLast(3)
        if (ids.isEmpty()) return null
        return ids.mapNotNull { id -> runCatchingLetterboxd { source.logEntry(id) }.getOrNull() }
            .filter { it.reviewText != null }
            .maxByOrNull { it.whenCreated.orEmpty() }
    }

    private fun connection(): LetterboxdFilmPanelState.Connection = connectionOf(source.account.value)

    private fun connectionOf(account: LetterboxdAccountState): LetterboxdFilmPanelState.Connection =
        when {
            !account.hasCredentials -> LetterboxdFilmPanelState.Connection.NotConnected
            account.status == LetterboxdConnectionStatus.ReconnectRequired -> LetterboxdFilmPanelState.Connection.ReconnectRequired
            else -> LetterboxdFilmPanelState.Connection.Connected
        }

    private suspend fun <T> runCatchingLetterboxd(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: LetterboxdException) {
        Result.failure(error)
    }
}
