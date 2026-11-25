// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.IOException

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
