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

/** The chibi Easter egg against the shared fixture `tests/fixtures/companion_unlock.json` ([UnlockFixture]). */
class AnkiquestChibiTest {
    private val fixture = UnlockFixture.json

    /** Every chibi as live, as flipping the fixture's `status` makes it. */
    private val live = AnkiquestChibi.ALL.map { it.copy(status = Status.LIVE) }

    /** Anki-tan's chibi: the fixture's `cases` tap her base. */
    private val chibi = live.single { it.base == ANKI_TAN }

    @Test
    fun `the chibis are the fixture's tap companions`() {
        val tapped = UnlockFixture.objects(fixture.getJSONArray("companions")).filter { it.getString("kind") == "taps" }
        val chibis = tapped.map { Chibi(it.getString("companion"), it.getString("requires"), status(it.getString("status"))) }
        assertEquals(chibis, AnkiquestChibi.ALL)

        // Each chibi's goal is the rule's tap count.
        for (entry in tapped) {
            assertEquals(AnkiquestChibi.TAPS, entry.getInt("goal"), entry.getString("companion"))
        }
    }

    @Test
    fun `the tap rule is the fixture's`() {
        assertEquals(fixture.getInt("taps"), AnkiquestChibi.TAPS)
        assertEquals(fixture.getLong("max_gap_ms"), AnkiquestChibi.MAX_GAP_MS)
        assertEquals(fixture.getInt("hint_from"), AnkiquestChibi.HINT_FROM)
    }

    @Test
    fun `the fixture cases decide each tap run`() {
        val cases = UnlockFixture.objects(fixture.getJSONArray("cases"))
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val steps = run(case.getJSONArray("sequence"), setOf(chibi.base))
            assertEquals(case.getBoolean("unlocks"), steps.last() == Step.Done(chibi), case.getString("name"))
        }
    }

    @Test
    fun `the fixture gives the hint after each tap count`() {
        val taps = Taps(live)
        for (entry in UnlockFixture.objects(fixture.getJSONArray("hints"))) {
            val count = entry.getInt("taps")
            val step = taps.tap(chibi.base, count * QUICK_MS, setOf(chibi.base))
            assertEquals(entry.opt("hint").takeIf { it != JSONObject.NULL }, hint(step), "after $count taps")
        }
    }

    @Test
    fun `the fixture chibi cases decide hints and unlocks`() {
        val cases = UnlockFixture.objects(fixture.getJSONArray("chibi_taps"))
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.getString("name")
            val unlocked = case.getJSONArray("unlocked").let { all -> (0 until all.length()).map { all.getString(it) }.toSet() }
            val steps = run(case.getJSONArray("sequence"), unlocked)
            val hints =
                case.getJSONArray("hints").let { all ->
                    (0 until all.length()).map { all.opt(it).takeIf { h -> h != JSONObject.NULL } }
                }
            assertEquals(hints, steps.map { hint(it) }, name)

            // An unlock finds the chibi of the base tapped last.
            val sequence = case.getJSONArray("sequence")
            val last = sequence.getJSONArray(sequence.length() - 1).getString(0)
            val found = live.singleOrNull { it.base == last }?.let { Step.Done(it) }
            assertEquals(case.getBoolean("unlocks"), steps.last() == found, name)
        }
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

    /** The steps of a fixture [sequence] of [choice, milliseconds] taps, with [unlocked] companions. */
    private fun run(
        sequence: JSONArray,
        unlocked: Set<String>,
    ): List<Step> {
        val taps = Taps(live)
        return (0 until sequence.length()).map {
            val tap = sequence.getJSONArray(it)
            taps.tap(tap.getString(0), tap.getLong(1), unlocked)
        }
    }

    /** The fixture's text for [step]'s hint, or null when it shows none. */
    private fun hint(step: Step): String? = (step as? Step.Hint)?.let { UnlockFixture.hint(it.left) }

    private fun status(id: String) = Status.entries.single { it.id == id }

    private companion object {
        /** A tap well inside the rule's gap. */
        const val QUICK_MS = 300L

        const val ANKI_TAN = "anki_tan"
    }
}
