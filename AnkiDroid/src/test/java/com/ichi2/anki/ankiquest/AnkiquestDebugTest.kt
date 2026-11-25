// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import com.ichi2.anki.ankiquest.AnkiquestDebug.Overrides
import com.ichi2.anki.ankiquest.AnkiquestDebug.Today
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.ankiquest.AnkiquestMood.Heat
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import com.ichi2.anki.ankiquest.AnkiquestMood.Rules
import com.ichi2.anki.common.preferences.sharedPrefs
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debug menu's overrides: each transition, how they are stored, and how the mood rule applies them. */
@RunWith(AndroidJUnit4::class)
class AnkiquestDebugTest : RobolectricTest() {
    private val prefs get() = targetContext.sharedPrefs()

    @Test
    fun `forcing a mood or a pose replaces the other`() {
        val mood = Overrides.NONE.forceMood(Mood.FIRE)
        assertEquals(Today.ForcedMood(Mood.FIRE), mood.today)

        val pose = mood.forcePose(AnkiquestPose.DEAD)
        assertEquals(Today.ForcedPose(AnkiquestPose.DEAD), pose.today)
        assertEquals(Today.ForcedMood(Mood.SAD), pose.forceMood(Mood.SAD).today)
    }

    @Test
    fun `computing today again keeps the thresholds`() {
        val rules = Rules(hotDays = 3)
        val edited = assertNotNull(Overrides.NONE.forceMood(Mood.FIRE).withRules(rules))
        val computed = edited.computeToday()
        assertEquals(Today.Computed, computed.today)
        assertEquals(rules, computed.rules)
        assertTrue(computed.active, "edited thresholds are still an override")
    }

    @Test
    fun `shared thresholds keep a forced mood`() {
        val edited = assertNotNull(Overrides.NONE.forceMood(Mood.SAD).withRules(Rules(legendDays = 200)))
        val shared = edited.sharedRules()
        assertEquals(Rules.SHARED, shared.rules)
        assertEquals(Today.ForcedMood(Mood.SAD), shared.today)
    }

    @Test
    fun `only overrides that change something are active`() {
        assertFalse(Overrides.NONE.active)
        val computed = Overrides.NONE.forceMood(Mood.FIRE).computeToday()
        assertFalse(computed.active)
        assertTrue(Overrides.NONE.forcePose(AnkiquestPose.HAPPY).active)
    }

    @Test
    fun `thresholds out of order are refused`() {
        val invalid =
            listOf(
                Rules(hopefulReviews = 0),
                Rules(frozen2Days = 1),
                Rules(frozen2Days = 3, frozen3Days = 3),
                Rules(hotDays = 1),
                Rules(hotDays = 30, blazingDays = 30),
                Rules(blazingDays = 100, legendDays = 100),
            )
        for (rules in invalid) {
            assertNull(Overrides.NONE.withRules(rules), rules.toString())
        }
        assertEquals(Rules(hopefulReviews = 5), Overrides.NONE.withRules(Rules(hopefulReviews = 5))?.rules)
    }

    @Test
    fun `the shared thresholds are the fixture's`() {
        // streak_moods.json: hopeful from the first review, frozen levels at 1, 2 and 3 days, heat at 7, 30 and 100 days.
        assertEquals(Rules(1, 2, 3, 7, 30, 100), Rules.SHARED)
        for (streak in 0..400) {
            assertEquals(Heat.entries.last { streak >= it.days || it == Heat.WARM }, Heat.of(streak), "$streak")
        }
    }

    @Test
    fun `edited thresholds move each mood and heat`() {
        val rules = Rules(hopefulReviews = 3, frozen2Days = 4, frozen3Days = 6, hotDays = 3, blazingDays = 5, legendDays = 9)
        val mood = { state: State, reviews: Int, frozen: Int -> AnkiquestMood.of(state, reviews, 12, frozen, Goal.OPEN, rules) }

        assertEquals(Mood.SAD, mood(State.SHORT, 2, 0), "two reviews are short of the edited hopeful")
        assertEquals(Mood.HOPEFUL, mood(State.SHORT, 3, 0))
        assertEquals(Mood.FROZEN_1, mood(State.PENDING, 0, 3))
        assertEquals(Mood.FROZEN_2, mood(State.PENDING, 0, 4))
        assertEquals(Mood.FROZEN_2, mood(State.PENDING, 0, 5))
        assertEquals(Mood.FROZEN_3, mood(State.PENDING, 0, 6))

        assertEquals(Heat.WARM, rules.heat(2))
        assertEquals(Heat.HOT, rules.heat(3))
        assertEquals(Heat.BLAZING, rules.heat(5))
        assertEquals(Heat.LEGEND, rules.heat(9))
    }

    @Test
    fun `a forced mood decides today's mood and pose`() {
        val forced = Overrides.NONE.forceMood(Mood.FROZEN_3)
        assertEquals(Mood.FROZEN_3, AnkiquestMood.today(null, 0, Goal.OPEN, forced))
        assertEquals(Mood.UNKNOWN, AnkiquestMood.today(null, 0, Goal.OPEN, Overrides.NONE))
        assertEquals(
            AnkiquestCompanion.art(AnkiquestPose.FROZEN_3, Choice.AKI),
            AnkiquestMood.todayPose(Mood.SAD, 1, forced, Choice.AKI),
            "a streak reminder shows the forced mood",
        )
    }

    @Test
    fun `a forced pose replaces only the picture`() {
        val forced = Overrides.NONE.forcePose(AnkiquestPose.DEAD)
        assertEquals(Mood.UNKNOWN, AnkiquestMood.today(null, 0, Goal.OPEN, forced))
        val dead = AnkiquestCompanion.art(AnkiquestPose.DEAD, Choice.AKI)
        assertEquals(dead, AnkiquestMood.todayPose(Mood.FIRE, 400, forced, Choice.AKI))
        assertEquals(listOf(dead), AnkiquestMood.poses(Mood.FIRE, 400, Choice.AKI, forced), "no legend rotation")
    }

    @Test
    fun `edited heat changes today's fire pose`() {
        val edited = assertNotNull(Overrides.NONE.withRules(Rules(hotDays = 3)))
        val hot = AnkiquestCompanion.art(AnkiquestPose.STREAK_HOT, Choice.AKI)
        assertEquals(hot, AnkiquestMood.todayPose(Mood.FIRE, 3, edited, Choice.AKI))
        assertEquals(AnkiquestMood.pose(Mood.FIRE, 3, Choice.AKI), AnkiquestMood.todayPose(Mood.FIRE, 3, Overrides.NONE, Choice.AKI))
    }

    @Test
    fun `overrides are stored and read back`() {
        val overrides = assertNotNull(Overrides.NONE.forcePose(AnkiquestPose.STREAK_LEGEND).withRules(Rules(legendDays = 50)))
        AnkiquestDebug.save(overrides, prefs)
        assertEquals(overrides, AnkiquestDebug.current(prefs))

        AnkiquestDebug.save(overrides.forceMood(Mood.HOPEFUL), prefs)
        assertEquals(Today.ForcedMood(Mood.HOPEFUL), AnkiquestDebug.current(prefs).today)
    }

    @Test
    fun `clearing removes every stored key`() {
        AnkiquestDebug.save(Overrides.NONE.forceMood(Mood.FIRE).withRules(Rules(hotDays = 4))!!, prefs)
        AnkiquestDebug.save(Overrides.NONE, prefs)
        assertEquals(Overrides.NONE, AnkiquestDebug.current(prefs))
        assertTrue(prefs.all.keys.none { it.startsWith("ankiquestDebug") }, prefs.all.keys.toString())
    }

    @Test
    fun `unreadable stored overrides fall back to the computed state`() {
        prefs.edit {
            putString("ankiquestDebugToday", "mood:NO_SUCH_MOOD")
            putString("ankiquestDebugRules", """{"hot_days": 1}""")
        }
        assertEquals(Overrides.NONE, AnkiquestDebug.current(prefs))
    }

    @Test
    fun `without preferences nothing is overridden`() {
        assertEquals(Overrides.NONE, AnkiquestDebug.current(null))
    }

    @Test
    fun `the marker shows only while something is overridden`() {
        assertNull(AnkiquestDebug.marker(targetContext, Overrides.NONE))
        assertEquals("Debug override", AnkiquestDebug.marker(targetContext, Overrides.NONE.forceMood(Mood.FIRE)))
    }
}
