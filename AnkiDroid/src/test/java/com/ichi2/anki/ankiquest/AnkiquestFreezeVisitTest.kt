// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestFreezeVisit.Closed
import com.ichi2.anki.ankiquest.AnkiquestFreezeVisit.Day
import com.ichi2.anki.ankiquest.AnkiquestFreezeVisit.Open
import com.ichi2.anki.ankiquest.AnkiquestFreezeVisit.Ready
import com.ichi2.anki.ankiquest.AnkiquestFreezeVisit.Showing
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class AnkiquestFreezeVisitTest {
    private val day = Day("player", 100L)
    private val history = AnkiquestMood.History(frozenDays = 1)

    @Test
    fun `first visible frame keeps its picture during redraws`() {
        val ready = assertIs<Ready>(Open.prepare(day, history) { false })
        assertEquals(AnkiquestPose.FREEZE_USED, ready.picture)
        assertSame(ready, ready.prepare(day, history) { false })

        val displayed = assertIs<Showing>(ready.displayed(ready))
        assertEquals(AnkiquestPose.FREEZE_USED, displayed.picture)
        assertSame(displayed, displayed.prepare(day, history) { true })
        assertSame(Open, Open.prepare(day, history) { true })
    }

    @Test
    fun `a discarded frame cannot count for a later visit`() {
        val discarded = assertIs<Ready>(Open.prepare(day, history) { false })
        assertSame(Closed, Closed.displayed(discarded))
        assertSame(Closed, Closed.prepare(day, history) { error("closed visit reads no marker") })

        val reopened = assertIs<Ready>(Open.prepare(day, history) { false })
        assertSame(reopened, reopened.displayed(discarded))
        assertIs<Showing>(reopened.displayed(reopened))
    }

    @Test
    fun `account and day changes must qualify for their own picture`() {
        val ready = Ready(day)
        val shown = ready.displayed(ready)
        assertNull(shown.prepare(null, history) { true }.picture)
        assertNull(shown.prepare(day.copy(scope = "another"), AnkiquestMood.History()) { false }.picture)
        val nextDay = day.copy(endsAt = 200L)
        assertEquals(Ready(nextDay), shown.prepare(nextDay, history) { false })
    }
}
