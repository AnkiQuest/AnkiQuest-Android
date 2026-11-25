// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.appwidget.AppWidgetManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestStreakWidgetTest : RobolectricTest() {
    private fun me(
        streak: Int,
        state: String,
        reviews: Int,
        minutes: Int = 0,
        needed: Int = 0,
    ): JSONObject =
        JSONObject()
            .put("user", "hill")
            .put("display", "hill")
            .put("streak", streak)
            .put("streak_state", state)
            .put("today_reviews", reviews)
            .put("today_minutes", minutes)
            .put("streak_minutes", needed)
            .put("day_ends_at", TimeManager.time.intTimeMS() + 3_600_000)

    private fun render(
        board: JSONArray?,
        player: String? = "hill",
    ): View =
        AnkiquestStreakWidget
            .views(targetContext, board, player)
            .apply(targetContext, FrameLayout(targetContext))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `shows your own streak as a big number with today's status`() {
        val board = JSONArray().put(me(5, "pending", 0).put("user", "friend")).put(me(42, "studied", 30, 12, 10))
        val widget = render(board)
        assertEquals("42", widget.text(R.id.ankiquest_streak_number))
        assertEquals("day streak", widget.text(R.id.ankiquest_streak_label))
        assertEquals("Studied today", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `counts the minutes still missing for the server's minimum`() {
        val widget = render(JSONArray().put(me(9, "pending", 8, 6, 10)))
        assertEquals("6 of 10 minutes today", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `shows progress to the minimum in each size`() {
        for (size in AnkiquestStreakWidget.Size.entries) {
            for (minutes in listOf(0, 6, 10, 12)) {
                val state = if (minutes >= 10) "studied" else "pending"
                val board = JSONArray().put(me(9, state, 8, minutes, 10))
                val widget =
                    AnkiquestStreakWidget
                        .views(targetContext, board, "hill", size)
                        .apply(targetContext, FrameLayout(targetContext))
                val progress = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
                val expected = minutes.coerceAtMost(10)
                assertEquals(View.VISIBLE, progress.visibility, size.name)
                assertEquals(10, progress.max, size.name)
                assertEquals(expected, progress.progress, size.name)
                val description = widget.contentDescription.toString()
                if (minutes < 10) {
                    assertContains(description, "$minutes of 10 minutes today", message = size.name)
                } else {
                    assertContains(description, "Studied today", message = size.name)
                    assertFalse(description.contains("10 of 10 minutes today"), size.name)
                }
            }
        }
    }

    @Test
    fun `hides the meter for stale or missing data`() {
        val stale = me(9, "pending", 8, 6, 10).put("day_ends_at", TimeManager.time.intTimeMS())
        for (board in listOf(null, JSONArray().put(stale), JSONArray().put(me(9, "pending", 8)))) {
            val progress = render(board).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.GONE, progress.visibility)
        }
    }

    @Test
    fun `studied and unstudied protected days fill the meter below the minimum`() {
        for ((state, reviews) in listOf("studied" to 5, "protected" to 0)) {
            val widget = render(JSONArray().put(me(9, state, reviews, 3, 10)))
            val meter = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.VISIBLE, meter.visibility, state)
            assertEquals(10, meter.progress, state)
            assertFalse(widget.contentDescription.toString().contains("3 of 10 minutes"), state)
        }
    }

    @Test
    fun `a protected day with reviews shows its minutes`() {
        val widget = render(JSONArray().put(me(9, "protected", 1, 3, 10)))
        val meter = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
        assertEquals(View.VISIBLE, meter.visibility)
        assertEquals(3, meter.progress)
        val description = widget.contentDescription.toString()
        assertTrue(description.contains("3 of 10 minutes"), description)
        assertFalse(description.contains("Not studied yet"), description)
    }

    @Test
    fun `a row without today's review count hides the meter`() {
        val row = me(9, "pending", 8, 6, 10).apply { remove("today_reviews") }
        val meter = render(JSONArray().put(row)).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
        assertEquals(View.GONE, meter.visibility)
    }

    @Test
    fun `an unsent earlier day hides the own meter despite a current row`() {
        val now = TimeManager.time.intTimeMS()
        val prefs = targetContext.sharedPrefs()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        val server =
            AnkiquestDay.Server(
                streak = 9,
                streakState = "pending",
                dayEndsAt = now + 3_600_000,
                lastReviewId = now - 7_200_000,
                startsAt = now - 3_600_000,
                reviews = 8,
                timeMs = 360_000,
                streakMinutes = 10,
            )
        AnkiquestDay.remember(server.json(), prefs)
        AnkiquestDay.rememberLocal(
            assertNotNull(HomeAccount.current(prefs)).scope,
            server,
            listOf(AnkiquestDay.Review(now - 3_600_001, 60_000)),
            prefs,
        )
        assertEquals(AnkiquestDay.State.UNKNOWN, assertNotNull(AnkiquestDay.own(now)).label)

        for (size in AnkiquestStreakWidget.Size.entries) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, JSONArray().put(me(9, "pending", 8, 6, 10)), "hill", size)
                    .apply(targetContext, FrameLayout(targetContext))
            val meter = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.GONE, meter.visibility, size.name)
        }
    }

    @Test
    fun `asks to set up the account when there is no board`() {
        val widget = render(null)
        assertEquals("–", widget.text(R.id.ankiquest_streak_number))
        assertEquals("Set up ankiquest in Settings", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `every size shows the streak number`() {
        val board = JSONArray().put(me(42, "studied", 30, 12, 10))
        for (size in AnkiquestStreakWidget.Size.entries) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, board, "hill", size)
                    .apply(targetContext, FrameLayout(targetContext))
            assertEquals("42", widget.text(R.id.ankiquest_streak_number), size.name)
        }
    }

    @Test
    fun `the layout grows with the widget`() {
        assertEquals(AnkiquestStreakWidget.Size.SMALL, AnkiquestStreakWidget.Size.fitting(57f, 57f))
        assertEquals(AnkiquestStreakWidget.Size.MEDIUM, AnkiquestStreakWidget.Size.fitting(130f, 110f))
        assertEquals(AnkiquestStreakWidget.Size.MEDIUM, AnkiquestStreakWidget.Size.fitting(320f, 110f), "a wide strip keeps the card")
        assertEquals(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.fitting(250f, 260f))
        assertEquals(
            AnkiquestStreakWidget.Size.LARGE,
            AnkiquestStreakWidget.Size.fitting(250f, 600f),
            "a narrow column keeps the large card",
        )
        assertEquals(AnkiquestStreakWidget.Size.HUGE, AnkiquestStreakWidget.Size.fitting(320f, 400f))
        assertEquals(AnkiquestStreakWidget.Size.HUGE, AnkiquestStreakWidget.Size.fitting(800f, 800f))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a 64-day streak whose day counts shows the blazing pose`() {
        // The minimum was reached a minute ago: the big layouts showed the calm minimum pose.
        val board = JSONArray().put(me(64, "studied", 20, 11, 10))
        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, board, "hill", size)
                    .apply(targetContext, FrameLayout(targetContext))
            val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
            val shown = assertNotNull((drawable as? BitmapDrawable)?.bitmap, size.name)
            val blazing = assertNotNull(AnkiquestArt.bitmap(targetContext, R.drawable.aki_streak_blazing, minOf(shown.width, shown.height)))
            assertTrue(shown.sameAs(blazing), "$size shows the blazing pose")
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `two frozen days before today show the second frozen pose`() {
        val now = TimeManager.time.intTimeMS()
        val row = me(10, "protected", 0)
        val heatmap = JSONArray()
        for (frozen in listOf(false, true, true, false)) {
            heatmap.put(JSONObject().put("frozen", frozen))
        }
        val profile =
            JSONObject()
                .put("streak", 10)
                .put("streak_state", "protected")
                .put("day_ends_at", row.getLong("day_ends_at"))
                .put("heatmap", heatmap)
        AnkiquestIcon.update(targetContext, profile, now, targetContext.sharedPrefs())

        val widget =
            AnkiquestStreakWidget
                .views(targetContext, JSONArray().put(row), "hill", AnkiquestStreakWidget.Size.HUGE)
                .apply(targetContext, FrameLayout(targetContext))
        val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
        val shown = assertNotNull((drawable as? BitmapDrawable)?.bitmap)
        val frozen = assertNotNull(AnkiquestArt.bitmap(targetContext, R.drawable.aki_freeze, minOf(shown.width, shown.height)))
        assertTrue(shown.sameAs(frozen))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a freeze used yesterday shows Ankilope frozen on every widget`() {
        // Regression: the transparent widget showed her study pose with "Streak protected".
        chooseCompanion(AnkiquestCompanion.Choice.ANKILOPE)
        val board = JSONArray().put(me(10, "protected", 0))
        for (style in AnkiquestStreakWidget.Style.entries) {
            for (size in AnkiquestStreakWidget.Size.entries) {
                val widget =
                    AnkiquestStreakWidget
                        .views(targetContext, board, "hill", size, style = style)
                        .apply(targetContext, FrameLayout(targetContext))
                val name = "$style $size"
                if (size == AnkiquestStreakWidget.Size.LARGE || size == AnkiquestStreakWidget.Size.HUGE) {
                    assertPose(widget, R.drawable.ankilope_frozen, name)
                    continue
                }
                val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
                assertEquals(R.drawable.ankilope_frozen, shadowOf(drawable).createdFromResId, name)
            }
        }
    }

    /** The companion [widget] shows is [pose]. */
    private fun assertPose(
        widget: View,
        pose: Int,
        message: String,
    ) {
        val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
        val shown = assertNotNull((drawable as? BitmapDrawable)?.bitmap, message)
        val expected = assertNotNull(AnkiquestArt.bitmap(targetContext, pose, minOf(shown.width, shown.height)))
        val name = targetContext.resources.getResourceEntryName(pose)
        assertTrue(shown.sameAs(expected), "$message: not $name")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a studied streak is sad once its day ends`() {
        // Yesterday counted and its goal was reached; nothing newer arrived before the day ended.
        val now = TimeManager.time.intTimeMS()
        val ended = now - 3_600_000
        val row = me(12, "studied", 30, 20, 10).put("day_ends_at", ended)
        val profile =
            JSONObject()
                .put("streak", 12)
                .put("streak_state", "studied")
                .put("day_ends_at", ended)
                .put(
                    "goal",
                    JSONObject()
                        .put("mode", "cards")
                        .put("value", 20)
                        .put("progress", 30)
                        .put("reached", true),
                )
        AnkiquestIcon.update(targetContext, profile, now, targetContext.sharedPrefs())

        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, JSONArray().put(row), "hill", size, now)
                    .apply(targetContext, FrameLayout(targetContext))
            assertPose(widget, AnkiquestMood.pose(AnkiquestMood.Mood.SAD, 12), size.name)
            assertEquals("12", widget.text(R.id.ankiquest_streak_number), size.name)
            // The status waits for the server to judge the new day.
            assertEquals(targetContext.getString(R.string.ankiquest_streak_unknown), widget.text(R.id.ankiquest_streak_status), size.name)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a goal reached on an ended day does not set today on fire`() {
        val now = TimeManager.time.intTimeMS()
        val ended = now - 3_600_000
        val row = me(12, "pending", 0, 0, 10).put("day_ends_at", ended)
        val profile =
            JSONObject()
                .put("streak", 12)
                .put("streak_state", "pending")
                .put("day_ends_at", ended)
                .put("goal", JSONObject().put("reached", true))
        AnkiquestIcon.update(targetContext, profile, now, targetContext.sharedPrefs())

        val widget =
            AnkiquestStreakWidget
                .views(targetContext, JSONArray().put(row), "hill", AnkiquestStreakWidget.Size.HUGE, now)
                .apply(targetContext, FrameLayout(targetContext))
        assertPose(widget, AnkiquestMood.pose(AnkiquestMood.Mood.UNKNOWN, 12), "an unknown day")
    }

    @Test
    fun `placed streak widgets are drawn again when the day ends`() {
        targetContext.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
        }
        mockkObject(AnkiquestPoll)
        every { AnkiquestPoll.refreshNow(any()) } just Runs
        try {
            val manager = AppWidgetManager.getInstance(targetContext)
            shadowOf(manager).createWidget(AnkiquestStreakWidget::class.java, R.layout.widget_ankiquest_streak)
            val row = me(12, "studied", 30, 20, 10)
            AnkiquestStreakWidget.render(targetContext, JSONArray().put(row))
            verify { AnkiquestPoll.atDayEnd(targetContext, row.getLong("day_ends_at")) }
        } finally {
            unmockkObject(AnkiquestPoll)
        }
    }

    /** Edge of the companion bitmap each big layout shows at its largest, from its ImageView maxHeight. */
    private fun shownPx(size: AnkiquestStreakWidget.Size): Int {
        val dp = if (size == AnkiquestStreakWidget.Size.LARGE) LARGE_COMPANION_DP else HUGE_COMPANION_DP
        return minOf((dp * targetContext.resources.displayMetrics.density).toInt(), COMPANION_MAX_PX)
    }

    /** The companion's bitmap edge in [size], for a first streak day (one pose in every layout). */
    private fun companionPx(
        size: AnkiquestStreakWidget.Size,
        options: Bundle? = null,
    ): Int {
        val board = JSONArray().put(me(1, "studied", 20, 30, 10))
        val widget =
            AnkiquestStreakWidget
                .views(targetContext, board, "hill", size, options = options)
                .apply(targetContext, FrameLayout(targetContext))
        val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
        val bitmap = assertNotNull((drawable as? BitmapDrawable)?.bitmap, size.name)
        return minOf(bitmap.width, bitmap.height)
    }

    @Test
    @Config(qualifiers = "560dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the big companion is never upscaled on a dense screen`() {
        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val shown = shownPx(size)
            val actual = companionPx(size)
            assertTrue(actual >= shown, "$size shows $shown px from $actual px")
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the big companion is decoded for its shown size`() {
        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val shown = shownPx(size)
            val actual = companionPx(size)
            // Power-of-two sampling covers the shown size with less than twice its edge.
            assertTrue(actual in shown until 2 * shown, "$size shows $shown px from $actual px")
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a narrow widget decodes a smaller companion`() {
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 150)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 400)
            }
        val shown = (150 * targetContext.resources.displayMetrics.density).toInt()
        val actual = companionPx(AnkiquestStreakWidget.Size.HUGE, options)
        assertTrue(actual in shown until 2 * shown, "150 dp shows $shown px from $actual px")
    }

    @Test
    @Config(qualifiers = "560dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `every streak pose covers the huge layout on a dense screen`() {
        val shown = shownPx(AnkiquestStreakWidget.Size.HUGE)
        // Every pose the big layouts rotate through, and Ankilope's for each mood.
        val aki =
            AnkiquestMood.Mood.entries
                .flatMap { mood -> listOf(0, 1, 7, 30, 100).flatMap { AnkiquestMood.poses(mood, it) } }
                .toSet()
        assertEquals(11, aki.size)
        val poses = aki + ankilopeMoodPoses.values
        for (pose in poses) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(targetContext.resources, pose, bounds)
            val name = targetContext.resources.getResourceEntryName(pose)
            assertTrue(minOf(bounds.outWidth, bounds.outHeight) >= shown, "$name is ${bounds.outWidth} px for $shown px")
        }
    }

    private companion object {
        /** The ImageView maxHeight of the large and huge layouts. */
        const val LARGE_COMPANION_DP = 220
        const val HUGE_COMPANION_DP = 520

        /** The streak widget poses are exported at this size (float3/ankiquest#253). */
        const val COMPANION_MAX_PX = 1024
    }
}
