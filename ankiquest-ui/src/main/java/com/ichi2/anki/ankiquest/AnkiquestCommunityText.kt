// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.annotation.StringRes
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Kind
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminder
import com.ichi2.anki.ankiquest.ui.R

// How the app words AnkiquestCommunity from :ankiquest-core: its reminders and calendar views.

/** The switch's title for this reminder. */
@get:StringRes
val Reminder.title: Int
    get() =
        when (this) {
            Reminder.GENTLE_DAILY -> R.string.aq_reminder_gentle_daily
            Reminder.URGENT_STREAK -> R.string.aq_reminder_urgent_streak
            Reminder.FREEZE_USED -> R.string.aq_reminder_freeze_used
            Reminder.FREEZE_REFILL -> R.string.aq_reminder_freeze_refill
            Reminder.MILESTONE -> R.string.aq_reminder_milestone
            Reminder.WEEKLY_CLOSING -> R.string.aq_reminder_weekly_closing
            Reminder.WEEKLY_RECAP -> R.string.aq_reminder_weekly_recap
        }

/** The line under [title]. */
@get:StringRes
val Reminder.summary: Int
    get() =
        when (this) {
            Reminder.GENTLE_DAILY -> R.string.aq_reminder_gentle_daily_summary
            Reminder.URGENT_STREAK -> R.string.aq_reminder_urgent_streak_summary
            Reminder.FREEZE_USED -> R.string.aq_reminder_freeze_used_summary
            Reminder.FREEZE_REFILL -> R.string.aq_reminder_freeze_refill_summary
            Reminder.MILESTONE -> R.string.aq_reminder_milestone_summary
            Reminder.WEEKLY_CLOSING -> R.string.aq_reminder_weekly_closing_summary
            Reminder.WEEKLY_RECAP -> R.string.aq_reminder_weekly_recap_summary
        }

/** The calendar view's tab title. */
@get:StringRes
val Kind.label: Int
    get() =
        when (this) {
            Kind.DAY -> R.string.aq_calendar_days
            Kind.WEEK -> R.string.aq_calendar_weeks
            Kind.MONTH -> R.string.aq_calendar_months
        }
