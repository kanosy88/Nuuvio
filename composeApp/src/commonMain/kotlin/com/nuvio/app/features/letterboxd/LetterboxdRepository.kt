package com.nuvio.app.features.letterboxd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Entry point of the Letterboxd integration for the rest of the app.
 * - [state] / [reconnectRequired]: account status (UI, other features).
 * - [resolveFilmByTmdb] / [film]: cached TMDB↔slug mapping and public film data.
 * - [authenticated]: run writes/personal reads with the stored session, failing with a clear [LetterboxdError].
 * One Letterboxd account per installation (not per Nuvio profile).
 */
object LetterboxdRepository {
    private val log = Logger.withTag("Letterboxd")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loadMutex = Mutex()
    private val actionMutex = Mutex()

    private val transport = LetterboxdTransport(KtorLetterboxdHttpEngine { LetterboxdPlatform.createHttpClient() })
    internal val client = LetterboxdClient(transport, onAuthExpired = ::markReconnectRequired)
    private val index = LetterboxdFilmIndex(
        load = { LetterboxdPlatform.readCache(FILM_INDEX_CACHE) },
        save = { LetterboxdPlatform.writeCache(FILM_INDEX_CACHE, it) },
    )

    private val mutableState = MutableStateFlow(LetterboxdAccountState())
    val state: StateFlow<LetterboxdAccountState> = mutableState.asStateFlow()
    val reconnectRequired: Flow<Boolean> = state.map { it.reconnectRequired }.distinctUntilChanged()

    val isSupported: Boolean get() = LetterboxdPlatform.isSupported

    @Serializable
    private data class StoredCredentials(val cookies: Map<String, String> = emptyMap(), val userAgent: String = "")

    @Serializable
    private data class StoredState(
        val status: LetterboxdConnectionStatus = LetterboxdConnectionStatus.Disconnected,
        val username: String? = null,
        val lastCheckedAtEpochMs: Long? = null,
    )

    init {
        transport.onCookiesChanged = { cookies -> persistCookies(cookies) }
    }

    fun ensureLoaded() {
        if (mutableState.value.isLoaded || !isSupported) return
        scope.launch { load() }
    }

    private suspend fun load() = loadMutex.withLock {
        if (mutableState.value.isLoaded) return@withLock
        withContext(Dispatchers.Default) {
            val credentials = runCatching { LetterboxdPlatform.readSecret() }
                .onFailure { log.w { "Could not read Letterboxd credentials: ${it::class.simpleName}" } }
                .getOrNull()
                ?.let { runCatching { json.decodeFromString(StoredCredentials.serializer(), it) }.getOrNull() }
            val stored = LetterboxdPlatform.readState()
                ?.let { runCatching { json.decodeFromString(StoredState.serializer(), it) }.getOrNull() }
                ?: StoredState()
            if (credentials != null && credentials.cookies.isNotEmpty()) {
                transport.configure(credentials.cookies, credentials.userAgent)
            }
            mutableState.value = LetterboxdAccountState(
                isLoaded = true,
                status = when {
                    credentials == null || credentials.cookies.isEmpty() -> LetterboxdConnectionStatus.Disconnected
                    stored.status == LetterboxdConnectionStatus.Disconnected -> LetterboxdConnectionStatus.Unverified
                    else -> stored.status
                },
                username = stored.username.takeIf { credentials != null },
                userAgent = credentials?.userAgent.orEmpty(),
                cookieNames = credentials?.cookies?.keys?.toList().orEmpty(),
                storageKind = LetterboxdPlatform.secretStorageKind,
            )
        }
    }

    suspend fun awaitLoaded() {
        if (!mutableState.value.isLoaded && isSupported) load()
    }

    /** Parses and stores pasted cookies, then runs [testConnection]. Returns null when no cookie could be parsed. */
    suspend fun saveCredentials(rawCookies: String, userAgent: String): LetterboxdConnectionReport? {
        awaitLoaded()
        val cookies = parseLetterboxdCookies(rawCookies)
        if (cookies.isEmpty()) {
            mutableState.update { it.copy(lastError = LetterboxdError.NotConnected) }
            return null
        }
        val ua = userAgent.trim()
        val stored = actionMutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    LetterboxdPlatform.writeSecret(json.encodeToString(StoredCredentials.serializer(), StoredCredentials(cookies, ua)))
                }
                transport.configure(cookies, ua)
                mutableState.update {
                    it.copy(
                        status = LetterboxdConnectionStatus.Unverified,
                        username = null,
                        userAgent = ua,
                        cookieNames = cookies.keys.toList(),
                        lastError = null,
                        lastReport = null,
                    )
                }
                persistState()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e { "Could not store Letterboxd credentials: ${error::class.simpleName}" }
                mutableState.update { it.copy(lastError = LetterboxdError.Storage) }
                false
            }
        }
        return if (stored) testConnection() else null
    }

    /** Checks the session (identity on a public page) and whether the authenticated API gets through Cloudflare. */
    suspend fun testConnection(): LetterboxdConnectionReport {
        awaitLoaded()
        return actionMutex.withLock {
            mutableState.update { it.copy(isBusy = true, lastError = null) }
            val cookies = transport.cookieSnapshot()
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (cookies.isEmpty()) {
                val report = LetterboxdConnectionReport(
                    null, LetterboxdProbeResult.Skipped, LetterboxdProbeResult.Skipped,
                    false, false, false, LetterboxdError.NotConnected, now,
                )
                mutableState.update { it.copy(isBusy = false, lastError = LetterboxdError.NotConnected, lastReport = report) }
                return@withLock report
            }
            var username: String? = null
            var identity = LetterboxdProbeResult.Failed
            var error: LetterboxdError? = null
            var httpStatus: Int? = null
            try {
                username = client.fetchSession().username
                identity = LetterboxdProbeResult.Ok
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: LetterboxdException) {
                error = failure.error
                httpStatus = failure.status
                log.w { "Letterboxd connection test failed: ${failure.error}${failure.status?.let { " (HTTP $it)" }.orEmpty()}" }
                identity = when (failure.error) {
                    LetterboxdError.AuthExpired, LetterboxdError.SessionCookieMissing -> LetterboxdProbeResult.Unauthorized
                    LetterboxdError.CloudflareChallenge -> LetterboxdProbeResult.CloudflareChallenge
                    else -> LetterboxdProbeResult.Failed
                }
            }
            val api = if (identity == LetterboxdProbeResult.Ok) client.probeApi() else LetterboxdProbeResult.Skipped
            val after = transport.cookieSnapshot()
            val report = LetterboxdConnectionReport(
                username = username,
                identity = identity,
                api = api,
                csrfTokenFound = after.containsKey(LETTERBOXD_CSRF_COOKIE),
                hasClearanceCookie = after.containsKey(LETTERBOXD_CLEARANCE_COOKIE),
                customUserAgent = mutableState.value.userAgent.isNotBlank(),
                error = error,
                checkedAtEpochMs = now,
                httpStatus = httpStatus,
            )
            mutableState.update {
                it.copy(
                    isBusy = false,
                    status = when (identity) {
                        LetterboxdProbeResult.Ok -> LetterboxdConnectionStatus.Connected
                        LetterboxdProbeResult.Unauthorized -> LetterboxdConnectionStatus.ReconnectRequired
                        else -> it.status
                    },
                    username = username ?: it.username,
                    lastError = error,
                    lastReport = report,
                )
            }
            persistState()
            report
        }
    }

    /** Forgets the cookies locally. Does not call Letterboxd's logout (it would also end the browser session). */
    suspend fun signOut() {
        awaitLoaded()
        actionMutex.withLock {
            runCatching { withContext(Dispatchers.Default) { LetterboxdPlatform.deleteSecret() } }
                .onFailure { log.w { "Could not delete Letterboxd credentials: ${it::class.simpleName}" } }
            transport.clear()
            mutableState.update {
                LetterboxdAccountState(isLoaded = true, storageKind = it.storageKind)
            }
            persistState()
        }
    }

    // ------------------------------------------------------------------ API for other features

    /**
     * Letterboxd film for a TMDB movie id (cached). Returns null when Letterboxd doesn't list the film.
     * [detailsMaxAgeMs] bounds the age of cached average rating / counts.
     */
    suspend fun resolveFilmByTmdb(tmdbId: Int, detailsMaxAgeMs: Long = DEFAULT_DETAILS_MAX_AGE_MS): LetterboxdFilm? {
        awaitLoaded()
        val slug = when (val lookup = index.lookupTmdb(tmdbId)) {
            is LetterboxdFilmIndex.Lookup.Found -> lookup.slug
            LetterboxdFilmIndex.Lookup.NotOnLetterboxd -> return null
            LetterboxdFilmIndex.Lookup.Miss -> {
                val resolved = client.resolveSlugForTmdb(tmdbId)
                index.putTmdb(tmdbId, resolved)
                resolved ?: return null
            }
        }
        return film(slug, detailsMaxAgeMs)
    }

    /** Same as [resolveFilmByTmdb] from an IMDb id (fallback when the TMDB id isn't known). */
    suspend fun resolveFilmByImdb(imdbId: String, detailsMaxAgeMs: Long = DEFAULT_DETAILS_MAX_AGE_MS): LetterboxdFilm? {
        awaitLoaded()
        val slug = when (val lookup = index.lookupImdb(imdbId)) {
            is LetterboxdFilmIndex.Lookup.Found -> lookup.slug
            LetterboxdFilmIndex.Lookup.NotOnLetterboxd -> return null
            LetterboxdFilmIndex.Lookup.Miss -> {
                val resolved = client.resolveSlugForImdb(imdbId)
                index.putImdb(imdbId, resolved)
                resolved ?: return null
            }
        }
        return film(slug, detailsMaxAgeMs)
    }

    /** Public film data by slug, from cache when fresh enough; falls back to a stale copy on network errors. */
    suspend fun film(slug: String, maxAgeMs: Long = DEFAULT_DETAILS_MAX_AGE_MS): LetterboxdFilm {
        awaitLoaded()
        index.film(slug, maxAgeMs)?.let { return it }
        return try {
            client.fetchFilm(slug).also { index.putFilm(it) }
        } catch (error: LetterboxdException) {
            if (error.error == LetterboxdError.NotFound) index.evict(slug)
            index.staleFilm(slug)?.takeIf { error.error != LetterboxdError.NotFound } ?: throw error
        }
    }

    /**
     * Runs [block] with the authenticated client. Throws [LetterboxdError.NotConnected] without cookies;
     * an expired session flips [state] to [LetterboxdConnectionStatus.ReconnectRequired].
     */
    internal suspend fun <T> authenticated(block: suspend LetterboxdClient.(username: String?) -> T): T {
        awaitLoaded()
        val current = mutableState.value
        if (!current.hasCredentials) throw LetterboxdException(LetterboxdError.NotConnected)
        return client.block(current.username)
    }

    // ------------------------------------------------------------------ internals

    private suspend fun markReconnectRequired() {
        if (mutableState.value.status == LetterboxdConnectionStatus.ReconnectRequired) return
        log.w { "Letterboxd session rejected: reconnection required" }
        mutableState.update { it.copy(status = LetterboxdConnectionStatus.ReconnectRequired, lastError = LetterboxdError.AuthExpired) }
        persistState()
    }

    private suspend fun persistCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val payload = json.encodeToString(StoredCredentials.serializer(), StoredCredentials(cookies, mutableState.value.userAgent))
        runCatching { withContext(Dispatchers.Default) { LetterboxdPlatform.writeSecret(payload) } }
            .onFailure { log.w { "Could not update Letterboxd cookies: ${it::class.simpleName}" } }
        mutableState.update { it.copy(cookieNames = cookies.keys.toList()) }
    }

    private suspend fun persistState() {
        val current = mutableState.value
        val payload = json.encodeToString(
            StoredState.serializer(),
            StoredState(current.status, current.username, current.lastReport?.checkedAtEpochMs),
        )
        runCatching { withContext(Dispatchers.Default) { LetterboxdPlatform.writeState(payload) } }
    }

    private const val FILM_INDEX_CACHE = "film_index.json"
    const val DEFAULT_DETAILS_MAX_AGE_MS: Long = 12L * 60 * 60 * 1000
}
