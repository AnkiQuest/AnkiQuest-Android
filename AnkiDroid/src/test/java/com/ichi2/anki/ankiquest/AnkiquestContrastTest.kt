// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.android.R as CommonR
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
        // A deck row's daily status badge writes in its colour's own ink over the deck row.
        assertDeckBadges()
    }

    @Test
    @Config(qualifiers = "night")
    fun `the night colours stay readable`() {
        assertReadable()
        // At night the deck row is dark and the same inks are the badge colours' light ones.
        assertDeckBadges()
    }

    /** The deck rows draw on the theme's own background: the inks must read on the real row. */
    private fun assertDeckBadges() {
        val row = resolve(android.R.attr.colorBackground)
        for (foreground in DECK_BADGE_INKS) {
            val name = targetContext.resources.getResourceEntryName(foreground)
            assertContrast(name, targetContext.getColor(foreground), row, MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `the review banner reads in both its looks`() {
        // The banners are translucent, so each writes over the reviewer it tints: the ink is
        // measured on the banner composited over the theme's background in this test's mode.
        val reviewer = resolve(android.R.attr.colorBackground)
        for ((ink, banner) in BANNER_PAIRS) {
            val name = targetContext.resources.getResourceEntryName(ink)
            assertContrast(
                name,
                targetContext.getColor(ink),
                ColorUtils.compositeColors(targetContext.getColor(banner), reviewer),
                MIN_TEXT_CONTRAST,
            )
        }
    }

    /** `attr`'s colour in this test's day or night theme. */
    private fun resolve(attr: Int): Int {
        // The deck rows draw in the app's own theme, whose night variant is the dark row,
        // not in the runner's default one.
        targetContext.setTheme(CommonR.style.Theme_AnkiDroid)
        val typed = targetContext.theme.obtainStyledAttributes(intArrayOf(attr))
        return typed.getColor(0, 0).also { typed.recycle() }
    }

    private fun assertReadable() {
        val context: Context = targetContext
        for ((foreground, background) in TEXT_PAIRS) {
            assertContrast(
                context.resources.getResourceEntryName(foreground),
                context.getColor(foreground),
                context.getColor(background),
                MIN_TEXT_CONTRAST,
            )
        }

        // The RWKV recall chart's axis labels are text on the card surface.
        assertContrast(
            "aq_rwkv_axis",
            context.getColor(R.color.aq_rwkv_axis),
            context.getColor(R.color.aq_home_surface),
            MIN_TEXT_CONTRAST,
        )
        for ((foreground, background) in CHART_PAIRS) {
            assertContrast(
                context.resources.getResourceEntryName(foreground),
                context.getColor(foreground),
                context.getColor(background),
                MIN_GRAPHIC_CONTRAST,
            )
        }
    }

    private fun assertContrast(
        name: String,
        foreground: Int,
        background: Int,
        minimum: Double,
    ) {
        val contrast = ColorUtils.calculateContrast(foreground, background)
        assertTrue(contrast >= minimum, "$name is $contrast:1 on $background, below $minimum:1")
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
                // the accent when it is text instead.
                R.color.aq_home_accent to R.color.aq_home_background,
                R.color.aq_home_accent to R.color.aq_home_surface,
                R.color.aq_home_primary_text to R.color.aq_home_primary,
                R.color.aq_goal_chip_text to R.color.aq_goal_chip_background,
            )

        // The review banner's ink and look pairs; the looks are translucent, so the banner's
        // own test measures them composited over the reviewer behind them.
        private val BANNER_PAIRS =
            listOf(
                R.color.aq_banner_important_ink to R.color.aq_banner_important,
                R.color.aq_banner_quiet_ink to R.color.aq_banner_quiet,
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
