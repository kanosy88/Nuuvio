package com.nuvio.app.features.letterboxd

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxdRateLimitsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun defaultsAreTheHistoricalValues() {
        val defaults = LetterboxdRateLimits()
        assertEquals(350, defaults.requestGapMs)
        assertEquals(1_500, defaults.syncReadGapMs)
        assertEquals(2_000, defaults.syncWriteGapMs)
        assertEquals(5, defaults.friendsMaxPages)
        assertEquals(1_500, defaults.friendsPageGapMs)
        assertEquals(10, defaults.friendsResolveBatch)
        assertEquals(1_500, defaults.friendsResolveGapMs)
        assertEquals(60, defaults.friendsMinAutoRefreshSec)
        assertEquals(360, defaults.ownDiaryCacheMin)
        assertEquals(90, defaults.personalCacheSec)
        assertEquals(defaults, defaults.sanitized(), "defaults must already be inside their ranges")
    }

    @Test
    fun sanitizedClampsEveryValueToItsRange() {
        val low = LetterboxdRateLimits(
            requestGapMs = 0, syncReadGapMs = 1, syncWriteGapMs = 1, friendsMaxPages = 0, friendsPageGapMs = 0,
            friendsResolveBatch = 0, friendsResolveGapMs = 0, friendsMinAutoRefreshSec = 0, ownDiaryCacheMin = 0,
            personalCacheSec = -5,
        ).sanitized()
        assertEquals(100, low.requestGapMs)
        assertEquals(500, low.syncReadGapMs)
        assertEquals(500, low.syncWriteGapMs)
        assertEquals(1, low.friendsMaxPages)
        assertEquals(500, low.friendsPageGapMs)
        assertEquals(1, low.friendsResolveBatch)
        assertEquals(500, low.friendsResolveGapMs)
        assertEquals(10, low.friendsMinAutoRefreshSec)
        assertEquals(30, low.ownDiaryCacheMin)
        assertEquals(0, low.personalCacheSec)

        val high = LetterboxdRateLimits(
            requestGapMs = 60_000, syncReadGapMs = 60_000, syncWriteGapMs = 60_000, friendsMaxPages = 500,
            friendsPageGapMs = 60_000, friendsResolveBatch = 500, friendsResolveGapMs = 60_000,
            friendsMinAutoRefreshSec = 9_999, ownDiaryCacheMin = 9_999, personalCacheSec = 9_999,
        ).sanitized()
        assertEquals(5_000, high.requestGapMs)
        assertEquals(30_000, high.syncReadGapMs)
        assertEquals(30_000, high.syncWriteGapMs)
        assertEquals(20, high.friendsMaxPages)
        assertEquals(30_000, high.friendsPageGapMs)
        assertEquals(50, high.friendsResolveBatch)
        assertEquals(30_000, high.friendsResolveGapMs)
        assertEquals(600, high.friendsMinAutoRefreshSec)
        assertEquals(1_440, high.ownDiaryCacheMin)
        assertEquals(600, high.personalCacheSec)
    }

    @Test
    fun storedJsonRoundTripsAndIgnoresUnknownKeys() {
        val custom = LetterboxdRateLimits(syncReadGapMs = 3_000, friendsResolveBatch = 7, personalCacheSec = 0)
        val encoded = json.encodeToString(LetterboxdRateLimits.serializer(), custom)
        assertEquals(custom, json.decodeFromString(LetterboxdRateLimits.serializer(), encoded))

        val withUnknown = """{"syncReadGapMs":2500,"someFutureKey":true}"""
        val decoded = json.decodeFromString(LetterboxdRateLimits.serializer(), withUnknown)
        assertEquals(2_500, decoded.syncReadGapMs)
        assertEquals(LetterboxdRateLimits().requestGapMs, decoded.requestGapMs)
    }
}
