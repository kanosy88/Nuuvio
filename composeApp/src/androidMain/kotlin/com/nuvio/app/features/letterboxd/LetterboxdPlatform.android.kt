package com.nuvio.app.features.letterboxd

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.HttpClient

/** Letterboxd is a desktop-only integration in this fork: nothing is stored on this platform. */
internal actual object LetterboxdPlatform {
    actual val isSupported: Boolean = false
    actual val secretStorageKind: LetterboxdSecretStorageKind = LetterboxdSecretStorageKind.Unavailable

    actual fun createHttpClient(): HttpClient = createApiHttpClient()

    actual fun readSecret(): String? = null
    actual fun writeSecret(value: String) = throw UnsupportedOperationException("Letterboxd is not supported on this platform")
    actual fun deleteSecret() = Unit

    actual fun readState(): String? = null
    actual fun writeState(value: String?) = Unit

    actual fun readCache(name: String): String? = null
    actual fun writeCache(name: String, value: String) = Unit
}
