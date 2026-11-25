// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONException
import org.json.JSONObject

/*
 * Companion packs as AnkiQuest-Art's docs/packs.md defines them: the release index `packs.json`,
 * each pack's `companion.json`, and the pose names with their fallbacks. Parsing only; reading a
 * zip is AnkiquestPackReader's, storing packs AnkiquestPackStore's.
 */

/** Why a pack or index was refused. Nothing of a refused pack is kept. */
internal enum class PackRefusal {
    TOO_LARGE,
    TOO_MANY_ENTRIES,
    BAD_ENTRY_NAME,
    DUPLICATE_ENTRY,
    NOT_A_PICTURE,
    PICTURE_TOO_LARGE,
    BAD_INDEX,
    NEWER_FORMAT,
    BAD_ID,
    UNKNOWN_POSE,
    MISSING_CORE_POSE,
    MISSING_FILE,
    UNUSED_FILE,
    RESERVED_ID,
    CHECKSUM,
    NOT_ALLOWED,
}

/** A pack or index broke a rule of docs/packs.md. */
internal class PackRefused(
    val reason: PackRefusal,
    detail: String,
) : Exception("$reason: $detail")

/** Limits and names of the pack format. */
internal object PackFormat {
    /** The highest format this app reads. */
    const val VERSION = 1

    /** The index inside a pack. */
    const val PACK_INDEX = "companion.json"

    /** The release index next to the packs. */
    const val RELEASE_INDEX = "packs.json"

    /** Longest companion name, in characters. */
    const val MAX_NAME = 40

    /** Longest licence notice, in characters. */
    const val MAX_NOTICE = 16 * 1024

    private val ID = Regex("[a-z][a-z0-9_]{0,31}")

    fun validId(id: String) = ID.matches(id)
}

/**
 * The names a pack may have a picture for: [core] poses every pack has, and every other name mapped
 * to the name shown when a pack lacks it.
 */
internal class PoseNames(
    val core: List<String>,
    val fallbacks: Map<String, String>,
) {
    init {
        // Every chain must end at a core pose; a cycle or an unknown name is a broken index.
        for (name in fallbacks.keys) {
            val end = chain(name).last()
            if (end !in core) {
                throw PackRefused(PackRefusal.BAD_INDEX, "the fallbacks from $name end at $end")
            }
        }
    }

    fun knows(name: String) = name in core || name in fallbacks

    /** [name], then each fallback in turn; stops before a name repeats. */
    fun chain(name: String): List<String> {
        val seen = mutableListOf(name)
        var at = fallbacks[name]
        while (at != null && at !in seen) {
            seen += at
            at = fallbacks[at]
        }
        return seen
    }

    companion object {
        /** The names this build knows without an index: [AnkiquestPose]'s core poses and extensions. */
        fun builtIn(): PoseNames {
            val core = AnkiquestPose.entries.filter { it.chain().count() == 1 }.map { it.id }
            val fallbacks =
                AnkiquestPose.entries
                    .mapNotNull { pose ->
                        pose
                            .chain()
                            .drop(1)
                            .firstOrNull()
                            ?.let { pose.id to it.id }
                    }.toMap()
            return PoseNames(core, fallbacks)
        }
    }
}

/** One pack listed in `packs.json`. */
internal data class PackEntry(
    val id: String,
    val name: String,
    val version: Int,
    val file: String,
    val size: Long,
    val sha256: String,
)

/** `packs.json`: a release's packs, in the order the app lists companions, and its pose names. */
internal data class PackIndex(
    val tag: String,
    val version: Int,
    val names: PoseNames,
    val companions: List<PackEntry>,
) {
    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** Parses `packs.json`; throws [PackRefused] when it breaks a rule. */
        fun parse(text: String): PackIndex = fields { read(jsonObject(text)) }

        private fun read(json: JSONObject): PackIndex {
            checkFormat(json)
            val names =
                PoseNames(
                    core = json.getJSONArray("core").strings(),
                    fallbacks = json.getJSONObject("fallbacks").let { map -> map.keys().asSequence().associateWith { map.getString(it) } },
                )
            val companions = json.getJSONArray("companions").objects().map { entry(it) }

            // Ids are unique, so each names one pack.
            if (companions.map { it.id }.toSet().size != companions.size) {
                throw PackRefused(PackRefusal.BAD_INDEX, "an id is listed twice")
            }
            return PackIndex(json.getString("tag"), positive(json, "version"), names, companions)
        }

        private fun entry(json: JSONObject): PackEntry {
            val entry =
                PackEntry(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    version = positive(json, "version"),
                    file = json.getString("file"),
                    size = json.getLong("size"),
                    sha256 = json.getString("sha256"),
                )
            if (!PackFormat.validId(entry.id)) {
                throw PackRefused(PackRefusal.BAD_ID, entry.id)
            }

            // The file is a plain asset name of this release; no path can sneak into a URL.
            if (entry.file != "pack-${entry.id}.zip" || !SHA256.matches(entry.sha256) || entry.size <= 0) {
                throw PackRefused(PackRefusal.BAD_INDEX, "entry ${entry.id}")
            }
            return entry
        }
    }
}

/** A pack's `companion.json`. [poses] maps each name to a picture file in the pack. */
internal data class PackInfo(
    val id: String,
    val name: String,
    val version: Int,
    val licence: String,
    val credit: String,
    val notice: String?,
    val poses: Map<String, String>,
) {
    companion object {
        /** Parses `companion.json` against [names]; throws [PackRefused] when it breaks a rule. */
        fun parse(
            text: String,
            names: PoseNames,
        ): PackInfo = fields { read(jsonObject(text), names) }

        private fun read(
            json: JSONObject,
            names: PoseNames,
        ): PackInfo {
            checkFormat(json)
            val poses = json.getJSONObject("poses").let { map -> map.keys().asSequence().associateWith { map.getString(it) } }
            val info =
                PackInfo(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    version = positive(json, "version"),
                    licence = json.getString("licence"),
                    credit = json.getString("credit"),
                    notice = if (json.isNull("notice")) null else json.getString("notice"),
                    poses = poses,
                )

            // The id, name and notice.
            if (!PackFormat.validId(info.id)) {
                throw PackRefused(PackRefusal.BAD_ID, info.id)
            }
            if (info.name.isBlank() || info.name.length > PackFormat.MAX_NAME) {
                throw PackRefused(PackRefusal.BAD_INDEX, "name of ${info.id}")
            }
            if ((info.notice?.length ?: 0) > PackFormat.MAX_NOTICE) {
                throw PackRefused(PackRefusal.TOO_LARGE, "notice of ${info.id}")
            }

            // Only known names, and every core pose.
            poses.keys.firstOrNull { !names.knows(it) }?.let { throw PackRefused(PackRefusal.UNKNOWN_POSE, it) }
            names.core.firstOrNull { it !in poses }?.let { throw PackRefused(PackRefusal.MISSING_CORE_POSE, it) }
            return info
        }
    }
}

/** [block]'s answer; a missing or mistyped field is [PackRefusal.BAD_INDEX]. */
private fun <T> fields(block: () -> T): T =
    try {
        block()
    } catch (e: JSONException) {
        throw PackRefused(PackRefusal.BAD_INDEX, e.message ?: "bad field")
    }

/** [text] as a JSON object, or [PackRefusal.BAD_INDEX]. */
private fun jsonObject(text: String): JSONObject =
    try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw PackRefused(PackRefusal.BAD_INDEX, e.message ?: "not JSON")
    }

/** Refuses a format this app does not know. */
private fun checkFormat(json: JSONObject) {
    val format = json.optInt("format", 0)
    if (format <= 0) {
        throw PackRefused(PackRefusal.BAD_INDEX, "no format")
    }
    if (format > PackFormat.VERSION) {
        throw PackRefused(PackRefusal.NEWER_FORMAT, "format $format")
    }
}

/** The positive integer [key] of [json]. */
private fun positive(
    json: JSONObject,
    key: String,
): Int {
    val value = json.optInt(key, 0)
    if (value <= 0) {
        throw PackRefused(PackRefusal.BAD_INDEX, "$key is not a positive number")
    }
    return value
}
