package com.nuvio.app.features.letterboxd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetterboxdParsersTest {
    /**
     * Real excerpt of /film/the-matrix/ fetched with a signed-in member's cookies (2026-11-19), anonymised
     * (username, CSRF token, member LID and avatar URL replaced, long lists trimmed). The cached guest literal
     * comes first, the per-member overrides are injected later in the page.
     */
    private val signedInFilmPage = "\t\t\t\treturn policies[sharePolicy]\n" +
        "\t\t\t},\n" +
        "\t\t\tperson = {\n" +
        "\t\t\t\tusername: \"\"\n" +
        "\t\t\t\t, loggedIn: false\n" +
        "\t\t\t\t\n" +
        "\t\t\t\t, showAds: true\n" +
        "\t\t\t\t, role: \"guest\"\n" +
        "\t\t\t\t, canBulkAddToLists: false\n" +
        "\t\t\t\t, blockedMembers: []\n" +
        "\t\t\t\t, validated: null\n" +
        "\t\t\t\t, trusted: false\n" +
        "\t\t\t\t, getCustomPoster : function(uid) { return null; }\n" +
        "\t\t\t},\n" +
        "\t\t\tdisableAds = false,\n" +
        "\t\t\tanalytic_params = {};\n" +
        "\t\t\n" +
        "\t\t\n" +
        "supermodelCSRF = '0123456789abcdef0123'; geolocation.country = 'US'; analytic_params['user_type'] = 'Member'; " +
        "analytic_params['member_type'] = 'FREE'; person.username = \"jane_doe\"; person.lid = \"AbCd1\"; " +
        "person.loggedIn = true; person.profileLink = \"/jane_doe/\"; person.optimisedPosters = false; " +
        "person.role = \"member\"; person.moderator = false; person.showAds = true; person.canUpgradeToPro = true; " +
        "person.blockedMembers = []; person.validated = true; person.trusted = true; " +
        "person.avatarURL16 = \"https://a.ltrbxd.com/resized/avatar/upload/0/0/0/0/shard/avtr-0-48-0-48-crop.jpg?v=0000000000\"; " +
        "person.posterMode = \"All\";\n" +
        "\t<script>\n\tASAP(function() {\n\t\tif ( \$.cookie(\"letterboxd.signed.in.as\") === person.username ) {\n"
    private val filmPage = """
        <html><head>
        <meta property="og:title" content="The Matrix (1999)">
        <meta name="production:identifier" content="{&quot;lid&quot;:&quot;2a1m&quot;,&quot;uid&quot;:&quot;film:51518&quot;,&quot;type&quot;:&quot;film&quot;,&quot;typeName&quot;:&quot;film&quot;}">
        <meta name="production:name" content="The Matrix">
        <meta name="production:name-and-year" content="The Matrix (1999)">
        <script>
            person = {
                username: ""
                , loggedIn: false
            }
            supermodelCSRF = '57785f6c28b7ce0cae4d';
        </script>
        <script type="application/ld+json">
        /* <![CDATA[ */
        {"image":"https://a.ltrbxd.com/poster.jpg","@type":"Movie","name":"The Matrix","aggregateRating":{"bestRating":5,"reviewCount":352088,"@type":"AggregateRating","ratingValue":4.18,"ratingCount":3214163,"worstRating":0.5}}
        /* ]]> */
        </script>
        </head><body>
        <div data-tmdb-type="movie" data-tmdb-id="603"></div>
        <a href="http://www.imdb.com/title/tt0133093/maindetails">IMDb</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesFilmPage() {
        val film = LetterboxdParsers.parseFilmPage("the-matrix", filmPage, null)
        assertEquals("2a1m", film.lid)
        assertEquals("film:51518", film.uid)
        assertEquals(603, film.tmdbId)
        assertEquals("tt0133093", film.imdbId)
        assertEquals("The Matrix", film.name)
        assertEquals(1999, film.year)
        assertEquals(4.18, film.averageRating)
        assertEquals(3214163, film.ratingCount)
        assertEquals(352088, film.reviewCount)
        assertEquals("https://letterboxd.com/film/the-matrix/", film.url)
    }

    @Test
    fun parsesGuestAndSignedInPersonBlock() {
        assertNull(LetterboxdParsers.parseSignedInUsername(filmPage))
        assertTrue(LetterboxdParsers.isGuestPage(filmPage))
        assertEquals("57785f6c28b7ce0cae4d", LetterboxdParsers.parseCsrfToken(filmPage))
        val signedIn = "person = {\n\t\t\t\tusername: \"jane_doe\"\n\t\t\t\t, loggedIn: true\n"
        assertEquals("jane_doe", LetterboxdParsers.parseSignedInUsername(signedIn))
        assertFalse(LetterboxdParsers.isGuestPage(signedIn))
    }

    @Test
    fun signedInPageOverridesCachedGuestLiteral() {
        val session = LetterboxdParsers.detectSession(signedInFilmPage)
        assertEquals(LetterboxdPageSession.State.SignedIn, session.state)
        assertEquals("jane_doe", session.username)
        assertEquals("jane_doe", LetterboxdParsers.parseSignedInUsername(signedInFilmPage))
        assertFalse(LetterboxdParsers.isGuestPage(signedInFilmPage))
        assertEquals("0123456789abcdef0123", LetterboxdParsers.parseCsrfToken(signedInFilmPage))
    }

    @Test
    fun sessionDetectionUsesSeveralMarkers() {
        // Only user_type + profileLink left (person.username / loggedIn assignments renamed): still signed in.
        val partial = signedInFilmPage.replace("person.username =", "person.name =").replace("person.loggedIn = true;", "")
        assertEquals(LetterboxdPageSession(LetterboxdPageSession.State.SignedIn, "jane_doe"), LetterboxdParsers.detectSession(partial))
        // Contradictory markers are not reported as an expired session.
        val contradictory = signedInFilmPage.replace("person.loggedIn = true", "person.loggedIn = false")
        assertEquals(LetterboxdPageSession.State.Unknown, LetterboxdParsers.detectSession(contradictory).state)
        // No marker at all (markup changed / unexpected page).
        assertEquals(LetterboxdPageSession.State.Unknown, LetterboxdParsers.detectSession("<html></html>").state)
        // Guest page: cached literal, empty CSRF placeholder, Visitor analytics.
        val guest = "supermodelCSRF = \"\", person = {\n username: \"\"\n , loggedIn: false\n , role: \"guest\" };" +
            " supermodelCSRF = 'abc123'; analytic_params['user_type'] = 'Visitor';"
        assertEquals(LetterboxdPageSession.State.Guest, LetterboxdParsers.detectSession(guest).state)
        assertEquals("abc123", LetterboxdParsers.parseCsrfToken(guest))
    }

    @Test
    fun detectsCloudflareChallenge() {
        val challenge = "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt = {}</script></body></html>"
        assertTrue(LetterboxdParsers.isCloudflareChallenge(403, challenge, emptyMap()))
        assertTrue(LetterboxdParsers.isCloudflareChallenge(200, "", mapOf("cf-mitigated" to listOf("challenge"))))
        assertFalse(LetterboxdParsers.isCloudflareChallenge(200, filmPage, emptyMap()))
        assertFalse(LetterboxdParsers.isCloudflareChallenge(403, "{\"result\":false}", emptyMap()))
    }

    @Test
    fun parsesWatchlistGrid() {
        val html = """
            <ul class="grid">
            <li class="griditem"> <div class="react-component" data-component-class="LazyPoster" data-item-name="Black Bag (2025)" data-item-slug="black-bag-2025" data-item-link="/film/black-bag-2025/" data-postered-identifier='{&quot;lid&quot;:&quot;KRbM&quot;,&quot;uid&quot;:&quot;film:1116755&quot;,&quot;type&quot;:&quot;film&quot;}'></div> </li>
            <li class="griditem"> <div data-item-name="Heat (1995)" data-item-slug="heat-1995"></div> </li>
            </ul>
            <div class="paginate-nextprev"><a class="next" href="/dave/watchlist/page/2/">Older</a></div>
        """.trimIndent()
        val items = LetterboxdParsers.parseWatchlistPage(html)
        assertEquals(2, items.size)
        assertEquals(LetterboxdWatchlistItem("black-bag-2025", "KRbM", "film:1116755", "Black Bag (2025)"), items[0])
        assertEquals("heat-1995", items[1].slug)
        assertTrue(LetterboxdParsers.hasNextPage(html))
        assertFalse(LetterboxdParsers.hasNextPage("<div></div>"))
    }

    @Test
    fun parsesDiaryRss() {
        val xml = """
            <rss><channel>
            <item> <title>Being Heumann, 2026 - ★★★</title> <link>https://letterboxd.com/dave/film/being-heumann/</link> <guid isPermaLink="false">letterboxd-watch-1591461084</guid> <pubDate>Wed, 18 Nov 2026 11:30:43 +1300</pubDate> <letterboxd:watchedDate>2026-11-17</letterboxd:watchedDate> <letterboxd:rewatch>No</letterboxd:rewatch> <letterboxd:filmTitle>Being Heumann</letterboxd:filmTitle> <letterboxd:filmYear>2026</letterboxd:filmYear> <letterboxd:memberRating>3.0</letterboxd:memberRating> <letterboxd:memberLike>Yes</letterboxd:memberLike> <tmdb:movieId>1050981</tmdb:movieId> </item>
            <item> <title>My list</title> <link>https://letterboxd.com/dave/list/x/</link> <guid>letterboxd-list-1</guid> </item>
            </channel></rss>
        """.trimIndent()
        val entries = LetterboxdParsers.parseDiaryRss(xml, "dave")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("being-heumann", entry.filmSlug)
        assertEquals(1050981, entry.tmdbId)
        assertEquals(3.0, entry.rating)
        assertTrue(entry.liked)
        assertFalse(entry.rewatch)
        assertEquals("2026-11-17", entry.watchedDate)
    }

    @Test
    fun parsesRatingHistogram() {
        val rows = (1..10).joinToString("") { i ->
            val title = if (i == 2) "" else " title=\"${i},000 &#9733; ratings (1%)\""
            "<tr class=\"column\" style=\"--value: 0.1;\"><th scope=\"row\" class=\"_sr-only\">x</th><td class=\"cell\"><a href=\"/film/x/ratings/rated/$i/by/rating/\" class=\"barcolumn tooltip\"$title><span class=\"_sr-only\">${i}K (1%)</span></a></td></tr>"
        }
        val histogram = LetterboxdParsers.parseRatingHistogram("<table class=\"chart\"><tbody class=\"plot\">$rows</tbody></table>")
        assertEquals(listOf(1000, 0, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000), histogram?.counts)
        assertNull(LetterboxdParsers.parseRatingHistogram("<div></div>"))
    }

    @Test
    fun parsesFollowingActivity() {
        val html = """
            <section data-activity-id="1" class="activity-row -review activity-review-from-others" > <div class="table-activity-user"> <a class="avatar -a24" href="/howatdk/" ><img alt="D" /></a> </div> <div class="table-activity-viewing"> <article class="production-viewing -viewing" data-owner="howatdk" data-object-name="review"> <div class="react-component figure" data-item-name="Ray Gunn (2026)" data-item-slug="ray-gunn" data-item-link="/film/ray-gunn/"></div> <span class="rating -green rated-8"> ★★★★ </span> </article> </div> <time datetime="2026-11-19T05:55:47.536Z" class="time">x</time> </section>
            <section data-activity-id="2" class="activity-row -basic" > <div class="table-activity-user"> <a class="avatar -a24" href="/jaimerebanal/" ><img /></a> </div> <div class="table-activity-description"> <p class="activity-summary"> <a href="/jaimerebanal/" class="name"><strong>Jaime</strong></a> liked <strong class="name">Josh Lewis&rsquo;s</strong> <a href="/thejoshl/film/i-am-sam/" class="target"><span class="context"><span class="rating -tiny rated-2"> ★ </span> review of</span> I Am Sam</a> </p"> </div> <time datetime="2026-11-19T04:33:31.110Z" class="time">x</time> </section>
            <section data-activity-id="3" class="activity-row -basic" > <div class="table-activity-user"> <a class="avatar -a24" href="/nadreviews/" ><img /></a> </div> <div class="table-activity-description"> <p class="activity-summary"> <a href="/nadreviews/" class="name"><strong>Nadia</strong></a> <a href="/nadreviews/film/misty-green/" class="target"> <span class="context"> watched and rated </span> Misty Green </a> <span class="rating -tiny rated-4"> ★★ </span> on Wednesday </p"> </div> <time datetime="2026-11-19T03:49:01.847Z" class="time">x</time> </section>
        """.trimIndent()
        val items = LetterboxdParsers.parseActivity(html)
        assertEquals(3, items.size)
        assertEquals(LetterboxdActivityItem("howatdk", "ray-gunn", "Ray Gunn (2026)", LetterboxdActivityType.Review, 4.0, "2026-11-19T05:55:47.536Z"), items[0])
        assertEquals(LetterboxdActivityType.ReviewLike, items[1].type)
        assertEquals("jaimerebanal", items[1].member)
        assertEquals("i-am-sam", items[1].filmSlug)
        assertEquals("I Am Sam", items[1].filmName)
        assertNull(items[1].rating)
        assertEquals(LetterboxdActivityItem("nadreviews", "misty-green", "Misty Green", LetterboxdActivityType.Rating, 2.0, "2026-11-19T03:49:01.847Z"), items[2])
    }

    @Test
    fun slugFromLocation() {
        assertEquals("the-matrix", LetterboxdParsers.slugFromFilmPath("/film/the-matrix/"))
        assertEquals("heat-1995", LetterboxdParsers.slugFromFilmPath("https://letterboxd.com/film/heat-1995/"))
        assertNull(LetterboxdParsers.slugFromFilmPath("/sign-in/"))
    }
}
