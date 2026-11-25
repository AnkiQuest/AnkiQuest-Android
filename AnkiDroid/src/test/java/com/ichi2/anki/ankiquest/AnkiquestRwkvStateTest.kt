// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestRwkvStateTest {
    private val gib = 1024L * 1024L * 1024L
    private val entry = RwkvLock.ENTRIES.first { it.head == RwkvHead.TWO_HEAD }
    private val models = RwkvModels(RwkvModelFile(entry, File("/models/${entry.sha256}.bin")), instant = null)

    private fun checks(
        ram: Long = 8 * gib,
        cards: Int = 22_000,
        models: RwkvModels? = this.models,
    ) = RwkvChecks(ram, cards, models)

    private fun refused(reason: RwkvBlocker) = RwkvState.Refused(reason)

    @Test
    fun `enabling with every check passing is ready and on`() {
        val state = RwkvState.Off.enable(checks())
        assertEquals(RwkvState.Ready, state)
        assertEquals(RwkvSwitch.ON, state.switch)
    }

    @Test
    fun `enabling with too little memory is refused before the model is looked at`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Off.enable(checks(ram = 1_900_000_000L, models = null)))
    }

    @Test
    fun `enabling without a model is refused`() {
        assertEquals(refused(RwkvBlocker.NO_MODEL), RwkvState.Off.enable(checks(models = null)))
    }

    @Test
    fun `every state but ready shows the switch off`() {
        for (state in listOf(RwkvState.Off, RwkvState.Stopping) + RwkvBlocker.entries.map(::refused)) {
            assertEquals(RwkvSwitch.OFF, state.switch, "$state")
        }
    }

    @Test
    fun `enabling again after a refusal is ready once the blocker is gone`() {
        val refused = RwkvState.Off.enable(checks(models = null))
        assertEquals(RwkvState.Ready, refused.enable(checks()))
    }

    @Test
    fun `enabling while stopping is ready again`() {
        assertEquals(RwkvState.Ready, RwkvState.Stopping.enable(checks()))
    }

    @Test
    fun `disabling without a loaded model is off from every state`() {
        for (state in listOf(RwkvState.Off, RwkvState.Ready, RwkvState.Stopping, refused(RwkvBlocker.NO_MODEL))) {
            assertEquals(RwkvState.Off, state.disable(RwkvLoad.NOT_LOADED))
        }
    }

    @Test
    fun `disabling with a loaded model stops at the next restart`() {
        // The backend has no call to drop a model, so it keeps scheduling until the process ends.
        assertEquals(RwkvState.Stopping, RwkvState.Ready.disable(RwkvLoad.LOADED))
        assertEquals(RwkvSwitch.OFF, RwkvState.Stopping.switch)
    }

    @Test
    fun `a backend failure refuses a ready scheduler and leaves the off states alone`() {
        assertEquals(refused(RwkvBlocker.BACKEND_FAILED), RwkvState.Ready.fail())
        assertEquals(RwkvState.Off, RwkvState.Off.fail())
        assertEquals(RwkvState.Stopping, RwkvState.Stopping.fail())
        assertEquals(refused(RwkvBlocker.NO_MODEL), refused(RwkvBlocker.NO_MODEL).fail())
    }

    @Test
    fun `restoring follows the saved switch`() {
        assertEquals(RwkvState.Off, RwkvState.restore(RwkvSwitch.OFF, checks(), RwkvLoad.NOT_LOADED))
        assertEquals(RwkvState.Ready, RwkvState.restore(RwkvSwitch.ON, checks(), RwkvLoad.NOT_LOADED))
        assertEquals(RwkvState.Ready, RwkvState.restore(RwkvSwitch.ON, checks(), RwkvLoad.LOADED))
    }

    @Test
    fun `restoring a saved off with a loaded model is stopping`() {
        assertEquals(RwkvState.Stopping, RwkvState.restore(RwkvSwitch.OFF, checks(), RwkvLoad.LOADED))
    }

    @Test
    fun `restoring a saved on that is now blocked is refused and off`() {
        val state = RwkvState.restore(RwkvSwitch.ON, checks(models = null), RwkvLoad.NOT_LOADED)
        assertEquals(refused(RwkvBlocker.NO_MODEL), state)
        assertEquals(RwkvSwitch.OFF, state.switch)
    }

    @Test
    fun `restoring a ready state refuses it when the collection outgrows memory`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.restore(RwkvSwitch.ON, checks(cards = 100_000), RwkvLoad.LOADED))
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
