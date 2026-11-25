// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packs shared through the server (AnkiQuest-Server docs/companion-packs.md): answers, upload zips and the friends' cache. */
class AnkiquestPackSharingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val core = listOf("face", "asleep", "happy", "streak", "sad", "hopeful", "frozen", "neglected")

    private val names = PoseNames(core, mapOf("icon" to "face"))

    /** A lossy WebP header, enough for the reader's size check. */
    private fun webp(): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBPVP8 ".toByteArray().copyInto(bytes, 8)
        bytes[26] = 4
        bytes[28] = 4
        return bytes
    }

    /** A pack of [id] whose core poses share one picture of [mark]. */
    private fun pack(
        id: String,
        mark: Byte = 0,
    ): ByteArray {
        val info =
            JSONObject()
                .put("format", 1)
                .put("id", id)
                .put("name", "Mochi")
                .put("version", 1)
                .put("licence", "CC-BY-4.0")
                .put("credit", "Ana")
                .put("poses", JSONObject(core.associateWith { "face.webp" }))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf(PackFormat.PACK_INDEX to info.toString().toByteArray(), "face.webp" to webp() + mark)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val sha = "a".repeat(64)

    @Test
    fun `answers are read and entries the API does not allow are left out`() {
        val pack =
            JSONObject()
                .put(
                    "sha256",
                    sha,
                ).put("id", "mochi")
                .put("name", "Mochi")
                .put("licence", "MIT")
                .put("credit", "Ana")
                .put("size", 12)
        val friends =
            JSONObject().put(
                "friends",
                JSONObject()
                    .put("cerro", pack)
                    .put("bad_hash", JSONObject(pack.toString()).put("sha256", "../x"))
                    .put("bad_id", JSONObject(pack.toString()).put("id", "Mochi")),
            )
        val read = SharedPack.friends(friends)
        assertEquals(setOf("cerro"), read.keys)
        assertNull(read.getValue("cerro").visibility)
        assertNull(read.getValue("cerro").notice)

        val mine = SharedPack.mine(JSONObject().put("packs", JSONArray().put(JSONObject(pack.toString()).put("visibility", "friends"))))
        assertEquals(PackVisibility.FRIENDS, mine.single().visibility)
        assertEquals(emptyMap(), SharedPack.friends(JSONObject()))
    }

    @Test
    fun `an imported pack zips back into a pack the reader accepts`() {
        val library =
            AnkiquestPackLibrary(FilePackFolder(folder.newFolder()), FilePackFolder(folder.newFolder()), FilePackFolder(folder.newFolder()))
        library.import(pack("mochi").inputStream())
        val installed = assertNotNull(library.companion("mochi"))
        val again = AnkiquestPackReader.read(zipOf(installed).inputStream(), names)
        assertEquals(installed.info, again.info)
    }

    @Test
    fun `the cache keeps a friend's pack only when its bytes have the hash asked for`() {
        val cache = FriendPackCache(folder.newFolder())
        val bytes = pack("mochi")
        val hash = sha256Hex(bytes)

        assertEquals(PackRefusal.CHECKSUM, assertFailsWith<PackRefused> { cache.put(sha, bytes, names) }.reason)
        assertNull(cache.get(sha, names))

        cache.put(hash, bytes, names)
        assertEquals("mochi", cache.get(hash, names)?.info?.id)
        assertNull(cache.get("../$hash", names))
    }

    @Test
    fun `the cache drops the least recently used pack and packs no friend shows`() {
        val cache = FriendPackCache(folder.newFolder(), maxPacks = 2)
        val hashes =
            (1..3).map { n ->
                val bytes = pack("mochi", n.toByte())
                sha256Hex(bytes).also {
                    cache.put(it, bytes, names)
                    // File times have a coarse resolution; order the packs explicitly.
                    folder.root
                        .walkTopDown()
                        .filter { dir -> dir.name == it }
                        .forEach { dir -> dir.setLastModified(n * 10_000L) }
                }
            }
        assertEquals(setOf(hashes[1], hashes[2]), cache.hashes())

        cache.retain(setOf(hashes[2]))
        assertEquals(setOf(hashes[2]), cache.hashes())
        assertTrue(cache.get(hashes[2], names) != null)
    }
}
