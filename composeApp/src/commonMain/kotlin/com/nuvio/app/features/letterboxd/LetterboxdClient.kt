package com.nuvio.app.features.letterboxd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Letterboxd website client (no public API): reads public pages anonymously and performs writes with
 * the user's session cookies, the way the website's own JavaScript does.
 *
 * Endpoints (found in the site's JS bundle, Nov 2026):
 * - JSON API, same origin, cookie auth, `X-CSRF-TOKEN` header for non-GET:
 *   `PATCH /api/v0/me/rate/{lid}` `{"rating": 3.5|null}`, `PATCH /api/v0/me/watchlist/{lid}` `{"inWatchlist": bool}`,
 *   `POST /api/v0/production-log-entries`, `DELETE /api/v0/production-log-entry/{id}`, `GET /api/v0/production/{lid}/me`.
 *   `GET|PATCH /api/v0/production-log-entry/{id}` (log entry: diary details, rating, review).
 * - Form endpoints with `__csrf` field: `POST /s/{uid}/watch/` `watched=bool`, `POST /s/{uid}/like/` `liked=bool`.
 * - HTML fragments: `/csi/film/{slug}/rating-histogram/`, `/csi/film/{slug}/friend-activity/?esiAllowUser=true`.
 * The CSRF token is the value of the `com.xk72.webparts.csrf` cookie (echoed in pages as `supermodelCSRF`).
 */
internal class LetterboxdClient(
    private val transport: LetterboxdTransport,
    private val onAuthExpired: suspend () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = true }

    // ---------------------------------------------------------------- public reads

    /** Letterboxd slug for a TMDB movie id, null when Letterboxd doesn't know the film. */
    suspend fun resolveSlugForTmdb(tmdbId: Int): String? {
        val response = transport.execute(LetterboxdHttpRequest("GET", "/tmdb/$tmdbId/"), authenticated = false)
        return when (response.status) {
            in 300..399 -> LetterboxdParsers.slugFromFilmPath(response.header("Location"))
                ?: throw LetterboxdException(LetterboxdError.Parse, "Unexpected redirect")
            404 -> null
            200 -> Regex("""<meta property="og:url" content="[^"]*(/film/[^"]+)"""").find(response.body)
                ?.groupValues?.get(1)?.let(LetterboxdParsers::slugFromFilmPath)
            else -> throw statusError(response.status)
        }
    }

    /** Letterboxd slug for an IMDb id (`/imdb/tt…/` redirects like `/tmdb/<id>/`), null when unknown. */
    suspend fun resolveSlugForImdb(imdbId: String): String? {
        require(Regex("tt\\d+").matches(imdbId)) { "Invalid IMDb id" }
        val response = transport.execute(LetterboxdHttpRequest("GET", "/imdb/$imdbId/"), authenticated = false)
        return when (response.status) {
            in 300..399 -> LetterboxdParsers.slugFromFilmPath(response.header("Location"))
                ?: throw LetterboxdException(LetterboxdError.Parse, "Unexpected redirect")
            404 -> null
            200 -> Regex("""<meta property="og:url" content="[^"]*(/film/[^"]+)"""").find(response.body)
                ?.groupValues?.get(1)?.let(LetterboxdParsers::slugFromFilmPath)
            else -> throw statusError(response.status)
        }
    }

    suspend fun fetchFilm(slug: String): LetterboxdFilm {
        var path = "/film/$slug/"
        repeat(3) {
            val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = false)
            when (response.status) {
                200 -> {
                    val finalSlug = LetterboxdParsers.slugFromFilmPath(path) ?: slug
                    return LetterboxdParsers.parseFilmPage(finalSlug, response.body, response.header("x-letterboxd-identifier"))
                }
                in 300..399 -> path = response.header("Location")?.removePrefix(LETTERBOXD_BASE_URL)
                    ?.takeIf { it.startsWith("/film/") } ?: throw LetterboxdException(LetterboxdError.NotFound)
                404 -> throw LetterboxdException(LetterboxdError.NotFound, status = 404)
                else -> throw statusError(response.status)
            }
        }
        throw LetterboxdException(LetterboxdError.Parse, "Too many redirects")
    }

    /** May hit a Cloudflare challenge without a valid `cf_clearance` cookie. */
    suspend fun fetchRatingHistogram(slug: String): LetterboxdRatingHistogram? {
        val response = transport.execute(
            LetterboxdHttpRequest("GET", "/csi/film/$slug/rating-histogram/"),
            authenticated = true,
            referer = "$LETTERBOXD_BASE_URL/film/$slug/",
        )
        if (response.status != 200) throw statusError(response.status)
        return LetterboxdParsers.parseRatingHistogram(response.body)
    }

    suspend fun fetchWatchlist(username: String, maxPages: Int = 60): List<LetterboxdWatchlistItem> {
        val items = mutableListOf<LetterboxdWatchlistItem>()
        for (page in 1..maxPages) {
            val result = fetchWatchlistPage(username, page)
            items += result.items
            if (!result.hasNext) break
        }
        return items.distinctBy { it.slug }
    }

    /**
     * One page (28 films) of `/<user>/watchlist/page/N/` (verified Nov 2026: `<li class="griditem">` posters with
     * `data-item-slug` + `data-postered-identifier` {lid, uid}, `<a class="next">` link, total in `data-num-entries`).
     */
    suspend fun fetchWatchlistPage(username: String, page: Int): LetterboxdWatchlistPage {
        require(page >= 1) { "Invalid page" }
        val path = if (page == 1) "/$username/watchlist/" else "/$username/watchlist/page/$page/"
        val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = true)
        handleAuthStatus(response)
        if (response.status == 404) throw LetterboxdException(LetterboxdError.NotFound, status = 404)
        if (response.status != 200) throw statusError(response.status)
        val items = LetterboxdParsers.parseWatchlistPage(response.body)
        return LetterboxdWatchlistPage(
            items = items,
            hasNext = items.isNotEmpty() && LetterboxdParsers.hasNextPage(response.body),
            totalEntries = LetterboxdParsers.parseWatchlistTotal(response.body),
        )
    }

    /** One page (72 films) of the member's watched films `/<user>/films/page/N/` (rating and like, no date). */
    suspend fun fetchWatchedFilmsPage(username: String, page: Int): LetterboxdWatchedFilmsPage {
        require(page >= 1) { "Invalid page" }
        val path = if (page == 1) "/$username/films/" else "/$username/films/page/$page/"
        val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = true)
        handleAuthStatus(response)
        if (response.status == 404) throw LetterboxdException(LetterboxdError.NotFound, status = 404)
        if (response.status != 200) throw statusError(response.status)
        val items = LetterboxdParsers.parseWatchedFilmsPage(response.body)
        return LetterboxdWatchedFilmsPage(items, hasNext = items.isNotEmpty() && LetterboxdParsers.hasNextPage(response.body))
    }

    /**
     * One page (100 films) of a list `/<owner>/list/<slug>/[page/N/]`. Sent with the session cookies when there are
     * some (the owner's private lists), public otherwise. A renamed list redirects to its new address (followed).
     */
    suspend fun fetchListPage(ref: LetterboxdListRef, page: Int): LetterboxdListPage {
        require(page >= 1) { "Invalid page" }
        var path = if (page == 1) ref.path else "${ref.path}page/$page/"
        repeat(3) {
            val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = true)
            handleAuthStatus(response)
            when (response.status) {
                200 -> return LetterboxdParsers.parseListPage(response.body)
                in 300..399 -> path = response.header("Location")?.removePrefix(LETTERBOXD_BASE_URL)
                    ?.takeIf { it.startsWith("/") && it.contains("/list/") }
                    ?: throw LetterboxdException(LetterboxdError.NotFound, status = response.status)
                404 -> throw LetterboxdException(LetterboxdError.NotFound, status = 404)
                else -> throw statusError(response.status)
            }
        }
        throw LetterboxdException(LetterboxdError.Parse, "Too many redirects")
    }

    /** One page of a member's lists `/<user>/lists/[page/N/]` and whether there is a next page. */
    suspend fun fetchMemberListsPage(username: String, page: Int): Pair<List<LetterboxdListSummary>, Boolean> {
        require(page >= 1) { "Invalid page" }
        val path = if (page == 1) "/$username/lists/" else "/$username/lists/page/$page/"
        val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = true)
        handleAuthStatus(response)
        if (response.status == 404) throw LetterboxdException(LetterboxdError.NotFound, status = 404)
        if (response.status != 200) throw statusError(response.status)
        val lists = LetterboxdParsers.parseMemberLists(response.body)
        return lists to (lists.isNotEmpty() && LetterboxdParsers.hasNextPage(response.body))
    }

    suspend fun fetchFollowing(username: String, maxPages: Int = 20): List<String> {
        val members = mutableListOf<String>()
        for (page in 1..maxPages) {
            val path = if (page == 1) "/$username/following/" else "/$username/following/page/$page/"
            val response = transport.execute(LetterboxdHttpRequest("GET", path), authenticated = true)
            if (response.status != 200) throw statusError(response.status)
            val pageMembers = LetterboxdParsers.parseFollowingPage(response.body).filter { it != username }
            members += pageMembers
            if (pageMembers.isEmpty() || !LetterboxdParsers.hasNextPage(response.body)) break
        }
        return members.distinct()
    }

    /** Public RSS feed of a member's latest diary entries/reviews (≈50 items, includes TMDB ids). */
    suspend fun fetchDiaryRss(username: String): List<LetterboxdDiaryEntry> {
        val response = transport.execute(
            LetterboxdHttpRequest("GET", "/$username/rss/", headers = mapOf("Accept" to "application/rss+xml, application/xml")),
            authenticated = false,
        )
        if (response.status != 200) throw statusError(response.status)
        return LetterboxdParsers.parseDiaryRss(response.body, username)
    }

    // ---------------------------------------------------------------- authenticated reads

    /**
     * Identity check: loads a public film page with the session cookies and reads who it was rendered for
     * (see [LetterboxdParsers.detectSession]). Also captures the CSRF token.
     * Throws [LetterboxdError.AuthExpired] (or [LetterboxdError.SessionCookieMissing] when no session cookie was
     * pasted) when Letterboxd treats us as a guest.
     */
    suspend fun fetchSession(): LetterboxdSessionInfo {
        val response = transport.execute(LetterboxdHttpRequest("GET", SESSION_PROBE_PATH), authenticated = true)
        handleAuthStatus(response)
        if (response.status != 200) throw statusError(response.status)
        val token = LetterboxdParsers.parseCsrfToken(response.body)
        if (token != null) transport.setCsrfToken(token)
        val session = LetterboxdParsers.detectSession(response.body)
        val cookies = transport.cookieSnapshot()
        when (session.state) {
            LetterboxdPageSession.State.SignedIn -> {
                val username = session.username?.takeIf { it.isNotBlank() }
                    ?: cookies[LETTERBOXD_SIGNED_IN_AS_COOKIE]?.takeIf { it.isNotBlank() }
                    ?: throw LetterboxdException(LetterboxdError.Parse, "Signed in but username not found")
                return LetterboxdSessionInfo(username = username, csrfToken = token ?: transport.csrfToken())
            }
            LetterboxdPageSession.State.Guest -> {
                onAuthExpired()
                val missing = cookies.keys.none { it in LETTERBOXD_SESSION_COOKIES }
                throw LetterboxdException(
                    if (missing) LetterboxdError.SessionCookieMissing else LetterboxdError.AuthExpired,
                    if (missing) "No session cookie" else "Guest page",
                )
            }
            LetterboxdPageSession.State.Unknown ->
                throw LetterboxdException(LetterboxdError.Parse, "Session markers not found in page", response.status)
        }
    }

    /** The member's relationship with [film] (watched/liked/watchlist/rating) from the JSON API. */
    suspend fun fetchMyFilmState(film: LetterboxdFilm): LetterboxdFilmState {
        val lid = film.lid ?: throw LetterboxdException(LetterboxdError.Parse, "Missing film LID")
        val body = apiCall("GET", "/api/v0/production/$lid/me", null, film) as? JsonObject
            ?: throw LetterboxdException(LetterboxdError.Parse, "Empty relationship")
        return parseFilmState(body)
    }

    /** The member's log entry [logEntryId] (diary details, rating, review), from the JSON API. */
    suspend fun fetchLogEntry(logEntryId: String): LetterboxdLogEntry {
        val body = apiCall("GET", "/api/v0/production-log-entry/$logEntryId", null, null) as? JsonObject
            ?: throw LetterboxdException(LetterboxdError.Parse, "Empty log entry")
        return parseLogEntry(body)
    }

    /**
     * What the accounts the member follows did with [film] (ratings, likes, reviews), from the film page's
     * "Activity from friends" fragment. Empty when nobody interacted with it.
     */
    suspend fun fetchFriendActivity(film: LetterboxdFilm): List<LetterboxdFriendActivity> {
        val response = transport.execute(
            LetterboxdHttpRequest(
                "GET",
                "/csi/film/${film.slug}/friend-activity/?esiAllowUser=true",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "text/html, */*; q=0.01"),
            ),
            authenticated = true,
            referer = film.url,
        )
        handleAuthStatus(response)
        if (response.status != 200) throw statusError(response.status)
        return LetterboxdParsers.parseFriendActivity(response.body)
    }

    /**
     * Raw activity rows of the accounts the member follows (one request). Requires a valid Cloudflare clearance.
     * Parsing is best-effort; see [LetterboxdParsers.parseActivity].
     */
    suspend fun fetchFollowingActivity(username: String): List<LetterboxdActivityItem> =
        fetchFollowingActivityPage(username, after = null).items

    /**
     * One page (≈20 rows) of the following activity; the next page is `?after=<last data-activity-id>`
     * (verified Nov 2026). Public fragment, sent with the session cookies like the website does.
     */
    suspend fun fetchFollowingActivityPage(username: String, after: String?): LetterboxdActivityPage {
        require(after == null || Regex("\\d+").matches(after)) { "Invalid activity cursor" }
        val query = after?.let { "?after=$it" }.orEmpty()
        val response = transport.execute(
            LetterboxdHttpRequest(
                "GET",
                "/ajax/activity-pagination/$username/following/$query",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "text/html, */*; q=0.01"),
            ),
            authenticated = true,
            referer = "$LETTERBOXD_BASE_URL/$username/activity/following/",
        )
        handleAuthStatus(response)
        if (response.status != 200) throw statusError(response.status)
        val items = LetterboxdParsers.parseActivity(response.body)
        return LetterboxdActivityPage(items, hasMore = items.isNotEmpty() && !LetterboxdParsers.isActivityEnd(response.body))
    }

    // ---------------------------------------------------------------- writes

    suspend fun setWatched(film: LetterboxdFilm, watched: Boolean): Boolean {
        val result = postForm(film, "watch", listOf("watched" to watched.toString()))
        return result["watched"]?.jsonPrimitive?.booleanOrNull ?: watched
    }

    suspend fun setLiked(film: LetterboxdFilm, liked: Boolean): Boolean {
        val result = postForm(film, "like", listOf("liked" to liked.toString()))
        return result["liked"]?.jsonPrimitive?.booleanOrNull ?: liked
    }

    /** [rating] out of 5 in 0.5 steps, null removes the rating. Rating also marks the film as watched. */
    suspend fun setRating(film: LetterboxdFilm, rating: Double?) {
        val lid = film.lid ?: throw LetterboxdException(LetterboxdError.Parse, "Missing film LID")
        val value = rating?.let(::validateRating)
        apiCall("PATCH", "/api/v0/me/rate/$lid", buildJsonObject {
            if (value == null) put("rating", JsonNull) else put("rating", value)
        }, film)
    }

    suspend fun setInWatchlist(film: LetterboxdFilm, inWatchlist: Boolean) {
        val lid = film.lid ?: throw LetterboxdException(LetterboxdError.Parse, "Missing film LID")
        apiCall("PATCH", "/api/v0/me/watchlist/$lid", buildJsonObject { put("inWatchlist", inWatchlist) }, film)
    }

    /** Creates a diary entry and/or review. Returns the created log entry id when the response exposes it. */
    suspend fun createLogEntry(film: LetterboxdFilm, draft: LetterboxdLogEntryDraft): String? {
        val lid = film.lid ?: throw LetterboxdException(LetterboxdError.Parse, "Missing film LID")
        val body = apiCall("POST", "/api/v0/production-log-entries", logEntryPayload(draft, productionId = lid), film) as? JsonObject
        return body?.let { obj ->
            obj.stringOrNull("id") ?: (obj["logEntry"] as? JsonObject)?.stringOrNull("id")
        }
    }

    /**
     * Replaces the fields of an existing log entry (same payload as creation, without the production id).
     * A null diary date / empty review removes them from the entry.
     */
    suspend fun updateLogEntry(film: LetterboxdFilm, logEntryId: String, draft: LetterboxdLogEntryDraft) {
        apiCall("PATCH", "/api/v0/production-log-entry/$logEntryId", logEntryPayload(draft, productionId = null), film)
    }

    private fun logEntryPayload(draft: LetterboxdLogEntryDraft, productionId: String?): JsonObject {
        val rating = draft.rating?.let(::validateRating)
        val update = productionId == null
        return buildJsonObject {
            if (productionId != null) put("productionId", productionId)
            if (draft.diaryDate != null) {
                require(Regex("""\d{4}-\d{2}-\d{2}""").matches(draft.diaryDate)) { "diaryDate must be yyyy-MM-dd" }
                putJsonObject("diaryDetails") {
                    put("diaryDate", draft.diaryDate)
                    put("rewatch", draft.rewatch)
                }
            } else if (update) {
                put("diaryDetails", JsonNull)
            }
            val review = draft.review?.trim().orEmpty()
            if (review.isNotEmpty()) {
                putJsonObject("review") {
                    put("text", review)
                    put("containsSpoilers", draft.containsSpoilers)
                }
            } else if (update) {
                put("review", JsonNull)
            }
            putJsonArray("tags") { draft.tags.forEach { add(JsonPrimitive(it)) } }
            if (rating != null) put("rating", rating) else if (update) put("rating", JsonNull)
            put("like", draft.liked)
        }
    }

    suspend fun deleteLogEntry(logEntryId: String) {
        apiCall("DELETE", "/api/v0/production-log-entry/$logEntryId", null, null)
    }

    /** Lightweight check that authenticated API calls get through Cloudflare. */
    suspend fun probeApi(): LetterboxdProbeResult = try {
        val response = transport.execute(
            LetterboxdHttpRequest("GET", "/api/v0/production/$SESSION_PROBE_LID/me", headers = mapOf("Accept" to "application/json")),
            authenticated = true,
            referer = "$LETTERBOXD_BASE_URL$SESSION_PROBE_PATH",
        )
        when {
            response.status == 401 || response.status == 403 -> LetterboxdProbeResult.Unauthorized
            response.status in 200..299 -> LetterboxdProbeResult.Ok
            else -> LetterboxdProbeResult.Failed
        }
    } catch (error: LetterboxdException) {
        if (error.error == LetterboxdError.CloudflareChallenge) LetterboxdProbeResult.CloudflareChallenge else LetterboxdProbeResult.Failed
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun ensureCsrf(): String =
        transport.csrfToken() ?: fetchSession().csrfToken
            ?: throw LetterboxdException(LetterboxdError.Rejected, "No CSRF token")

    private suspend fun postForm(film: LetterboxdFilm, action: String, fields: List<Pair<String, String>>): JsonObject {
        val uid = film.uid ?: throw LetterboxdException(LetterboxdError.Parse, "Missing film UID")
        var refreshed = false
        while (true) {
            val csrf = ensureCsrf()
            val response = transport.execute(
                LetterboxdHttpRequest(
                    method = "POST",
                    path = "/s/$uid/$action/",
                    headers = mapOf("Accept" to "application/json, text/javascript, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest"),
                    body = formEncode(fields + ("__csrf" to csrf)),
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                ),
                authenticated = true,
                referer = film.url,
            )
            handleAuthStatus(response)
            val body = parseJsonOrNull(response.body) as? JsonObject
            body?.stringOrNull("csrf")?.let { transport.setCsrfToken(it) }
            if (response.status in 200..299 && body?.get("result")?.jsonPrimitive?.booleanOrNull == true) return body
            if (!refreshed && (response.status == 403 || body?.get("result")?.jsonPrimitive?.booleanOrNull == false)) {
                // Most likely a stale CSRF token: reload it once and retry.
                refreshed = true
                fetchSession()
                continue
            }
            throw LetterboxdException(LetterboxdError.Rejected, body?.let(::messages), response.status)
        }
    }

    private suspend fun apiCall(method: String, path: String, payload: JsonObject?, film: LetterboxdFilm?): JsonElement? {
        var refreshed = false
        while (true) {
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = "application/json"
            if (method != "GET") headers["X-CSRF-TOKEN"] = ensureCsrf()
            val response = transport.execute(
                LetterboxdHttpRequest(
                    method = method,
                    path = path,
                    headers = headers,
                    body = payload?.toString(),
                    contentType = payload?.let { "application/json; charset=UTF-8" },
                ),
                authenticated = true,
                referer = film?.url,
            )
            handleAuthStatus(response)
            val body = parseJsonOrNull(response.body)
            when {
                response.status in 200..299 -> return body
                response.status == 403 && method != "GET" && !refreshed -> {
                    refreshed = true
                    fetchSession()
                }
                response.status == 404 -> throw LetterboxdException(LetterboxdError.NotFound, status = 404)
                response.status in 400..499 ->
                    throw LetterboxdException(LetterboxdError.Rejected, (body as? JsonObject)?.let(::messages), response.status)
                else -> throw statusError(response.status)
            }
        }
    }

    private suspend fun handleAuthStatus(response: LetterboxdHttpResponse) {
        val location = response.header("Location").orEmpty()
        val toSignIn = response.status in 300..399 && (location.contains("/sign-in") || location.contains("/user/login"))
        if (response.status == 401 || toSignIn) {
            onAuthExpired()
            throw LetterboxdException(LetterboxdError.AuthExpired, status = response.status)
        }
    }

    private fun parseFilmState(body: JsonObject): LetterboxdFilmState {
        fun bool(key: String) = body[key]?.jsonPrimitive?.booleanOrNull ?: false
        return LetterboxdFilmState(
            watched = bool("watched"),
            liked = bool("liked"),
            inWatchlist = bool("inWatchlist"),
            rating = body["rating"]?.let { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it > 0.0 },
            reviewCount = (body["reviews"] as? JsonArray)?.size ?: 0,
            diaryEntryCount = (body["diaryEntries"] as? JsonArray)?.size ?: 0,
            reviewIds = ids(body["reviews"]),
            diaryEntryIds = ids(body["diaryEntries"]),
            whenWatched = body.stringOrNull("whenWatched"),
        )
    }

    /** `["hdhvZL", …]` or `[{"id": "hdhvZL"}, …]`. */
    private fun ids(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull ?: (item as? JsonObject)?.stringOrNull("id")
        }

    /**
     * Log entry JSON (verified Nov 2026 for diary entries): `{id, diaryDetails: {diaryDate, rewatch}, rating, like,
     * whenCreated, links: [{type: "letterboxd", url}], review?: {text|lbml, containsSpoilers}}`.
     */
    private fun parseLogEntry(body: JsonObject): LetterboxdLogEntry {
        val diary = body["diaryDetails"] as? JsonObject
        val review = body["review"] as? JsonObject
        val reviewText = review?.let { it.stringOrNull("lbml") ?: it.stringOrNull("text") }
            ?.let(::reviewPlainText)?.takeIf { it.isNotBlank() }
        val url = (body["links"] as? JsonArray)?.firstNotNullOfOrNull { link ->
            (link as? JsonObject)?.takeIf { it.stringOrNull("type") == "letterboxd" }?.stringOrNull("url")
        }
        return LetterboxdLogEntry(
            id = body.stringOrNull("id") ?: throw LetterboxdException(LetterboxdError.Parse, "Log entry without id"),
            diaryDate = diary?.stringOrNull("diaryDate"),
            rewatch = diary?.get("rewatch")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false,
            rating = (body["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 },
            liked = (body["like"] as? JsonPrimitive)?.booleanOrNull ?: false,
            reviewText = reviewText,
            containsSpoilers = review?.get("containsSpoilers")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false,
            whenCreated = body.stringOrNull("whenCreated"),
            url = url,
        )
    }

    private fun reviewPlainText(markup: String): String =
        LetterboxdParsers.decodeEntities(
            markup.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</p>\\s*<p>"), "\n\n")
                .replace(Regex("<[^>]+>"), ""),
        ).trim()

    private fun parseJsonOrNull(text: String): JsonElement? =
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun messages(body: JsonObject): String? {
        val list = (body["messages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: (body["errors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.stringOrNull("message") ?: (it as? JsonPrimitive)?.contentOrNull }
        val text = list?.joinToString(" ")?.replace(Regex("<[^>]+>"), "")?.trim()
        return text?.takeIf { it.isNotEmpty() } ?: body.stringOrNull("message")
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun statusError(status: Int): LetterboxdException = when (status) {
        401 -> LetterboxdException(LetterboxdError.AuthExpired, status = status)
        404 -> LetterboxdException(LetterboxdError.NotFound, status = status)
        429 -> LetterboxdException(LetterboxdError.RateLimited, status = status)
        in 500..599 -> LetterboxdException(LetterboxdError.Server, status = status)
        else -> LetterboxdException(LetterboxdError.Server, "HTTP $status", status)
    }

    companion object {
        /** Stable public page used for identity checks (The Matrix). */
        internal const val SESSION_PROBE_PATH = "/film/the-matrix/"
        internal const val SESSION_PROBE_LID = "2a1m"

        internal fun validateRating(rating: Double): Double {
            val doubled = rating * 2
            require(rating in 0.5..5.0 && doubled == kotlin.math.round(doubled)) { "Rating must be 0.5–5 in 0.5 steps" }
            return rating
        }
    }
}
