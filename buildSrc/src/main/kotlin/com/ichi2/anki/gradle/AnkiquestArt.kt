// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The companion pictures of the AnkiQuest-Art release that `ankiquest-art.json` pins. No picture of
 * a companion is committed: every build takes the release's `android-drawables.zip`, checks it
 * against the pinned SHA-256 and unpacks its drawables into generated resources.
 *
 * Assets are cached by SHA-256 in a folder (one file per asset, named by its hash), so a build works
 * offline after the first one. The F-Droid recipe and CI fill that folder before building.
 */
object AnkiquestArt {
    const val PIN = "ankiquest-art.json"
    const val DRAWABLES = "android-drawables.zip"
    const val NODPI = "drawable-nodpi"

    private const val RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download"
    private val TAG = Regex("art-[1-9][0-9]*")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** A drawable of the zip: a flat name in `drawable-nodpi/`. */
    private val DRAWABLE = Regex("$NODPI/([a-z][a-z0-9_]{0,63})\\.webp")

    /** Other entries the zip may have: its folder and the licence files, which the app does not read. */
    private val OTHER = Regex("$NODPI/|LICENSE-[A-Za-z-]+\\.md")

    /** One pinned asset: release [tag], file [name], its [sha256]. */
    data class Asset(
        val tag: String,
        val name: String,
        val sha256: String,
    ) {
        val url: URI get() = URI("$RELEASES/$tag/$name")
    }

    /** The pinned `android-drawables.zip` in the text of `ankiquest-art.json`. */
    fun drawables(pin: String): Asset {
        val json = JsonSlurper().parseText(pin) as? Map<*, *> ?: throw GradleException("$PIN is not a JSON object")
        val tag = json["tag"] as? String
        if (tag == null || !TAG.matches(tag)) {
            throw GradleException("$PIN names no art-<N> tag")
        }
        val sha256 = (json["assets"] as? Map<*, *>)?.get(DRAWABLES) as? String
        if (sha256 == null || !SHA256.matches(sha256)) {
            throw GradleException("$PIN pins no SHA-256 for $DRAWABLES")
        }
        return Asset(tag, DRAWABLES, sha256)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * [asset] from [cache], or from [download] when the cache lacks it or holds other bytes. Bytes
     * that do not have the pinned SHA-256 fail the build and are never cached.
     */
    fun fetch(
        asset: Asset,
        cache: File,
        download: (URI) -> ByteArray = ::get,
    ): File {
        // A cached copy counts only with the pinned hash, so a damaged file is fetched again.
        val cached = cache.resolve(asset.sha256)
        if (isCached(cache, asset.sha256)) {
            return cached
        }

        val bytes = download(asset.url)
        val actual = sha256(bytes)
        if (actual != asset.sha256) {
            throw GradleException("${asset.name} of ${asset.tag} has SHA-256 $actual; $PIN says ${asset.sha256}")
        }
        cache.mkdirs()
        cached.writeBytes(bytes)
        return cached
    }

    /**
     * Whether [cache] holds the bytes with SHA-256 [sha256] (one file per asset, named by its hash),
     * undamaged. AnkiQuest-Server's rules releases are cached the same way ([AnkiquestRules]).
     */
    fun isCached(
        cache: File,
        sha256: String,
    ): Boolean {
        val cached = cache.resolve(sha256)
        return cached.isFile && sha256(cached.readBytes()) == sha256
    }

    fun get(url: URI): ByteArray = url.toURL().openStream().use { it.readBytes() }

    /**
     * Unpacks the drawables of [zip] into [res]`/drawable-nodpi` and answers their names. An entry
     * that is neither a flat drawable name nor a licence fails, so nothing lands outside the folder.
     */
    fun unpack(
        zip: File,
        res: File,
    ): List<String> {
        res.deleteRecursively()
        val folder = res.resolve(NODPI)
        folder.mkdirs()

        val names = mutableListOf<String>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val drawable = DRAWABLE.matchEntire(entry.name)
                if (drawable == null) {
                    if (!OTHER.matches(entry.name)) {
                        throw GradleException("${zip.name}: unexpected entry ${entry.name}")
                    }
                    continue
                }
                val name = drawable.groupValues[1]
                folder.resolve("$name.webp").outputStream().use { input.copyTo(it) }
                names += name
            }
        }
        if (names.isEmpty()) {
            throw GradleException("$DRAWABLES has no drawables")
        }
        return names.sorted()
    }
}

/** Writes the pinned release's companion drawables into [resDir]; see [AnkiquestArt]. */
@CacheableTask
abstract class AnkiquestArtTask : DefaultTask() {
    /** `ankiquest-art.json` at the repository root. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pin: RegularFileProperty

    /** Downloaded assets by SHA-256, shared between builds. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    /** The generated resources folder. */
    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val asset = AnkiquestArt.drawables(pin.get().asFile.readText())
        val zip = AnkiquestArt.fetch(asset, cacheDir.get().asFile)
        val names = AnkiquestArt.unpack(zip, resDir.get().asFile)
        logger.info("${asset.tag}: ${names.size} companion drawables")
    }
}
