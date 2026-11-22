// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The shared deck name rule; Robolectric gives it Android's ICU case folding. */
@RunWith(AndroidJUnit4::class)
class AnkiquestDeckPathsTest {
    @Test
    fun `cleaned names follow all shared desktop cases`() {
        val fixture = JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest/deck_names.json")).readText())
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.getString("expected"), AnkiquestDeckPaths.clean(case.getString("path")), case.getString("path"))
        }
    }

    @Test
    fun `Unicode digits and spaces clean like ASCII ones`() {
        assertEquals(AnkiquestDeckPaths.clean("12. Spanish"), AnkiquestDeckPaths.clean("１２. Spanish"))
        assertEquals(AnkiquestDeckPaths.clean("Spanish Core"), AnkiquestDeckPaths.clean("Spanish\u00A0Core"))
    }

    @Test
    fun `the desktop character limit never splits a supplementary character`() {
        val name = "語".repeat(119) + "🐼"
        assertEquals(name, AnkiquestDeckPaths.clean("Languages::$name"))
    }

    @Test
    fun `a short supplementary leaf keeps its parent like the desktop cleaner`() {
        assertEquals("Japanese › 𠮷𠮷", AnkiquestDeckPaths.clean("Japanese::𠮷𠮷"))
    }
}
