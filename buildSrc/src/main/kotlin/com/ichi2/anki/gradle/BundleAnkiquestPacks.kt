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
 * Puts the companion packs of the AnkiQuest-Art release that `ankiquest-art.json` pins into
 * `<outputDir>/ankiquest/packs/`: `packs.json` and one folder per pack, as the app's
 * AssetPackFolder reads them (AnkiQuest-Art docs/packs.md).
 *
 * Each asset is downloaded once from the release, checked against the pin's SHA-256 and cached by
 * hash in [cacheDir], so a build works offline after the first. A pin without packs bundles none.
 */
@CacheableTask
abstract class BundleAnkiquestPacks : DefaultTask() {
    /** `ankiquest-art.json` at the repository root. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pin: RegularFileProperty

    /** Downloaded assets by SHA-256, shared between builds. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile.resolve(ASSETS)
        out.deleteRecursively()
        out.mkdirs()

        // The pinned release and the hashes of its packs; an older pin has none.
        @Suppress("UNCHECKED_CAST")
        val pinned = JsonSlurper().parse(pin.get().asFile) as Map<String, Any?>
        val tag = pinned["tag"] as String?
        val assets = pinned["assets"] as Map<String, String>? ?: emptyMap()
        val packs = assets.keys.filter { PACK.matches(it) }.sorted()
        if (tag == null || INDEX !in assets || packs.isEmpty()) {
            logger.lifecycle("ankiquest-art.json pins no companion packs; none are bundled")
            return
        }
        if (!TAG.matches(tag)) {
            throw GradleException("ankiquest-art.json: $tag is not art-<N>")
        }

        // The index as it is, each pack unpacked into its folder.
        fetch(tag, INDEX, assets.getValue(INDEX)).copyTo(out.resolve(INDEX))
        for (name in packs) {
            val id = PACK.matchEntire(name)!!.groupValues[1]
            unzip(fetch(tag, name, assets.getValue(name)), out.resolve(id))
        }
    }

    /** Release [tag]'s asset [name], from the cache or downloaded, checked against [sha256]. */
    private fun fetch(
        tag: String,
        name: String,
        sha256: String,
    ): File {
        val cached = cacheDir.get().asFile.resolve(sha256)
        if (cached.isFile && sha256(cached.readBytes()) == sha256) {
            return cached
        }
        val bytes = URI("$RELEASES/$tag/$name").toURL().openStream().use { it.readBytes() }
        val actual = sha256(bytes)
        if (actual != sha256) {
            throw GradleException("$name of $tag has SHA-256 $actual; ankiquest-art.json says $sha256")
        }
        cached.parentFile.mkdirs()
        cached.writeBytes(bytes)
        return cached
    }

    /** Unpacks the flat pack [zip] into [folder]; refuses an entry that is not a plain file name. */
    private fun unzip(
        zip: File,
        folder: File,
    ) {
        folder.mkdirs()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!ENTRY.matches(entry.name)) {
                    throw GradleException("${zip.name}: unexpected entry ${entry.name}")
                }
                folder.resolve(entry.name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Where the app reads bundled packs, inside assets. */
        const val ASSETS = "ankiquest/packs"

        private const val INDEX = "packs.json"
        private const val RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download"
        private val PACK = Regex("pack-([a-z][a-z0-9_]{0,31})\\.zip")
        private val TAG = Regex("art-[1-9][0-9]*")
        private val ENTRY = Regex("companion\\.json|[a-z0-9_]{1,64}\\.(webp|png)")
    }
}
