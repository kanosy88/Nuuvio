package com.nuvio.app.features.letterboxd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LetterboxdCookiesTest {
    @Test
    fun parsesCookieHeader() {
        val cookies = parseLetterboxdCookies("Cookie: letterboxd.user.CURRENT=abc%3D; com.xk72.webparts.csrf=0123; cf_clearance=x.y-z")
        assertEquals(listOf("letterboxd.user.CURRENT", "com.xk72.webparts.csrf", "cf_clearance"), cookies.keys.toList())
        assertEquals("abc%3D", cookies["letterboxd.user.CURRENT"])
        assertEquals("letterboxd.user.CURRENT=abc%3D; com.xk72.webparts.csrf=0123; cf_clearance=x.y-z", cookies.toCookieHeader())
    }

    @Test
    fun parsesJsonExportAndDropsOtherDomains() {
        val cookies = parseLetterboxdCookies(
            """[{"domain":".letterboxd.com","name":"cf_clearance","value":"cf"},
                {"domain":"letterboxd.com","name":"letterboxd.user.CURRENT","value":"session"},
                {"domain":".google.com","name":"NID","value":"x"}]""",
        )
        assertEquals(mapOf("cf_clearance" to "cf", "letterboxd.user.CURRENT" to "session"), cookies)
    }

    @Test
    fun parsesNetscapeCookiesTxt() {
        val text = "# Netscape HTTP Cookie File\n" +
            ".letterboxd.com\tTRUE\t/\tTRUE\t1893456000\tcf_clearance\tcf\n" +
            "#HttpOnly_letterboxd.com\tFALSE\t/\tTRUE\t1893456000\tletterboxd.user.CURRENT\tsession\n" +
            ".example.com\tTRUE\t/\tTRUE\t0\tother\tx\n"
        assertEquals(mapOf("cf_clearance" to "cf", "letterboxd.user.CURRENT" to "session"), parseLetterboxdCookies(text))
    }

    @Test
    fun parsesOnePairPerLine() {
        val cookies = parseLetterboxdCookies("letterboxd.user.CURRENT=s\ncf_clearance=c\n")
        assertEquals(mapOf("letterboxd.user.CURRENT" to "s", "cf_clearance" to "c"), cookies)
    }

    @Test
    fun emptyOrGarbageGivesNoCookie() {
        assertTrue(parseLetterboxdCookies("   ").isEmpty())
        assertTrue(parseLetterboxdCookies("hello world").isEmpty())
    }

    @Test
    fun appliesSetCookieRotation() {
        val jar = linkedMapOf("com.xk72.webparts.csrf" to "old", "letterboxd.user.CURRENT" to "s")
        val changed = applySetCookieHeaders(
            jar,
            listOf(
                "com.xk72.webparts.csrf=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=letterboxd.com",
                "com.xk72.webparts.csrf=new; Path=/; HttpOnly; Secure",
            ),
        )
        assertTrue(changed)
        assertEquals("new", jar["com.xk72.webparts.csrf"])
        assertFalse(applySetCookieHeaders(jar, listOf("com.xk72.webparts.csrf=new; Path=/")))
        applySetCookieHeaders(jar, listOf("letterboxd.user.CURRENT=; Max-Age=0; Path=/"))
        assertFalse(jar.containsKey("letterboxd.user.CURRENT"))
    }
}
