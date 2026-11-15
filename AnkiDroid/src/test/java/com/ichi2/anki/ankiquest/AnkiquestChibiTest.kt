// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestChibi.Chibi
import com.ichi2.anki.ankiquest.AnkiquestChibi.Status
import com.ichi2.anki.ankiquest.AnkiquestChibi.Step
import com.ichi2.anki.ankiquest.AnkiquestChibi.TapRun
import com.ichi2.anki.ankiquest.AnkiquestChibi.Taps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chibi Easter egg against the shared fixture `tests/fixtures/companion_unlock.json`. */
class AnkiquestChibiTest {
    /** The shared fixture from the pinned server commit. */
    private val fixture =
        JSONObject(checkNotNull(javaClass.classLoader?.getResource("ankiquest/companion_unlock.json")).readText())

    /** Every chibi as live, as flipping the fixture's `status` makes it. */
    private val live = AnkiquestChibi.ALL.map { it.copy(status = Status.LIVE) }

    private val chibi = live.single()

    @Test
    fun `the chibis are the fixture's tap companions`() {
        val companions = fixture.getJSONArray("companions")
        val tapped =
            (0 until companions.length())
                .map { companions.getJSONObject(it) }
                .filter { it.getString("kind") == "taps" }
                .map { Chibi(it.getString("companion"), it.getString("requires"), status(it.getString("status"))) }
        assertEquals(tapped, AnkiquestChibi.ALL)

        // Each chibi's goal is the rule's tap count.
        for (i in 0 until companions.length()) {
            val entry = companions.getJSONObject(i)
            if (entry.getString("kind") == "taps") {
                assertEquals(AnkiquestChibi.TAPS, entry.getInt("goal"), entry.getString("companion"))
            }
        }
    }

    @Test
    fun `the tap rule is the fixture's`() {
        assertEquals(fixture.getInt("taps"), AnkiquestChibi.TAPS)
        assertEquals(fixture.getLong("max_gap_ms"), AnkiquestChibi.MAX_GAP_MS)
    }

    @Test
    fun `the fixture cases decide each tap run`() {
        val cases = cases()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val taps = Taps(live)
            val sequence = case.getJSONArray("sequence")
            var last: Step = Step.Silent
            for (i in 0 until sequence.length()) {
                val tap = sequence.getJSONArray(i)
                last = taps.tap(tap.getString(0), tap.getLong(1), setOf(chibi.base))
            }
            assertEquals(case.getBoolean("unlocks"), last == Step.Done(chibi), case.getString("name"))
        }
    }

    @Test
    fun `the player hears how many taps are left from the fourth tap`() {
        val taps = Taps(live)

        val steps = (0 until AnkiquestChibi.TAPS).map { taps.tap(chibi.base, it * QUICK_MS, setOf(chibi.base)) }

        val hints = (AnkiquestChibi.TAPS - AnkiquestChibi.HINT_FROM downTo 1).map { Step.Hint(it) }
        assertEquals(List(AnkiquestChibi.HINT_FROM - 1) { Step.Silent } + hints + Step.Done(chibi), steps)
    }

    @Test
    fun `a locked base gives no hint and never unlocks its chibi`() {
        val taps = Taps(live)

        val steps = (0 until 2 * AnkiquestChibi.TAPS).map { taps.tap(chibi.base, it * QUICK_MS, emptySet()) }

        assertEquals(List(2 * AnkiquestChibi.TAPS) { Step.Silent }, steps)
    }

    @Test
    fun `a base unlocked during a run starts counting from its first tap after`() {
        val taps = Taps(live)
        repeat(AnkiquestChibi.TAPS - 1) { taps.tap(chibi.base, it * QUICK_MS, emptySet()) }

        val steps = (0 until AnkiquestChibi.TAPS).map { taps.tap(chibi.base, (AnkiquestChibi.TAPS + it) * QUICK_MS, setOf(chibi.base)) }

        assertEquals(Step.Done(chibi), steps.last())
        assertTrue(steps.dropLast(1).none { it is Step.Done })
    }

    @Test
    fun `an unlocked chibi counts no more taps`() {
        val taps = Taps(live)

        val steps = (0 until AnkiquestChibi.TAPS).map { taps.tap(chibi.base, it * QUICK_MS, setOf(chibi.base, chibi.id)) }

        assertEquals(List(AnkiquestChibi.TAPS) { Step.Silent }, steps)
    }

    @Test
    fun `a planned chibi stays hidden`() {
        val taps = Taps()

        val steps = (0 until AnkiquestChibi.TAPS).map { taps.tap(chibi.base, it * QUICK_MS, setOf(chibi.base)) }

        assertEquals(List(AnkiquestChibi.TAPS) { Step.Silent }, steps)
    }

    @Test
    fun `a completed run starts again`() {
        val taps = Taps(live)
        repeat(AnkiquestChibi.TAPS) { taps.tap(chibi.base, it * QUICK_MS, setOf(chibi.base)) }

        val next = taps.tap(chibi.base, AnkiquestChibi.TAPS * QUICK_MS, setOf(chibi.base))

        assertEquals(Step.Silent, next)
    }

    @Test
    fun `a run counts each quick tap and restarts after a slow one`() {
        val run = TapRun()

        val counts = listOf(0L, QUICK_MS, QUICK_MS + AnkiquestChibi.MAX_GAP_MS + 1).map { run.tap(chibi.base, it) }

        assertEquals(listOf(1, 2, 1), counts)
    }

    /**
     * The pinned fixture's cases and the [localCases] it does not have yet, by name.
     */
    private fun cases(): List<JSONObject> {
        val pinned = fixture.getJSONArray("cases").objects()
        val names = pinned.map { it.getString("name") }.toSet()
        return pinned + JSONArray(localCases).objects().filter { it.getString("name") !in names }
    }

    private fun status(id: String) = Status.entries.single { it.id == id }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private companion object {
        /** A tap well inside the rule's gap. */
        const val QUICK_MS = 300L

        /**
         * LOCAL COPY, not yet in the pinned fixture: the cases AnkiQuest/AnkiQuest-Desktop#289 adds
         * to `tests/fixtures/companion_unlock.json` (earlier, same-time and 64-bit-overflowing
         * taps). Delete once the server bump brings them; the pinned cases replace these by name.
         */
        val localCases =
            """
            [
              {"name": "an earlier tap starts again",
               "sequence": [["anki_tan", 9000], ["anki_tan", 8000], ["anki_tan", 7000], ["anki_tan", 6000], ["anki_tan", 5000],
                            ["anki_tan", 4000], ["anki_tan", 3000], ["anki_tan", 2000], ["anki_tan", 1000]],
               "unlocks": false},
              {"name": "taps at the same time continue the run",
               "sequence": [["anki_tan", 1000], ["anki_tan", 1000], ["anki_tan", 1000], ["anki_tan", 1000], ["anki_tan", 1000],
                            ["anki_tan", 1000], ["anki_tan", 1000], ["anki_tan", 1000], ["anki_tan", 1000]],
               "unlocks": true},
              {"name": "a gap too wide for 64 bits starts again",
               "sequence": [["anki_tan", -9223372036854775808], ["anki_tan", -9223372036854775508], ["anki_tan", -9223372036854775208],
                            ["anki_tan", -9223372036854774908], ["anki_tan", -9223372036854774608], ["anki_tan", -9223372036854774308],
                            ["anki_tan", -9223372036854774008], ["anki_tan", -9223372036854773708], ["anki_tan", 9223372036854775807]],
               "unlocks": false},
              {"name": "a tap that wraps around to the earliest time starts again",
               "sequence": [["anki_tan", 9223372036854773707], ["anki_tan", 9223372036854774007], ["anki_tan", 9223372036854774307],
                            ["anki_tan", 9223372036854774607], ["anki_tan", 9223372036854774907], ["anki_tan", 9223372036854775207],
                            ["anki_tan", 9223372036854775507], ["anki_tan", 9223372036854775807], ["anki_tan", -9223372036854775808]],
               "unlocks": false}
            ]
            """.trimIndent()
    }
}
