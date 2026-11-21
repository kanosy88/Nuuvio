package com.nuvio.app.features.journal

/**
 * Persistent storage of the journal (JSON documents by name: `profile_<id>`, `meta`, `settings`).
 * Desktop keeps them with the app settings; other targets don't persist (the Journal is a desktop screen).
 * May block: call off the main thread.
 */
internal expect object JournalStorage {
    val isPersistent: Boolean
    fun load(name: String): String?
    fun save(name: String, value: String)
}
