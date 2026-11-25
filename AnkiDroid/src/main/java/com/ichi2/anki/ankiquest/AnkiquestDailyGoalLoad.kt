// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Load
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Status
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import org.json.JSONObject

// What AnkiquestDailyGoal reads from the collection: today's study and the cards still due.

internal fun AnkiquestDailyGoal.load(
    col: Collection,
    prefs: SharedPreferences = AnkiquestStore.preferences(),
): Load {
    val today = col.sched.today
    val cutoff = col.sched.dayCutoff
    val server = AnkiquestDay.last(prefs)
    val (studied, minutes) =
        col.db
            .query(
                "select count(distinct cid), coalesce(sum(time), 0) from revlog " +
                    "where id >= ? and ${RevlogSource.UPLOADED_REVIEWS}",
                (cutoff - 86_400) * 1000,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0) to (cursor.getLong(1) / 60_000).toInt()
            }
    val (dueReviews, backlog) =
        col.db
            .query(
                "select coalesce(sum(day = ?), 0), coalesce(sum(day < ?), 0) from " +
                    "(select case when odid != 0 then odue else due end as day from cards where queue = 2)",
                today,
                today,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0) to cursor.getInt(1)
            }
    val learning =
        col.db.queryScalar(
            "select count() from cards where (queue in (1, 4) and due < ?) or (queue = 3 and due <= ?)",
            cutoff,
            today,
        )
    val newCards =
        col.sched
            .deckDueTree()
            .children
            .sumOf { it.newCount }
    return Load(
        today,
        studied,
        minutes,
        dueReviews + learning + newCards,
        backlog,
        AnkiquestDecks.studyDay(col, TimeManager.time.intTimeMS()),
        server,
        AnkiquestDay.unsent(RevlogSource(col), server),
    )
}

/** Today's targets and progress. The first call each day fixes the goal. */
suspend fun AnkiquestDailyGoal.status(prefs: SharedPreferences = AnkiquestStore.preferences()): Status {
    val load = CollectionManager.withCol { load(this) }
    return status(load, prefs)
}

/** [AnkiquestDailyGoal.afterUpload] with today's study read from the collection. */
internal suspend fun AnkiquestDailyGoal.afterUpload(
    account: HomeAccount,
    profile: JSONObject,
    prefs: SharedPreferences = AnkiquestStore.preferences(),
) = afterUpload(account, profile, CollectionStudy, prefs)

/** Today's study as the collection has it. */
private object CollectionStudy : AnkiquestDailyGoal.Study {
    override suspend fun day(): Long = CollectionManager.withCol { AnkiquestDecks.studyDay(this, TimeManager.time.intTimeMS()) }

    override suspend fun status(prefs: SharedPreferences): Pair<Status, Long> =
        CollectionManager.withCol {
            AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), prefs) to
                AnkiquestDecks.studyDay(this, TimeManager.time.intTimeMS())
        }
}
