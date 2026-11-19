// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestGoalPlan.Forecast
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection

// What AnkiquestGoalPlan's estimates read from the collection.

private const val DAY_MS = 86_400_000L

/**
 * Reads the [Forecast] from [col]: `due` and `backlog` as [AnkiquestDailyGoal.load] counts them.
 *
 * `dailyDue` is the collection's due forecast without the backlog: the review and interday
 * learning cards (queues 2 and 3, so not suspended or buried) whose due day, the original one for
 * cards in a filtered deck, falls on one of the next [FORECAST_DAYS] Anki days
 * (`sched.today + 1` to `sched.today + 7`), divided by [FORECAST_DAYS]. Both queues keep their due
 * date as an Anki day number, so this is one indexed count. New cards and daily limits are left
 * out, like Anki's own forecast graph.
 *
 * `secondsPerCard` is [secondsPerCard].
 */
internal fun AnkiquestGoalPlan.forecast(
    col: Collection,
    now: Long = TimeManager.time.intTimeMS(),
): Forecast {
    val load = AnkiquestDailyGoal.load(col)
    val today = col.sched.today
    val upcoming =
        col.db.queryScalar(
            "select count() from cards where queue in (2, 3) and " +
                "(case when odid != 0 then odue else due end) between ? and ?",
            today + 1,
            today + AnkiquestGoalPlan.FORECAST_DAYS,
        )
    return Forecast(
        due = load.dueToday,
        backlog = load.backlog,
        dailyDue = upcoming.toDouble() / AnkiquestGoalPlan.FORECAST_DAYS,
        secondsPerCard = secondsPerCard(col, now),
    )
}

/**
 * The average answer time in the review log over the last [ANSWER_TIME_DAYS] days (`revlog.time`
 * is in milliseconds), leaving out manual reschedules, or [DEFAULT_SECONDS_PER_CARD] without any history.
 */
internal fun AnkiquestGoalPlan.secondsPerCard(
    col: Collection,
    now: Long = TimeManager.time.intTimeMS(),
): Double =
    col.db
        .query(
            "select avg(time) from revlog where id >= ? and time > 0 and type < 4",
            now - AnkiquestGoalPlan.ANSWER_TIME_DAYS * DAY_MS,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getDouble(0) / 1000.0
        }?.takeIf { it > 0 } ?: AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD
