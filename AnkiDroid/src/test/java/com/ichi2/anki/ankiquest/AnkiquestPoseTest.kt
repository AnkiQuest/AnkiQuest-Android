// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Checks [AnkiquestPose] against `tests/fixtures/companion_poses.json` from AnkiQuest-Server, which
 * the desktop add-on also uses.
 */
class AnkiquestPoseTest {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/companion_poses.json")) { "missing companion poses" }.readText(),
        )

    @Test
    fun `the core poses and fallbacks are the shared ones`() {
        val core = fixture.getJSONArray("core")
        val expectedCore = (0 until core.length()).map { core.getString(it) }
        assertEquals(expectedCore, AnkiquestPose.entries.filter { it.chain().count() == 1 }.map { it.id })
        assertEquals(expectedCore, AnkiquestPose.entries.filter { it.group == AnkiquestPose.Group.CORE }.map { it.id })

        // Each extension falls back to the fixture's pose.
        val extensions = fixture.getJSONObject("extensions")
        val expected = extensions.keys().asSequence().associateWith { extensions.getString(it) }
        val actual =
            AnkiquestPose.entries
                .filter { it.group == AnkiquestPose.Group.EXTENSION }
                .mapNotNull { pose ->
                    pose
                        .chain()
                        .drop(1)
                        .firstOrNull()
                        ?.let { pose.id to it.id }
                }.toMap()
        assertEquals(expected, actual)
    }

    @Test
    fun `every moment picture and launcher icon falls back to a core pose`() {
        for (pose in AnkiquestPose.entries.filter { it.group == AnkiquestPose.Group.PICTURE || it.group == AnkiquestPose.Group.ICON }) {
            assertEquals(AnkiquestPose.Group.CORE, pose.chain().last().group, pose.id)
        }
        assertEquals(
            listOf("place_1", "winner", "happy"),
            AnkiquestPose.PLACE_1
                .chain()
                .map { it.id }
                .toList(),
        )
        assertEquals(
            listOf("icon_frozen", "icon", "face"),
            AnkiquestPose.ICON_FROZEN
                .chain()
                .map { it.id }
                .toList(),
        )
    }

    @Test
    fun `packs without an index know every pose by name`() {
        val names = PoseNames.builtIn()
        for (pose in AnkiquestPose.entries) {
            assertEquals(pose.chain().map { it.id }.toList(), names.chain(pose.id), pose.id)
        }
    }
}
