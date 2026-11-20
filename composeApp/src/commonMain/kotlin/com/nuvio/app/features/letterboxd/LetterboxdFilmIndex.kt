package com.nuvio.app.features.letterboxd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent TMDB↔Letterboxd mapping + film details cache (one JSON file in the disk cache).
 * - Positive TMDB→slug mappings never expire (slugs are stable; a 404 later evicts them).
 * - Negative mappings (film unknown to Letterboxd) expire after [negativeTtlMs].
 * - Film details (average rating, counts) are considered fresh for the max age given by the caller.
 */
internal class LetterboxdFilmIndex(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val negativeTtlMs: Long = 3L * 24 * 60 * 60 * 1000,
    private val maxFilms: Int = 5_000,
) {
    @Serializable
    internal data class TmdbEntry(val slug: String? = null, val at: Long = 0L)

    @Serializable
    internal data class FilmEntry(val film: LetterboxdFilm, val at: Long = 0L)

    @Serializable
    internal data class Snapshot(
        val version: Int = 1,
        val tmdb: Map<String, TmdbEntry> = emptyMap(),
        val films: Map<String, FilmEntry> = emptyMap(),
        /** IMDb id → slug, used when no TMDB id is known (same expiry rules as [tmdb]). */
        val imdb: Map<String, TmdbEntry> = emptyMap(),
    )

    sealed interface Lookup {
        data object Miss : Lookup
        data object NotOnLetterboxd : Lookup
        data class Found(val slug: String) : Lookup
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private var loaded = false
    private val tmdb = LinkedHashMap<String, TmdbEntry>()
    private val films = LinkedHashMap<String, FilmEntry>()
    private val imdb = LinkedHashMap<String, TmdbEntry>()

    suspend fun lookupTmdb(tmdbId: Int): Lookup = mutex.withLock {
        ensureLoaded()
        val entry = tmdb[tmdbId.toString()] ?: return@withLock Lookup.Miss
        when {
            entry.slug != null -> Lookup.Found(entry.slug)
            nowMs() - entry.at < negativeTtlMs -> Lookup.NotOnLetterboxd
            else -> Lookup.Miss
        }
    }

    suspend fun lookupImdb(imdbId: String): Lookup = mutex.withLock {
        ensureLoaded()
        val entry = imdb[imdbId] ?: return@withLock Lookup.Miss
        when {
            entry.slug != null -> Lookup.Found(entry.slug)
            nowMs() - entry.at < negativeTtlMs -> Lookup.NotOnLetterboxd
            else -> Lookup.Miss
        }
    }

    suspend fun putImdb(imdbId: String, slug: String?) = mutex.withLock {
        ensureLoaded()
        imdb[imdbId] = TmdbEntry(slug, nowMs())
        persist()
    }

    suspend fun putTmdb(tmdbId: Int, slug: String?) = mutex.withLock {
        ensureLoaded()
        tmdb[tmdbId.toString()] = TmdbEntry(slug, nowMs())
        persist()
    }

    suspend fun film(slug: String, maxAgeMs: Long): LetterboxdFilm? = mutex.withLock {
        ensureLoaded()
        films[slug]?.takeIf { nowMs() - it.at <= maxAgeMs }?.film
    }

    /** Any cached copy, regardless of age (used as a fallback when the network fails). */
    suspend fun staleFilm(slug: String): LetterboxdFilm? = mutex.withLock {
        ensureLoaded()
        films[slug]?.film
    }

    suspend fun slugForTmdbFromFilms(tmdbId: Int): String? = mutex.withLock {
        ensureLoaded()
        films.values.firstOrNull { it.film.tmdbId == tmdbId }?.film?.slug
    }

    suspend fun putFilm(film: LetterboxdFilm) = mutex.withLock {
        ensureLoaded()
        films.remove(film.slug)
        films[film.slug] = FilmEntry(film, nowMs())
        film.tmdbId?.let { tmdb[it.toString()] = TmdbEntry(film.slug, nowMs()) }
        film.imdbId?.let { imdb[it] = TmdbEntry(film.slug, nowMs()) }
        while (films.size > maxFilms) films.remove(films.keys.first())
        persist()
    }

    suspend fun evict(slug: String) = mutex.withLock {
        ensureLoaded()
        films.remove(slug)
        tmdb.entries.removeAll { it.value.slug == slug }
        imdb.entries.removeAll { it.value.slug == slug }
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val payload = runCatching { load() }.getOrNull() ?: return
        runCatching { json.decodeFromString(Snapshot.serializer(), payload) }.getOrNull()?.let { snapshot ->
            tmdb.putAll(snapshot.tmdb)
            films.putAll(snapshot.films)
            imdb.putAll(snapshot.imdb)
        }
    }

    private fun persist() {
        runCatching { save(json.encodeToString(Snapshot.serializer(), Snapshot(tmdb = tmdb, films = films, imdb = imdb))) }
    }
}

/** TMDB/IMDb id or slug → [LetterboxdFilm], through the persistent [index] first (used by the repository and F3). */
internal class LetterboxdFilmResolver(
    private val client: LetterboxdClient,
    private val index: LetterboxdFilmIndex,
) {
    suspend fun byTmdb(tmdbId: Int, detailsMaxAgeMs: Long): LetterboxdFilm? {
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

    suspend fun byImdb(imdbId: String, detailsMaxAgeMs: Long): LetterboxdFilm? {
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

    /** From cache when fresh enough; falls back to a stale copy on network errors. */
    suspend fun film(slug: String, maxAgeMs: Long): LetterboxdFilm {
        index.film(slug, maxAgeMs)?.let { return it }
        return try {
            client.fetchFilm(slug).also { index.putFilm(it) }
        } catch (error: LetterboxdException) {
            if (error.error == LetterboxdError.NotFound) index.evict(slug)
            index.staleFilm(slug)?.takeIf { error.error != LetterboxdError.NotFound } ?: throw error
        }
    }
}
