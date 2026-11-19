package com.nuvio.app.features.letterboxd

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.storage.DesktopStorage
import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal actual object LetterboxdPlatform {
    private const val stateKey = "account_state"
    private val store by lazy { DesktopStorage.store("nuuvio_letterboxd") }
    private val cacheFolder: Path by lazy {
        DesktopStorage.cacheDir.resolve("letterboxd").also { Files.createDirectories(it) }
    }

    actual val isSupported: Boolean = true

    actual val secretStorageKind: LetterboxdSecretStorageKind
        get() = LetterboxdSecretStore.currentKind

    /** Same Ktor CIO client as the rest of the desktop app (redirects not followed, no exception on 4xx/5xx). */
    actual fun createHttpClient(): HttpClient = createApiHttpClient()

    actual fun readSecret(): String? = LetterboxdSecretStore.read()

    actual fun writeSecret(value: String) = LetterboxdSecretStore.write(value)

    actual fun deleteSecret() = LetterboxdSecretStore.delete()

    actual fun readState(): String? = store.getString(stateKey)

    actual fun writeState(value: String?) = store.putString(stateKey, value)

    actual fun readCache(name: String): String? {
        val file = cacheFolder.resolve(safeName(name))
        return if (Files.exists(file)) runCatching { Files.readString(file) }.getOrNull() else null
    }

    actual fun writeCache(name: String, value: String) {
        val file = cacheFolder.resolve(safeName(name))
        val temp = Files.createTempFile(cacheFolder, "cache", ".tmp")
        try {
            Files.writeString(temp, value)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
