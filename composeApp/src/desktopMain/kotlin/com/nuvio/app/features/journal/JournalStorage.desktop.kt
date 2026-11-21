package com.nuvio.app.features.journal

import com.nuvio.app.core.storage.DesktopStorage

internal actual object JournalStorage {
    private val store by lazy { DesktopStorage.store("nuuvio_journal") }

    actual val isPersistent: Boolean = true

    actual fun load(name: String): String? = store.getString(name)

    actual fun save(name: String, value: String) {
        store.putString(name, value)
    }
}
