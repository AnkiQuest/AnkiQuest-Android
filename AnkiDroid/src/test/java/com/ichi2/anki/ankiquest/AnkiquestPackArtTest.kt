// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Every picture the app asks for has an AnkiQuest-Art name, so a companion's pack can draw it. */
class AnkiquestPackArtTest {
    @Test
    fun `every pose picture has an art name`() {
        for (pose in AnkiquestPose.entries) {
            val drawable = AnkiquestCompanion.art(pose, Choice.AKI)
            assertNotNull(AnkiquestCompanion.artName(drawable), pose.id)
        }
    }

    @Test
    fun `scenes and notices have art names`() {
        val pictures =
            listOf(
                R.drawable.aki_nudge,
                R.drawable.aki_backlog,
                R.drawable.aki_challenge,
                R.drawable.aki_challenge_won,
                R.drawable.aki_challenge_lost,
                R.drawable.aki_place_1,
                R.drawable.aki_place_2,
                R.drawable.aki_place_3,
                R.drawable.aki_achievement,
                R.drawable.aki_goal,
                R.drawable.aki_deck_conquered,
                R.drawable.aki_level_up,
            )
        for (picture in pictures) {
            assertNotNull(AnkiquestCompanion.artName(picture), picture.toString())
        }
    }

    @Test
    fun `pose pictures carry their pose name`() {
        val poses = PoseNames.builtIn()
        val named =
            listOf("face", "asleep", "happy", "streak", "neglected", "streak_hot", "streak_blazing", "streak_legend", "streak_milestone")
        for (pose in named) {
            val drawable = AnkiquestCompanion.art(AnkiquestPose.entries.first { it.id == pose }, Choice.AKI)
            assertEquals(pose, AnkiquestCompanion.artName(drawable))
            assertEquals(true, poses.knows(pose))
        }
    }
}
