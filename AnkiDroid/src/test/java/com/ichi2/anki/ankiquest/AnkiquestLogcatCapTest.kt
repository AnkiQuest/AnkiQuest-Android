// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.exception.SanitizedReportCollector
import org.acra.collector.LogCatCollector
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

    @Test
    fun `the cap runs after logcat is read and before it is sanitized`() {
        // ACRA runs collectors of one order group concurrently: sharing the sanitizer's group would let
        // the cap write back the unfiltered log the sanitizer just replaced.
        val read = LogCatCollector().order
        val cap = AnkiquestLogcatCap().order
        val sanitized = SanitizedReportCollector().order

        assertTrue(read < cap, "cap ($cap) must run after LogCatCollector ($read)")
        assertTrue(cap < sanitized, "cap ($cap) must run before SanitizedReportCollector ($sanitized)")
    }
}
