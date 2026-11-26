// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestRwkvStateTest {
    private val gib = 1024L * 1024L * 1024L

    /** A collection as kuma3 14's: 22,000 cards; notes as many, so the estimate errs high. */
    private val kuma3 = RwkvCollection(cards = 22_000, notes = 22_000)

    private fun model(
        size: RwkvSize,
        head: RwkvHead,
    ): RwkvLockEntry = RwkvLock.ENTRIES.single { it.size == size && it.head == head }

    private fun file(entry: RwkvLockEntry) = RwkvModelFile(entry, File("/models/${entry.sha256}.bin"))

    private val small = RwkvModels(file(model(RwkvSize.SMALL, RwkvHead.TWO_HEAD)), instant = null)

    private fun checks(
        ram: Long = 8 * gib,
        cards: Int = 22_000,
        models: RwkvModels? = small,
    ) = RwkvChecks(ram, RwkvCollection(cards, cards), models)

    private fun refused(reason: RwkvBlocker) = RwkvState.Refused(reason)

    @Test
    fun `enabling with every check passing is ready and on`() {
        val state = RwkvState.Off.enable(checks())
        assertEquals(RwkvState.Ready, state)
        assertEquals(RwkvSwitch.ON, state.switch)
    }

    @Test
    fun `enabling without a model is refused before the memory is looked at`() {
        // The memory depends on the model's size: without one there is nothing to size.
        assertEquals(refused(RwkvBlocker.NO_MODEL), RwkvState.Off.enable(checks(ram = 0, models = null)))
    }

    @Test
    fun `enabling with too little memory for the imported model is refused`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Off.enable(checks(ram = 512_000_000L)))
    }

    @Test
    fun `the memory check counts the Instant model beside the two-head model`() {
        val big = file(model(RwkvSize.BIG, RwkvHead.TWO_HEAD))
        val pair = RwkvModels(big, file(model(RwkvSize.BIG, RwkvHead.INSTANT)))
        val ram = RwkvMemory.needed(listOf(big.entry), kuma3) * RwkvMemory.RAM_SHARE
        assertEquals(RwkvState.Ready, RwkvState.Off.enable(checks(ram = ram, models = RwkvModels(big, instant = null))))
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.Off.enable(checks(ram = ram, models = pair)))
    }

    @Test
    fun `enabling without a model is refused`() {
        assertEquals(refused(RwkvBlocker.NO_MODEL), RwkvState.Off.enable(checks(models = null)))
    }

    @Test
    fun `every state but ready shows the switch off`() {
        for (state in listOf(RwkvState.Off) + RwkvBlocker.entries.map(::refused)) {
            assertEquals(RwkvSwitch.OFF, state.switch, "$state")
        }
    }

    @Test
    fun `enabling again after a refusal is ready once the blocker is gone`() {
        val refused = RwkvState.Off.enable(checks(models = null))
        assertEquals(RwkvState.Ready, refused.enable(checks()))
    }

    @Test
    fun `disabling is off from every state`() {
        for (state in listOf(RwkvState.Off, RwkvState.Ready, refused(RwkvBlocker.NO_MODEL))) {
            assertEquals(RwkvState.Off, state.disable())
        }
    }

    @Test
    fun `a backend failure refuses a ready scheduler and leaves the off states alone`() {
        assertEquals(refused(RwkvBlocker.BACKEND_FAILED), RwkvState.Ready.fail())
        assertEquals(RwkvState.Off, RwkvState.Off.fail())
        assertEquals(refused(RwkvBlocker.NO_MODEL), refused(RwkvBlocker.NO_MODEL).fail())
    }

    @Test
    fun `restoring follows the saved switch`() {
        assertEquals(RwkvState.Off, RwkvState.restore(RwkvSwitch.OFF, checks()))
        assertEquals(RwkvState.Ready, RwkvState.restore(RwkvSwitch.ON, checks()))
    }

    @Test
    fun `restoring a saved on that is now blocked is refused and off`() {
        val state = RwkvState.restore(RwkvSwitch.ON, checks(models = null))
        assertEquals(refused(RwkvBlocker.NO_MODEL), state)
        assertEquals(RwkvSwitch.OFF, state.switch)
    }

    @Test
    fun `restoring a ready state refuses it when the collection outgrows memory`() {
        assertEquals(refused(RwkvBlocker.NOT_ENOUGH_MEMORY), RwkvState.restore(RwkvSwitch.ON, checks(cards = 1_000_000)))
    }

    @Test
    fun `the state per card and note follows the release's int8 sizes`() {
        // INTEGRATION.md section 8.4: Small card 2,987 B and note 1,567 B; the fork's (Big) 15,415 B and 10,283 B.
        assertEquals(2_987L, RwkvSize.SMALL.shape.cardBytes)
        assertEquals(1_567L, RwkvSize.SMALL.shape.noteBytes)
        assertEquals(15_415L, RwkvSize.BIG.shape.cardBytes)
        assertEquals(10_283L, RwkvSize.BIG.shape.noteBytes)

        // Tiny is Small with one card layer.
        assertEquals(1_567L, RwkvSize.TINY.shape.cardBytes)
        assertEquals(1_567L, RwkvSize.TINY.shape.noteBytes)
    }

    @Test
    fun `memory estimates per size match the release's RAM table`() {
        // INTEGRATION.md section 11.1: 13,973 cards and 13,198 notes; Big at int8 state 491 MB estimated
        // (506 MB measured), Small at int8 state 192 MB.
        val collection = RwkvCollection(cards = 13_973, notes = 13_198)

        fun mb(entry: RwkvLockEntry) = RwkvMemory.needed(listOf(entry), collection) / 1_000_000
        assertEquals(491L, mb(model(RwkvSize.BIG, RwkvHead.TWO_HEAD)))
        assertEquals(192L, mb(model(RwkvSize.SMALL, RwkvHead.TWO_HEAD)))
        assertEquals(172L, mb(model(RwkvSize.TINY, RwkvHead.TWO_HEAD)))
    }

    @Test
    fun `kuma3's phone figure is the big model's`() {
        // kuma3 14: 505 MB for 22,000 cards at int8 state on a Pixel 8a, from the fork's big model.
        val big = RwkvMemory.needed(listOf(model(RwkvSize.BIG, RwkvHead.TWO_HEAD)), RwkvCollection(22_000, notes = 0))
        assertTrue(big in 470_000_000L..505_000_000L, "$big")
    }

    @Test
    fun `memory estimates count each model once and never go below the fixed part`() {
        val twoHead = model(RwkvSize.SMALL, RwkvHead.TWO_HEAD)
        val instant = model(RwkvSize.SMALL, RwkvHead.INSTANT)
        val one = RwkvMemory.needed(listOf(twoHead), kuma3)
        val both = RwkvMemory.needed(listOf(twoHead, instant), kuma3)
        assertEquals(one - RwkvMemory.FIXED_BYTES, both - one)

        val empty = RwkvCollection(cards = -1, notes = 0)
        assertEquals(RwkvMemory.FIXED_BYTES + twoHead.bytes, RwkvMemory.needed(listOf(twoHead), empty))
    }

    @Test
    fun `memory check allows a quarter of RAM`() {
        assertEquals(4L, RwkvMemory.RAM_SHARE)
        assertEquals(1_000_000_000L, RwkvMemory.allowed(4_000_000_000L))
        val small = listOf(model(RwkvSize.SMALL, RwkvHead.TWO_HEAD))
        val big = listOf(model(RwkvSize.BIG, RwkvHead.TWO_HEAD))

        // A 2 GB phone fits Small for kuma3's collection, not Big; a 4 GB one fits Big.
        assertTrue(RwkvMemory.fits(1_900_000_000L, small, kuma3))
        assertFalse(RwkvMemory.fits(1_900_000_000L, big, kuma3))
        assertTrue(RwkvMemory.fits(3_800_000_000L, big, kuma3))

        // The boundary is inclusive.
        val needed = RwkvMemory.needed(small, kuma3)
        assertTrue(RwkvMemory.fits(needed * RwkvMemory.RAM_SHARE, small, kuma3))
        assertFalse(RwkvMemory.fits(needed * RwkvMemory.RAM_SHARE - 1, small, kuma3))
    }
}
