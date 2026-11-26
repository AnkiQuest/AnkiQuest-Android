// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A model size of JSchoreels' anki-rwkv family. Big is left out: its state needs far more memory than [RwkvMemory] allows for. */
internal enum class RwkvSize {
    SMALL,
    TINY,
}

/**
 * Which of a size's two models a file is. The two-head model serves RWKV-Curve and, alone, RWKV-Instant;
 * the Instant model is RWKV-Instant's own, used beside it when a preset runs both.
 */
internal enum class RwkvHead {
    TWO_HEAD,
    INSTANT,
}

/**
 * One model file the app accepts: the `weights.f32.bin` ([bytes], [sha256]) inside release asset
 * [asset] of [RwkvLock.RELEASE] ([zipBytes], [zipSha256], as the release's `SHA256SUMS` lists it).
 */
internal data class RwkvLockEntry(
    val id: String,
    val size: RwkvSize,
    val head: RwkvHead,
    val asset: String,
    val bytes: Long,
    val sha256: String,
    val zipBytes: Long,
    val zipSha256: String,
)

/**
 * The lock file: the model files the engine accepts, whatever their source. A player imports them
 * from the release; the app never downloads or bundles them, since the weights have no licence for
 * redistribution (AnkiQuest/AnkiQuest-Anki `docs/rwkv-weights.md`). A model update is a change here.
 */
internal object RwkvLock {
    const val RELEASE = "rwkv-family-2026-11-13"
    const val RELEASE_URL = "https://github.com/JSchoreels/anki-rwkv/releases/tag/$RELEASE"

    /** Where [RwkvDownload] fetches an entry's asset: the release itself; AnkiQuest hosts nothing. */
    const val DOWNLOAD_URL = "https://github.com/JSchoreels/anki-rwkv/releases/download/$RELEASE/"

    private const val SMALL_BYTES = 1_351_553L
    private const val TINY_BYTES = 1_270_749L

    val ENTRIES =
        listOf(
            RwkvLockEntry(
                "sm92",
                RwkvSize.SMALL,
                RwkvHead.TWO_HEAD,
                "rwkv-small-two-head.zip",
                SMALL_BYTES,
                "4fbc5f963b6ef18d23dbc0f11358fed8a9c7c97c271d6eda95df3b8ed6fbeb6e",
                3_005_254L,
                "4d46763f92ea05b9ce03942391f190f252e2326eaa96fe1410a9e8e83fa421ca",
            ),
            RwkvLockEntry(
                "sm98",
                RwkvSize.SMALL,
                RwkvHead.INSTANT,
                "rwkv-small-instant-only.zip",
                SMALL_BYTES,
                "a006dcc039d19142dc95d257d9c238fb93c7efa12b78e18ec6ae881ac9009a24",
                4_227_917L,
                "8c982ebe703e3d832a5cd8a8f9a5f23c59828bf3afd48988b7fc769030c6c3b4",
            ),
            RwkvLockEntry(
                "sm106",
                RwkvSize.TINY,
                RwkvHead.TWO_HEAD,
                "rwkv-tiny-two-head.zip",
                TINY_BYTES,
                "5b346308bbab6ff32241742544af762b714a16334f6a979423b40dfec8897ec3",
                4_097_873L,
                "53216b442b983e85d7c5d0715b5797383ab8db3e2f62710f2944058123392e3b",
            ),
            RwkvLockEntry(
                "sm101",
                RwkvSize.TINY,
                RwkvHead.INSTANT,
                "rwkv-tiny-instant-only.zip",
                TINY_BYTES,
                "2faacb5b913bcfbdc0e363532d533e3bcff6872c8358f1ef753ca87f9d2a60fd",
                4_027_791L,
                "a16d5aa98b7120583518e4e2619d0285a6f4966706c6f0cf74acefa6416d0af9",
            ),
        )
}

/** An imported model file, verified against its [entry]. */
internal data class RwkvModelFile(
    val entry: RwkvLockEntry,
    val file: File,
)

/** The models the engine runs: one size's two-head model, and its Instant model if imported. */
internal data class RwkvModels(
    val twoHead: RwkvModelFile,
    val instant: RwkvModelFile?,
)

/** How much [RwkvModelStore.models] checks: the size, for the settings text, or the SHA-256, before the engine loads them. */
internal enum class RwkvVerify {
    SIZE,
    HASH,
}

/** Why a picked or downloaded file was not imported. */
internal enum class RwkvImportError {
    /** Neither the file nor any `.bin` in it matches the lock file. */
    UNKNOWN_FILE,

    /** The file could not be read or stored. */
    UNREADABLE,

    /** A download failed: no connection, or the release answered with an error. */
    NETWORK_FAILED,

    /** A download's size or SHA-256 differs from the lock, or it went to a host other than GitHub's. */
    DOWNLOAD_MISMATCH,
}

/** The outcome of importing one picked or downloaded file. */
internal sealed interface RwkvImport {
    data class Imported(
        val entries: List<RwkvLockEntry>,
    ) : RwkvImport

    data class Rejected(
        val error: RwkvImportError,
    ) : RwkvImport
}

/**
 * The imported models, in app-private storage that is not backed up. Each file is named by its
 * SHA-256, as `RwkvPrepareOffline` asks: a new model gets a new path, so its state is rebuilt.
 * Only one size is kept: importing a file of another size deletes the old size's files.
 */
internal class RwkvModelStore(
    private val dir: File,
    private val lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
) {
    /**
     * Imports [input]: a model file itself, or a release zip whose `.bin` files are checked one by
     * one. A file is kept only if its byte size and SHA-256 match a lock entry.
     */
    fun import(input: InputStream): RwkvImport =
        try {
            val entries = BufferedInputStream(input).use { read(it) }
            if (entries.isEmpty()) {
                RwkvImport.Rejected(RwkvImportError.UNKNOWN_FILE)
            } else {
                RwkvImport.Imported(entries)
            }
        } catch (e: IOException) {
            RwkvImport.Rejected(RwkvImportError.UNREADABLE)
        }

    /** The models to run, or null without a two-head model. [verify] says how far each file is checked; a file that fails is deleted. */
    fun models(verify: RwkvVerify): RwkvModels? {
        val installed =
            lock.mapNotNull { entry ->
                val file = file(entry)
                if (checked(entry, file, verify)) {
                    RwkvModelFile(entry, file)
                } else {
                    null
                }
            }
        val twoHead = installed.firstOrNull { it.entry.head == RwkvHead.TWO_HEAD } ?: return null
        val instant = installed.firstOrNull { it.entry.head == RwkvHead.INSTANT && it.entry.size == twoHead.entry.size }
        return RwkvModels(twoHead, instant)
    }

    /** The lock entries of the files stored now, checked by size. */
    fun stored(): List<RwkvLockEntry> = lock.filter { checked(it, file(it), RwkvVerify.SIZE) }

    private fun read(input: BufferedInputStream): List<RwkvLockEntry> {
        // A release zip starts with a local file header. BufferedInputStream supports mark and
        // reset, and its buffer is far larger than the header.
        input.mark(ZIP_MAGIC.size)
        val head = ByteArray(ZIP_MAGIC.size)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) {
                break
            }
            read += n
        }
        input.reset()
        if (read == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)) {
            return readZip(ZipInputStream(input))
        }
        return listOfNotNull(store(input))
    }

    private fun readZip(zip: ZipInputStream): List<RwkvLockEntry> {
        val imported = mutableListOf<RwkvLockEntry>()
        while (true) {
            val item = zip.nextEntry ?: break
            if (item.isDirectory || !item.name.endsWith(BIN_SUFFIX)) {
                continue
            }
            store(zip)?.let { imported.add(it) }
        }
        return imported
    }

    /**
     * Copies [input] to a temporary file while hashing it, and keeps it under its hash if it
     * matches a lock entry. Reading stops past the largest entry, so an unknown large file is not
     * copied whole.
     */
    private fun store(input: InputStream): RwkvLockEntry? {
        dir.mkdirs()
        val tmp = File.createTempFile(TMP_PREFIX, TMP_SUFFIX, dir)
        try {
            val digest = MessageDigest.getInstance(SHA_256)
            val limit = lock.maxOf { it.bytes }
            var total = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) {
                        break
                    }
                    total += n
                    if (total > limit) {
                        return null
                    }
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            val sha = digest.digest().toHex()
            val entry = lock.firstOrNull { it.bytes == total && it.sha256 == sha } ?: return null
            install(entry, tmp)
            return entry
        } finally {
            tmp.delete()
        }
    }

    /** Moves [tmp] into place and deletes the files of other sizes and leftovers. */
    private fun install(
        entry: RwkvLockEntry,
        tmp: File,
    ) {
        // The same file imported again is already in place under its hash: keep it.
        val target = file(entry)
        if (!checked(entry, target, RwkvVerify.HASH) && !tmp.renameTo(target)) {
            throw IOException("could not store the RWKV model")
        }
        val keep = lock.filter { it.size == entry.size }.map { file(it).name }.toSet()
        dir
            .listFiles()
            ?.filter { it.isFile && it.name !in keep && !it.name.startsWith(TMP_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun checked(
        entry: RwkvLockEntry,
        file: File,
        verify: RwkvVerify,
    ): Boolean {
        if (!file.isFile) {
            return false
        }
        val ok =
            when (verify) {
                RwkvVerify.SIZE -> file.length() == entry.bytes
                RwkvVerify.HASH -> file.length() == entry.bytes && sha256(file) == entry.sha256
            }
        if (!ok) {
            file.delete()
        }
        return ok
    }

    private fun file(entry: RwkvLockEntry) = File(dir, entry.sha256 + BIN_SUFFIX)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    break
                }
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        const val BIN_SUFFIX = ".bin"
        const val TMP_PREFIX = "import-"
        const val TMP_SUFFIX = ".tmp"
        const val SHA_256 = "SHA-256"
        const val BUFFER_BYTES = 64 * 1024
    }
}
