// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app records which rules release it was built with. The rules themselves are tested through
 * [AnkiquestSharedRules] by each rule's own fixture test ([AnkiquestOptimizeReminderTest]).
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestSharedRulesTest : RobolectricTest() {
    @Test
    fun `the app names the rules release and the hash of each file it ships`() {
        val record =
            JSONObject(
                targetContext.assets
                    .open("ankiquest/rules-release.json")
                    .bufferedReader()
                    .readText(),
            )
        val tag = record.getString("tag")
        assertTrue(tag == "local" || tag.matches(Regex("rules-[1-9][0-9]*")), tag)

        val assets = record.getJSONObject("assets")
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        assertEquals(
            (listOf("ankiquest_rules.kt") + abis.map { "libankiquest_rules-android-$it.so" }).sorted(),
            assets.keys().asSequence().toList(),
        )
        for (name in assets.keys()) {
            assertTrue(assets.getString(name).matches(Regex("[0-9a-f]{64}")), name)
        }
    }
}
