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
    @Synchronized
    override fun ids(): List<String> =
        root
            .listFiles()
            .orEmpty()
            .mapNotNull { directory ->
                val name = directory.name
                val id = if (name.startsWith('.') && name.endsWith(OLD_SUFFIX)) name.drop(1).removeSuffix(OLD_SUFFIX) else name
                id.takeIf { PackFormat.validId(it) && File(directory, PackFormat.PACK_INDEX).isFile }
            }.distinct()
            .sorted()

    @Synchronized
    override fun read(
        id: String,
        file: String,
    ): ByteArray? {
        // Names come from checked packs, but never leave the folder whatever they hold.
        if (!PackFormat.validId(id) || file.contains('/') || file.contains('\\') || file.startsWith(".")) {
            return null
        }
        // A process can stop after moving the previous pack aside and before installing the new one.
        val target = File(root, id)
        val complete = if (target.isDirectory) target else backup(id)
        return File(complete, file).takeIf { it.isFile }?.readBytes()
    }

    @Synchronized
    override fun index(): ByteArray? = File(root, PackFormat.RELEASE_INDEX).takeIf { it.isFile }?.readBytes()

    @Synchronized
    override fun install(pack: Pack) {
        val id = pack.info.id
        root.mkdirs()

        // Write the pack beside its final place, then swap it in by renames.
        val staging = File(root, ".$id.new")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw IOException("cannot create $staging")
        }
        for ((file, bytes) in pack.pictures) {
            File(staging, file).writeBytes(bytes)
        }
        File(staging, PackFormat.PACK_INDEX).writeBytes(pack.index)

        // Keep any previous complete pack until the replacement is in place.
        val target = File(root, id)
        val old = backup(id)
        if (target.exists()) {
            if (!old.deleteRecursively() || !target.renameTo(old)) {
                throw IOException("cannot move $target aside")
            }
        }
        if (!staging.renameTo(target)) {
            old.renameTo(target)
            throw IOException("cannot install $target")
        }
        old.deleteRecursively()
    }

    @Synchronized
    override fun remove(id: String) {
        if (!PackFormat.validId(id)) {
            return
        }
        if (!backup(id).deleteRecursively() || !File(root, id).deleteRecursively()) {
            throw IOException("cannot remove pack $id")
        }
    }

    @Synchronized
    override fun writeIndex(bytes: ByteArray) {
        root.mkdirs()
        val staging = File(root, ".${PackFormat.RELEASE_INDEX}.new")
        staging.writeBytes(bytes)
        if (!staging.renameTo(File(root, PackFormat.RELEASE_INDEX))) {
            throw IOException("cannot store ${PackFormat.RELEASE_INDEX}")
        }
    }

    private fun backup(id: String) = File(root, ".$id$OLD_SUFFIX")

    private companion object {
        const val OLD_SUFFIX = ".old"
    }
}
