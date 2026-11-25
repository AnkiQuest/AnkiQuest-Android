// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import java.io.File
import java.io.IOException

/**
 * One folder of installed packs: `<id>/companion.json` and its pictures per pack, and the release
 * index `packs.json` that came with them. The app's assets and its storage look alike through this,
 * so the library never touches files or assets itself.
 */
internal interface PackFolder {
    /** The ids of the packs in this folder. */
    fun ids(): List<String>

    /** [file] of pack [id], or null when it is not there. */
    fun read(
        id: String,
        file: String,
    ): ByteArray?

    /** The release index stored with these packs, or null. */
    fun index(): ByteArray?
}

/** A [PackFolder] packs can be installed into. */
internal interface WritablePackFolder : PackFolder {
    /** Installs [pack], replacing a pack with its id; a reader never sees half a pack. */
    fun install(pack: Pack)

    /** Removes pack [id], if it is there. */
    fun remove(id: String)

    /** Stores the release index the packs came from. */
    fun writeIndex(bytes: ByteArray)
}

/** A [WritablePackFolder] in the directory [root] of app storage. */
internal class FilePackFolder(
    private val root: File,
) : WritablePackFolder {
    override fun ids(): List<String> =
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && PackFormat.validId(it.name) && File(it, PackFormat.PACK_INDEX).isFile }
            .map { it.name }
            .sorted()

    override fun read(
        id: String,
        file: String,
    ): ByteArray? {
        // Names come from checked packs, but never leave the folder whatever they hold.
        if (!PackFormat.validId(id) || file.contains('/') || file.contains('\\') || file.startsWith(".")) {
            return null
        }
        return File(File(root, id), file).takeIf { it.isFile }?.readBytes()
    }

    override fun index(): ByteArray? = File(root, PackFormat.RELEASE_INDEX).takeIf { it.isFile }?.readBytes()

    override fun install(pack: Pack) {
        val id = pack.info.id
        root.mkdirs()

        // Write the pack beside its final place, then swap it in by renames.
        val staging = File(root, ".$id.new")
        val old = File(root, ".$id.old")
        staging.deleteRecursively()
        old.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IOException("cannot create $staging")
        }
        for ((file, bytes) in pack.pictures) {
            File(staging, file).writeBytes(bytes)
        }
        File(staging, PackFormat.PACK_INDEX).writeBytes(pack.index)

        // ids() skips the dot-named staging folders, so it lists the pack only once it is whole.
        val target = File(root, id)
        if (target.exists() && !target.renameTo(old)) {
            throw IOException("cannot move $target aside")
        }
        if (!staging.renameTo(target)) {
            old.renameTo(target)
            throw IOException("cannot install $target")
        }
        old.deleteRecursively()
    }

    override fun remove(id: String) {
        if (!PackFormat.validId(id)) {
            return
        }
        File(root, id).deleteRecursively()
    }

    override fun writeIndex(bytes: ByteArray) {
        root.mkdirs()
        val staging = File(root, ".${PackFormat.RELEASE_INDEX}.new")
        staging.writeBytes(bytes)
        if (!staging.renameTo(File(root, PackFormat.RELEASE_INDEX))) {
            throw IOException("cannot store ${PackFormat.RELEASE_INDEX}")
        }
    }
}
