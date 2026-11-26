// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnkiquestRwkvOffersTest {
    /** kuma3 14's 22,000 cards, with as many notes. */
    private val collection = RwkvCollection(cards = 22_000, notes = 22_000)

    private fun device(ram: Long) = RwkvDevice(ram, collection)

    private fun model(
        size: RwkvSize,
        head: RwkvHead,
    ): RwkvLockEntry = RwkvLock.ENTRIES.single { it.size == size && it.head == head }

    private val bigTwoHead = model(RwkvSize.BIG, RwkvHead.TWO_HEAD)
    private val bigInstant = model(RwkvSize.BIG, RwkvHead.INSTANT)
    private val smallTwoHead = model(RwkvSize.SMALL, RwkvHead.TWO_HEAD)
    private val tinyTwoHead = model(RwkvSize.TINY, RwkvHead.TWO_HEAD)

    /** The RAM at which [entry] just fits. */
    private fun edge(entry: RwkvLockEntry) = RwkvMemory.needed(RwkvOffers.runsWith(entry), collection) * RwkvMemory.RAM_SHARE

    private fun offers(
        ram: Long,
        stored: List<RwkvLockEntry> = emptyList(),
        attempts: Map<String, RwkvAttempt> = emptyMap(),
    ) = RwkvOffers.list(device(ram), stored, attempts)

    private fun List<RwkvOffer>.of(entry: RwkvLockEntry) = single { it.entry == entry }

    @Test
    fun `every model of the lock is offered, biggest first and two-head first`() {
        val ids = offers(ram = 16_000_000_000L).map { it.entry.id }
        assertEquals(listOf("sm91s2", "sm99", "sm92", "sm98", "sm106", "sm101"), ids)
    }

    @Test
    fun `each offer needs its own memory, an Instant model with its two-head model`() {
        val list = offers(ram = 16_000_000_000L)
        assertEquals(RwkvMemory.needed(listOf(bigTwoHead), collection), list.of(bigTwoHead).neededBytes)
        assertEquals(RwkvMemory.needed(listOf(bigTwoHead, bigInstant), collection), list.of(bigInstant).neededBytes)
        assertEquals(listOf(bigTwoHead, bigInstant), RwkvOffers.runsWith(bigInstant))
        assertEquals(listOf(bigTwoHead), RwkvOffers.runsWith(bigTwoHead))
    }

    @Test
    fun `a model that just fits is offered, a byte less greys it out with the reason`() {
        val ram = edge(bigTwoHead)
        assertEquals(RwkvOfferState.Fits, offers(ram).of(bigTwoHead).state)
        assertTrue(offers(ram).of(bigTwoHead).tappable)

        val tooBig = offers(ram - 1).of(bigTwoHead)
        assertEquals(RwkvOfferState.TooBig(tooBig.neededBytes, (ram - 1) / RwkvMemory.RAM_SHARE), tooBig.state)
        assertFalse(tooBig.tappable)
    }

    @Test
    fun `the Instant-only model needs room for its two-head model too`() {
        // Room for the Big two-head model alone, not for the pair.
        val list = offers(edge(bigTwoHead))
        assertEquals(RwkvOfferState.Fits, list.of(bigTwoHead).state)
        assertTrue(list.of(bigInstant).state is RwkvOfferState.TooBig)
    }

    @Test
    fun `the most accurate two-head model that fits is recommended`() {
        // Big's curve LogLoss is the lowest the release publishes; then Small; then Tiny.
        assertEquals(bigTwoHead, RwkvOffers.recommend(device(edge(bigTwoHead))))
        assertEquals(smallTwoHead, RwkvOffers.recommend(device(edge(bigTwoHead) - 1)))
        assertEquals(smallTwoHead, RwkvOffers.recommend(device(edge(smallTwoHead))))
        assertEquals(tinyTwoHead, RwkvOffers.recommend(device(edge(smallTwoHead) - 1)))
        assertNull(RwkvOffers.recommend(device(edge(tinyTwoHead) - 1)))
    }

    @Test
    fun `the recommendation follows the published scores, not the size`() {
        // A lock where Tiny scored best: it is recommended even with room for Small.
        val lock = RwkvLock.ENTRIES.map { if (it == tinyTwoHead) it.copy(logLoss = 0.1) else it }
        assertEquals(tinyTwoHead.id, RwkvOffers.recommend(device(edge(smallTwoHead)), lock)?.id)
    }

    @Test
    fun `exactly one offer is recommended when a model fits, none when nothing does`() {
        val list = offers(ram = 4_000_000_000L)
        assertEquals(listOf(bigTwoHead), list.filter { it.pick == RwkvPick.RECOMMENDED }.map { it.entry })

        val none = offers(ram = 512_000_000L)
        assertTrue(none.all { it.pick == RwkvPick.OTHER })
        assertTrue(none.all { it.state is RwkvOfferState.TooBig })
        assertTrue(none.none { it.tappable })
    }

    @Test
    fun `a stored model shows as downloaded and cannot be tapped`() {
        val offer = offers(ram = 4_000_000_000L, stored = listOf(smallTwoHead)).of(smallTwoHead)
        assertEquals(RwkvOfferState.Downloaded, offer.state)
        assertFalse(offer.tappable)
    }

    @Test
    fun `a model being checked shows as verifying and cannot be tapped`() {
        val offer = offers(ram = 4_000_000_000L, attempts = mapOf(smallTwoHead.id to RwkvAttempt.Verifying)).of(smallTwoHead)
        assertEquals(RwkvOfferState.Verifying, offer.state)
        assertFalse(offer.tappable)
    }

    @Test
    fun `a failed download that fits can be tried again`() {
        val failed = RwkvAttempt.Failed(RwkvImportError.NETWORK_FAILED)
        val offer = offers(ram = 4_000_000_000L, attempts = mapOf(smallTwoHead.id to failed)).of(smallTwoHead)
        assertEquals(RwkvOfferState.Failed(RwkvImportError.NETWORK_FAILED), offer.state)
        assertTrue(offer.tappable)
    }

    @Test
    fun `a failed download that no longer fits is too big`() {
        val failed = RwkvAttempt.Failed(RwkvImportError.DOWNLOAD_MISMATCH)
        val offer = offers(ram = edge(bigTwoHead) - 1, attempts = mapOf(bigTwoHead.id to failed)).of(bigTwoHead)
        assertTrue(offer.state is RwkvOfferState.TooBig)
    }

    @Test
    fun `a stored model is downloaded even where it no longer fits`() {
        val offer = offers(ram = edge(bigTwoHead) - 1, stored = listOf(bigTwoHead)).of(bigTwoHead)
        assertEquals(RwkvOfferState.Downloaded, offer.state)
    }
}
