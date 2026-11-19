package com.nuvio.app.features.letterboxd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val LETTERBOXD_CSRF_COOKIE = "com.xk72.webparts.csrf"
internal const val LETTERBOXD_CLEARANCE_COOKIE = "cf_clearance"

/**
 * Session cookies set by Letterboxd at sign-in (verified with a real session, 2026-11-19): each one alone is
 * enough to be signed in. `letterboxd.signed.in.as` only holds the username (no auth value).
 */
internal const val LETTERBOXD_SESSION_COOKIE = "letterboxd.user.CURRENT"
internal const val LETTERBOXD_LEGACY_SESSION_COOKIE = "letterboxd.user"
internal const val LETTERBOXD_SIGNED_IN_AS_COOKIE = "letterboxd.signed.in.as"
internal val LETTERBOXD_SESSION_COOKIES = setOf(LETTERBOXD_SESSION_COOKIE, LETTERBOXD_LEGACY_SESSION_COOKIE)

/**
 * Parses cookies pasted by the user. Accepted formats:
 * - a `Cookie:` request header value (`a=1; b=2`), with or without the `Cookie:` prefix;
 * - a Netscape `cookies.txt` export (tab separated, 7 columns);
 * - a JSON array export (`[{"name": "...", "value": "...", "domain": "..."}]`, Cookie-Editor / EditThisCookie);
 * - one `name=value` or `name<TAB>value` pair per line (DevTools table copy).
 * Cookies for other domains are dropped. Order is preserved, later duplicates win.
 */
internal fun parseLetterboxdCookies(raw: String): LinkedHashMap<String, String> {
    val text = raw.trim().removePrefix("﻿")
    val result = LinkedHashMap<String, String>()
    if (text.isEmpty()) return result

    if (text.startsWith("[") || text.startsWith("{")) {
        runCatching { parseJsonCookies(text, result) }
        if (result.isNotEmpty()) return result
    }

    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val netscape = lines.filterNot { it.startsWith("#") && !it.startsWith("#HttpOnly_") }
        .map { it.removePrefix("#HttpOnly_").split('\t') }
    if (netscape.isNotEmpty() && netscape.all { it.size >= 7 }) {
        netscape.forEach { columns ->
            if (isLetterboxdDomain(columns[0])) putCookie(result, columns[5], columns[6])
        }
        return result
    }

    if (lines.size > 1 && lines.none { it.contains(';') }) {
        lines.forEach { line ->
            val columns = line.split('\t')
            if (columns.size >= 2 && !columns[0].contains('=')) {
                // DevTools table copy: name, value, domain, …
                val domain = columns.getOrNull(2)
                if (domain == null || isLetterboxdDomain(domain)) putCookie(result, columns[0], columns[1])
            } else {
                parseHeaderPairs(line, result)
            }
        }
        return result
    }

    parseHeaderPairs(lines.joinToString("; "), result)
    return result
}

private fun parseHeaderPairs(header: String, into: MutableMap<String, String>) {
    header.trim()
        .removePrefix("Cookie:").removePrefix("cookie:")
        .split(';')
        .forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) putCookie(into, pair.substring(0, index), pair.substring(index + 1))
        }
}

private fun parseJsonCookies(text: String, into: MutableMap<String, String>) {
    val element = Json.parseToJsonElement(text)
    val items = when (element) {
        is JsonArray -> element
        is JsonObject -> (element["cookies"] as? JsonArray) ?: JsonArray(listOf(element))
        else -> return
    }
    items.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
        val value = obj["value"]?.jsonPrimitive?.content ?: return@forEach
        val domain = obj["domain"]?.jsonPrimitive?.content
        if (domain == null || isLetterboxdDomain(domain)) putCookie(into, name, value)
    }
}

private fun putCookie(into: MutableMap<String, String>, name: String, value: String) {
    val cleanName = name.trim()
    val cleanValue = value.trim().removeSurrounding("\"")
    if (cleanName.isEmpty() || cleanName.any { it.isWhitespace() || it == ';' || it == ',' }) return
    if (cleanValue.any { it == ';' || it == '\n' || it == '\r' }) return
    into.remove(cleanName)
    into[cleanName] = cleanValue
}

private fun isLetterboxdDomain(domain: String): Boolean {
    val host = domain.trim().trimStart('.').lowercase()
    return host == "letterboxd.com" || host.endsWith(".letterboxd.com")
}

internal fun Map<String, String>.toCookieHeader(): String =
    entries.joinToString("; ") { (name, value) -> "$name=$value" }

/**
 * Applies `Set-Cookie` response headers to [jar]. Returns true when a value changed.
 * Expired/empty cookies are removed.
 */
internal fun applySetCookieHeaders(jar: MutableMap<String, String>, setCookies: List<String>): Boolean {
    var changed = false
    setCookies.forEach { header ->
        val parts = header.split(';')
        val first = parts.firstOrNull() ?: return@forEach
        val index = first.indexOf('=')
        if (index <= 0) return@forEach
        val name = first.substring(0, index).trim()
        val value = first.substring(index + 1).trim()
        val attributes = parts.drop(1).map { it.trim().lowercase() }
        val domainAttr = attributes.firstOrNull { it.startsWith("domain=") }?.removePrefix("domain=")
        if (domainAttr != null && !isLetterboxdDomain(domainAttr)) return@forEach
        val expired = value.isEmpty() || attributes.any { it == "max-age=0" || it.startsWith("max-age=-") }
        if (expired) {
            // Letterboxd sends "csrf=; Max-Age=0" immediately followed by the new value: only drop if not re-set.
            val reSet = setCookies.any { other ->
                other !== header && other.substringBefore(';').let { it.startsWith("$name=") && it.length > name.length + 1 }
            }
            if (!reSet && jar.remove(name) != null) changed = true
        } else if (jar[name] != value) {
            jar[name] = value
            changed = true
        }
    }
    return changed
}
