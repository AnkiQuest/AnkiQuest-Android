// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Style
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The flame beside the streak number is lit only once today counts, as Aki's mood says. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiquestStreakFlameTest : RobolectricTest() {
    private val hour = 3_600_000L

    /** Signs in as hill with the companion set to "none", as in the owner's report. */
    private fun noCompanion() {
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "token-hill")
        }
        val account = assertNotNull(AnkiquestHomeData.account())
        assertTrue(AnkiquestCompanion.remember(account, "none"))
        assertFalse(AnkiquestCompanion.visible())
    }

    private fun row(
        state: String,
        reviews: Int,
        minutes: Int,
        endsAt: Long = TimeManager.time.intTimeMS() + hour,
    ): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("user", "hill")
                .put("display", "hill")
                .put("streak", 64)
                .put("streak_state", state)
                .put("today_reviews", reviews)
                .put("today_minutes", minutes)
                .put("streak_minutes", 10)
                .put("day_ends_at", endsAt),
        )

    /** The pixels [drawable] draws at its own size, to tell flames apart by colour. */
    private fun pixels(drawable: Drawable): Bitmap {
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Every style of the widget for [board] shows [flame] beside 64 in the small sizes. The big
     * sizes show [line] under it instead, so they show one flame.
     */
    private fun assertFlame(
        board: JSONArray,
        @DrawableRes flame: Int,
        line: String,
        now: Long = TimeManager.time.intTimeMS(),
    ) {
        val expected = pixels(assertNotNull(AppCompatResources.getDrawable(targetContext, flame)))
        val name = targetContext.resources.getResourceEntryName(flame)
        for (style in Style.entries) {
            for (size in Size.entries) {
                val view =
                    AnkiquestStreakWidget
                        .views(targetContext, board, "hill", size, now, style)
                        .apply(targetContext, FrameLayout(targetContext))
                val message = "$style $size"
                val number = view.findViewById<TextView>(R.id.ankiquest_streak_number)
                assertEquals("64", number.text.toString(), message)
                if (size == Size.SMALL || size == Size.MEDIUM) {
                    val shown = assertNotNull(number.compoundDrawablesRelative[2], message)
                    assertTrue(pixels(shown).sameAs(expected), "$message: not $name")
                    continue
                }

                assertNull(number.compoundDrawablesRelative[2], message)
                val milestone = view.findViewById<TextView>(R.id.ankiquest_streak_milestone)
                assertEquals(View.VISIBLE, milestone.visibility, message)
                assertEquals(line, milestone.text.toString(), message)
            }
        }
    }

    @Test
    fun `a pending day without study shows the unlit flame and a nudge`() {
        noCompanion()
        assertFlame(row("pending", 0, 0), R.drawable.ankiquest_streak_unlit_30, "Study today to keep it")
    }

    @Test
    fun `study below the minimum keeps the flame unlit`() {
        noCompanion()
        assertFlame(row("pending", 5, 3), R.drawable.ankiquest_streak_unlit_30, "Study today to keep it")
    }

    @Test
    fun `a counted day lights the tier flame`() {
        noCompanion()
        assertFlame(row("studied", 20, 12), R.drawable.ankiquest_streak_tier_30, "36 days to 100 🔥")
    }

    @Test
    fun `a day after a freeze shows the icy flame`() {
        noCompanion()
        assertFlame(row("protected", 0, 0), R.drawable.ankiquest_streak_icy_30, "Study today to keep it")
    }

    @Test
    fun `the flame goes out when a counted day ends`() {
        // The redraw at the day's end runs before the server answers for the new day.
        noCompanion()
        val now = TimeManager.time.intTimeMS()
        assertFlame(row("studied", 20, 12, now - hour), R.drawable.ankiquest_streak_unlit_30, "Study today to keep it", now)
    }

    @Test
    fun `the unlit and icy flames keep each tier's size`() {
        val tiers =
            listOf(
                Triple(R.drawable.ankiquest_streak_tier_1, R.drawable.ankiquest_streak_unlit_1, R.drawable.ankiquest_streak_icy_1),
                Triple(R.drawable.ankiquest_streak_tier_7, R.drawable.ankiquest_streak_unlit_7, R.drawable.ankiquest_streak_icy_7),
                Triple(R.drawable.ankiquest_streak_tier_30, R.drawable.ankiquest_streak_unlit_30, R.drawable.ankiquest_streak_icy_30),
                Triple(R.drawable.ankiquest_streak_tier_100, R.drawable.ankiquest_streak_unlit_100, R.drawable.ankiquest_streak_icy_100),
                Triple(R.drawable.ankiquest_streak_tier_365, R.drawable.ankiquest_streak_unlit_365, R.drawable.ankiquest_streak_icy_365),
            )
        for ((lit, unlit, icy) in tiers) {
            val size = assertNotNull(AppCompatResources.getDrawable(targetContext, lit)).intrinsicHeight
            for (other in listOf(unlit, icy)) {
                val drawable = assertNotNull(AppCompatResources.getDrawable(targetContext, other))
                assertEquals(size, drawable.intrinsicHeight, targetContext.resources.getResourceEntryName(other))
                assertEquals(size, drawable.intrinsicWidth, targetContext.resources.getResourceEntryName(other))
            }
        }
    }
}
