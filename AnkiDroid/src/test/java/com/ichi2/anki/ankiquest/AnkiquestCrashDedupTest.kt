// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestCrashRecord.Verdict
import org.junit.Test
import kotlin.test.assertEquals

/** The device sends each distinct crash at most twice, without sending anything that identifies the player. */
class AnkiquestCrashDedupTest {
    private val store = MemoryStore()
    private val record = AnkiquestCrashRecord(store)

    /** These messages hold no host or credential. */
    private val noRedaction = AnkiquestRedaction(emptyList(), emptyList())

    @Test
    fun `the same crash is sent twice, then skipped`() {
        val signature = AnkiquestCrashSignature.of(crash("Card 12 missing"), noRedaction)

        val verdicts = List(3) { record.admit(signature) }

        assertEquals(listOf(Verdict.SEND, Verdict.SEND, Verdict.SKIP), verdicts)
    }

    @Test
    fun `a different crash is still sent`() {
        val first = AnkiquestCrashSignature.of(crash("Card 12 missing"), noRedaction)
        repeat(3) { record.admit(first) }

        val other = AnkiquestCrashSignature.of(IllegalStateException("Card 12 missing"), noRedaction)

        assertEquals(Verdict.SEND, record.admit(other))
    }

    @Test
    fun `the record keeps the most recent signatures only`() {
        val oldest = "signature-0"
        repeat(2) { record.admit(oldest) }

        // Fill the record so the oldest signature is dropped.
        for (n in 1..AnkiquestCrashRecord.CAPACITY) {
            record.admit("signature-$n")
        }

        assertEquals(AnkiquestCrashRecord.CAPACITY, store.text!!.lines().size)
        assertEquals(Verdict.SEND, record.admit(oldest))
    }

    @Test
    fun `the record survives a restart`() {
        val signature = "signature"
        repeat(2) { record.admit(signature) }

        assertEquals(Verdict.SKIP, AnkiquestCrashRecord(store).admit(signature))
    }

    @Test
    fun `a forgotten record sends again`() {
        val signature = "signature"
        repeat(2) { record.admit(signature) }

        record.forget()

        assertEquals(Verdict.SEND, record.admit(signature))
    }

    @Test
    fun `a cancelled report is taken back for its crash only`() {
        repeat(2) { record.admit("cancelled") }
        repeat(2) { record.admit("other") }

        record.retract("cancelled")

        assertEquals(Verdict.SEND, record.admit("cancelled"))
        assertEquals(Verdict.SKIP, record.admit("cancelled"))
        assertEquals(Verdict.SKIP, record.admit("other"))
    }

    @Test
    fun `retracting an unknown crash changes nothing`() {
        repeat(2) { record.admit("signature") }

        record.retract("unknown")

        assertEquals(Verdict.SKIP, record.admit("signature"))
    }

    @Test
    fun `a damaged record starts over`() {
        store.text = "not a record\nsignature many"

        assertEquals(Verdict.SEND, record.admit("signature"))
    }

    /** An exception with a fixed stack trace, so tests do not depend on where they run. */
    private fun crash(message: String): Throwable =
        IllegalStateException(message).apply {
            stackTrace = arrayOf(StackTraceElement("com.example.Deck", "open", "Deck.kt", 1))
        }

    private class MemoryStore : AnkiquestCrashRecord.Store {
        var text: String? = null

        override fun load() = text

        override fun save(text: String) {
            this.text = text
        }
    }
}
