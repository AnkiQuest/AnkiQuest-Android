// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks [AnkiquestStudyTime] against `tests/fixtures/study_time.json` from AnkiQuest-Server,
 * which the desktop add-on also follows. Each case runs the real query on a revlog holding the
 * case's rows.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestStudyTimeTest : RobolectricTest() {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/study_time.json")) { "missing study time" }.readText(),
        )

    @Test
    fun `every shared case`() {
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")

            // Only this case's rows in the review log.
            col.db.execute("delete from revlog")
            val reviews = case.getJSONArray("reviews")
            for (j in 0 until reviews.length()) {
                val review = reviews.getJSONObject(j)
                col.db.execute(
                    "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, -1, ?, 1, 1, 2500, ?, ?)",
                    review.getLong("id"),
                    review.getInt("ease"),
                    review.getLong("time_ms"),
                    review.getInt("type"),
                )
            }

            val clock = case.getJSONObject("clock")
            val totals =
                AnkiquestStudyTime.load(col, case.getLong("now_ms"), clock.getInt("offset_west_min"), clock.getInt("rollover_hour"))
            val expected = case.getJSONObject("expected")
            assertEquals(expected.getString("today"), totals.today.toString(), name)
            assertEquals(
                listOf("today_ms", "week_ms", "month_ms", "total_ms", "study_days", "average_ms").map { expected.getLong(it) },
                listOf(totals.todayMs, totals.weekMs, totals.monthMs, totals.totalMs, totals.studyDays.toLong(), totals.averageMs),
                name,
            )
        }
    }

    @Test
    fun `durations round down to minutes and show hours from 60 minutes on`() {
        val durations = fixture.getJSONArray("durations")
        assertTrue(durations.length() > 0)
        for (i in 0 until durations.length()) {
            val case = durations.getJSONObject(i)
            val hours = case.getInt("hours")
            val minutes = case.getInt("minutes")
            val expected = if (hours == 0) "$minutes min" else "$hours h $minutes min"
            assertEquals(expected, AnkiquestStudyTime.duration(targetContext, case.getLong("ms")), "${case.getLong("ms")} ms")
        }
    }
}
