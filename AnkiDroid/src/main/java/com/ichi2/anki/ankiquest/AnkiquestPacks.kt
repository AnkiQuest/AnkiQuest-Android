// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's companion packs: the bundled ones in assets, and the downloaded and imported ones in
 * app storage. Everything else asks [library] and [download].
 */
internal object AnkiquestPacks {
    /** Where the full build puts its bundled packs and their index, inside assets. */
    const val ASSETS = "ankiquest/packs"

    /** Where downloaded and imported packs live, inside the app's files. */
    private const val STORAGE = "ankiquest/packs"

    @Volatile
    private var library: AnkiquestPackLibrary? = null

    /** The library over this app's folders, created once. */
    fun library(context: Context): AnkiquestPackLibrary {
        library?.let { return it }
        synchronized(this) {
            library?.let { return it }
            val storage = File(context.filesDir, STORAGE)
            return AnkiquestPackLibrary(
                bundled = AssetPackFolder(context.assets, ASSETS),
                downloaded = FilePackFolder(File(storage, "downloaded")),
                imported = FilePackFolder(File(storage, "imported")),
            ).also { library = it }
        }
    }

    @Volatile
    private var build: ArtBuild? = null

    /** The automatic download of the pinned packs in the slim APK; tried once per process. */
    private enum class AutoDownload { WAITING, RUNNING, DONE }

    private val auto = AtomicReference(AutoDownload.WAITING)

    /** What this build says about its packs, read once from assets. */
    fun build(context: Context): ArtBuild {
        build?.let { return it }
        val text =
            try {
                context.assets.open(ArtBuild.ASSET).use { it.readBytes().decodeToString() }
            } catch (e: IOException) {
                null
            }
        return (text?.let(ArtBuild::parse) ?: ArtBuild.LOCAL).also { build = it }
    }

    /**
     * In the slim APK, downloads the packs of the release the build pins while no index as new is
     * installed. Runs once per process; a failure waits for the next start or a tap on "Download
     * companions". Blocking: call it off the main thread.
     */
    fun fetchPinned(context: Context) {
        val build = build(context)
        val pinned = build.pinned ?: return
        if (build.apk != ApkKind.SLIM || !auto.compareAndSet(AutoDownload.WAITING, AutoDownload.RUNNING)) {
            return
        }
        try {
            if ((library(context).index()?.version ?: 0) < build.pinnedVersion) {
                download(context).update(pinned)
            }
        } catch (e: PackRefused) {
            Timber.w(e, "AnkiQuest pinned packs refused")
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest pinned packs not downloaded")
        } finally {
            auto.set(AutoDownload.DONE)
        }
    }

    @Volatile
    private var download: AnkiquestPackDownload? = null

    /** The downloader for [context]'s library over AnkiQuest's HTTP client, created once so updates never overlap. */
    fun download(context: Context): AnkiquestPackDownload {
        download?.let { return it }
        synchronized(this) {
            download?.let { return it }
            return AnkiquestPackDownload(library(context), GithubPackFetch(AnkiquestHttp.client)).also { download = it }
        }
    }
}

/** Which APK this is: the full one bundles the pinned release's packs, the slim one downloads them. */
internal enum class ApkKind(
    /** The release asset an update of this APK installs. */
    val file: String,
) {
    FULL("AnkiQuest.apk"),
    SLIM("AnkiQuest-slim.apk"),
}

/**
 * What a release build writes into [ASSET] (buildSrc BundleAnkiquestPacks): which APK it is and the
 * Art release it pins, with the SHA-256 of that release's `packs.json`.
 */
internal class ArtBuild(
    val apk: ApkKind,
    val pinned: PackSource.Pinned?,
) {
    /** `N` of the pinned `art-<N>`, or 0 without a pin. */
    val pinnedVersion: Int =
        pinned?.let {
            TAG
                .matchEntire(it.tag)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        } ?: 0

    companion object {
        const val ASSET = "ankiquest/art.json"

        // Before LOCAL, which is built while the companion is initialised.
        private val TAG = Regex("art-([1-9][0-9]{0,8})")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** A build without [ASSET], such as a debug build: a full APK with no packs pinned. */
        val LOCAL = ArtBuild(ApkKind.FULL, null)

        /** [text] of [ASSET]; [LOCAL] when it cannot be read. */
        fun parse(text: String): ArtBuild =
            try {
                val json = JSONObject(text)
                val apk = ApkKind.entries.firstOrNull { it.name.lowercase() == json.getString("apk") } ?: ApkKind.FULL
                val tag = json.optString("tag")
                val sha256 = json.optString("packs_sha256")
                val pinned = PackSource.Pinned(tag, sha256).takeIf { TAG.matches(tag) && SHA256.matches(sha256) }
                ArtBuild(apk, pinned)
            } catch (e: JSONException) {
                Timber.w(e, "AnkiQuest %s ignored", ASSET)
                LOCAL
            }
    }
}

/** The bundled packs in assets under [root]; empty in a build without them. */
internal class AssetPackFolder(
    private val assets: AssetManager,
    private val root: String,
) : PackFolder {
    override fun ids(): List<String> =
        try {
            assets
                .list(root)
                .orEmpty()
                .filter { PackFormat.validId(it) }
                .sorted()
        } catch (e: IOException) {
            emptyList()
        }

    override fun read(
        id: String,
        file: String,
    ): ByteArray? {
        if (!PackFormat.validId(id) || file.contains('/')) {
            return null
        }
        return open("$root/$id/$file")
    }

    override fun index(): ByteArray? = open("$root/${PackFormat.RELEASE_INDEX}")

    private fun open(path: String): ByteArray? =
        try {
            assets.open(path).use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
}
