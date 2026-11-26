// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * The shared rules (AnkiQuest-Server's `crates/rules`) from the newest `rules-<N>` release of
 * AnkiQuest-Server, as the desktop add-on takes them: the Kotlin bindings, the library of each
 * Android ABI, and the desktop libraries the JVM unit tests load. Nothing is committed or pinned:
 * the app uses server `master`, so every build takes the newest release, checks each asset against
 * that release's `SHA256SUMS` and records the tag and hashes in the app's assets
 * (`ankiquest/rules-release.json`), so a build can be traced and rebuilt.
 *
 * `-PankiquestRulesTag=<tag>` or `ANKIQUEST_RULES_TAG` takes that release instead, to rebuild one.
 * `-PankiquestRulesDir=<dir>` or `ANKIQUEST_RULES_DIR` takes the assets and their `SHA256SUMS`
 * from a folder, for a release not published yet. Assets are cached by SHA-256 like the art
 * ([AnkiquestArt]); offline, a build uses the newest release it saw before.
 */
object AnkiquestRules {
    const val SUMS = "SHA256SUMS"
    const val BINDINGS = "ankiquest_rules.kt"

    /** Where the bindings go in the generated sources: their package is `org.ankiquest.rules`. */
    const val BINDINGS_PATH = "org/ankiquest/rules/$BINDINGS"

    /** What every Android library is called in the APK, and what the bindings load. */
    const val LIBRARY = "libankiquest_rules.so"

    /** The record of the release a build took, in the app's assets. */
    const val RECORD = "ankiquest/rules-release.json"

    /** The tag recorded for assets from a folder without a tag. */
    const val LOCAL_TAG = "local"

    /** The ABIs the app ships, as the release names their libraries. */
    val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun androidAsset(abi: String) = "libankiquest_rules-android-$abi.so"

    /** A desktop library: its release asset, its folder here and the name JNA loads it by. */
    data class Host(
        val asset: String,
        val platform: String,
        val name: String,
    )

    /** Every desktop library the release has, for the JVM unit tests of each CI system. */
    val HOSTS =
        listOf(
            Host("libankiquest_rules-linux-x86_64.so", "linux-x86_64", "libankiquest_rules.so"),
            Host("libankiquest_rules-linux-aarch64.so", "linux-aarch64", "libankiquest_rules.so"),
            Host("ankiquest_rules-windows-x86_64.dll", "windows-x86_64", "ankiquest_rules.dll"),
            Host("libankiquest_rules-macos-arm64.dylib", "macos-arm64", "libankiquest_rules.dylib"),
            Host("libankiquest_rules-macos-x86_64.dylib", "macos-x86_64", "libankiquest_rules.dylib"),
        )

    /** The assets the app takes from a release. */
    val APP_ASSETS = listOf(BINDINGS) + ABIS.map(::androidAsset)

    /** The desktop library of [platform]. */
    fun host(platform: String): Host =
        HOSTS.firstOrNull { it.platform == platform } ?: throw GradleException("No shared rules library for $platform")

    private const val RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Server/releases/download"

    // The tags, from git's ref listing: unlike GitHub's API it has no rate limit for the many
    // builds CI runs, and the release workflow names releases the same way.
    private const val REFS = "https://github.com/AnkiQuest/AnkiQuest-Server.git/info/refs?service=git-upload-pack"
    private val TAG = Regex("rules-([1-9][0-9]*)")
    private val TAG_REF = Regex("refs/tags/(rules-[1-9][0-9]*)$")
    private val SUM = Regex("([0-9a-f]{64}) [ *]([A-Za-z0-9._-]+)")

    /** The last newest tag a build found, in the cache, for builds without network. */
    private const val NEWEST = "newest"

    /** A release: its [tag], its `SHA256SUMS` by asset name, and each asset's cached file. */
    data class Release(
        val tag: String,
        val sums: Map<String, String>,
        val files: Map<String, File>,
    )

    /** The highest `rules-<N>` tag in a git ref listing, or null when there is none. */
    fun newest(refs: String): String? =
        refs
            .lineSequence()
            .mapNotNull { line -> TAG_REF.find(line.trim())?.groupValues?.get(1) }
            .maxByOrNull { TAG.matchEntire(it)!!.groupValues[1].toBigInteger() }

    /** The hashes of a `SHA256SUMS` file by asset name. Any other line fails. */
    fun sums(text: String): Map<String, String> =
        text
            .lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val match = SUM.matchEntire(line) ?: throw GradleException("$SUMS: unexpected line $line")
                match.groupValues[2] to match.groupValues[1]
            }

    private fun listed(
        sums: Map<String, String>,
        tag: String,
        name: String,
    ): String = sums[name] ?: throw GradleException("$SUMS of $tag lists no $name")

    /**
     * Release [tag], or the newest one, with the assets [names] checked against its `SHA256SUMS`
     * and cached in [cache]. Without network, the newest tag and its sums come from the cache.
     */
    fun released(
        tag: String?,
        cache: File,
        names: List<String>,
        download: (URI) -> ByteArray = AnkiquestArt::get,
    ): Release {
        // The tag: the one asked for, the newest online, or the newest seen before.
        val newest = cache.resolve(NEWEST)
        val chosen =
            tag ?: try {
                val found =
                    newest(String(download(URI(REFS)), Charsets.UTF_8))
                        ?: throw GradleException("AnkiQuest-Server has no rules-<N> release yet")
                cache.mkdirs()
                newest.writeText(found)
                found
            } catch (e: IOException) {
                if (!newest.isFile) {
                    throw GradleException("Could not list AnkiQuest-Server's rules releases", e)
                }
                newest.readText().trim()
            }
        if (!TAG.matches(chosen)) {
            throw GradleException("$chosen is not a rules-<N> release")
        }

        // The sums of a published release never change, so a cached copy serves offline.
        val cachedSums = cache.resolve("$SUMS-$chosen")
        val sumsText =
            try {
                String(download(URI("$RELEASES/$chosen/$SUMS")), Charsets.UTF_8).also {
                    cache.mkdirs()
                    cachedSums.writeText(it)
                }
            } catch (e: IOException) {
                if (!cachedSums.isFile) {
                    throw GradleException("Could not download $SUMS of $chosen", e)
                }
                cachedSums.readText()
            }
        val sums = sums(sumsText)

        val files =
            names.associateWith { name ->
                val sha256 = listed(sums, chosen, name)
                AnkiquestArt.cached(URI("$RELEASES/$chosen/$name"), sha256, cache, download) { actual ->
                    "$name of $chosen has SHA-256 $actual; its $SUMS says $sha256"
                }
            }
        return Release(chosen, sums, files)
    }

    /** The assets [names] in [folder], checked against the `SHA256SUMS` there. */
    fun local(
        folder: File,
        tag: String?,
        names: List<String>,
    ): Release {
        val sums = sums(folder.resolve(SUMS).readText())
        val label = tag ?: LOCAL_TAG
        val files =
            names.associateWith { name ->
                val file = folder.resolve(name)
                val sha256 = listed(sums, label, name)
                if (!file.isFile || AnkiquestArt.sha256(file.readBytes()) != sha256) {
                    throw GradleException("$name in $folder does not have the SHA-256 its $SUMS lists")
                }
                file
            }
        return Release(label, sums, files)
    }

    /** Writes the bindings into [kotlin], the libraries into [jniLibs] and the record into [assets]. */
    fun stageApp(
        release: Release,
        kotlin: File,
        jniLibs: File,
        assets: File,
    ) {
        listOf(kotlin, jniLibs, assets).forEach { it.deleteRecursively() }

        release.files.getValue(BINDINGS).copyTo(kotlin.resolve(BINDINGS_PATH))
        for (abi in ABIS) {
            release.files.getValue(androidAsset(abi)).copyTo(jniLibs.resolve("$abi/$LIBRARY"))
        }

        // The tag and the hashes of what the app ships, sorted, so the same release writes the
        // same bytes.
        val hashes = APP_ASSETS.sorted().joinToString(",\n") { "    \"$it\": \"${release.sums.getValue(it)}\"" }
        assets.resolve(RECORD).apply { parentFile.mkdirs() }.writeText(
            "{\n  \"tag\": \"${release.tag}\",\n  \"assets\": {\n$hashes\n  }\n}\n",
        )
    }

    /** Writes the library of [host] into [dir] under the name JNA loads. */
    fun stageHost(
        release: Release,
        host: Host,
        dir: File,
    ) {
        dir.deleteRecursively()
        release.files.getValue(host.asset).copyTo(dir.resolve(host.name))
    }

    /** The platform folder of the machine Gradle runs on, as [HOSTS] names it. */
    fun hostPlatform(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String {
        val system =
            when {
                os.startsWith("Linux") -> "linux"
                os.startsWith("Windows") -> "windows"
                os.startsWith("Mac") -> "macos"
                else -> throw GradleException("No shared rules library for $os")
            }
        val cpu =
            when (arch) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> if (system == "macos") "arm64" else "aarch64"
                else -> throw GradleException("No shared rules library for $arch")
            }
        return host("$system-$cpu").platform
    }
}

/** Where a build takes the rules from: a folder, a release tag, or the newest release. */
abstract class AnkiquestRulesSource : DefaultTask() {
    /** A folder with the assets and their `SHA256SUMS`, for a release not published yet. */
    @get:Internal
    abstract val localDir: DirectoryProperty

    /** The release to use instead of the newest. */
    @get:Internal
    abstract val tag: Property<String>

    /** Downloaded assets by SHA-256, shared between builds. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    protected fun release(names: List<String>): AnkiquestRules.Release {
        val folder = localDir.orNull?.asFile
        if (folder != null) {
            return AnkiquestRules.local(folder, tag.orNull, names)
        }
        return AnkiquestRules.released(tag.orNull, cacheDir.get().asFile, names)
    }
}

/**
 * Writes the rules' Kotlin bindings, Android libraries and release record into generated sources.
 * Untracked: which release is newest can change between builds. The files it writes only change
 * with the release, so the tasks that read them stay up to date.
 */
@UntrackedTask(because = "takes the newest rules release, which can change between builds")
abstract class AnkiquestRulesTask : AnkiquestRulesSource() {
    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val release = release(AnkiquestRules.APP_ASSETS)
        AnkiquestRules.stageApp(release, kotlinDir.get().asFile, jniLibsDir.get().asFile, assetsDir.get().asFile)
        logger.info("Shared rules from ${release.tag}")
    }
}

/**
 * Writes the desktop library of the machine Gradle runs on, which the JVM unit tests load; see
 * [AnkiquestRulesLibraryPath].
 */
@UntrackedTask(because = "takes the newest rules release, which can change between builds")
abstract class AnkiquestRulesHostTask : AnkiquestRulesSource() {
    @get:OutputDirectory
    abstract val hostDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val host = AnkiquestRules.host(AnkiquestRules.hostPlatform())
        AnkiquestRules.stageHost(release(listOf(host.asset)), host, hostDir.get().asFile)
    }
}

/** Points JNA at the rules library [AnkiquestRulesHostTask] wrote, for a unit test JVM. */
class AnkiquestRulesLibraryPath(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val dir: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Djna.library.path=${dir.get().asFile}")
}
