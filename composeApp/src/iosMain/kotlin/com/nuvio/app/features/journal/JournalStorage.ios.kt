package com.nuvio.app.features.journal

/** The Journal is a desktop screen: nothing is persisted on this target. */
internal actual object JournalStorage {
    actual val isPersistent: Boolean = false

    actual fun load(name: String): String? = null

    actual fun save(name: String, value: String) = Unit
}
