// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import timber.log.Timber
import java.io.InputStream

/** Where an installed pack came from (docs/packs.md, "Where packs come from"). */
internal enum class PackOrigin {
    /** In the APK's assets, from the Art release the build pins. */
    BUNDLED,

    /** Downloaded from the latest Art release. */
    DOWNLOADED,

    /** Picked by the player; shown on this device only. */
    IMPORTED,
}

/** A pack the library can show, read through the folder it lives in. */
internal class InstalledPack(
    val info: PackInfo,
    val origin: PackOrigin,
    private val folder: PackFolder,
) {
    /** The bytes of [file] in this pack, or null when it cannot be read. */
    fun file(file: String): ByteArray? = folder.read(info.id, file)
}

/**
 * Every installed companion pack, and which one shows each companion. An official companion (one
 * an Art index lists) shows the bundled or downloaded pack with the higher version; imported packs
 * add companions of their own and never replace an official one.
 */
internal class AnkiquestPackLibrary(
    private val bundled: PackFolder,
    private val downloaded: WritablePackFolder,
    private val imported: WritablePackFolder,
) {
    /** What the folders held when last read; null until read or after a change. */
    @Volatile
    private var snapshot: Snapshot? = null

    /** The installed packs and the newest index, read together. */
    private class Snapshot(
        val index: PackIndex?,
        val names: PoseNames,
        val official: List<InstalledPack>,
        val imported: List<InstalledPack>,
    )

    /** The newest Art index installed: bundled or downloaded, by release version. */
    fun index(): PackIndex? = read().index

    /** The names packs may use: the newest index's, else this build's own. */
    fun names(): PoseNames = read().names

    /** Every companion, official ones in the index's order, then imported ones by name. */
    fun companions(): List<InstalledPack> = read().let { it.official + it.imported }

    /** The pack that shows companion [id], or null. */
    fun companion(id: String): InstalledPack? = companions().firstOrNull { it.info.id == id }

    /** Ids an imported pack may not use: every companion an index lists or a bundled or downloaded pack has. */
    fun officialIds(): Set<String> =
        read().let { snapshot ->
            snapshot.official.map { it.info.id }.toSet() +
                snapshot.index
                    ?.companions
                    .orEmpty()
                    .map { it.id }
        }

    /** The version of the official pack installed for [id], or 0. */
    fun installedVersion(id: String): Int =
        read()
            .official
            .firstOrNull { it.info.id == id }
            ?.info
            ?.version ?: 0

    /** Companion [id]'s picture for [name], or for its nearest fallback the pack has; null without a pack. */
    fun picture(
        id: String,
        name: String,
    ): ByteArray? {
        val pack = companion(id) ?: return null
        val file = names().chain(name).firstNotNullOfOrNull { pack.info.poses[it] } ?: return null
        return pack.file(file)
    }

    /** Checks the pack in [input] and installs it as an imported companion. */
    fun import(input: InputStream): PackInfo {
        val pack = AnkiquestPackReader.read(input, names())
        if (pack.info.id in officialIds()) {
            throw PackRefused(PackRefusal.RESERVED_ID, pack.info.id)
        }
        imported.install(pack)
        changed()
        return pack.info
    }

    /** Removes the imported companion [id]. */
    fun removeImported(id: String) {
        imported.remove(id)
        changed()
    }

    /** Installs downloaded [packs] and, once they are in, the [index] they came from. */
    fun installDownloaded(
        packs: List<Pack>,
        index: ByteArray,
    ) {
        packs.forEach { downloaded.install(it) }
        downloaded.writeIndex(index)
        changed()
    }

    /** Forgets what was read, so the next call reads the folders again. */
    fun changed() {
        snapshot = null
    }

    private fun read(): Snapshot {
        snapshot?.let { return it }

        // The newer index decides the names and the order of official companions.
        val index = listOfNotNull(parseIndex(bundled), parseIndex(downloaded)).maxByOrNull { it.version }
        val names = index?.names ?: PoseNames.builtIn()

        // Per official id, the higher version of bundled and downloaded; bundled wins a tie.
        val candidates = packs(bundled, PackOrigin.BUNDLED, names) + packs(downloaded, PackOrigin.DOWNLOADED, names)
        val best = candidates.groupBy { it.info.id }.mapValues { (_, packs) -> packs.maxWith(newest) }
        val order = index?.companions.orEmpty().map { it.id }
        val official = best.values.sortedWith(compareBy({ rank(order, it.info.id) }, { it.info.id }))

        // Imported packs never shadow an official companion.
        val reserved = best.keys + order
        val imported =
            packs(imported, PackOrigin.IMPORTED, names)
                .filter { it.info.id !in reserved }
                .sortedBy { it.info.name.lowercase() }
        return Snapshot(index, names, official, imported).also { snapshot = it }
    }

    /** Higher version first; on a tie the bundled pack, which needs no storage. */
    private val newest = compareBy<InstalledPack> { it.info.version }.thenBy { it.origin == PackOrigin.BUNDLED }

    /** [id]'s place in [order]; ids the index does not list go last. */
    private fun rank(
        order: List<String>,
        id: String,
    ): Int = order.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE

    /** The index stored in [folder], or null when there is none or it is broken. */
    private fun parseIndex(folder: PackFolder): PackIndex? {
        val bytes = folder.index() ?: return null
        return try {
            PackIndex.parse(bytes.decodeToString())
        } catch (e: PackRefused) {
            Timber.w(e, "AnkiQuest pack index ignored")
            null
        }
    }

    /** The packs in [folder] whose index still checks against [names]. */
    private fun packs(
        folder: PackFolder,
        origin: PackOrigin,
        names: PoseNames,
    ): List<InstalledPack> =
        folder.ids().mapNotNull { id ->
            val bytes = folder.read(id, PackFormat.PACK_INDEX) ?: return@mapNotNull null
            try {
                val info = PackInfo.parse(bytes.decodeToString(), names)
                InstalledPack(info, origin, folder).takeIf { info.id == id }
            } catch (e: PackRefused) {
                Timber.w(e, "AnkiQuest pack %s ignored", id)
                null
            }
        }
}
