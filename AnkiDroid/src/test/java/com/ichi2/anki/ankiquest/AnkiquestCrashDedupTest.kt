// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestCrashRecord.Verdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The device sends each distinct crash at most twice, without sending anything that identifies the player. */
class AnkiquestCrashDedupTest {
    private val store = MemoryStore()
    private val record = AnkiquestCrashRecord(store)

    @Test
    fun `the same crash is sent twice, then skipped`() {
        val signature = AnkiquestCrashSignature.of(crash("Card 12 missing"))

        val verdicts = List(3) { record.admit(signature) }

        assertEquals(listOf(Verdict.SEND, Verdict.SEND, Verdict.SKIP), verdicts)
    }

    @Test
    fun `a different crash is still sent`() {
        val first = AnkiquestCrashSignature.of(crash("Card 12 missing"))
        repeat(3) { record.admit(first) }

        val other = AnkiquestCrashSignature.of(IllegalStateException("Card 12 missing"))

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
    fun `a damaged record starts over`() {
        store.text = "not a record\nsignature many"

        assertEquals(Verdict.SEND, record.admit("signature"))
    }

    @Test
    fun `the signature ignores numbers, ids and line numbers`() {
        val first = crash("Card 12 missing in deck 3f2a9c1e-77b0", line = 10)
        val second = crash("Card 345 missing in deck 9b1d0c2a-1234", line = 99)

        assertEquals(AnkiquestCrashSignature.of(first), AnkiquestCrashSignature.of(second))
    }

    @Test
    fun `the signature tells crashes apart by class, message and frames`() {
        val base = AnkiquestCrashSignature.of(crash("Card 12 missing"))

        assertNotEquals(base, AnkiquestCrashSignature.of(crash("Deck 12 missing")))
        assertNotEquals(base, AnkiquestCrashSignature.of(crash("Card 12 missing", method = "load")))
        assertNotEquals(base, AnkiquestCrashSignature.of(IllegalArgumentException("Card 12 missing")))
    }

    @Test
    fun `the signature reads only the top eight frames`() {
        val deep = crash("Card 12 missing", depth = 12)
        val different = crash("Card 12 missing", depth = 12)
        different.stackTrace = different.stackTrace.copyOf().also { it[10] = frame("Other", "run", 1) }

        assertEquals(AnkiquestCrashSignature.of(deep), AnkiquestCrashSignature.of(different))
    }

    @Test
    fun `the signature is a SHA-256 hex digest`() {
        val signature = AnkiquestCrashSignature.of(RuntimeException())

        assertEquals(SHA256_HEX_LENGTH, signature.length)
        assertEquals(signature, signature.filter { it in "0123456789abcdef" })
    }

    /** An exception with a fixed stack trace, so tests do not depend on where they run. */
    private fun crash(
        message: String,
        method: String = "open",
        line: Int = 1,
        depth: Int = 3,
    ): Throwable =
        IllegalStateException(message).apply {
            stackTrace = Array(depth) { if (it == 0) frame("Deck", method, line) else frame("Caller$it", "call", line + it) }
        }

    private fun frame(
        type: String,
        method: String,
        line: Int,
    ) = StackTraceElement("com.example.$type", method, "$type.kt", line)

    private class MemoryStore : AnkiquestCrashRecord.Store {
        var text: String? = null

        override fun load() = text

        override fun save(text: String) {
            this.text = text
        }
    }

    companion object {
        private const val SHA256_HEX_LENGTH = 64
    }
}
