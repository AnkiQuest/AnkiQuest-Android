// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.ankiquest.AnkiquestMood.Heat
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapView
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.ichi2.anki.common.android.R as CommonR

/**
 * Checks [AnkiquestMood] against `tests/fixtures/streak_moods.json` from the ankiquest server, which
 * the desktop add-on also tests against. The ankiquest_fixtures workflow keeps this copy identical.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestMoodTest : RobolectricTest() {
    private val fixtures =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/streak_moods.json")) { "missing streak moods" }.readText(),
        )

    /** The fixture's cases under [key]; each rule the fixture defines has its own key. */
    private fun cases(key: String): List<JSONObject> = fixtures.getJSONArray(key).objects().also { check(it.isNotEmpty()) }

    /** A fixture mood or state: the lowercase name of the enum entry. */
    private inline fun <reified T : Enum<T>> named(name: String): T = enumValueOf(name.uppercase())

    @Test
    fun `every shared mood and pose matches`() {
        for (case in cases("moods")) {
            val name = case.getString("name")
            val today = case.getJSONObject("today")
            val expected = case.getJSONObject("expected")
            val streak = today.getInt("streak")
            val goal = if (today.getBoolean("goal_reached")) Goal.REACHED else Goal.OPEN

            // The mood from today's verdict, then the pose for that mood by drawable name.
            val state = named<State>(today.getString("state"))
            val mood = AnkiquestMood.of(state, today.getInt("reviews"), streak, today.getInt("frozen_days"), goal)
            assertEquals(named<Mood>(expected.getString("mood")), mood, name)
            assertEquals(expected.getString("pose"), targetContext.resources.getResourceEntryName(AnkiquestMood.pose(mood, streak)), name)
        }
    }

    @Test
    fun `the shared moods cover every mood`() {
        val covered = cases("moods").map { named<Mood>(it.getJSONObject("expected").getString("mood")) }.toSet()
        assertEquals(Mood.entries.toSet(), covered)
    }

    @Test
    fun `every shared frozen run matches`() {
        for (case in cases("frozen_run")) {
            assertEquals(case.getInt("expected"), AnkiquestMood.frozenRun(case.optJSONArray("heatmap")), case.getString("name"))
        }
    }

    @Test
    fun `the big widget rotates only within today's mood`() {
        for (mood in Mood.entries) {
            for (streak in listOf(0, 1, 7, 30, 100, 365)) {
                val poses = AnkiquestMood.poses(mood, streak)
                assertEquals(AnkiquestMood.pose(mood, streak), poses.first(), "$mood $streak")
                if (mood != Mood.FIRE || streak < 100) {
                    assertEquals(1, poses.size, "$mood $streak")
                }
            }
        }
        assertEquals(listOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone), AnkiquestMood.poses(Mood.FIRE, 365))
    }

    @Test
    fun `the big pose stays all day and changes between days`() {
        val day = 86_400_000L
        val today = 20_000 * day + 5 * 3_600_000
        val pose = { endsAt: Long -> AnkiquestMood.bigPose(Mood.FIRE, 120, endsAt) }
        assertEquals(
            setOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone),
            setOf(pose(today), pose(today + day)),
            "two days in a row show both poses",
        )
    }

    @Test
    fun `the flame is lit only once today counts`() {
        val flames = Mood.entries.associateWith(AnkiquestMood::flame)
        assertEquals(AnkiquestMood.Flame.LIT, flames[Mood.FIRE])
        assertEquals(AnkiquestMood.Flame.UNLIT, flames[Mood.SAD])
        assertEquals(AnkiquestMood.Flame.UNLIT, flames[Mood.HOPEFUL])
        for (frozen in listOf(Mood.FROZEN_1, Mood.FROZEN_2, Mood.FROZEN_3)) {
            assertEquals(AnkiquestMood.Flame.ICY, flames[frozen], frozen.name)
        }
        assertEquals(AnkiquestMood.Flame.NONE, flames[Mood.ASLEEP])
        assertEquals(AnkiquestMood.Flame.NONE, flames[Mood.UNKNOWN])
    }

    @Test
    fun `the line under the number nudges until today counts`() {
        assertEquals("36 days to 100", AnkiquestMood.line(targetContext, AnkiquestMood.Flame.LIT, 64))
        val nudge = "Study today to keep it"
        assertEquals(nudge, AnkiquestMood.line(targetContext, AnkiquestMood.Flame.UNLIT, 64))
        assertEquals(nudge, AnkiquestMood.line(targetContext, AnkiquestMood.Flame.ICY, 64))
        assertEquals(nudge, AnkiquestMood.line(targetContext, AnkiquestMood.Flame.UNLIT, 1))
        assertNull(AnkiquestMood.line(targetContext, AnkiquestMood.Flame.NONE, 64))
        assertNull(AnkiquestMood.line(targetContext, AnkiquestMood.Flame.UNLIT, 0))
    }

    @Test
    fun `the next milestone counts down`() {
        assertEquals("36 days to 100 🔥", AnkiquestMood.nextLine(targetContext, 64))
        assertEquals("1 day to 7 🔥", AnkiquestMood.nextLine(targetContext, 6))
        assertNull(AnkiquestMood.nextLine(targetContext, 0))
        assertNull(AnkiquestMood.nextLine(targetContext, 365))
    }

    @Test
    fun `streak colours keep text readable`() {
        val ink = targetContext.getColor(R.color.aq_heat_ink)
        val gold = targetContext.getColor(R.color.aq_heat_number)
        for (heat in Heat.entries) {
            for (background in listOf(heat.top, heat.bottom).map(targetContext::getColor)) {
                assertTrue(ColorUtils.calculateContrast(ink, background) >= AA_TEXT, "$heat text")
                assertTrue(ColorUtils.calculateContrast(gold, background) >= AA_LARGE, "$heat number")
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "notnight")
    fun `the widget flames stand out on the light card`() = assertFlames()

    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun `the widget flames stand out on the dark card`() = assertFlames()

    private fun assertFlames() {
        val card = targetContext.getColor(R.color.ankiquest_widget_bg) or OPAQUE
        val flames =
            listOf(
                R.color.ankiquest_streak_flame_1,
                R.color.ankiquest_streak_flame_7,
                R.color.ankiquest_streak_flame_30,
                R.color.ankiquest_streak_flame_100,
                R.color.ankiquest_streak_flame_365,
                R.color.ankiquest_streak_warm,
            )
        for (flame in flames) {
            val contrast = ColorUtils.calculateContrast(targetContext.getColor(flame), card)
            assertTrue(contrast >= AA_LARGE, "${targetContext.resources.getResourceEntryName(flame)} is $contrast:1")
        }
    }

    @Test
    fun `the heatmap companion shows today's mood`() {
        val now = TimeManager.time.intTimeMS()
        targetContext.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        val today = JSONObject().put("starts_at", now - HOUR).put("reviews", 0).put("streak_minutes", 10)
        AnkiquestDay.remember(
            JSONObject()
                .put("streak", 12)
                .put("streak_state", "pending")
                .put("day_ends_at", now + HOUR)
                .put("today", today),
        )

        val view = ReviewHeatmapView(ContextThemeWrapper(targetContext, CommonR.style.Theme_AnkiDroid))
        val aki = view.findViewById<ImageView>(R.id.review_heatmap_aki)
        assertTrue(aki.isVisible)
        assertEquals(AnkiquestMood.pose(Mood.SAD, 12), shadowOf(aki.drawable).createdFromResId)
    }

    private companion object {
        /** WCAG 2 AA contrast for normal text, and for large text and graphics. */
        const val AA_TEXT = 4.5
        const val AA_LARGE = 3.0
        const val OPAQUE = 0xFF000000.toInt()
        const val HOUR = 3_600_000L
    }
}
