// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnkiquestFeedTextTest {
    @Test
    fun `line breaks and other control characters become spaces`() {
        assertEquals("Great job ! Keep going", AnkiquestFeed.clean("\nGreat\njob\t!\nKeep\u0007going\u0085"))
        assertEquals("", AnkiquestFeed.clean("\n\t\r"))
    }

    @Test
    fun `the limit counts UTF-8 bytes like the server`() {
        assertEquals("a".repeat(200), AnkiquestFeed.clean("a".repeat(250)))
        assertEquals("é".repeat(100), AnkiquestFeed.clean("é".repeat(150)))
        assertEquals("😀".repeat(50), AnkiquestFeed.clean("😀".repeat(60)))
    }

    @Test
    fun `a character is never split at the limit`() {
        val cut = AnkiquestFeed.clean("a" + "😀".repeat(60))
        assertEquals("a" + "😀".repeat(49), cut)
        val bytes = cut.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= AnkiquestFeed.MAX_MESSAGE)
        assertEquals(cut, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `short replies are kept as written`() {
        assertEquals("¡Gracias! 🎉", AnkiquestFeed.clean("  ¡Gracias! 🎉  "))
    }
}
