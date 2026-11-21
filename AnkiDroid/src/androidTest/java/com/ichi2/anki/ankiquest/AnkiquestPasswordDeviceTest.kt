// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Check password text on Android's runtime as well as the host JVM. */
@RunWith(AndroidJUnit4::class)
class AnkiquestPasswordDeviceTest {
    @Test
    fun unicodePasswordBounds() {
        // A supplementary character occupies two UTF-16 units but counts once on the server.
        val emoji = "\uD83D\uDE00"
        val length = R.string.ankiquest_password_length
        val cases = listOf(5 to length, 9 to length, 10 to null, 129 to null, 256 to null, 257 to length)
        for ((count, expected) in cases) {
            val typed = emoji.repeat(count)
            assertEquals(expected, AnkiquestPasswordPrompt.problem(typed, typed), "$count Unicode characters")
        }
    }

    @Test
    fun unicodePasswordRepeat() {
        val typed = "\uD83D\uDE00".repeat(10)
        assertNull(AnkiquestPasswordPrompt.problem(typed, typed))
        assertEquals(R.string.ankiquest_password_mismatch, AnkiquestPasswordPrompt.problem(typed, "\uD83D\uDE01".repeat(10)))
    }
}
