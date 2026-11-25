// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkiquestArtTest {
    @Test
    fun `artwork is sampled down as far as it still covers the shown size`() {
        assertEquals(16, sampleSize(1254, 64))
        assertEquals(2, sampleSize(1254, 520))
        assertEquals(1, sampleSize(100, 200))
        assertEquals(1, sampleSize(1254, 1254))
    }

    @Test
    fun `each moment gets its own art`() {
        assertEquals(AnkiquestPose.STREAK_MILESTONE, AnkiquestArt.forNotice("celebration", "streak"))
        assertEquals(AnkiquestPose.ACHIEVEMENT, AnkiquestArt.forNotice("celebration", "achievement"))
        assertEquals(AnkiquestPose.ACHIEVEMENT, AnkiquestArt.forNotice("celebration", null), "older servers send no topic")
        assertEquals(AnkiquestPose.GOAL, AnkiquestArt.forNotice("celebration", "goal"))
        assertEquals(AnkiquestPose.STREAK, AnkiquestArt.forNotice("celebration", "secured"), "a counted day is on fire")
        assertEquals(AnkiquestPose.DECK_CONQUERED, AnkiquestArt.forNotice("celebration", "conquered"))
        assertEquals(AnkiquestPose.CHALLENGE, AnkiquestArt.forNotice("challenge_invite", null))
        assertEquals(AnkiquestPose.CHALLENGE_WON, AnkiquestArt.forNotice("challenge_complete", null))
        assertEquals(AnkiquestPose.NUDGE, AnkiquestArt.forNotice("nudge", null))
        assertEquals(AnkiquestPose.PLACE_2, AnkiquestArt.forNotice("reminder_weekly_recap", "place:2"))
        assertEquals(AnkiquestPose.FACE, AnkiquestArt.forNotice("reminder_weekly_recap", "place:4"))
        assertEquals(AnkiquestPose.FACE, AnkiquestArt.forNotice("completion", null))
        assertEquals(AnkiquestPose.PLACE_1, AnkiquestArt.forNotice("share", "place:1"), "a friend's shared win keeps its art")
        assertEquals(AnkiquestPose.STREAK_MILESTONE, AnkiquestArt.forNotice("share", "streak"))
        assertEquals(AnkiquestPose.GOAL, AnkiquestArt.forNotice("share", "goal"))
        assertEquals(AnkiquestPose.DECK_CONQUERED, AnkiquestArt.forNotice("share", "conquered"))
    }

    @Test
    fun `streak reminders show today's mood`() {
        val sad = AnkiquestMood.pose(AnkiquestMood.Mood.SAD, 1)
        assertEquals(sad, AnkiquestArt.forNotice("reminder_urgent", null), "a streak with no study today")
        assertEquals(sad, AnkiquestArt.forNotice("reminder_milestone", null), "a streak one study day from a milestone")
        assertEquals(AnkiquestMood.pose(AnkiquestMood.Mood.FROZEN_1, 1), AnkiquestArt.forNotice("reminder_freeze_used", null))
    }

    @Test
    fun `only the top three get a medal`() {
        assertEquals(R.drawable.icon_place_1, AnkiquestArt.medal(1))
        assertEquals(R.drawable.icon_place_3, AnkiquestArt.medal(3))
        assertNull(AnkiquestArt.medal(4))
    }
}
