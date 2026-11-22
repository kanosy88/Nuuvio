package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuuvioDataDirsTest {
    @Test
    fun forkPathRenamesTheLastNuvioElementKeepingItsCase() {
        assertEquals(
            Paths.get("/Users/me/Library/Application Support/Nuuvio"),
            NuuvioDataDirs.forkPath(Paths.get("/Users/me/Library/Application Support/Nuvio")),
        )
        assertEquals(Paths.get("/home/me/.config/nuuvio"), NuuvioDataDirs.forkPath(Paths.get("/home/me/.config/nuvio")))
        assertEquals(
            Paths.get("C:/Users/me/AppData/Local/Nuuvio/Cache"),
            NuuvioDataDirs.forkPath(Paths.get("C:/Users/me/AppData/Local/Nuvio/Cache")),
        )
        assertEquals(Paths.get("/tmp/other"), NuuvioDataDirs.forkPath(Paths.get("/tmp/other")))
    }

    @Test
    fun copiesOnceWhenTheForkFolderIsMissingAndNeverTouchesTheSource() {
        val base = Files.createTempDirectory("nuuvio-dirs")
        val source = base.resolve("Nuvio")
        Files.createDirectories(source.resolve("letterboxd"))
        Files.createDirectories(source.resolve("updates"))
        Files.writeString(source.resolve("nuvio_profiles.properties"), "a=1")
        Files.writeString(source.resolve("letterboxd/secret.bin"), "s")
        Files.writeString(source.resolve("updates/Nuvio.dmg"), "x")
        runCatching {
            Files.setPosixFilePermissions(source.resolve("letterboxd"), PosixFilePermissions.fromString("rwx------"))
        }

        val target = NuuvioDataDirs.adopt(source) { it != "updates" }

        assertEquals(base.resolve("Nuuvio"), target)
        assertEquals("a=1", Files.readString(target.resolve("nuvio_profiles.properties")))
        assertEquals("s", Files.readString(target.resolve("letterboxd/secret.bin")))
        assertFalse(Files.exists(target.resolve("updates")))
        assertFalse(Files.exists(base.resolve(".Nuuvio.copying")))
        runCatching {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("letterboxd"))))
        }
        assertTrue(Files.exists(source.resolve("updates/Nuvio.dmg")), "source must be left untouched")

        // Second launch, or after a wipe that left an empty folder: no re-copy.
        Files.writeString(source.resolve("nuvio_profiles.properties"), "a=2")
        Files.delete(target.resolve("nuvio_profiles.properties"))
        NuuvioDataDirs.adopt(source) { true }
        assertFalse(Files.exists(target.resolve("nuvio_profiles.properties")))
    }

    @Test
    fun missingSourceJustYieldsTheForkPath() {
        val base: Path = Files.createTempDirectory("nuuvio-dirs-empty")
        val target = NuuvioDataDirs.adopt(base.resolve("nuvio")) { true }
        assertEquals(base.resolve("nuuvio"), target)
        assertFalse(Files.exists(target))
    }
}
