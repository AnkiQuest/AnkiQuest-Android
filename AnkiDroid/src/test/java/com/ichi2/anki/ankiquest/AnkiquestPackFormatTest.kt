// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lock list's SHA-256 shape: 64 hex characters. */
private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** Checks `packs.json` and `companion.json` parsing (AnkiquestPackFormat) against docs/packs.md. */
class AnkiquestPackFormatTest {
    private val names = PoseNames(core = listOf("face", "asleep"), fallbacks = mapOf("winner" to "face", "streak_hot" to "winner"))

    @Test
    fun ids_are_lowercase_words() {
        assertTrue(PackFormat.validId("aki"))
        assertTrue(PackFormat.validId("anki_tan"))
        assertTrue(PackFormat.validId("a" + "1".repeat(31)))
        assertFalse(PackFormat.validId("Aki"))
        assertFalse(PackFormat.validId("2aki"))
        assertFalse(PackFormat.validId(""))
        assertFalse(PackFormat.validId("a b"))
        assertFalse(PackFormat.validId("a" + "1".repeat(32)))
    }

    @Test
    fun fallback_chains_end_at_a_core_pose() {
        assertEquals(listOf("streak_hot", "winner", "face"), names.chain("streak_hot"))
        assertEquals(listOf("face"), names.chain("face"))
        assertTrue(names.knows("winner"))
        assertFalse(names.knows("leech_kun"))

        val cycle = PoseNames(core = listOf("face"), fallbacks = mapOf("a" to "b", "b" to "a"))
        val refused = assertFailsWith<PackRefused> { cycle.chain("a") }
        assertEquals(PackRefusal.BAD_INDEX, refused.reason)
    }

    @Test
    fun the_built_in_names_are_the_pose_enum_s() {
        val builtIn = PoseNames.builtIn()
        assertEquals(
            AnkiquestPose.entries.filter { it.chain().count() == 1 }.map { it.id },
            builtIn.core,
        )
        assertEquals(
            AnkiquestPose.entries
                .mapNotNull { pose ->
                    pose
                        .chain()
                        .drop(1)
                        .firstOrNull()
                        ?.let { pose.id to it.id }
                }.toMap(),
            builtIn.fallbacks,
        )
    }

    @Test
    fun a_release_index_is_parsed_with_its_packs_in_order() {
        val index =
            PackIndex.parse(
                index(
                    companions = """[{"id":"aki","name":"Aki","version":11,"file":"pack-aki.zip","size":10,"sha256":"$SHA"}]""",
                ),
            )
        assertEquals("art-11", index.tag)
        assertEquals(11, index.version)
        assertEquals(names.core, index.names.core)
        assertEquals(names.fallbacks, index.names.fallbacks)
        assertEquals(listOf("aki"), index.companions.map { it.id })
    }

    @Test
    fun a_release_index_is_refused_with_the_rule_it_breaks() {
        fun refused(text: String): PackRefusal = assertFailsWith<PackRefused> { PackIndex.parse(text) }.reason

        assertEquals(PackRefusal.BAD_INDEX, refused("not json"))
        assertEquals(PackRefusal.BAD_INDEX, refused(index(tag = "art-11", format = 0)))
        assertEquals(PackRefusal.NEWER_FORMAT, refused(index(tag = "art-99", format = 2)))
        assertEquals(PackRefusal.BAD_INDEX, refused(index(companions = "[]", version = 0)))
        assertEquals(
            PackRefusal.BAD_INDEX,
            refused(
                index(
                    companions =
                        """[{"id":"aki","name":"Aki","version":1,"file":"pack-aki.zip","size":10,"sha256":"$SHA"},
                            {"id":"aki","name":"Aki","version":1,"file":"pack-aki.zip","size":10,"sha256":"$SHA"}]""",
                ),
            ),
            "an id listed twice",
        )
        assertEquals(
            PackRefusal.BAD_ID,
            refused(
                index(
                    companions = """[{"id":"Aki","name":"Aki","version":1,"file":"pack-aki.zip","size":10,"sha256":"$SHA"}]""",
                ),
            ),
        )
        assertEquals(
            PackRefusal.BAD_INDEX,
            refused(
                index(
                    companions =
                        """[{"id":"aki","name":"Aki","version":1,"file":"elsewhere/pack-aki.zip","size":10,"sha256":"$SHA"}]""",
                ),
            ),
        )
        assertEquals(
            PackRefusal.BAD_INDEX,
            refused(
                index(
                    companions = """[{"id":"aki","name":"Aki","version":1,"file":"pack-aki.zip","size":10,"sha256":"short"}]""",
                ),
            ),
        )
        assertEquals(
            PackRefusal.BAD_INDEX,
            refused(
                index(
                    companions = """[{"id":"aki","name":"Aki","version":1,"file":"pack-aki.zip","size":0,"sha256":"$SHA"}]""",
                ),
            ),
        )
        assertEquals(
            PackRefusal.TOO_LARGE,
            refused(
                index(
                    companions =
                        """[{"id":"aki","name":"Aki","version":1,"file":"pack-aki.zip","size":${PackFormat.MAX_ARCHIVE + 1},"sha256":"$SHA"}]""",
                ),
            ),
        )
        assertEquals(PackRefusal.BAD_INDEX, refused(index(fallbacks = """{"a":"b","b":"a"}""", companions = "[]")))
    }

    @Test
    fun a_pack_index_lists_its_pictures_by_pose() {
        val info = PackInfo.parse(info(poses = """{"face":"aki_face.webp"}"""), names)
        assertEquals("aki", info.id)
        assertEquals("Aki", info.name)
        assertEquals(11, info.version)
        assertEquals("CC BY-SA", info.licence)
        assertEquals(mapOf("face" to "aki_face.webp"), info.poses)
        assertNull(info.notice)
    }

    @Test
    fun a_pack_index_is_refused_with_the_rule_it_breaks() {
        fun refused(text: String): PackRefusal = assertFailsWith<PackRefused> { PackInfo.parse(text, names) }.reason

        assertEquals(PackRefusal.BAD_INDEX, refused("not json"))
        assertEquals(PackRefusal.NEWER_FORMAT, refused(info(format = 2)))
        assertEquals(PackRefusal.BAD_ID, refused(info(id = "Aki")))
        assertEquals(PackRefusal.BAD_INDEX, refused(info(name = " ")))
        assertEquals(PackRefusal.BAD_INDEX, refused(info(name = "a".repeat(PackFormat.MAX_NAME + 1))))
        assertEquals(PackRefusal.TOO_LARGE, refused(info(notice = "a".repeat(PackFormat.MAX_NOTICE + 1))))
        assertEquals(PackRefusal.UNKNOWN_POSE, refused(info(poses = """{"face":"aki_face.webp","leech_kun":"aki.png"}""")))
        assertEquals(
            PackRefusal.MISSING_CORE_POSE,
            refused(
                info(poses = """{"face":"aki_face.webp","winner":"aki_win.webp"}"""),
            ),
            "a pack without every core pose",
        )
        val extended = info(poses = """{"face":"aki_face.webp","asleep":"aki_asleep.webp","winner":"aki_win.webp"}""")
        val parsed = PackInfo.parse(extended, names)
        assertEquals(
            mapOf("face" to "aki_face.webp", "asleep" to "aki_asleep.webp", "winner" to "aki_win.webp"),
            parsed.poses,
            "a known extension is a picture the pack may have",
        )
    }

    private fun index(
        format: Int = 1,
        tag: String = "art-11",
        version: Int = 11,
        core: String = """["face","asleep"]""",
        fallbacks: String = """{"winner":"face","streak_hot":"winner"}""",
        companions: String = "[]",
    ): String = """{"format":$format,"tag":"$tag","version":$version,"core":$core,"fallbacks":$fallbacks,"companions":$companions}"""

    private fun info(
        format: Int = 1,
        id: String = "aki",
        name: String = "Aki",
        notice: String? = null,
        poses: String = """{"face":"aki_face.webp","asleep":"aki_asleep.webp"}""",
    ): String {
        val noticeField = if (notice == null) "null" else "\"$notice\""
        val fields = """{"format":$format,"id":"$id","name":"$name","version":11,"licence":"CC BY-SA","credit":"AnkiQuest","""
        return """$fields"notice":$noticeField,"poses":$poses}"""
    }
}
