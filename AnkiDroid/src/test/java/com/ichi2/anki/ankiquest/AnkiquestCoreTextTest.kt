// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.ankiquest.ui.R
import com.ichi2.testutils.EmptyApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** How the app words the verdicts :ankiquest-core works out ([AnkiquestDay.words]). */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class AnkiquestCoreTextTest {
    @Test
    fun `a review after a frozen yesterday is worded as short, not unstudied`() {
        val profile =
            JSONObject(
                """{"streak": 4, "streak_state": "protected", "day_ends_at": 2000, "last_review_id": 10,
                   "today": {"starts_at": 100, "reviews": 1, "time_ms": 180000, "minutes": 3, "streak_minutes": 10}}""",
            )
        val verdict = checkNotNull(AnkiquestDay.judge(AnkiquestDay.Server.profile(profile), emptyList(), 1000))

        // The status line counts the minutes instead of saying nothing was studied.
        val context = RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.ankiquest_streak_short, 3, 10), AnkiquestDay.words(context, verdict))
    }
}
