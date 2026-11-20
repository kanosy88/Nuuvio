package com.nuvio.app.features.letterboxd

import io.ktor.client.HttpClient

/**
 * Platform services for the Letterboxd integration. Only the desktop target implements it for now;
 * other targets report [isSupported] = false and the settings entry is hidden.
 * All functions may block (file / keychain access): call them off the main thread.
 */
internal expect object LetterboxdPlatform {
    val isSupported: Boolean
    val secretStorageKind: LetterboxdSecretStorageKind

    fun createHttpClient(): HttpClient

    /** Session cookies + user agent (JSON), stored in the OS keychain when available. */
    fun readSecret(): String?
    fun writeSecret(value: String)
    fun deleteSecret()

    /** Non-secret account state (username, status). */
    fun readState(): String?
    fun writeState(value: String?)

    /** Non-secret feature settings (JSON), kept with the app settings (not in the disk cache). */
    fun readPreference(name: String): String?
    fun writePreference(name: String, value: String?)

    /** Disk cache files (TMDB↔slug index, film details…). */
    fun readCache(name: String): String?
    fun writeCache(name: String, value: String)
}
