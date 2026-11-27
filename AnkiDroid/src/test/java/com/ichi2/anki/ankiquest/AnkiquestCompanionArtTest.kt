// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which exported copy each companion shows in each slot. The copies' bytes are checked against the
 * pinned AnkiQuest-Art release by `.github/ankiquest_art_check.cjs`; this pins the names.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestCompanionArtTest : RobolectricTest() {
    private fun name(resource: Int) = targetContext.resources.getResourceEntryName(resource)

    @Test
    fun `the picker shows each companion's face, streak and sad pose`() {
        // Regressions: Bluma showed Ankilope recoloured and Ankilope her study picture for streak.
        for (choice in drawn) {
            val prefix = prefixes.getValue(choice)
            val shown = AnkiquestCompanion.preview(choice).map { name(AnkiquestCompanion.resource(it, choice)) }
            assertEquals(listOf("${prefix}face", "${prefix}streak", "${prefix}sad"), shown, "$choice")
        }
    }

    @Test
    fun `the home widget shows each companion's own face`() {
        for (choice in drawn) {
            assertEquals("${prefixes.getValue(choice)}face", name(AnkiquestCompanion.resource(R.drawable.aki_face, choice)), "$choice")
        }
    }

    @Test
    fun `a companion's own pose is shown as it is`() {
        // Regression: the streak widget passes the mood's pose, and Anki-tan's turned into her face.
        for (choice in drawn) {
            for (pose in AnkiquestPose.entries) {
                val art = AnkiquestCompanion.art(pose, choice)
                assertEquals(art, AnkiquestCompanion.resource(art, choice), "$choice $pose")
            }
        }
    }

    @Test
    fun `every companion shows only its own exported copies`() {
        val scenes =
            listOf(
                R.drawable.aki_nudge,
                R.drawable.aki_backlog,
                R.drawable.aki_place_1,
                R.drawable.aki_place_2,
                R.drawable.aki_place_3,
                R.drawable.aki_challenge,
                R.drawable.aki_challenge_won,
                R.drawable.aki_challenge_lost,
            )
        for (choice in drawn) {
            val prefix = prefixes.getValue(choice)
            val poses = AnkiquestPose.entries.map { AnkiquestCompanion.art(it, choice) }
            val pictures = poses + scenes.map { AnkiquestCompanion.resource(it, choice) }
            for (picture in pictures) {
                assertTrue(name(picture).startsWith(prefix), "$choice shows ${name(picture)}")
            }
        }
    }

    @Test
    fun `only the face is a headshot`() {
        // Framing: face is a headshot, every other pose full body, and no icon stands in for a pose.
        for (choice in drawn) {
            val prefix = prefixes.getValue(choice)
            assertEquals("${prefix}face", name(AnkiquestCompanion.art(AnkiquestPose.FACE, choice)), "$choice")
            for (pose in AnkiquestPose.entries - AnkiquestPose.FACE) {
                val shown = name(AnkiquestCompanion.art(pose, choice))
                assertFalse(shown.endsWith("_face") || "_icon" in shown || "chibi" in shown, "$choice $pose shows $shown")
            }
        }
    }

    @Test
    fun `missing core poses and extensions show their poses json fallback`() {
        // Bluma has every core pose and no extension yet.
        assertEquals(R.drawable.bluma_streak, AnkiquestCompanion.art(AnkiquestPose.STREAK_LEGEND, Choice.COW))
        assertEquals(R.drawable.bluma_happy, AnkiquestCompanion.art(AnkiquestPose.WINNER, Choice.COW))
        assertEquals(R.drawable.bluma_frozen, AnkiquestCompanion.art(AnkiquestPose.FROZEN_3, Choice.COW))
        assertEquals(R.drawable.bluma_neglected, AnkiquestCompanion.art(AnkiquestPose.DEAD, Choice.COW))

        // Anki-tan's welcome is her hopeful master; her other extensions fall back.
        assertEquals(R.drawable.anki_tan_hopeful, AnkiquestCompanion.art(AnkiquestPose.WELCOME, Choice.ANKI_TAN))
        assertEquals(R.drawable.anki_tan_happy, AnkiquestCompanion.art(AnkiquestPose.REVIEW, Choice.ANKI_TAN))
        assertEquals(R.drawable.anki_tan_sad, AnkiquestCompanion.art(AnkiquestPose.HURT, Choice.ANKI_TAN))
        assertEquals(R.drawable.anki_tan_neglected, AnkiquestCompanion.art(AnkiquestPose.DEAD, Choice.ANKI_TAN))
        assertEquals(R.drawable.anki_tan_streak, AnkiquestCompanion.art(AnkiquestPose.STREAK_MILESTONE, Choice.ANKI_TAN))
    }

    private companion object {
        /** Every companion with art, as the picker lists them. */
        val drawn = Choice.entries - Choice.NONE

        /** Each companion's drawable prefix, as AnkiQuest-Art's exporter names them; Bluma's id is `cow`. */
        val prefixes =
            mapOf(
                Choice.AKI to "aki_",
                Choice.ANKILOPE to "ankilope_",
                Choice.COW to "bluma_",
                Choice.ANKI_TAN to "anki_tan_",
            )
    }
}
