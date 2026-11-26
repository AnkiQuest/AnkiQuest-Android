// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests for [AnkiquestArt] */
class AnkiquestArtTest {
    @TempDir
    lateinit var dir: File

    /** A zip with [entries] (name to text), as Art's exporter writes it. */
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun asset(bytes: ByteArray) = AnkiquestArt.Asset("art-11", AnkiquestArt.DRAWABLES, AnkiquestArt.sha256(bytes))

    private val release =
        zip(
            "drawable-nodpi/" to "",
            "drawable-nodpi/aki_face.webp" to "aki",
            "drawable-nodpi/bluma_streak.webp" to "bluma",
            "LICENSE-Anki-tan.md" to "licence",
        )

    @Test
    fun `the pin names the tag and the zip's hash`() {
        val sha = "a".repeat(64)
        val asset = AnkiquestArt.drawables("""{"tag": "art-11", "assets": {"android-drawables.zip": "$sha"}}""")
        assertEquals(AnkiquestArt.Asset("art-11", "android-drawables.zip", sha), asset)
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Art/releases/download/art-11/android-drawables.zip", asset.url.toString())
    }

    @Test
    fun `a pin without a tag or hash fails`() {
        assertThrows<GradleException> { AnkiquestArt.drawables("""{"tag": "--help", "assets": {}}""") }
        assertThrows<GradleException> { AnkiquestArt.drawables("""{"tag": "art-11", "assets": {}}""") }
        assertThrows<GradleException> { AnkiquestArt.drawables("""{"tag": "art-11", "assets": {"android-drawables.zip": "abc"}}""") }
    }

    @Test
    fun `this repository pins android-drawables zip`() {
        val pin = File("../${AnkiquestArt.PIN}").readText()
        assertTrue(AnkiquestArt.drawables(pin).sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `a download with another hash fails the build and is not cached`() {
        val cache = dir.resolve("cache")
        val pinned = asset(release)
        val error = assertThrows<GradleException> { AnkiquestArt.fetch(pinned, cache) { zip("drawable-nodpi/aki_face.webp" to "other") } }
        assertTrue(error.message!!.contains("ankiquest-art.json says ${pinned.sha256}"), error.message)
        assertFalse(cache.resolve(pinned.sha256).exists())
    }

    @Test
    fun `a download with the pinned hash is cached and used offline`() {
        val cache = dir.resolve("cache")
        val pinned = asset(release)
        val first = AnkiquestArt.fetch(pinned, cache) { release }
        assertEquals(cache.resolve(pinned.sha256), first)
        val second = AnkiquestArt.fetch(pinned, cache) { error("downloaded although cached") }
        assertTrue(second.readBytes().contentEquals(release))
    }

    @Test
    fun `a prefetched file in the cache folder is used`() {
        val cache = dir.resolve("prefetched").apply { mkdirs() }
        val pinned = asset(release)
        cache.resolve(pinned.sha256).writeBytes(release)
        AnkiquestArt.fetch(pinned, cache) { error("downloaded although prefetched") }
    }

    @Test
    fun `a damaged cached file is downloaded again`() {
        val cache = dir.resolve("cache").apply { mkdirs() }
        val pinned = asset(release)
        cache.resolve(pinned.sha256).writeText("damaged")
        val file = AnkiquestArt.fetch(pinned, cache) { release }
        assertTrue(file.readBytes().contentEquals(release))
    }

    @Test
    fun `the drawables are unpacked into drawable-nodpi`() {
        val zip = dir.resolve("art.zip").apply { writeBytes(release) }
        val res = dir.resolve("res")
        res.resolve("drawable-nodpi/stale.webp").apply { parentFile.mkdirs() }.writeText("old")

        assertEquals(listOf("aki_face", "bluma_streak"), AnkiquestArt.unpack(zip, res))
        assertEquals(setOf("aki_face.webp", "bluma_streak.webp"), res.resolve("drawable-nodpi").list()!!.toSet())
        assertEquals("aki", res.resolve("drawable-nodpi/aki_face.webp").readText())
        assertEquals(listOf("drawable-nodpi"), res.list()!!.toList())
    }

    @Test
    fun `an entry outside drawable-nodpi fails`() {
        for (name in listOf("../evil.webp", "drawable-nodpi/../x.webp", "run.sh", "drawable-nodpi/sub/a.webp", "drawable-nodpi/Aki.webp")) {
            val zip = dir.resolve("bad.zip").apply { writeBytes(zip(name to "x")) }
            assertThrows<GradleException>(name) { AnkiquestArt.unpack(zip, dir.resolve("res")) }
        }
        assertFalse(dir.resolve("evil.webp").exists())
    }

    @Test
    fun `a zip without drawables fails`() {
        val zip = dir.resolve("empty.zip").apply { writeBytes(zip("LICENSE-Anki-tan.md" to "licence")) }
        assertThrows<GradleException> { AnkiquestArt.unpack(zip, dir.resolve("res")) }
    }
}
