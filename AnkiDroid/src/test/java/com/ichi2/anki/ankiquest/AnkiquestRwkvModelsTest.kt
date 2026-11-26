// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnkiquestRwkvModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Stand-ins for the weights, which the repository must not hold: same layout, made-up bytes. */
    private val smallTwoHead = bytes(100, 1)
    private val smallInstant = bytes(100, 2)
    private val tinyTwoHead = bytes(90, 3)

    private val lock =
        listOf(
            entry("sm92", RwkvSize.SMALL, RwkvHead.TWO_HEAD, smallTwoHead),
            entry("sm98", RwkvSize.SMALL, RwkvHead.INSTANT, smallInstant),
            entry("sm106", RwkvSize.TINY, RwkvHead.TWO_HEAD, tinyTwoHead),
        )

    private val dir by lazy { File(folder.root, "rwkv") }
    private val store by lazy { RwkvModelStore(dir, lock) }

    @Test
    fun `a model file that matches the lock is imported under its hash`() {
        val result = store.import(smallTwoHead.inputStream())
        assertEquals(RwkvImport.Imported(listOf(lock[0])), result)
        assertTrue(File(dir, "${lock[0].sha256}.bin").isFile)
        assertEquals(listOf(lock[0]), store.stored())
    }

    @Test
    fun `a release zip is imported from its matching bin files`() {
        val zip =
            zip(
                "rwkv-small-two-head/WEIGHTS-NOTICE.md" to "notice".toByteArray(),
                "rwkv-small-two-head/weights.int8.bin" to bytes(40, 9),
                "rwkv-small-two-head/weights.f32.bin" to smallTwoHead,
            )
        assertEquals(RwkvImport.Imported(listOf(lock[0])), store.import(zip.inputStream()))
        assertEquals(listOf(lock[0]), store.stored())
    }

    @Test
    fun `a file of the right size with another hash is rejected`() {
        val forged = smallTwoHead.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(RwkvImport.Rejected(RwkvImportError.UNKNOWN_FILE), store.import(forged.inputStream()))
        assertEquals(emptyList(), store.stored())
        assertEquals(emptyList(), dir.listFiles().orEmpty().toList(), "no temporary file is left")
    }

    @Test
    fun `a file larger than any model is rejected without keeping it`() {
        assertEquals(RwkvImport.Rejected(RwkvImportError.UNKNOWN_FILE), store.import(bytes(1_000, 4).inputStream()))
        assertEquals(emptyList(), dir.listFiles().orEmpty().toList())
    }

    @Test
    fun `a zip without a matching file is rejected`() {
        val zip = zip("readme.txt" to "hello".toByteArray())
        assertEquals(RwkvImport.Rejected(RwkvImportError.UNKNOWN_FILE), store.import(zip.inputStream()))
    }

    @Test
    fun `importing the same files again keeps them`() {
        store.import(smallTwoHead.inputStream())
        store.import(smallInstant.inputStream())

        assertEquals(RwkvImport.Imported(listOf(lock[0])), store.import(smallTwoHead.inputStream()))
        val zip = zip("a/weights.f32.bin" to smallInstant, "b/weights.f32.bin" to smallInstant)
        assertEquals(RwkvImport.Imported(listOf(lock[1], lock[1])), store.import(zip.inputStream()))
        assertEquals(listOf(lock[0], lock[1]), store.stored())
        assertEquals(2, dir.listFiles().orEmpty().size, "no temporary file is left")
    }

    @Test
    fun `importing another size deletes the old size's files`() {
        store.import(smallTwoHead.inputStream())
        store.import(smallInstant.inputStream())
        assertEquals(listOf(lock[0], lock[1]), store.stored())

        store.import(tinyTwoHead.inputStream())
        assertEquals(listOf(lock[2]), store.stored())
    }

    @Test
    fun `the models need a two-head model`() {
        assertNull(store.models(RwkvVerify.SIZE))
        store.import(smallInstant.inputStream())
        assertNull(store.models(RwkvVerify.SIZE), "an Instant model alone is not enough")
    }

    @Test
    fun `the instant model runs beside the two-head model of its size`() {
        store.import(smallTwoHead.inputStream())
        val alone = assertNotNull(store.models(RwkvVerify.HASH))
        assertEquals(lock[0], alone.twoHead.entry)
        assertNull(alone.instant)

        store.import(smallInstant.inputStream())
        val both = assertNotNull(store.models(RwkvVerify.HASH))
        assertEquals(lock[1], both.instant?.entry)
    }

    @Test
    fun `a stored file changed since its import is deleted before it is loaded`() {
        store.import(smallTwoHead.inputStream())
        val file = File(dir, "${lock[0].sha256}.bin")
        file.writeBytes(smallTwoHead.copyOf().also { it[5] = 0 })

        assertNotNull(store.models(RwkvVerify.SIZE), "the size still matches")
        assertNull(store.models(RwkvVerify.HASH))
        assertFalse(file.exists())
    }

    @Test
    fun `the lock lists every model of the release`() {
        val entries = RwkvLock.ENTRIES
        assertEquals(RwkvSize.entries.size * RwkvHead.entries.size, entries.size)
        assertEquals(entries.size, entries.map { it.sha256 }.toSet().size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        for (entry in entries) {
            assertTrue(entry.sha256.matches(Regex("[0-9a-f]{64}")), entry.id)
            assertTrue(entry.zipSha256.matches(Regex("[0-9a-f]{64}")), entry.id)
            assertTrue(entry.zipBytes > entry.bytes, entry.id)
            assertTrue(entry.asset.startsWith("rwkv-${entry.size.name.lowercase()}-"), entry.id)
            assertTrue(entry.logLoss in 0.2..0.4, entry.id)
        }
        for (size in RwkvSize.entries) {
            for (head in RwkvHead.entries) {
                assertNotNull(entries.singleOrNull { it.size == size && it.head == head }, "$size $head")
            }
        }
    }

    @Test
    fun `the lock is the release's SHA256SUMS, every zip and nothing else`() {
        // The release's own SHA256SUMS, as published: "<sha256> *<asset>".
        val sums =
            fixture("rwkv-family-2026-11-13.SHA256SUMS")
                .lines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (sha, name) = line.split(" *", limit = 2)
                    name to sha
                }
        val zips = sums.filterKeys { it.endsWith(".zip") }
        assertEquals(zips, RwkvLock.ENTRIES.associate { it.asset to it.zipSha256 })
    }

    @Test
    fun `the lock's weights are the ones each asset's manifest names`() {
        // Asset, zip bytes, weights.f32.bin bytes and SHA-256, from each release zip and its manifest.json.
        val rows =
            fixture("rwkv-family-2026-11-13.weights.tsv")
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split("\t") }
        assertEquals(RwkvLock.ENTRIES.size, rows.size)
        for ((asset, zipBytes, bytes, sha) in rows) {
            val entry = RwkvLock.ENTRIES.single { it.asset == asset }
            assertEquals(zipBytes.toLong(), entry.zipBytes, asset)
            assertEquals(bytes.toLong(), entry.bytes, asset)
            assertEquals(sha, entry.sha256, asset)
        }
    }

    @Test
    fun `the store accepts a Big model`() {
        val big = bytes(120, 7)
        val store = RwkvModelStore(dir, lock + entry("sm91s2", RwkvSize.BIG, RwkvHead.TWO_HEAD, big))
        assertEquals("sm91s2", (store.import(big.inputStream()) as RwkvImport.Imported).entries.single().id)
        val models = assertNotNull(store.models(RwkvVerify.HASH))
        assertEquals(RwkvSize.BIG, models.twoHead.entry.size)
    }

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("rwkv/$name")) { "missing $name" }.readText()

    private fun entry(
        id: String,
        size: RwkvSize,
        head: RwkvHead,
        content: ByteArray,
    ): RwkvLockEntry {
        val zip = zip("rwkv-$id/weights.f32.bin" to content)
        val bytes = content.size.toLong()
        return RwkvLockEntry(id, size, head, "rwkv-$id.zip", bytes, sha256(content), zip.size.toLong(), sha256(zip), LOG_LOSS)
    }

    /** Serves each lock entry's zip, as the release would, and records the URLs asked for. */
    private inner class FakeRelease(
        private val broken: Set<String> = emptySet(),
        private val offline: Boolean = false,
    ) : PackFetch {
        val asked = mutableListOf<String>()

        override fun get(
            url: String,
            limit: Long,
        ): ByteArray {
            asked.add(url)
            if (offline) {
                throw IOException("offline")
            }
            val entry = lock.single { url == RwkvLock.DOWNLOAD_URL + it.asset }
            val zip = zip("rwkv-${entry.id}/weights.f32.bin" to contents.getValue(entry.id))
            return if (entry.id in broken) zip.copyOf().also { it[it.size - 1] = 0x7f } else zip
        }
    }

    private val contents by lazy { mapOf("sm92" to smallTwoHead, "sm98" to smallInstant, "sm106" to tinyTwoHead) }

    @Test
    fun `a download fetches the entry's asset from the release and checks it`() {
        val release = FakeRelease()
        val phases = mutableListOf<RwkvPhase>()
        val result = RwkvDownload(store, release).download(lock[1]) { phases.add(it) }

        assertEquals(RwkvImport.Imported(listOf(lock[1])), result)
        assertEquals(listOf(RwkvLock.DOWNLOAD_URL + lock[1].asset), release.asked)
        assertEquals(listOf(RwkvPhase.DOWNLOADING, RwkvPhase.VERIFYING), phases)
        assertEquals(listOf(lock[1]), store.stored())
    }

    @Test
    fun `a download that differs from the lock is not kept`() {
        val result = RwkvDownload(store, FakeRelease(broken = setOf("sm92"))).download(lock[0]) { }
        assertEquals(RwkvImport.Rejected(RwkvImportError.DOWNLOAD_MISMATCH), result)
        assertNull(store.models(RwkvVerify.SIZE))
    }

    @Test
    fun `a download without a connection says so and is never checked`() {
        val phases = mutableListOf<RwkvPhase>()
        val result = RwkvDownload(store, FakeRelease(offline = true)).download(lock[2]) { phases.add(it) }
        assertEquals(RwkvImport.Rejected(RwkvImportError.NETWORK_FAILED), result)
        assertEquals(listOf(RwkvPhase.DOWNLOADING), phases)
    }

    private fun bytes(
        count: Int,
        seed: Int,
    ) = ByteArray(count) { (it * 31 + seed).toByte() }

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                // A fixed time, so the same files always make the same bytes.
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** A score in the range the release publishes; these tests do not compare scores. */
        const val LOG_LOSS = 0.3

        /** 1990-01-01, inside the zip format's date range. */
        const val FIXED_TIME = 631_152_000_000L
    }
}
