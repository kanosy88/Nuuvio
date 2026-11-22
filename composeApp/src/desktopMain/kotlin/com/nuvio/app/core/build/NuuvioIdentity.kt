package com.nuvio.app.core.build

/**
 * Nuuvio fork identity (desktop). The fork installs next to the official Nuvio, so everything that is
 * named after the app on disk (data folders, Windows shortcuts, launcher) uses [APP_NAME], and the
 * in-app updater only looks at the fork's own GitHub Releases. See README-NUUVIO.md.
 */
internal object NuuvioIdentity {
    const val APP_NAME = "Nuuvio"
    const val GITHUB_OWNER = "kanosy88"
    const val GITHUB_REPO = "Nuuvio"

    /** Release workflow run number, baked into release builds as `-Dnuuvio.build=<n>`; null for local builds. */
    val buildNumber: String?
        get() = System.getProperty("nuuvio.build")?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /**
     * Version the in-app updater compares with the fork's release tags `nuuvio-<upstream tag>-<run>`:
     * `0.1.37-alpha` + build 12 → `0.1.37-alpha-12` (same numeric parts as `nuuvio-0.1.37-alpha-12`).
     */
    fun updaterVersionName(upstreamVersion: String): String =
        buildNumber?.let { "$upstreamVersion-$it" } ?: upstreamVersion
}
