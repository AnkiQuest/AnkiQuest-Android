// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks pack zips: a pack is kept only with every rule of docs/packs.md (AnkiquestPackReader). */
class AnkiquestPackReaderTest {
    private val names = PoseNames(core = listOf("face"), fallbacks = mapOf("winner" to "face"))

    @Test
    fun a_pack_with_its_pictures_is_read() {
        val pack =
            read(
                zip(
                    "companion.json" to index(poses = """{"face":"aki_face.webp"}"""),
                    "aki_face.webp" to webp(),
                ),
            )
        assertEquals("aki", pack.info.id)
        assertEquals(mapOf("face" to "aki_face.webp"), pack.info.poses)
        assertContentEquals(info(poses = """{"face":"aki_face.webp"}""").toByteArray(), pack.index)
        assertEquals(setOf("aki_face.webp"), pack.pictures.keys)
    }

    @Test
    fun the_companion_index_alone_is_not_a_pack() {
        assertEquals(PackRefusal.MISSING_FILE, refused(zip("aki_face.webp" to webp())), "no index")
        assertEquals(
            PackRefusal.MISSING_FILE,
            refused(zip("companion.json" to index(poses = """{"face":"aki_face.webp"}"""))),
            "the pose's file is missing",
        )
    }

    @Test
    fun every_named_picture_is_there_and_nothing_else() {
        // A pose names a file the zip does not hold.
        assertEquals(
            PackRefusal.MISSING_FILE,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"aki_face.webp"}"""),
                    "aki_asleep.webp" to webp(),
                ),
            ),
        )
        assertEquals(
            PackRefusal.UNUSED_FILE,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"aki_face.webp"}"""),
                    "aki_face.webp" to webp(),
                    "extra.png" to png(),
                ),
            ),
            "nothing the index does not name",
        )
    }

    @Test
    fun entries_are_flat_picture_names_each_once() {
        assertEquals(
            PackRefusal.BAD_ENTRY_NAME,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"a.webp"}"""),
                    "nested/a.webp" to webp(),
                ),
            ),
        )
        assertEquals(
            PackRefusal.BAD_ENTRY_NAME,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"a.webp"}"""),
                    "A.webp" to webp(),
                ),
            ),
        )
    }

    @Test
    fun pictures_are_checked_before_anything_is_kept() {
        assertEquals(
            PackRefusal.NOT_A_PICTURE,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"a.webp"}"""),
                    "a.webp" to "not a picture".toByteArray(),
                ),
            ),
        )
        assertEquals(
            PackRefusal.PICTURE_TOO_LARGE,
            refused(
                zip(
                    "companion.json" to index(poses = """{"face":"a.png"}"""),
                    "a.png" to png(width = AnkiquestPackReader.MAX_EDGE + 1),
                ),
            ),
        )
    }

    private fun refused(bytes: ByteArray): PackRefusal = assertFailsWith<PackRefused> { read(bytes) }.reason

    private fun read(bytes: ByteArray): Pack = AnkiquestPackReader.read(ByteArrayInputStream(bytes), names)

    /** A pack zip of [entries], the way the downloaded and imported ones are read. */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The pack's index file, as bytes for the zip. */
    private fun index(poses: String): ByteArray = info(poses).encodeToByteArray()

    private fun info(poses: String): String =
        """{"format":1,"id":"aki","name":"Aki","version":11,"licence":"CC BY-SA","credit":"AnkiQuest","notice":null,"poses":$poses}"""

    /** A PNG header of [width]x[height], which is all the reader checks. */
    private fun png(
        width: Int = 64,
        height: Int = 64,
    ): ByteArray {
        val bytes = ByteArray(30)
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a)
        signature.copyInto(bytes, 0)
        "IHDR".toByteArray().copyInto(bytes, 12)
        for (index in 0 until 4) {
            bytes[16 + index] = ((width shr (8 * (3 - index))) and 0xff).toByte()
            bytes[20 + index] = ((height shr (8 * (3 - index))) and 0xff).toByte()
        }
        return bytes
    }

    /** A lossy WebP header of one pixel, which is all the reader checks. */
    private fun webp(): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8 ".toByteArray().copyInto(bytes, 12)
        bytes[26] = 0x01
        bytes[28] = 0x01
        return bytes
    }
}
