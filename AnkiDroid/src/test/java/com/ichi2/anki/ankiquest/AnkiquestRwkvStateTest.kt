// SPDX-License-Identifier: AGPL-3.0-only

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

    private fun unavailable(reason: RwkvBlocker) = RwkvState.Unavailable(reason)

    @Test
    fun `enabling with every check passing is ready`() {
        assertEquals(RwkvState.Ready, RwkvState.Off.enable(checks()))
    }

    @Test
    fun `enabling without an engine is unavailable and checks nothing else`() {
        // No ABI, RAM or model either: the engine check comes first.
        val none = checks(engine = NoRwkvEngine, abis = emptyList(), ram = 0, model = null)
        assertEquals(unavailable(RwkvBlocker.NO_ENGINE), RwkvState.Off.enable(none))
    }

    @Test
    fun `enabling on an unsupported ABI is unavailable`() {
        assertEquals(unavailable(RwkvBlocker.UNSUPPORTED_ABI), RwkvState.Off.enable(checks(abis = listOf("x86"))))
    }

    @Test
    fun `enabling with too little memory is unavailable`() {
        assertEquals(unavailable(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Off.enable(checks(ram = 3 * gib)))
    }

    @Test
    fun `enabling without a model is unavailable`() {
        assertEquals(unavailable(RwkvBlocker.NO_MODEL), RwkvState.Off.enable(checks(model = null)))
    }

    @Test
    fun `disabling goes off from every state`() {
        for (state in listOf(RwkvState.Off, RwkvState.Ready, unavailable(RwkvBlocker.NO_ENGINE))) {
            assertEquals(RwkvState.Off, state.disable())
        }
    }

    @Test
    fun `rechecking keeps off off`() {
        assertEquals(RwkvState.Off, RwkvState.Off.recheck(checks()))
    }

    @Test
    fun `rechecking an unavailable state becomes ready once a model is chosen`() {
        val waiting = RwkvState.Off.enable(checks(model = null))
        assertEquals(RwkvState.Ready, waiting.recheck(checks()))
    }

    @Test
    fun `rechecking a ready state becomes unavailable when the collection outgrows memory`() {
        val ready = RwkvState.Off.enable(checks())
        assertEquals(unavailable(RwkvBlocker.NOT_ENOUGH_MEMORY), ready.recheck(checks(cards = 100_000)))
    }

    @Test
    fun `restoring follows the saved switch`() {
        assertEquals(RwkvState.Off, RwkvState.restore(RwkvSwitch.OFF, checks()))
        assertEquals(RwkvState.Ready, RwkvState.restore(RwkvSwitch.ON, checks()))
    }

    @Test
    fun `the app has no engine yet so turning it on never makes it ready`() {
        assertEquals(unavailable(RwkvBlocker.NO_ENGINE), RwkvState.restore(RwkvSwitch.ON, checks(engine = NoRwkvEngine)))
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
    fun `memory check allows an eighth of RAM`() {
        // kuma3's measurement phone (Pixel 8a, 8 GB) fits its own collection.
        assertTrue(RwkvMemory.fits(8 * gib, 22_000))

        // A 4 GB phone reports a little under 4 GiB and does not.
        assertFalse(RwkvMemory.fits(3_800_000_000L, 22_000))
        assertTrue(RwkvMemory.fits(3_800_000_000L, 10_000))

        // The boundary is inclusive.
        val needed = RwkvMemory.needed(1_000) * RwkvMemory.RAM_SHARE
        assertTrue(RwkvMemory.fits(needed, 1_000))
        assertFalse(RwkvMemory.fits(needed - 1, 1_000))
    }
}
