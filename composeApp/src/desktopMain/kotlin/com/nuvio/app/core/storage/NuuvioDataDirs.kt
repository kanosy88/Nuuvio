package com.nuvio.app.core.storage

import com.nuvio.app.core.build.NuuvioIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator

/**
 * Nuuvio fork: the app keeps its data next to the official Nuvio's instead of sharing it
 * (`…/Nuvio` → `…/Nuuvio`, `~/.config/nuvio` → `~/.config/nuuvio`).
 *
 * The first time a Nuuvio folder is needed and does not exist yet, the matching Nuvio folder is
 * COPIED into it (never moved), so settings, add-ons, profiles, Letterboxd cookies… carry over.
 * Only a missing folder triggers the copy: `DesktopStorage.wipe()` keeps the (empty) root folder,
 * so a "clear local data" is never undone by a re-copy at the next launch.
 */
internal object NuuvioDataDirs {
    /** Downloaded installers of the official app: never copied. */
    private val appDataSkipped = setOf("updates")

    /** Only the caches worth keeping (Letterboxd film index, feeds); images etc. are rebuilt. */
    private val cacheCopied = setOf("letterboxd")

    fun appData(upstreamDir: Path): Path =
        adopt(upstreamDir) { topLevel -> topLevel !in appDataSkipped }

    fun cache(upstreamDir: Path): Path =
        adopt(upstreamDir) { topLevel -> topLevel in cacheCopied }

    /** Replaces the last `Nuvio`/`nuvio` path element by `Nuuvio`/`nuuvio`; unchanged if there is none. */
    internal fun forkPath(upstreamDir: Path): Path {
        val names = (0 until upstreamDir.nameCount).map { upstreamDir.getName(it).toString() }
        val index = names.indexOfLast { it.equals("nuvio", ignoreCase = true) }
        if (index < 0) return upstreamDir
        val replacement = if (names[index] == "nuvio") NuuvioIdentity.APP_NAME.lowercase() else NuuvioIdentity.APP_NAME
        val start = upstreamDir.root ?: Paths.get("")
        return names.foldIndexed(start) { i, acc, name -> acc.resolve(if (i == index) replacement else name) }
    }

    internal fun adopt(upstreamDir: Path, includeTopLevel: (String) -> Boolean): Path {
        val target = forkPath(upstreamDir)
        if (target == upstreamDir) return upstreamDir
        runCatching { copyIfMissing(upstreamDir, target, includeTopLevel) }
            .onFailure { System.err.println("[Nuuvio] Could not copy $upstreamDir to $target: $it") }
        return target
    }

    private fun copyIfMissing(source: Path, target: Path, includeTopLevel: (String) -> Boolean) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(source)) return
        target.parent?.let { Files.createDirectories(it) }
        val staging = target.resolveSibling(".${target.fileName}.copying")
        deleteRecursively(staging)
        Files.createDirectories(staging)
        copyPermissions(source, staging)
        Files.walk(source).use { stream ->
            stream.sorted().forEach { path ->
                val relative = source.relativize(path)
                if (relative.toString().isEmpty()) return@forEach
                if (!includeTopLevel(relative.getName(0).toString())) return@forEach
                val destination = staging.resolve(relative.toString())
                when {
                    Files.isSymbolicLink(path) -> Unit
                    Files.isDirectory(path) -> {
                        Files.createDirectories(destination)
                        copyPermissions(path, destination)
                    }
                    Files.isRegularFile(path) -> {
                        destination.parent?.let { Files.createDirectories(it) }
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(staging, target)
        }
        System.err.println("[Nuuvio] Copied existing data from $source to $target")
    }

    private fun copyPermissions(from: Path, to: Path) {
        runCatching { Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from)) }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
