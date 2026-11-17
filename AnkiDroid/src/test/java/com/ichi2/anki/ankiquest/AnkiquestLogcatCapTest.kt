// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A report's logcat stays small enough for the server's 256 KiB limit. */
class AnkiquestLogcatCapTest {
    @Test
    fun `a short logcat is kept whole`() {
        val logcat = "first line\nsecond line\n"

        assertEquals(logcat, AnkiquestLogcatCap.tail(logcat, AnkiquestLogcatCap.LIMIT_BYTES))
    }

    @Test
    fun `a long logcat keeps its newest whole lines`() {
        // 1500 long-format entries of the developer configuration, with multi-byte text.
        val logcat = (1..1500).joinToString("\n") { "11-17 08:00:00.000 I/AnkiDroid( 123): entry $it ".padEnd(200, 'ü') }

        val tail = AnkiquestLogcatCap.tail(logcat, AnkiquestLogcatCap.LIMIT_BYTES)

        assertTrue(tail.toByteArray(Charsets.UTF_8).size <= AnkiquestLogcatCap.LIMIT_BYTES)
        assertTrue(logcat.endsWith(tail))
        assertTrue(tail.startsWith("11-17 08:00:00.000"), tail.take(40))
        assertEquals(logcat.lines().last(), tail.lines().last())
    }
}
