// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.TimeZone

/**
 * Study time from the local review log: today, this week, this month, total and per study day.
 *
 * AnkiQuest-Server's `tests/fixtures/study_time.json` pins the rule for this and the desktop
 * add-on (`addon/study_time.py`). It reads the collection, so it works offline and counts
 * reviews not uploaded yet.
 */
internal object AnkiquestStudyTime {
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val HOUR_MINUTES = 60L

    // Time per study day, as AnkiQuest counts days (clients.json study_day). Answers only: manual
    // (type 4) and rescheduled (type 5) entries are not study, as in the review heatmap. The upper
    // bound leaves out reviews on a later day than today, which a clock moved back can leave.
    private const val DAYS_SQL =
        "select (id - ?) / $DAY_MS, sum(time) from revlog " +
            "where id >= 0 and id < ? and ease > 0 and type < 4 group by 1"

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

    /** Call inside CollectionManager.withCol, away from the main thread. */
    fun load(
        col: Collection,
        now: Long,
    ): Totals = load(col, now, -TimeZone.getDefault().getOffset(now) / 60_000, AnkiquestDecks.rollover(col))

    fun load(
        col: Collection,
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): Totals {
        val today = AnkiquestCompletionPolicy.studyDay(now, offsetWestMinutes, rolloverHour)
        val shift = offsetWestMinutes * MINUTE_MS + rolloverHour * HOUR_MS
        val days = mutableMapOf<Long, Long>()
        col.db.query(DAYS_SQL, shift, (today + 1) * DAY_MS + shift).use { cursor ->
            while (cursor.moveToNext()) {
                days[cursor.getLong(0)] = cursor.getLong(1)
            }
        }
        return summarize(today, days)
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
