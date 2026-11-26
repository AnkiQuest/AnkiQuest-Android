// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Checks AnkiQuest's own colours against the surfaces the app draws them on, computed from the
 * colour resources in day and night mode (#207): text and its symbols need 4.5:1, the recall
 * chart's bars and lines are graphics and need 3:1.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestContrastTest : RobolectricTest(loadBackend = false) {
    @Test
    @Config(qualifiers = "notnight")
    fun `the day colours stay readable`() {
        assertReadable()
        // A deck row's daily status badge writes in its colour's own ink over the deck row,
        // which is light by day: the later-time amber, the done-today green and the muted ready.
        assertDeckBadges(android.R.color.white)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the night colours stay readable`() {
        assertReadable()
        // At night the deck row is dark and the same inks are the badge colours' light ones,
        // as the palette's night inks are.
        assertDeckBadges(android.R.color.black)
    }

    private fun assertDeckBadges(background: Int) {
        for (foreground in DECK_BADGE_INKS) {
            assert(targetContext, foreground, background, MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `the review banner reads in both its looks`() {
        // The important look is bright amber and needs dark ink on it; the quiet one is near-black
        // and keeps white. Both are top-level, so pinning them needs no AnkiQuest object.
        for ((foreground, background) in listOf(bannerImportantInk to bannerImportant, bannerQuietInk to bannerQuiet)) {
            val opaque = -0x1000000 or background
            val contrast = ColorUtils.calculateContrast(foreground, opaque)
            assertTrue(contrast >= MIN_TEXT_CONTRAST, "$foreground on $background is $contrast:1")
        }
    }

    private fun assertReadable() {
        val context: Context = targetContext
        for ((foreground, background) in TEXT_PAIRS) {
            assert(context, foreground, background, MIN_TEXT_CONTRAST)
        }

        // The RWKV recall chart's axis labels are text on the card surface.
        assert(context, R.color.aq_rwkv_axis, R.color.aq_home_surface, MIN_TEXT_CONTRAST)
        for ((foreground, background) in CHART_PAIRS) {
            assert(context, foreground, background, MIN_GRAPHIC_CONTRAST)
        }
    }

    private fun assert(
        context: Context,
        foreground: Int,
        background: Int,
        minimum: Double,
    ) {
        val contrast = ColorUtils.calculateContrast(context.getColor(foreground), context.getColor(background))
        val name = context.resources.getResourceEntryName(foreground)
        assertTrue(contrast >= minimum, "$name is $contrast:1, below $minimum:1")
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_GRAPHIC_CONTRAST = 3.0

        // What the Home cards draw as text, on the backgrounds it is drawn over.
        private val TEXT_PAIRS =
            listOf(
                R.color.aq_home_text to R.color.aq_home_background,
                R.color.aq_home_text to R.color.aq_home_surface,
                R.color.aq_home_muted to R.color.aq_home_background,
                R.color.aq_home_muted to R.color.aq_home_surface,
                // The colour of the emphasized words of a line: the primary that fills buttons is
                // the accent when it is text instead (AQ-night: aq_home_accent).
                R.color.aq_home_accent to R.color.aq_home_background,
                R.color.aq_home_accent to R.color.aq_home_surface,
                R.color.aq_home_primary_text to R.color.aq_home_primary,
                R.color.aq_goal_chip_text to R.color.aq_goal_chip_background,
            )

        // The RWKV recall chart's bars and lines, drawn on the card surface.
        private val CHART_PAIRS =
            listOf(
                R.color.aq_rwkv_due to R.color.aq_home_surface,
                R.color.aq_rwkv_near to R.color.aq_home_surface,
                R.color.aq_rwkv_safe to R.color.aq_home_surface,
            )

        // The deck rows' daily status texts (AnkiquestDailyDeckState.color), the badge colour's
        // own ink: the later-time amber, the done-today green and the muted ready count.
        private val DECK_BADGE_INKS =
            listOf(
                R.color.aq_deck_amber,
                R.color.aq_deck_green,
                R.color.aq_home_muted,
            )
    }
}
