// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestAki.Card
import com.ichi2.anki.ankiquest.AnkiquestAki.Message
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject

/** Aki's mood on Home today; the collection, not the server, says whether cards are still due. */
internal fun AnkiquestAki.mood(
    local: HomeLocal?,
    profile: JSONObject?,
    now: Long = TimeManager.time.intTimeMS(),
): Card {
    val goal = local?.goal
    val day =
        if (goal == null) {
            AnkiquestDay.judge(AnkiquestDay.Server.profile(profile), emptyList(), now)
        } else {
            goal.withServerDay(profile, now).streakDay
        }

    // The pose follows the one mood rule; the profile's goal and frozen days count only while its day is today.
    val current = profile?.takeIf { day != null }
    val reached = if (current?.optJSONObject("goal")?.optBoolean("reached") == true) Goal.REACHED else Goal.OPEN
    val mood = AnkiquestMood.today(day, AnkiquestMood.frozenRun(current?.optJSONArray("heatmap")), reached)
    return Card(mood, day?.streak ?: 0, message(mood, local, day))
}

private fun message(
    mood: AnkiquestMood.Mood,
    local: HomeLocal?,
    day: AnkiquestDay.Verdict?,
): Message {
    // A streak waiting for today, or kept by a freeze, comes before anything about the decks.
    if (mood == AnkiquestMood.Mood.SAD) {
        return Message.WAITING
    }
    if (mood in frozen) {
        return Message.FREEZE
    }

    // Otherwise the decks decide: all done after real study, or cards still due.
    val reviewed = (day?.reviews ?: 0) > 0
    if (reviewed && local?.decks?.isNotEmpty() == true && local.decks.all { it.due == 0 }) {
        return Message.CELEBRATE
    }
    if ((local?.focus?.due ?: 0) > 0) {
        return Message.REVIEW
    }
    if (mood == AnkiquestMood.Mood.FIRE) {
        return Message.STREAK
    }
    return Message.WELCOME
}

private val frozen = setOf(AnkiquestMood.Mood.FROZEN_1, AnkiquestMood.Mood.FROZEN_2, AnkiquestMood.Mood.FROZEN_3)
