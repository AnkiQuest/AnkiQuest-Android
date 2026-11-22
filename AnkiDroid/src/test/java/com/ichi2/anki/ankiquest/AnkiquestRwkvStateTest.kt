// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestRwkvStateTest {
    private val gib = 1024L * 1024L * 1024L
    private val arm64 = "arm64-v8a"
    private val model = RwkvModel("/models/small-int8.bin")

    /** An engine that is present for [abis] and never called by the checks. */
    private class FakeEngine(
        override val abis: Set<String>,
    ) : RwkvEngine {
        override val availability = RwkvEngine.Availability.PRESENT

        override suspend fun rebuild(
            model: RwkvModel,
            reviews: Sequence<RwkvReview>,
        ): Unit = error("not used")

        override suspend fun score(cardIds: List<Long>): Map<Long, Double> = error("not used")
    }

    private fun checks(
        engine: RwkvEngine = FakeEngine(setOf(arm64)),
        abis: List<String> = listOf(arm64),
        ram: Long = 8 * gib,
        cards: Int = 22_000,
        model: RwkvModel? = this.model,
    ) = RwkvChecks(engine, abis, ram, cards, model)

    private fun refused(reason: RwkvBlocker) = RwkvState.Refused(reason)

    @Test
    fun `enabling with every check passing is ready and on`() {
        val state = RwkvState.Off.enable(checks())
        assertEquals(RwkvState.Ready, state)
        assertEquals(RwkvSwitch.ON, state.switch)
    }

    @Test
    fun `enabling without an engine is refused and checks nothing else`() {
        // No ABI, RAM or model either: the engine check comes first.
        val none = checks(engine = NoRwkvEngine, abis = emptyList(), ram = 0, model = null)
        assertEquals(refused(RwkvBlocker.NO_ENGINE), RwkvState.Off.enable(none))
    }

    @Test
    fun `enabling on an unsupported ABI is refused`() {
        assertEquals(refused(RwkvBlocker.UNSUPPORTED_ABI), RwkvState.Off.enable(checks(abis = listOf("x86"))))
    }

    @Test
    fun `enabling with too little memory is refused`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Off.enable(checks(ram = 1_900_000_000L)))
    }

    @Test
    fun `enabling without a model is refused`() {
        assertEquals(refused(RwkvBlocker.NO_MODEL), RwkvState.Off.enable(checks(model = null)))
    }

    @Test
    fun `a refused scheduler stays off`() {
        assertEquals(RwkvSwitch.OFF, refused(RwkvBlocker.NO_ENGINE).switch)
        assertEquals(RwkvSwitch.OFF, RwkvState.Off.switch)
    }

    @Test
    fun `enabling again after a refusal is ready once the blocker is gone`() {
        val refused = RwkvState.Off.enable(checks(model = null))
        assertEquals(RwkvState.Ready, refused.enable(checks()))
    }

    @Test
    fun `disabling goes off from every state`() {
        for (state in listOf(RwkvState.Off, RwkvState.Ready, refused(RwkvBlocker.NO_ENGINE))) {
            assertEquals(RwkvState.Off, state.disable())
        }
    }

    @Test
    fun `rechecking keeps the off states as they are`() {
        assertEquals(RwkvState.Off, RwkvState.Off.recheck(checks()))
        // A refusal does not turn itself on when the blocker goes away; the player turns it on.
        assertEquals(refused(RwkvBlocker.NO_MODEL), refused(RwkvBlocker.NO_MODEL).recheck(checks()))
    }

    @Test
    fun `rechecking a ready state keeps it ready while the checks pass`() {
        assertEquals(RwkvState.Ready, RwkvState.Ready.recheck(checks()))
    }

    @Test
    fun `rechecking a ready state refuses it when the collection outgrows memory`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Ready.recheck(checks(cards = 100_000)))
    }

    @Test
    fun `restoring follows the saved switch`() {
        assertEquals(RwkvState.Off, RwkvState.restore(RwkvSwitch.OFF, checks()))
        assertEquals(RwkvState.Ready, RwkvState.restore(RwkvSwitch.ON, checks()))
    }

    @Test
    fun `restoring a saved on that is now blocked is refused and off`() {
        val state = RwkvState.restore(RwkvSwitch.ON, checks(model = null))
        assertEquals(refused(RwkvBlocker.NO_MODEL), state)
        assertEquals(RwkvSwitch.OFF, state.switch)
    }

    @Test
    fun `the app has no engine yet so turning it on is always refused`() {
        assertEquals(refused(RwkvBlocker.NO_ENGINE), RwkvState.Off.enable(checks(engine = NoRwkvEngine)))
        assertEquals(RwkvEngine.Availability.ABSENT, NoRwkvEngine.availability)
        assertEquals(null, NoRwkvModel.chosen())
    }

    @Test
    fun `memory estimate scales kuma3's measurement`() {
        // kuma3 14: 505 MB for 22,000 cards.
        assertEquals(24_070L, RwkvMemory.BYTES_PER_CARD)
        assertTrue(RwkvMemory.needed(22_000) >= RwkvMemory.MEASURED_BYTES)
        assertEquals(0L, RwkvMemory.needed(0))
        assertEquals(0L, RwkvMemory.needed(-1))
    }

    @Test
    fun `memory check allows a quarter of RAM`() {
        assertEquals(4L, RwkvMemory.RAM_SHARE)

        // A 4 GB phone reports a little under 4 GiB and fits kuma3's collection.
        assertTrue(RwkvMemory.fits(3_800_000_000L, 22_000))

        // A 2 GB phone does not, but fits a smaller one.
        assertFalse(RwkvMemory.fits(1_900_000_000L, 22_000))
        assertTrue(RwkvMemory.fits(1_900_000_000L, 10_000))

        // The boundary is inclusive.
        val needed = RwkvMemory.needed(1_000) * RwkvMemory.RAM_SHARE
        assertTrue(RwkvMemory.fits(needed, 1_000))
        assertFalse(RwkvMemory.fits(needed - 1, 1_000))
    }
}
