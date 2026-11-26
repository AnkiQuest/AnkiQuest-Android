// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Event
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Inputs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Quiet
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Scheduler
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Since
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.State
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Verdict
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Wording
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared rules' library loads through JNA on Android's runtime, from the APK's own copy for
 * the device's ABI; the unit tests load a desktop build of it. The cases are the fixture's.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestSharedRulesDeviceTest {
    private val day = 86_400_000L

    @Test
    fun theLibraryAnswersOnTheDevice() {
        val now = 1_788_926_400_000L
        val inputs = Inputs(now, now - 30 * day, 1000, listOf(Scheduler.FSRS, Scheduler.RWKV_CURVE), deck = 1)
        assertEquals(Verdict.Remind(Since.STALE, Wording.MIXED), AnkiquestSharedRules.verdict(inputs, State.On))
        assertEquals(Verdict.Silent(Quiet.TOO_FEW_REVIEWS), AnkiquestSharedRules.verdict(inputs.copy(reviewsSince = 999), State.On))
        assertEquals(State.Snoozed(now + 7 * day), AnkiquestSharedRules.next(State.On, Event.SNOOZE, now, null))
    }
}
