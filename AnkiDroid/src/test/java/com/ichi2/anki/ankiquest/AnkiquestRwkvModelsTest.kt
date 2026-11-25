// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
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
    fun `the lock lists the Small and Tiny models of one release`() {
        val entries = RwkvLock.ENTRIES
        assertEquals(RwkvSize.entries.size * RwkvHead.entries.size, entries.size)
        assertEquals(entries.size, entries.map { it.sha256 }.toSet().size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        for (entry in entries) {
            assertTrue(entry.sha256.matches(Regex("[0-9a-f]{64}")), entry.id)
            assertTrue(entry.asset.startsWith("rwkv-${entry.size.name.lowercase()}-"), entry.id)
        }
        for (size in RwkvSize.entries) {
            for (head in RwkvHead.entries) {
                assertNotNull(entries.singleOrNull { it.size == size && it.head == head }, "$size $head")
            }
        }
    }

    private fun entry(
        id: String,
        size: RwkvSize,
        head: RwkvHead,
        content: ByteArray,
    ) = RwkvLockEntry(id, size, head, "rwkv-$id.zip", content.size.toLong(), sha256(content))

    private fun bytes(
        count: Int,
        seed: Int,
    ) = ByteArray(count) { (it * 31 + seed).toByte() }

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
