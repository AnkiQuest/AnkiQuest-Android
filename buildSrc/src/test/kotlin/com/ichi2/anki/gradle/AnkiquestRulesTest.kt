// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.URI

/** Tests for [AnkiquestRules] */
class AnkiquestRulesTest {
    @TempDir
    lateinit var dir: File

    // Each asset's bytes are its name, so a file shows where it came from.
    private val assets = (AnkiquestRules.APP_ASSETS + AnkiquestRules.HOSTS.map { it.asset }).associateWith { it.toByteArray() }

    private val app = AnkiquestRules.APP_ASSETS

    private fun sums(files: Map<String, ByteArray> = assets) =
        files.entries.joinToString("") { (name, bytes) -> "${AnkiquestArt.sha256(bytes)}  $name\n" }

    private val refs =
        """
        001e# service=git-upload-pack
        003f0000000000000000000000000000000000000001 refs/tags/rules-2
        00420000000000000000000000000000000000000001 refs/tags/rules-2^{}
        003f0000000000000000000000000000000000000002 refs/tags/rules-10
        00400000000000000000000000000000000000000003 refs/tags/art-11
        0040000000000000000000000000000000000000004 refs/tags/rules-0
        """.trimIndent()

    /** A server with [refs] and release `rules-10`, recording what was downloaded. */
    private class Server(
        val refs: String,
        val files: Map<String, ByteArray>,
        val sums: String,
    ) {
        val downloaded = mutableListOf<String>()

        fun get(url: URI): ByteArray {
            val path = url.toString()
            downloaded += path
            if (path.endsWith("info/refs?service=git-upload-pack")) {
                return refs.toByteArray()
            }
            val name = path.substringAfterLast('/')
            if (!path.contains("/download/rules-10/")) {
                throw IOException("404 $path")
            }
            return when (name) {
                AnkiquestRules.SUMS -> sums.toByteArray()
                AnkiquestRules.BUNDLE -> BUNDLE_BYTES
                else -> files[name] ?: throw IOException("404 $path")
            }
        }
    }

    private fun server(files: Map<String, ByteArray> = assets) = Server(refs, files, sums(assets))

    /** The digests each verification was asked for; [refuse] fails them instead. */
    private val verified = mutableListOf<Map<String, String>>()
    private var refuse = false

    private fun verify(
        bundle: File,
        digests: Map<String, String>,
    ) {
        assertEquals(String(BUNDLE_BYTES), bundle.readText())
        if (refuse) {
            throw GradleException("not attested")
        }
        verified += digests
    }

    private fun released(
        tag: String?,
        cache: File,
        names: List<String>,
        download: (URI) -> ByteArray,
    ) = AnkiquestRules.released(tag, cache, names, download, ::verify)

    @Test
    fun `the newest tag is the highest rules number`() {
        assertEquals("rules-10", AnkiquestRules.newest(refs))
        assertNull(AnkiquestRules.newest("003f0000 refs/tags/art-1\n"))
    }

    @Test
    fun `sums are read by asset name and other lines fail`() {
        val sha = "a".repeat(64)
        assertEquals(mapOf("ankiquest_rules.kt" to sha), AnkiquestRules.sums("$sha  ankiquest_rules.kt\n"))
        assertThrows<GradleException> { AnkiquestRules.sums("$sha  ../ankiquest_rules.kt\n") }
        assertThrows<GradleException> { AnkiquestRules.sums("abc  ankiquest_rules.kt\n") }
    }

    @Test
    fun `the newest release is downloaded, checked and cached`() {
        val cache = dir.resolve("cache")
        val release = released(null, cache, app, server()::get)
        assertEquals("rules-10", release.tag)
        assertEquals(app, release.files.keys.toList())
        for (name in app) {
            assertEquals(name, release.files.getValue(name).readText())
        }

        // Offline, the same release comes from the cache.
        val offline = released(null, cache, app) { throw IOException("offline") }
        assertEquals("rules-10", offline.tag)
        assertEquals(release.sums, offline.sums)
    }

    @Test
    fun `a named release is used instead of the newest`() {
        val server = server()
        released("rules-10", dir, app, server::get)
        assertTrue(server.downloaded.none { it.contains("info/refs") })
        assertThrows<GradleException> { released("--help", dir, app, server::get) }
    }

    @Test
    fun `an asset without its listed hash fails and is not cached`() {
        val damaged = assets + (AnkiquestRules.BINDINGS to "other".toByteArray())
        val cache = dir.resolve("cache")
        val error = assertThrows<GradleException> { released(null, cache, app, server(damaged)::get) }
        assertTrue(error.message!!.contains(AnkiquestRules.BINDINGS), error.message)
        assertTrue(cache.listFiles()!!.none { it.readText() == "other" })
    }

    @Test
    fun `a release that lists no asset the app needs fails`() {
        val missing = Server(refs, assets, sums(assets - AnkiquestRules.androidAsset("x86")))
        assertThrows<GradleException> { released(null, dir, app, missing::get) }
    }

    @Test
    fun `downloaded assets are cached only once their attestation verifies`() {
        val cache = dir.resolve("cache")
        val digests = app.associateWith { AnkiquestArt.sha256(it.toByteArray()) }

        refuse = true
        assertThrows<GradleException> { released(null, cache, app, server()::get) }
        assertTrue(digests.values.none { cache.resolve(it).exists() })

        refuse = false
        released(null, cache, app, server()::get)
        assertEquals(listOf(digests), verified)

        // What the cache holds was verified, so it is not checked again, online or offline.
        released(null, cache, app, server()::get)
        released(null, cache, app) { throw IOException("offline") }
        assertEquals(1, verified.size)
    }

    @Test
    fun `only what the cache lacks is verified`() {
        val cache = dir.resolve("cache")
        released(null, cache, app.take(1), server()::get)
        released(null, cache, app, server()::get)
        assertEquals(listOf(app.take(1), app.drop(1)), verified.map { it.keys.toList() })
    }

    @Test
    fun `a local folder is checked against its sums`() {
        val folder = dir.resolve("release").apply { mkdirs() }
        assets.forEach { (name, bytes) -> folder.resolve(name).writeBytes(bytes) }
        folder.resolve(AnkiquestRules.SUMS).writeText(sums())
        assertEquals(AnkiquestRules.LOCAL_TAG, AnkiquestRules.local(folder, null, app).tag)
        assertEquals("rules-3", AnkiquestRules.local(folder, "rules-3", app).tag)

        folder.resolve(AnkiquestRules.androidAsset("x86")).writeText("other")
        assertThrows<GradleException> { AnkiquestRules.local(folder, null, app) }
    }

    @Test
    fun `the app gets the bindings, a library per ABI and the record`() {
        val release = released(null, dir.resolve("cache"), app, server()::get)
        val (kotlin, jniLibs, assetsDir) = listOf("kotlin", "jniLibs", "assets").map { dir.resolve(it) }
        jniLibs.resolve("mips/stale.so").apply { parentFile.mkdirs() }.writeText("stale")
        AnkiquestRules.stageApp(release, kotlin, jniLibs, assetsDir)

        assertEquals(AnkiquestRules.BINDINGS, kotlin.resolve(AnkiquestRules.BINDINGS_PATH).readText())
        assertEquals(AnkiquestRules.ABIS.sorted(), jniLibs.list()!!.sorted())
        for (abi in AnkiquestRules.ABIS) {
            assertEquals(AnkiquestRules.androidAsset(abi), jniLibs.resolve("$abi/${AnkiquestRules.LIBRARY}").readText())
        }
        val record = assetsDir.resolve(AnkiquestRules.RECORD).readText()
        assertTrue(record.contains("\"tag\": \"rules-10\""), record)
        assertTrue(record.contains(AnkiquestArt.sha256(AnkiquestRules.BINDINGS.toByteArray())), record)
        assertTrue(AnkiquestRules.HOSTS.none { record.contains(it.asset) }, record)
    }

    @Test
    fun `the tests get this machine's library under the name JNA loads`() {
        for (host in AnkiquestRules.HOSTS) {
            val release = released(null, dir.resolve("cache"), listOf(host.asset), server()::get)
            val folder = dir.resolve(host.platform)
            AnkiquestRules.stageHost(release, host, folder)
            assertEquals(listOf(host.name), folder.list()!!.toList())
            assertEquals(host.asset, folder.resolve(host.name).readText())
        }
    }

    @Test
    fun `the app takes no desktop library and the tests no Android one`() {
        val server = server()
        released(null, dir, app, server::get)
        assertTrue(AnkiquestRules.HOSTS.none { host -> server.downloaded.any { it.endsWith(host.asset) } })
        assertTrue(AnkiquestRules.APP_ASSETS.all { name -> server.downloaded.any { it.endsWith(name) } })
    }

    @Test
    fun `each CI system finds its library`() {
        assertEquals("linux-x86_64", AnkiquestRules.hostPlatform("Linux", "amd64"))
        assertEquals("linux-aarch64", AnkiquestRules.hostPlatform("Linux", "aarch64"))
        assertEquals("windows-x86_64", AnkiquestRules.hostPlatform("Windows Server 2025", "amd64"))
        assertEquals("macos-arm64", AnkiquestRules.hostPlatform("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64", AnkiquestRules.hostPlatform("Mac OS X", "x86_64"))
        assertThrows<GradleException> { AnkiquestRules.hostPlatform("Windows 11", "aarch64") }
        assertThrows<GradleException> { AnkiquestRules.hostPlatform("FreeBSD", "amd64") }
    }

    private companion object {
        val BUNDLE_BYTES = "{\"mediaType\": \"bundle\"}".toByteArray()
    }
}
