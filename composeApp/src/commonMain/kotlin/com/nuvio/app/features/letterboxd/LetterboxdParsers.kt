package com.nuvio.app.features.letterboxd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Regex based parsers for Letterboxd HTML/RSS. Kept dependency-free (no HTML parser in commonMain)
 * and tolerant: every field is optional so markup changes degrade gracefully.
 */
internal object LetterboxdParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isCloudflareChallenge(status: Int, body: String, headers: Map<String, List<String>>): Boolean {
        val mitigated = headers.entries.any { (key, values) ->
            key.equals("cf-mitigated", ignoreCase = true) && values.any { it.contains("challenge", ignoreCase = true) }
        }
        if (mitigated) return true
        if (status != 403 && status != 503 && status != 429) return false
        val head = body.take(4_000)
        return head.contains("<title>Just a moment...</title>") ||
            head.contains("challenge-platform") ||
            head.contains("_cf_chl_opt") ||
            body.contains("window._cf_chl_opt")
    }

    /**
     * Who the page was rendered for. Every full page carries a CACHED guest literal
     * (`person = { username: "", loggedIn: false, role: "guest" … }`), then, for a signed-in member, an inline
     * script that overrides it: `supermodelCSRF = '…'; analytic_params['user_type'] = 'Member';
     * person.username = "x"; person.loggedIn = true; person.profileLink = "/x/"; person.role = "member"; …`.
     * The literal is only a default: the injected assignments win. Several markers are combined so a single
     * markup change doesn't flip a valid session to "guest"; contradictory markers give [LetterboxdPageSession.State.Unknown].
     */
    fun detectSession(html: String): LetterboxdPageSession {
        fun last(pattern: String) = Regex(pattern).findAll(html).lastOrNull()?.groupValues?.get(1)

        val literal = Regex("""person\s*=\s*\{\s*username:\s*"([^"]*)"\s*,\s*loggedIn:\s*(true|false)""").findAll(html).lastOrNull()
        val literalLoggedIn = literal?.groupValues?.get(2)?.toBooleanStrict()
        val literalUsername = literal?.groupValues?.get(1)

        val loggedIn = last("""person\.loggedIn\s*=\s*(true|false)""")?.toBooleanStrict()
        val username = last("""person\.username\s*=\s*"([^"]*)"""")
        val profileLink = last("""person\.profileLink\s*=\s*"/([^"/]+)/"""")
        val role = last("""person\.role\s*=\s*"(\w+)"""")?.lowercase()
        val userType = last("""analytic_params\[\s*['"]user_type['"]\s*]\s*=\s*['"](\w+)['"]""")?.lowercase()

        val name = listOf(username, profileLink, literalUsername).firstOrNull { !it.isNullOrBlank() }
        val member = userType == "member" || role == "member"
        val guest = userType == "visitor" || role == "guest"
        val state = when {
            loggedIn == true -> LetterboxdPageSession.State.SignedIn
            loggedIn == false -> if (member) LetterboxdPageSession.State.Unknown else LetterboxdPageSession.State.Guest
            member && guest -> LetterboxdPageSession.State.Unknown
            member -> LetterboxdPageSession.State.SignedIn
            guest -> LetterboxdPageSession.State.Guest
            literalLoggedIn == true -> LetterboxdPageSession.State.SignedIn
            literalLoggedIn == false -> LetterboxdPageSession.State.Guest
            else -> LetterboxdPageSession.State.Unknown
        }
        return LetterboxdPageSession(state, name.takeIf { state == LetterboxdPageSession.State.SignedIn })
    }

    /** Username of the signed-in member, null for a guest page or when it can't be determined. */
    fun parseSignedInUsername(html: String): String? =
        detectSession(html).takeIf { it.state == LetterboxdPageSession.State.SignedIn }?.username

    /** True when the page was rendered for a guest (cookies sent but not accepted). */
    fun isGuestPage(html: String): Boolean = detectSession(html).state == LetterboxdPageSession.State.Guest

    /** Last non-empty `supermodelCSRF` assignment (the cached template first declares `supermodelCSRF = ""`). */
    fun parseCsrfToken(html: String): String? =
        Regex("""supermodelCSRF\s*=\s*['"]([0-9A-Za-z]+)['"]""").findAll(html).lastOrNull()?.groupValues?.get(1)

    fun parseFilmPage(slug: String, html: String, lidHeader: String?): LetterboxdFilm {
        val identifier = metaContent(html, "production:identifier")?.let { raw ->
            runCatching { json.parseToJsonElement(decodeEntities(raw)).jsonObject }.getOrNull()
        }
        val lid = identifier?.string("lid") ?: lidHeader?.trim()?.takeIf { it.isNotEmpty() }
        val uid = identifier?.string("uid")
            ?: Regex("""data-production-uid="(film:\d+)"""").find(html)?.groupValues?.get(1)
        val tmdbId = Regex("""data-tmdb-id="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { Regex("""data-tmdb-type="movie"""").containsMatchIn(html) || !html.contains("data-tmdb-type=") }
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""").find(html)?.groupValues?.get(1)
        val nameAndYear = metaContent(html, "production:name-and-year")?.let(::decodeEntities)
        val name = metaContent(html, "production:name")?.let(::decodeEntities)
            ?: nameAndYear?.replace(Regex("""\s*\(\d{4}\)$"""), "")
        val year = nameAndYear?.let { Regex("""\((\d{4})\)$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val ld = parseJsonLd(html)
        val aggregate = (ld?.get("aggregateRating") as? JsonObject)
        return LetterboxdFilm(
            slug = slug,
            lid = lid,
            uid = uid,
            tmdbId = tmdbId,
            imdbId = imdbId,
            name = name ?: ld?.string("name"),
            year = year,
            posterUrl = ld?.string("image"),
            averageRating = aggregate?.get("ratingValue")?.jsonPrimitive?.doubleOrNull,
            ratingCount = aggregate?.get("ratingCount")?.jsonPrimitive?.intOrNull,
            reviewCount = aggregate?.get("reviewCount")?.jsonPrimitive?.intOrNull,
        )
    }

    /**
     * `/csi/film/<slug>/rating-histogram/` fragment: one bar per half star (½★ first), whose tooltip reads
     * e.g. `title="12,915 ★ ratings (0%)"`. Current markup uses `<tr class="column">`, older markup
     * `<li class="rating-histogram-bar">`. Bars without ratings have no count.
     */
    fun parseRatingHistogram(html: String): LetterboxdRatingHistogram? {
        val rows = Regex("""<tr[^>]*class="column"[^>]*>([\s\S]*?)</tr>""")
            .findAll(html).map { it.groupValues[1] }.toList()
            .ifEmpty {
                Regex("""<li[^>]*class="[^"]*rating-histogram-bar[^"]*"[^>]*>([\s\S]*?)</li>""")
                    .findAll(html).map { it.groupValues[1] }.toList()
            }
        if (rows.size != 10) return null
        val counts = rows.map { row ->
            val tooltip = Regex("""title="([^"]*)"""").find(row)?.groupValues?.get(1)?.let(::decodeEntities).orEmpty()
            Regex("""^\s*([\d,.]+)""").find(tooltip)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        }
        return LetterboxdRatingHistogram(counts)
    }

    /** Poster grid of `/<user>/watchlist/page/N/`. */
    fun parseWatchlistPage(html: String): List<LetterboxdWatchlistItem> =
        Regex("""<li class="griditem[^"]*">([\s\S]*?)</li>""").findAll(html).mapNotNull { match ->
            val item = match.groupValues[1]
            val slug = Regex("""data-item-slug="([^"]+)"""").find(item)?.groupValues?.get(1)
                ?: Regex("""data-target-link="/film/([^/"]+)/"""").find(item)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val identifier = Regex("""data-postered-identifier='([^']*)'""").find(item)?.groupValues?.get(1)
                ?.let { runCatching { json.parseToJsonElement(decodeEntities(it)).jsonObject }.getOrNull() }
            LetterboxdWatchlistItem(
                slug = slug,
                lid = identifier?.string("lid"),
                uid = identifier?.string("uid"),
                name = Regex("""data-item-name="([^"]*)"""").find(item)?.groupValues?.get(1)?.let(::decodeEntities),
            )
        }.toList()

    fun hasNextPage(html: String): Boolean =
        Regex("""<a[^>]*class="next"[^>]*href=""").containsMatchIn(html) ||
            Regex("""<a[^>]*href="[^"]*/page/\d+/"[^>]*class="next"""").containsMatchIn(html)

    /** Usernames from `/<user>/following/page/N/`. */
    fun parseFollowingPage(html: String): List<String> =
        Regex("""<a[^>]*class="[^"]*\bname\b[^"]*"[^>]*href="/([A-Za-z0-9_]+)/"""").findAll(html)
            .map { it.groupValues[1] }
            .plus(Regex("""<a[^>]*href="/([A-Za-z0-9_]+)/"[^>]*class="[^"]*\bname\b""").findAll(html).map { it.groupValues[1] })
            .distinct().toList()

    /** Public diary RSS feed `/<user>/rss/`. */
    fun parseDiaryRss(xml: String, member: String?): List<LetterboxdDiaryEntry> =
        Regex("""<item>([\s\S]*?)</item>""").findAll(xml).mapNotNull { match ->
            val item = match.groupValues[1]
            fun tag(name: String) = Regex("""<$name>([\s\S]*?)</$name>""").find(item)
                ?.groupValues?.get(1)?.trim()?.let(::decodeEntities)
            val link = tag("link")
            val guid = tag("guid").orEmpty()
            // Lists also appear in the feed; keep film entries only.
            val title = tag("letterboxd:filmTitle") ?: return@mapNotNull null
            val slug = link?.let { Regex("""/film/([^/]+)/""").find(it)?.groupValues?.get(1) }
            LetterboxdDiaryEntry(
                member = member,
                filmSlug = slug,
                filmTitle = title,
                filmYear = tag("letterboxd:filmYear")?.toIntOrNull(),
                tmdbId = tag("tmdb:movieId")?.toIntOrNull(),
                watchedDate = tag("letterboxd:watchedDate"),
                rating = tag("letterboxd:memberRating")?.toDoubleOrNull(),
                liked = tag("letterboxd:memberLike").equals("Yes", ignoreCase = true),
                rewatch = tag("letterboxd:rewatch").equals("Yes", ignoreCase = true),
                publishedAt = tag("pubDate"),
                link = link,
                isReview = guid.startsWith("letterboxd-review"),
            )
        }.toList()

    /**
     * Activity fragment from `/ajax/activity-pagination/<user>/following/` (verified Nov 2026). Rows are
     * `<section class="activity-row -review|-basic">`: reviews carry a poster (`data-owner`, `data-item-slug`),
     * basic rows a sentence (`<a class="name">` actor, `<a class="target" href="/owner/film/slug/">`, context text
     * like "watched and rated", "liked X’s review of", "added … to their watchlist") and an optional `rated-N`.
     */
    fun parseActivity(html: String): List<LetterboxdActivityItem> =
        Regex("""<section[^>]*class="([^"]*activity-row[^"]*)"[^>]*>([\s\S]*?)</section>""")
            .findAll(html).mapNotNull { match ->
                val classes = match.groupValues[1]
                val row = match.groupValues[2]
                val timestamp = Regex("""<time[^>]*datetime="([^"]+)"""").find(row)?.groupValues?.get(1)
                val actor = Regex("""<a class="avatar[^"]*"\s+href="/([A-Za-z0-9_]+)/"""").find(row)?.groupValues?.get(1)
                    ?: Regex("""<a href="/([A-Za-z0-9_]+)/" class="name"""").find(row)?.groupValues?.get(1)
                    ?: Regex("""data-owner="([^"]+)"""").find(row)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val rating = Regex("""\brated-(\d{1,2})\b""").find(row)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 1..10 }?.let { it / 2.0 }

                if (classes.contains("-review")) {
                    val slug = Regex("""data-item-slug="([^"]+)"""").find(row)?.groupValues?.get(1)
                        ?: Regex("""href="/[A-Za-z0-9_]+/film/([^/"]+)/""").find(row)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    return@mapNotNull LetterboxdActivityItem(
                        member = Regex("""data-owner="([^"]+)"""").find(row)?.groupValues?.get(1) ?: actor,
                        filmSlug = slug,
                        filmName = Regex("""data-item-name="([^"]*)"""").find(row)?.groupValues?.get(1)?.let(::decodeEntities),
                        type = LetterboxdActivityType.Review,
                        rating = rating,
                        timestamp = timestamp,
                    )
                }

                val target = Regex("""<a href="([^"]*)" class="target">([\s\S]*?)</a>""").find(row)
                    ?: return@mapNotNull null
                val href = target.groupValues[1]
                val slug = Regex("""/film/([^/"#]+)/""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val summary = Regex("""<p class="activity-summary">([\s\S]*?)</p""").find(row)
                    ?.groupValues?.get(1).orEmpty()
                val text = summary.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").lowercase()
                // Film title = last text node of the target link, after the context span(s).
                val filmName = Regex("""(?:</span>|^)\s*([^<]+?)\s*$""").find(target.groupValues[2])
                    ?.groupValues?.get(1)?.let(::decodeEntities)?.takeIf { it.isNotBlank() }
                val type = when {
                    "review of" in text && " liked " in " $text " -> LetterboxdActivityType.ReviewLike
                    "commented" in text -> LetterboxdActivityType.Comment
                    "watchlist" in text -> LetterboxdActivityType.Watchlist
                    "reviewed" in text -> LetterboxdActivityType.Review
                    "rated" in text -> LetterboxdActivityType.Rating
                    "watched" in text || "rewatched" in text || "logged" in text -> LetterboxdActivityType.Watch
                    " liked " in " $text " -> LetterboxdActivityType.Like
                    else -> LetterboxdActivityType.Other
                }
                LetterboxdActivityItem(
                    member = actor,
                    filmSlug = slug,
                    filmName = filmName,
                    type = type,
                    // For a liked review the stars belong to the review's author, not to the actor.
                    rating = rating.takeIf { type != LetterboxdActivityType.ReviewLike && type != LetterboxdActivityType.Comment },
                    timestamp = timestamp,
                )
            }.toList()

    /**
     * `/csi/film/<slug>/friend-activity/?esiAllowUser=true` (verified Nov 2026, signed in): one
     * `<li class="listitem -rated|-watched|-watchlisted …">` per followed member, with
     * `<a href="/<member>/…" title="Rated by X">`, the avatar `<img src alt="Display name">` and, when rated,
     * `<span class="rating -nano rated-7">`. Empty for guests or when no friend interacted with the film.
     */
    fun parseFriendActivity(html: String): List<LetterboxdFriendActivity> =
        Regex("""<li[^>]*class="([^"]*\blistitem\b[^"]*)"[^>]*>([\s\S]*?)</li>""").findAll(html).mapNotNull { match ->
            val classes = match.groupValues[1]
            val row = match.groupValues[2]
            val username = Regex("""<a[^>]*href="/([A-Za-z0-9_]+)/""").find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("""<a[^>]*title="([^"]*)"""").find(row)?.groupValues?.get(1)?.let(::decodeEntities).orEmpty()
            val image = Regex("""<img[^>]*>""").find(row)?.value.orEmpty()
            val displayName = Regex("""alt="([^"]*)"""").find(image)?.groupValues?.get(1)?.let(::decodeEntities)
                ?.takeIf { it.isNotBlank() } ?: username
            val rating = Regex("""\brated-(\d{1,2})\b""").find(row)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..10 }?.let { it / 2.0 }
            val wantsToWatch = classes.contains("-watchlisted") ||
                (title.contains("wants to watch", ignoreCase = true) && rating == null)
            LetterboxdFriendActivity(
                username = username,
                displayName = displayName,
                avatarUrl = Regex("""src="([^"]+)"""").find(image)?.groupValues?.get(1)?.let(::decodeEntities),
                rating = rating,
                liked = classes.contains("-liked") || Regex("""class="[^"]*\b(?:like|icon-liked|has-icon icon-liked)\b""").containsMatchIn(row),
                reviewed = classes.contains("-reviewed") || title.startsWith("Reviewed", ignoreCase = true),
                watched = !wantsToWatch,
                wantsToWatch = wantsToWatch,
            )
        }.distinctBy { it.username }.toList()

    /** Slug from a `/film/<slug>/` location header or URL. */
    fun slugFromFilmPath(location: String?): String? =
        location?.let { Regex("""/film/([^/?#]+)/?""").find(it)?.groupValues?.get(1) }

    private fun parseJsonLd(html: String): JsonObject? {
        val raw = Regex("""<script type="application/ld\+json">([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1) ?: return null
        val cleaned = raw.replace("/* <![CDATA[ */", "").replace("/* ]]> */", "").trim()
        return runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
    }

    private fun metaContent(html: String, name: String): String? {
        val escaped = Regex.escape(name)
        return Regex("""<meta\s+(?:name|property)="$escaped"\s+content="([^"]*)"""").find(html)?.groupValues?.get(1)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(value) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> when (entity) {
                    "quot" -> "\""
                    "amp" -> "&"
                    "apos" -> "'"
                    "lt" -> "<"
                    "gt" -> ">"
                    "nbsp" -> " "
                    "lrm" -> ""
                    "rsquo" -> "’"
                    "lsquo" -> "‘"
                    "hellip" -> "…"
                    "bull" -> "•"
                    else -> match.value
                }
            }
        }
    }

    private fun codePointToString(codePoint: Int): String =
        if (codePoint in 0..0xFFFF) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
}
