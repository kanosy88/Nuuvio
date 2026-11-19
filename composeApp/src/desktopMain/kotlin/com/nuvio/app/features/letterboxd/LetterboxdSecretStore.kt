package com.nuvio.app.features.letterboxd

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Secure storage for the Letterboxd session cookies, without new dependencies:
 * - macOS: login Keychain through `/usr/bin/security` (secret sent on stdin via `security -i`, never in argv);
 * - Windows: DPAPI (`CryptProtectData`, current user scope) through JNA, ciphertext in the app data folder;
 * - Linux: Secret Service through `secret-tool` (libsecret) when installed, secret sent on stdin;
 * - fallback: JSON file readable by the owner only (0600 in a 0700 folder; default user-profile ACL on Windows).
 */
internal object LetterboxdSecretStore {
    private const val SERVICE = "nuuvio.letterboxd"
    private const val ACCOUNT = "session-cookies"
    private const val TIMEOUT_SECONDS = 15L

    private val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val isMac = osName.contains("mac")
    private val isWindows = osName.contains("win")

    private val folder: Path by lazy {
        DesktopStorage.rootDir.resolve("letterboxd").also { dir ->
            Files.createDirectories(dir)
            restrictPermissions(dir, "rwx------")
        }
    }
    private val fallbackFile: Path get() = folder.resolve("credentials.json")
    private val dpapiFile: Path get() = folder.resolve("credentials.dpapi")

    @Volatile
    private var lastWriteKind: LetterboxdSecretStorageKind? = null

    val preferredKind: LetterboxdSecretStorageKind by lazy {
        when {
            isMac && Files.isExecutable(Path.of("/usr/bin/security")) -> LetterboxdSecretStorageKind.MacKeychain
            isWindows -> LetterboxdSecretStorageKind.WindowsDpapi
            !isMac && !isWindows && findExecutable("secret-tool") != null -> LetterboxdSecretStorageKind.LinuxSecretService
            else -> LetterboxdSecretStorageKind.RestrictedFile
        }
    }

    val currentKind: LetterboxdSecretStorageKind get() = lastWriteKind ?: preferredKind

    fun read(): String? {
        val secure = runCatching {
            when (preferredKind) {
                LetterboxdSecretStorageKind.MacKeychain -> macRead()
                LetterboxdSecretStorageKind.WindowsDpapi -> dpapiRead()
                LetterboxdSecretStorageKind.LinuxSecretService -> secretToolRead()
                else -> null
            }
        }.getOrNull()
        if (secure != null) return secure
        return if (Files.exists(fallbackFile)) {
            lastWriteKind = LetterboxdSecretStorageKind.RestrictedFile
            Files.readString(fallbackFile)
        } else {
            null
        }
    }

    fun write(value: String) {
        val stored = runCatching {
            when (preferredKind) {
                LetterboxdSecretStorageKind.MacKeychain -> macWrite(value)
                LetterboxdSecretStorageKind.WindowsDpapi -> dpapiWrite(value)
                LetterboxdSecretStorageKind.LinuxSecretService -> secretToolWrite(value)
                else -> false
            }
        }.getOrDefault(false)
        if (stored) {
            Files.deleteIfExists(fallbackFile)
            lastWriteKind = preferredKind
            return
        }
        writeRestricted(fallbackFile, value.toByteArray())
        lastWriteKind = LetterboxdSecretStorageKind.RestrictedFile
    }

    fun delete() {
        runCatching {
            when (preferredKind) {
                LetterboxdSecretStorageKind.MacKeychain -> macDelete()
                LetterboxdSecretStorageKind.LinuxSecretService ->
                    run(listOf("secret-tool", "clear", "service", SERVICE, "account", ACCOUNT))
                else -> Unit
            }
        }
        Files.deleteIfExists(dpapiFile)
        Files.deleteIfExists(fallbackFile)
        lastWriteKind = null
    }

    // ---------------------------------------------------------------- macOS

    // `security -i` reads one command per line with a ~4 KB line buffer, so the base64 secret is split
    // into several items: "<account>" holds "chunks:N", "<account>.0" … "<account>.N-1" hold the data.
    private const val MAC_CHUNK_SIZE = 3_000

    private fun macFind(account: String): String? {
        val result = run(listOf("/usr/bin/security", "find-generic-password", "-a", account, "-s", SERVICE, "-w"))
        return if (result.exitCode == 0) result.stdout.trim() else null
    }

    private fun macChunkCount(): Int? = macFind(ACCOUNT)?.removePrefix("chunks:")?.toIntOrNull()

    private fun macRead(): String? {
        val count = macChunkCount() ?: return null
        val encoded = buildString {
            repeat(count) { index -> append(macFind("$ACCOUNT.$index") ?: return null) }
        }
        if (encoded.isEmpty()) return null
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private fun macWrite(value: String): Boolean {
        // Base64 keeps the secret free of quotes/spaces for the interactive command parser.
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val chunks = encoded.chunked(MAC_CHUNK_SIZE)
        val previous = macChunkCount() ?: 0
        val commands = buildString {
            chunks.forEachIndexed { index, chunk ->
                append("add-generic-password -U -a $ACCOUNT.$index -s $SERVICE -l $SERVICE -w $chunk\n")
            }
            for (index in chunks.size until previous) append("delete-generic-password -a $ACCOUNT.$index -s $SERVICE\n")
            append("add-generic-password -U -a $ACCOUNT -s $SERVICE -l $SERVICE -w chunks:${chunks.size}\n")
        }
        run(listOf("/usr/bin/security", "-i"), stdin = commands)
        return macRead() == value
    }

    private fun macDelete() {
        val count = macChunkCount() ?: 0
        val commands = buildString {
            for (index in 0 until count) append("delete-generic-password -a $ACCOUNT.$index -s $SERVICE\n")
            append("delete-generic-password -a $ACCOUNT -s $SERVICE\n")
        }
        run(listOf("/usr/bin/security", "-i"), stdin = commands)
    }

    // ---------------------------------------------------------------- Linux

    private fun secretToolRead(): String? {
        val result = run(listOf("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT))
        if (result.exitCode != 0) return null
        val encoded = result.stdout.trim().ifEmpty { return null }
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    private fun secretToolWrite(value: String): Boolean {
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val result = run(
            listOf("secret-tool", "store", "--label=$SERVICE", "service", SERVICE, "account", ACCOUNT),
            stdin = encoded,
        )
        return result.exitCode == 0 && secretToolRead() == value
    }

    // ---------------------------------------------------------------- Windows DPAPI

    @Suppress("FunctionName")
    private interface Crypt32 : Library {
        fun CryptProtectData(
            pDataIn: DataBlob,
            szDataDescr: String?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            pDataIn: DataBlob,
            ppszDataDescr: Pointer?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob,
        ): Boolean
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun LocalFree(hMem: Pointer?): Pointer?
    }

    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null

        fun bytes(): ByteArray = pbData?.getByteArray(0, cbData) ?: ByteArray(0)

        companion object {
            fun of(data: ByteArray): DataBlob = DataBlob().apply {
                val memory = Memory(maxOf(1, data.size).toLong())
                memory.write(0, data, 0, data.size)
                cbData = data.size
                pbData = memory
            }
        }
    }

    private val crypt32 by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel32 by lazy { Native.load("Kernel32", Kernel32::class.java) }
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    private fun dpapiWrite(value: String): Boolean {
        val output = DataBlob()
        val ok = crypt32.CryptProtectData(
            DataBlob.of(value.toByteArray(Charsets.UTF_8)), SERVICE, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output,
        )
        if (!ok) return false
        val encrypted = try {
            output.bytes()
        } finally {
            kernel32.LocalFree(output.pbData)
        }
        writeRestricted(dpapiFile, encrypted)
        return dpapiRead() == value
    }

    private fun dpapiRead(): String? {
        if (!Files.exists(dpapiFile)) return null
        val output = DataBlob()
        val ok = crypt32.CryptUnprotectData(
            DataBlob.of(Files.readAllBytes(dpapiFile)), null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output,
        )
        if (!ok) return null
        return try {
            String(output.bytes(), Charsets.UTF_8)
        } finally {
            kernel32.LocalFree(output.pbData)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun writeRestricted(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(folder, "lb", ".tmp")
        try {
            restrictPermissions(temp, "rw-------")
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            restrictPermissions(target, "rw-------")
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun restrictPermissions(path: Path, permissions: String) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)) }
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String)

    private fun run(command: List<String>, stdin: String? = null): ProcessResult {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { output -> if (stdin != null) output.write(stdin.toByteArray(Charsets.UTF_8)) }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ProcessResult(-1, "")
        }
        return ProcessResult(process.exitValue(), stdout)
    }

    private fun findExecutable(name: String): Path? =
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it, name) }
            .firstOrNull { Files.isExecutable(it) }
}
