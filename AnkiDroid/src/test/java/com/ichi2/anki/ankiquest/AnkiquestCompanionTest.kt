// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Color
import androidx.core.content.edit
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestCompanionTest : RobolectricTest() {
    private fun account(user: String): HomeAccount {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences.edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://example.com")
            putString(Ankiquest.PREF_USER, user)
            putString(Ankiquest.PREF_TOKEN, "token-$user")
        }
        return requireNotNull(AnkiquestHomeData.account())
    }

    @Test
    fun `choice follows only the current account and maps all mascot poses`() {
        val alice = account("alice")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
        assertEquals("ankilope", AnkiquestCompanion.selected())
        assertEquals(R.drawable.ankilope_frozen, AnkiquestCompanion.resource(R.drawable.aki_freeze))
        assertEquals(R.drawable.ankilope_frozen, AnkiquestCompanion.resource(R.drawable.aki_freeze_used))
        assertEquals(R.drawable.ankilope_sad, AnkiquestCompanion.resource(R.drawable.aki_at_risk))
        assertEquals(R.drawable.ankilope_sleeping, AnkiquestCompanion.resource(R.drawable.aki_sleeping))
        assertEquals(R.drawable.ankilope_welcome, AnkiquestCompanion.resource(R.drawable.aki_welcome))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.resource(R.drawable.aki_winner))
        assertEquals(R.drawable.ankilope_place_1, AnkiquestCompanion.resource(R.drawable.aki_place_1))
        assertEquals(R.drawable.ankilope_place_2, AnkiquestCompanion.resource(R.drawable.aki_place_2))
        assertEquals(R.drawable.ankilope_place_3, AnkiquestCompanion.resource(R.drawable.aki_place_3))
        assertEquals(R.drawable.ankilope_study, AnkiquestCompanion.resource(R.drawable.aki_streak_milestone))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.resource(R.drawable.aki_deck_conquered))
        assertEquals(R.drawable.ankilope_challenge, AnkiquestCompanion.resource(R.drawable.aki_challenge))
        assertEquals(R.drawable.ankilope_challenge_won, AnkiquestCompanion.resource(R.drawable.aki_challenge_won))
        assertEquals(R.drawable.ankilope_challenge_lost, AnkiquestCompanion.resource(R.drawable.aki_challenge_lost))
        assertEquals(R.drawable.ankilope_nudge, AnkiquestCompanion.resource(R.drawable.aki_nudge))
        assertEquals(R.drawable.ankilope_backlog, AnkiquestCompanion.resource(R.drawable.aki_backlog))

        val bob = account("bob")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertFalse(AnkiquestCompanion.remember(alice, "none"))
        assertTrue(AnkiquestCompanion.remember(bob, "none"))
        assertFalse(AnkiquestCompanion.visible())

        account("alice")
        assertEquals("ankilope", AnkiquestCompanion.selected())
    }

    @Test
    fun `every streak mood shows Ankilope's own pose`() {
        // hill's report: a freeze used yesterday showed Ankilope's study pose although her frozen pose exists.
        for (choice in listOf(Choice.ANKILOPE, Choice.COW)) {
            chooseCompanion(choice)
            for ((mood, expected) in ankilopeMoodPoses) {
                for (streak in listOf(0, 1, 7, 30, 100, 365)) {
                    val name = targetContext.resources.getResourceEntryName(expected)
                    val shown = AnkiquestMood.poses(mood, streak).map { AnkiquestCompanion.resource(it) }
                    assertEquals(listOf(expected), shown.distinct(), "$choice $mood $streak: $name")
                    assertEquals(expected, AnkiquestCompanion.resource(AnkiquestMood.pose(mood, streak)), "$choice $mood $streak")
                }
            }
        }
    }

    @Test
    fun `every streak mood shows Anki-tan's own pose`() {
        chooseCompanion(Choice.ANKI_TAN)
        assertEquals(AnkiquestMood.Mood.entries.toSet() - AnkiquestMood.Mood.FIRE, ankiTanMoodPoses.keys)
        for ((mood, expected) in ankiTanMoodPoses) {
            for (streak in listOf(0, 1, 7, 30, 100, 365)) {
                assertEquals(listOf(expected), AnkiquestMood.poses(mood, streak), "$mood $streak")
            }
        }

        // On fire she burns as her streak pose until her hot and blazing art is drawn, then wears her crown.
        for (streak in listOf(1, 7, 30, 99)) {
            assertEquals(listOf(R.drawable.anki_tan_streak), AnkiquestMood.poses(AnkiquestMood.Mood.FIRE, streak), "$streak")
        }
        val legend = listOf(R.drawable.anki_tan_streak_legend, R.drawable.anki_tan_streak_milestone)
        assertEquals(legend, AnkiquestMood.poses(AnkiquestMood.Mood.FIRE, 100))
    }

    @Test
    fun `Anki-tan shows her pose for Aki's pictures and her face for scenes`() {
        val shown = { aki: Int -> AnkiquestCompanion.resource(aki, Choice.ANKI_TAN) }
        assertEquals(R.drawable.anki_tan_face, shown(R.drawable.aki_face))
        assertEquals(R.drawable.anki_tan_happy, shown(R.drawable.aki_celebrate))
        assertEquals(R.drawable.anki_tan_welcome, shown(R.drawable.aki_welcome))
        assertEquals(R.drawable.anki_tan_review, shown(R.drawable.aki_review))
        assertEquals(R.drawable.anki_tan_streak, shown(R.drawable.aki_streak_blazing))
        assertEquals(R.drawable.anki_tan_streak_milestone, shown(R.drawable.aki_streak_milestone))
        assertEquals(R.drawable.anki_tan_frozen, shown(R.drawable.aki_freeze))
        assertEquals(R.drawable.anki_tan_neglected, shown(R.drawable.aki_neglected))

        // Her missing extensions fall back: a defeated leech and a win to happy.
        assertEquals(R.drawable.anki_tan_happy, shown(R.drawable.aki_leech))
        assertEquals(R.drawable.anki_tan_happy, shown(R.drawable.aki_winner))

        // She has no scene art: never Aki's or Ankilope's.
        for (scene in listOf(R.drawable.aki_nudge, R.drawable.aki_place_1, R.drawable.aki_challenge_lost, R.drawable.aki_backlog)) {
            assertEquals(R.drawable.anki_tan_face, shown(scene))
        }
        val preview = AnkiquestCompanion.preview(Choice.ANKI_TAN).map { shown(it) }
        assertEquals(listOf(R.drawable.anki_tan_face, R.drawable.anki_tan_streak, R.drawable.anki_tan_sad), preview)
    }

    @Test
    fun `every pose falls back to art Anki-tan has`() {
        for (pose in AnkiquestPose.entries) {
            val art = AnkiquestCompanion.art(pose, Choice.ANKI_TAN)
            assertTrue(targetContext.resources.getResourceEntryName(art).startsWith("anki_tan_"), "$pose")
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the cow is Ankilope recoloured on every surface`() {
        val alice = account("alice")
        assertTrue(AnkiquestCompanion.remember(alice, "cow"))
        assertEquals(Choice.COW, AnkiquestCompanion.choice())
        assertTrue(AnkiquestCompanion.visible())
        for (pose in AnkiquestCompanion.preview(Choice.AKI) + R.drawable.aki_challenge + R.drawable.aki_place_1) {
            assertEquals(AnkiquestCompanion.resource(pose, Choice.ANKILOPE), AnkiquestCompanion.resource(pose))
        }

        // The cow's bitmap has the Ankilope pose's shape in other colours.
        val size = 64
        val ankilope = requireNotNull(AnkiquestCompanion.bitmap(targetContext, R.drawable.aki_face, size, Choice.ANKILOPE))
        val cow = requireNotNull(AnkiquestCompanion.bitmap(targetContext, R.drawable.aki_face, size))
        assertEquals(ankilope.width to ankilope.height, cow.width to cow.height)
        assertFalse(ankilope.sameAs(cow))
        assertEquals(cow, AnkiquestCompanion.bitmap(targetContext, R.drawable.aki_face, size), "the recoloured bitmap is cached")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a locked companion is drawn as a grey silhouette of its art`() {
        val size = 64
        val art = requireNotNull(AnkiquestCompanion.bitmap(targetContext, R.drawable.aki_face, size, Choice.ANKILOPE))
        val shadow = requireNotNull(AnkiquestCompanion.silhouette(targetContext, R.drawable.aki_face, size, Choice.ANKILOPE))
        assertEquals(art.width to art.height, shadow.width to shadow.height)

        // Where the art is mostly opaque, the silhouette is the desktop's grey at the art's opacity scaled
        // to the silhouette's (allowing for rounding). The art has soft edges, so few pixels are fully opaque.
        val solid =
            (0 until art.width)
                .flatMap { x -> (0 until art.height).map { y -> x to y } }
                .filter { (x, y) -> Color.alpha(art[x, y]) >= MOSTLY_OPAQUE }
        assertTrue(solid.isNotEmpty())
        for ((x, y) in solid) {
            val pixel = shadow[x, y]
            for (channel in listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                assertTrue(channel in SILHOUETTE_GREY - ROUNDING..SILHOUETTE_GREY + ROUNDING, "grey at $x,$y: $channel")
            }
            val alpha = Color.alpha(art[x, y]) * SILHOUETTE_ALPHA / OPAQUE
            assertTrue(Color.alpha(pixel) in alpha - ROUNDING..alpha + ROUNDING, "opacity at $x,$y")
        }
    }

    @Test
    fun `previews show face, fire and sad without repeating a picture`() {
        assertEquals(listOf(R.drawable.aki_face, R.drawable.aki_streak, R.drawable.aki_at_risk), AnkiquestCompanion.preview(Choice.AKI))
        for (choice in listOf(Choice.ANKILOPE, Choice.COW)) {
            val pictures = AnkiquestCompanion.preview(choice).map { AnkiquestCompanion.resource(it, choice) }
            assertEquals(listOf(R.drawable.ankilope_face, R.drawable.ankilope_study, R.drawable.ankilope_sad), pictures)
        }
    }

    @Test
    fun `a server value the app does not know falls back to Aki`() {
        assertEquals("aki", AnkiquestCompanion.normalize("unicorn"))
        assertEquals("cow", AnkiquestCompanion.normalize("cow"))
        assertEquals("anki_tan", AnkiquestCompanion.normalize("anki_tan"))
    }

    private companion object {
        const val OPAQUE = 255
        const val MOSTLY_OPAQUE = 200

        /** The desktop's silhouette: grey 128 at an opacity of 110. */
        const val SILHOUETTE_GREY = 128
        const val SILHOUETTE_ALPHA = 110
        const val ROUNDING = 2
    }
}
