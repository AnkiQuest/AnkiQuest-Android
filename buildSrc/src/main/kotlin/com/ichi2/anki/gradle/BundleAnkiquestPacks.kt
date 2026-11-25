// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
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

/** Which APK a release build makes: the full one bundles the pinned packs, the slim one downloads them. */
enum class AnkiquestArtMode {
    BUNDLED,
    DOWNLOAD,
    ;

    /** The `apk` value of `art.json`, as the app's ApkKind reads it. */
    val apk: String get() = if (this == BUNDLED) "full" else "slim"

    companion object {
        /** The `-PankiquestArt` value [value]: `bundled` or `download`. */
        fun of(value: String): AnkiquestArtMode =
            entries.firstOrNull { it.name.lowercase() == value }
                ?: throw GradleException("Unsupported -PankiquestArt value '$value'. Expected bundled or download")
    }
}

/**
 * Writes the app's view of the AnkiQuest-Art release that `ankiquest-art.json` pins into assets:
 * `ankiquest/art.json` (which APK this is, the pinned tag and the SHA-256 of its `packs.json`) and,
 * for [AnkiquestArtMode.BUNDLED], `ankiquest/packs/` with `packs.json` and one folder per pack, as
 * the app's AssetPackFolder reads them (AnkiQuest-Art docs/packs.md). The slim APK downloads the
 * same packs and checks `packs.json` against the SHA-256 in `art.json`.
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

    @get:Input
    abstract val mode: Property<AnkiquestArtMode>

    /** Downloaded assets by SHA-256, shared between builds. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val out = root.resolve(ASSETS)
        out.mkdirs()

        // The pinned release and the hashes of its packs; an older pin has none.
        val pinned = JsonSlurper().parse(pin.get().asFile) as Map<*, *>
        val tag = pinned["tag"] as String?
        val assets = (pinned["assets"] as Map<*, *>?).orEmpty().entries.associate { (k, v) -> k as String to v as String }
        val packs = assets.keys.filter { PACK.matches(it) }.sorted()
        val release = tag.takeIf { INDEX in assets && packs.isNotEmpty() }
        if (release != null && !TAG.matches(release)) {
            throw GradleException("ankiquest-art.json: $release is not art-<N>")
        }

        // What the app needs to know about this build, in every release build.
        val build =
            mapOf(
                "apk" to mode.get().apk,
                "tag" to release,
                "packs_sha256" to release?.let { assets.getValue(INDEX) },
            )
        out.resolve(BUILD).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(build)) + "\n")
        if (release == null) {
            logger.lifecycle("ankiquest-art.json pins no companion packs; none are bundled or downloaded")
            return
        }
        if (mode.get() == AnkiquestArtMode.DOWNLOAD) {
            return
        }

        // The index as it is, each pack unpacked into its folder.
        val folder = out.resolve(PACKS)
        folder.mkdirs()
        fetch(release, INDEX, assets.getValue(INDEX)).copyTo(folder.resolve(INDEX))
        for (name in packs) {
            val id = PACK.matchEntire(name)!!.groupValues[1]
            unzip(fetch(release, name, assets.getValue(name)), folder.resolve(id))
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
        /** AnkiQuest's folder inside assets. */
        const val ASSETS = "ankiquest"

        /** The build's `art.json` and the bundled packs, inside [ASSETS]. */
        private const val BUILD = "art.json"
        private const val PACKS = "packs"

        private const val INDEX = "packs.json"
        private const val RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases/download"
        private val PACK = Regex("pack-([a-z][a-z0-9_]{0,31})\\.zip")
        private val TAG = Regex("art-[1-9][0-9]*")
        private val ENTRY = Regex("companion\\.json|[a-z0-9_]{1,64}\\.(webp|png)")
    }
}
