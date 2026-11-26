// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The countdown name limit also runs against Android's text editing implementation. */
@RunWith(AndroidJUnit4::class)
class AnkiquestTextLimitTest {
    @Test
    fun countdownNameKeepsWholeCharacters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val name = EditText(instrumentation.targetContext)
            name.filters = arrayOf(AnkiquestTextLimit.filter(AnkiquestCountdowns.MAX_NAME_CHARS))
            val character = "\uD83C\uDF93"
            val full = character.repeat(AnkiquestCountdowns.MAX_NAME_CHARS)

            name.setText(full + "x")
            assertEquals(full, name.text.toString())

            // Replacing one supplementary character frees one character, not two UTF-16 units.
            name.text.replace(full.length - character.length, full.length, "AB")
            assertEquals(character.repeat(AnkiquestCountdowns.MAX_NAME_CHARS - 1) + "A", name.text.toString())
        }
    }
}
