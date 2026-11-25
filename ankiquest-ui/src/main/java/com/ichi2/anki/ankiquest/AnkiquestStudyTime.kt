// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.ankiquest.ui.R
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Study time: today, this week, this month, total and per study day.
 *
 * AnkiQuest-Server's `tests/fixtures/study_time.json` pins the rule for this and the desktop
 * add-on (`addon/study_time.py`). The app reads the per-day times from its review log
 * ([AnkiquestHost.studyTime]), so it works offline and counts reviews not uploaded yet.
 */
object AnkiquestStudyTime {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MINUTES = 60L

    data class Totals(
        val today: LocalDate,
        val todayMs: Long,
        val weekMs: Long,
        val monthMs: Long,
        val totalMs: Long,
        val studyDays: Int,
    ) {
        val averageMs: Long get() = if (studyDays > 0) totalMs / studyDays else 0L
    }

    /** The totals from study day numbers to milliseconds; days after [today] are not counted. */
    fun summarize(
        today: Long,
        days: Map<Long, Long>,
    ): Totals {
        val shown = LocalDate.ofEpochDay(today)
        val counted = days.filterKeys { it <= today }

        // Weeks start on Monday; the month is today's calendar month.
        val monday = today - (shown.dayOfWeek.value - DayOfWeek.MONDAY.value)
        val month = shown.withDayOfMonth(1)
        return Totals(
            today = shown,
            todayMs = counted[today] ?: 0L,
            weekMs = counted.filterKeys { it >= monday }.values.sum(),
            monthMs = counted.filterKeys { LocalDate.ofEpochDay(it).withDayOfMonth(1) == month }.values.sum(),
            totalMs = counted.values.sum(),
            studyDays = counted.size,
        )
    }

    /** Whole minutes, rounded down; hours and minutes from an hour on. */
    fun duration(
        context: Context,
        ms: Long,
    ): String {
        val minutes = ms / MINUTE_MS
        if (minutes < HOUR_MINUTES) {
            return context.getString(R.string.aq_study_time_minutes, minutes)
        }
        return context.getString(R.string.aq_study_time_hours, minutes / HOUR_MINUTES, minutes % HOUR_MINUTES)
    }
}
