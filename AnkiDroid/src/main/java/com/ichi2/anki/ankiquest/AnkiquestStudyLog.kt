// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.libanki.Collection
import java.util.TimeZone

/** The time per study day in the collection's review log, summed up by [AnkiquestStudyTime]. */
internal object AnkiquestStudyLog {
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L

    // Time per study day, as AnkiQuest counts days (clients.json study_day). Answers only: manual
    // (type 4) and rescheduled (type 5) entries are not study, as in the review heatmap. The upper
    // bound leaves out reviews on a later day than today, which a clock moved back can leave.
    private const val DAYS_SQL =
        "select (id - ?) / $DAY_MS, sum(time) from revlog " +
            "where id >= 0 and id < ? and ease > 0 and type < 4 group by 1"

    /** Call inside CollectionManager.withCol, away from the main thread. */
    fun load(
        col: Collection,
        now: Long,
    ): AnkiquestStudyTime.Totals = load(col, now, -TimeZone.getDefault().getOffset(now) / MINUTE_MS.toInt(), AnkiquestDecks.rollover(col))

    fun load(
        col: Collection,
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): AnkiquestStudyTime.Totals {
        val today = AnkiquestCompletionPolicy.studyDay(now, offsetWestMinutes, rolloverHour)
        val shift = offsetWestMinutes * MINUTE_MS + rolloverHour * HOUR_MS
        val days = mutableMapOf<Long, Long>()
        col.db.query(DAYS_SQL, shift, (today + 1) * DAY_MS + shift).use { cursor ->
            while (cursor.moveToNext()) {
                days[cursor.getLong(0)] = cursor.getLong(1)
            }
        }
        return AnkiquestStudyTime.summarize(today, days)
    }
}
