// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
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

        // Each extension falls back to the fixture's pose.
        val extensions = fixture.getJSONObject("extensions")
        val expected = extensions.keys().asSequence().associateWith { extensions.getString(it) }
        val actual =
            AnkiquestPose.entries
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
    fun `every companion has art for every core pose`() {
        val core = AnkiquestPose.entries.filter { it.chain().count() == 1 }
        for (choice in Choice.entries) {
            for (pose in core) {
                AnkiquestCompanion.art(pose, choice)
            }
        }
        val ankilope = core.map { AnkiquestCompanion.art(it, Choice.ANKILOPE) }
        assertEquals(
            listOf(
                R.drawable.ankilope_face,
                R.drawable.ankilope_sleeping,
                R.drawable.ankilope_celebrate,
                // Interim until her on-fire art exists.
                R.drawable.ankilope_study,
                R.drawable.ankilope_sad,
                R.drawable.ankilope_hopeful,
                R.drawable.ankilope_frozen,
                R.drawable.ankilope_neglected,
            ),
            ankilope,
        )
    }

    @Test
    fun `an extension without art shows its fallback`() {
        assertEquals(R.drawable.ankilope_frozen, AnkiquestCompanion.art(AnkiquestPose.FROZEN_3, Choice.ANKILOPE))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.art(AnkiquestPose.LEECH_DEFEATED, Choice.ANKILOPE))
        assertEquals(R.drawable.ankilope_study, AnkiquestCompanion.art(AnkiquestPose.STREAK_MILESTONE, Choice.ANKILOPE))
        assertEquals(R.drawable.aki_freeze, AnkiquestCompanion.art(AnkiquestPose.FROZEN_2, Choice.AKI))

        // Anki-tan's seven extensions still to draw (AnkiQuest-Server#57), until then their fallbacks.
        val ankiTan =
            mapOf(
                AnkiquestPose.STREAK_HOT to R.drawable.anki_tan_streak,
                AnkiquestPose.STREAK_BLAZING to R.drawable.anki_tan_streak,
                AnkiquestPose.FROZEN_2 to R.drawable.anki_tan_frozen,
                AnkiquestPose.FROZEN_3 to R.drawable.anki_tan_frozen,
                AnkiquestPose.ABANDONED to R.drawable.anki_tan_neglected,
                AnkiquestPose.LEECH_DEFEATED to R.drawable.anki_tan_happy,
                AnkiquestPose.WINNER to R.drawable.anki_tan_happy,
            )
        for ((pose, expected) in ankiTan) {
            assertEquals(expected, AnkiquestCompanion.art(pose, Choice.ANKI_TAN), "$pose")
        }
    }
}
