// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmap
import com.ichi2.anki.libanki.Collection
import java.time.LocalDate

/**
 * [StudySource] for the fork: reads [col]'s review log and cards directly. Call it inside
 * `CollectionManager.withCol`, away from the main thread.
 */
internal class RevlogSource(
    private val col: Collection,
) : StudySource {
    override fun reviewsAfter(after: Long): List<AnkiquestDay.Review> {
        val rows = mutableListOf<AnkiquestDay.Review>()
        col.db
            .query(
                "select id, time from revlog where id > ? and $UPLOADED_REVIEWS order by id",
                after,
            ).use { cursor ->
                while (cursor.moveToNext()) rows += AnkiquestDay.Review(cursor.getLong(0), cursor.getLong(1))
            }
        return rows
    }

    override fun history(): StudyHistory {
        val timing = col.backend.schedTimingToday()
        val today = ReviewHeatmap.studyDate(timing.nextDayAt)
        val cutoffMillis = timing.nextDayAt * 1000L
        val reviews = mutableMapOf<LocalDate, Int>()
        // Anki's day searches use consecutive 24-hour windows ending at its current
        // scheduler cutoff. Revlog has no historical timezone, so use the same windows
        // as prop:rated, including the exact millisecond at the start of a study day.
        // Do not join cards: retained reviews of deleted cards still represent study.
        col.db
            .query(
                "select (? - 1 - id) / $DAY_MS, count(*) from revlog " +
                    "where id >= 0 and id < ? and $UPLOADED_REVIEWS group by 1",
                cutoffMillis,
                cutoffMillis,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    reviews[today.minusDays(cursor.getLong(0))] = cursor.getInt(1)
                }
            }
        val scheduled = mutableMapOf<LocalDate, Int>()
        // Match the native browser's prop:due expression, including original due dates
        // in filtered decks and timestamp-based learning/preview queues. New, suspended,
        // and buried cards are not currently scheduled answers in this forecast.
        col.db
            .query(
                "select day_offset, count(*) from (" +
                    "select case when queue in (2, 3) then " +
                    "(case when odue != 0 then odue else due end) - ? " +
                    "else ((case when odue != 0 then odue else due end) - ?) / 86400 end as day_offset " +
                    "from cards where queue in (1, 2, 3, 4) and type != 0" +
                    ") where day_offset between 1 and ? group by day_offset",
                timing.daysElapsed,
                timing.nextDayAt,
                ReviewHeatmap.FORECAST_DAYS,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    scheduled[today.plusDays(cursor.getLong(0))] = cursor.getInt(1)
                }
            }
        return StudyHistory(today, reviews, scheduled)
    }

    override fun today(): LocalDate = ReviewHeatmap.studyDate(col.backend.schedTimingToday().nextDayAt)

    companion object {
        /** Revlog rows the upload sends: answers, not manual rescheduling. */
        const val UPLOADED_REVIEWS = "ease > 0 and type < 4"
    }
}
