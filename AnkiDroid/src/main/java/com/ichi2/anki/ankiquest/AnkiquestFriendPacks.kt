// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Companion packs shared through the AnkiQuest server (AnkiQuest-Server docs/companion-packs.md):
 * the player uploads an imported pack, and friends' packs are fetched by hash into a bounded cache.
 * A friend's pack is never one of the player's companions: it only draws that friend.
 */

/** Who may read an uploaded pack; the server's `visibility`. */
internal enum class PackVisibility(
    val wire: String,
) {
    /** The owner only. */
    OWNER("owner"),

    /** The owner and their friends; at most one pack per player. */
    FRIENDS("friends"),
    ;

    companion object {
        fun of(wire: String): PackVisibility? = entries.firstOrNull { it.wire == wire }
    }
}

/** Why a player reports a friend's pack; the server's `reason`. */
internal enum class PackReport(
    val wire: String,
) {
    OFFENSIVE("offensive"),
    COPYRIGHT("copyright"),
    OTHER("other"),
}

/** A pack as the server lists it. [visibility] is null in a friend's listing. */
internal data class SharedPack(
    val sha256: String,
    val id: String,
    val name: String,
    val licence: String,
    val credit: String,
    val notice: String?,
    val size: Long,
    val visibility: PackVisibility?,
) {
    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** [json] as a pack, or null when a field is missing or not what the API says. */
        fun parse(json: JSONObject): SharedPack? {
            val sha256 = json.optString("sha256")
            val id = json.optString("id")
            if (!SHA256.matches(sha256) || !PackFormat.validId(id)) {
                return null
            }
            return SharedPack(
                sha256 = sha256,
                id = id,
                name = json.optString("name").take(PackFormat.MAX_NAME),
                licence = json.optString("licence"),
                credit = json.optString("credit"),
                notice = if (json.isNull("notice")) null else json.optString("notice"),
                size = json.optLong("size"),
                visibility = PackVisibility.of(json.optString("visibility")),
            )
        }

        /** `GET /api/packs/{user}/friends`: each friend's pack, leaving out unreadable entries. */
        fun friends(json: JSONObject): Map<String, SharedPack> {
            val friends = json.optJSONObject("friends") ?: return emptyMap()
            return friends
                .keys()
                .asSequence()
                .mapNotNull { friend -> friends.optJSONObject(friend)?.let(::parse)?.let { friend to it } }
                .toMap()
        }

        /** `GET /api/packs/{user}`: the player's own packs. */
        fun mine(json: JSONObject): List<SharedPack> {
            val packs = json.optJSONArray("packs") ?: return emptyList()
            return (0 until packs.length()).mapNotNull { packs.optJSONObject(it)?.let(::parse) }
        }
    }
}

/** Lower-case hex SHA-256 of [bytes]. */
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A zip of an installed pack: its index and every picture it names, for upload. */
internal fun zipOf(pack: InstalledPack): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        val index = pack.file(PackFormat.PACK_INDEX) ?: throw IOException("${pack.info.id} has no index")
        val files =
            listOf(PackFormat.PACK_INDEX to index) +
                pack.info.poses.values
                    .distinct()
                    .sorted()
                    .map { it to (pack.file(it) ?: throw IOException("${pack.info.id} has no $it")) }
        for ((name, bytes) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * Friends' packs by hash in [root]: `<sha256>/<id>/...`, each checked with the pack reader before
 * it is kept. Holds at most [maxPacks] packs and [maxBytes] of pictures; the least recently used
 * goes first.
 */
internal class FriendPackCache(
    private val root: File,
    private val maxPacks: Int = MAX_PACKS,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val hash = Regex("[0-9a-f]{64}")

    /** The cached pack [sha256], read against [names], or null. Marks it used. */
    fun get(
        sha256: String,
        names: PoseNames,
    ): InstalledPack? {
        if (!hash.matches(sha256)) {
            return null
        }
        val dir = File(root, sha256)
        val folder = FilePackFolder(dir)
        val id = folder.ids().singleOrNull() ?: return null
        val index = folder.read(id, PackFormat.PACK_INDEX) ?: return null
        val info =
            try {
                PackInfo.parse(index.decodeToString(), names)
            } catch (e: PackRefused) {
                Timber.w(e, "AnkiQuest friend pack %s dropped", sha256)
                dir.deleteRecursively()
                return null
            }
        dir.setLastModified(System.currentTimeMillis())
        return InstalledPack(info, PackOrigin.IMPORTED, folder)
    }

    /**
     * Keeps [bytes] as pack [sha256]: refused with [PackRefusal.CHECKSUM] unless they hash to it,
     * then read like an import against [names].
     */
    fun put(
        sha256: String,
        bytes: ByteArray,
        names: PoseNames,
    ): InstalledPack {
        if (!hash.matches(sha256) || sha256Hex(bytes) != sha256) {
            throw PackRefused(PackRefusal.CHECKSUM, "friend pack $sha256")
        }
        val pack = AnkiquestPackReader.read(bytes.inputStream(), names)
        val dir = File(root, sha256)
        FilePackFolder(dir).install(pack)
        dir.setLastModified(System.currentTimeMillis())
        trim(keep = sha256)
        return InstalledPack(pack.info, PackOrigin.IMPORTED, FilePackFolder(dir))
    }

    /** Drops every pack but [shas]: friends no longer show the others. */
    fun retain(shas: Set<String>) {
        cached().filter { it.name !in shas }.forEach { it.deleteRecursively() }
    }

    /** The hashes cached now. */
    fun hashes(): Set<String> = cached().map { it.name }.toSet()

    private fun cached(): List<File> = root.listFiles().orEmpty().filter { it.isDirectory && hash.matches(it.name) }

    /** Drops the least recently used packs, never [keep], until both limits hold. */
    private fun trim(keep: String) {
        val packs = cached().sortedByDescending { it.lastModified() }.toMutableList()
        while (packs.size > maxPacks || packs.sumOf { size(it) } > maxBytes) {
            val oldest = packs.lastOrNull { it.name != keep } ?: break
            oldest.deleteRecursively()
            packs.remove(oldest)
        }
    }

    private fun size(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MAX_PACKS = 16
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
