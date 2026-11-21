// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Status
import com.ichi2.anki.ankiquest.ui.R

// How the app words AnkiquestDailyGoal from :ankiquest-core.

/** "34 of 50 cards" or "12 of 20 minutes", toward the daily goal. */
fun AnkiquestDailyGoal.progress(
    context: Context,
    status: Status,
): String =
    context.getString(
        if (status.mode == Mode.MINUTES) R.string.aq_goal_progress_minutes else R.string.aq_goal_progress_cards,
        status.progress.coerceAtMost(status.goal.target),
        status.goal.target,
    )

/** A short description of [choice], such as "Clear the backlog in 30 days". */
fun AnkiquestDailyGoal.describe(
    context: Context,
    choice: Choice,
): String =
    when (choice.mode) {
        Mode.BACKLOG -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_backlog, choice.value, choice.value)
        Mode.CARDS -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_cards, choice.value, choice.value)
        Mode.MINUTES -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_minutes, choice.value, choice.value)
        Mode.OFF -> context.getString(R.string.ankiquest_goal_off)
    }
