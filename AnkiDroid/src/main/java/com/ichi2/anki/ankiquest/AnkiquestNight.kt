// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankiquest.AnkiquestMood.Night
import com.ichi2.anki.ankiquest.AnkiquestMood.Yesterday
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * The night rule's inputs from the collection ([AnkiquestMood.Night]): the study day's rollover hour
 * and the local time of the first review on each recent studied day. The upload reads them from the
 * collection and keeps them, so the streak widget and the launcher icon, which cannot wait for the
 * collection, use the same rule as Home.
 */
internal object AnkiquestNight {
    private const val PREF_ROLLOVER = "ankiquestNightRollover"
    private const val PREF_FIRST_REVIEWS = "ankiquestNightFirstReviews"
    private const val SEPARATOR = ","
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L

    /** How far back the first reviews are read; more than the rule's 14 studied days. */
    private const val WINDOW_MS = 60 * DAY_MS

    /** The first review of each study day, the day counted from the rollover in local time. */
    private const val FIRST_REVIEWS_SQL =
        "select min(id) from revlog where id > ? and ease > 0 and type < 4 group by (id - ?) / $DAY_MS order by 1"

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /** The night rule's inputs for now, with how [yesterday] ended; null before the first upload kept them. */
    fun inputs(
        yesterday: Yesterday,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Night? {
        if (!prefs.contains(PREF_ROLLOVER)) {
            return null
        }
        val first =
            prefs
                .getString(PREF_FIRST_REVIEWS, null)
                .orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotEmpty() }
        return Night(localTime(now), prefs.getInt(PREF_ROLLOVER, 0), first, yesterday)
    }

    /** The local time of day of [ms], HH:MM. */
    fun localTime(
        ms: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        Instant
            .ofEpochMilli(ms)
            .atZone(zone)
            .toLocalTime()
            .format(clock)

    /**
     * The local times of the first review on each of the last [days] studied days before today,
     * oldest first, from each study day's first review id ([firstIds], ascending).
     */
    fun firstReviews(
        firstIds: List<Long>,
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
        days: Int = AnkiquestMood.RECENT_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val shift = offsetWestMinutes * MINUTE_MS + rolloverHour * HOUR_MS
        val todayStarts = Math.floorDiv(now - shift, DAY_MS) * DAY_MS + shift
        return firstIds.filter { it < todayStarts }.takeLast(days).map { localTime(it, zone) }
    }

    /** Keeps the night rule's inputs for the widget and the icon. */
    fun remember(
        rolloverHour: Int,
        firstReviews: List<String>,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        prefs.edit {
            putInt(PREF_ROLLOVER, rolloverHour)
            putString(PREF_FIRST_REVIEWS, firstReviews.joinToString(SEPARATOR))
        }
    }

    /** Reads the rollover and the first reviews from the collection and keeps them. */
    suspend fun refresh(now: Long) {
        val offsetWest = -TimeZone.getDefault().getOffset(now) / MINUTE_MS.toInt()
        val (rollover, ids) =
            CollectionManager.withCol {
                val rollover = AnkiquestDecks.rollover(this)
                val shift = offsetWest * MINUTE_MS + rollover * HOUR_MS
                val ids = mutableListOf<Long>()
                db.query(FIRST_REVIEWS_SQL, now - WINDOW_MS, shift).use { cursor ->
                    while (cursor.moveToNext()) {
                        ids += cursor.getLong(0)
                    }
                }
                rollover to ids
            }
        remember(rollover, firstReviews(ids, now, offsetWest, rollover))
    }
}
