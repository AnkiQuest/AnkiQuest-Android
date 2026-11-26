// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** The RWKV help page players open from the settings: readable, and saying what they must know. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestRwkvGuideTest : RobolectricTest(loadBackend = false) {
    @Test
    fun `the guide says what a player must know`() {
        val page = AnkiquestReleaseNotes.html(AnkiquestReleaseNotes.read(targetContext, AnkiquestReleaseNotes.GUIDE))
        // Off by default, and what it does while on.
        assertTrue("off by default" in page, page)
        assertTrue("neural network" in page, page)

        // Getting the weights: download or choose files, with the sizes.
        assertTrue("Download" in page, page)
        assertTrue("Choose files" in page, page)
        assertTrue("Small" in page, page)
        assertTrue("Tiny" in page, page)
        // No weights of ours ship; the weights guide of the Anki fork is linked.
        assertTrue("ships no RWKV weights" in page, page)
        assertTrue("rwkv-weights.md" in page, page)

        // Deck options turn each preset on; the switch off returns every preset.
        assertTrue("deck options" in page, page)
        assertTrue("RWKV-Instant" in page, page)
        assertTrue("RWKV-Curve" in page, page)

        // The statistics panel has a forecast and a recall chart.
        assertTrue("RWKV forecast" in page, page)
        assertTrue("recall" in page, page)
    }

    @Test
    fun `the guide's sizes match the model lock list`() {
        val page = AnkiquestReleaseNotes.html(AnkiquestReleaseNotes.read(targetContext, AnkiquestReleaseNotes.GUIDE))

        // Every size the guide names comes from the lock list: the zips the Download fetches,
        // and the weights.f32.bin a player can pick instead of them.
        fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)
        for (size in RwkvSize.values()) {
            val name = size.name.lowercase().replaceFirstChar { it.uppercaseChar() }
            val twoHead = RwkvLock.ENTRIES.single { it.size == size && it.head == RwkvHead.TWO_HEAD }
            val instant = RwkvLock.ENTRIES.single { it.size == size && it.head == RwkvHead.INSTANT }
            assertTrue("$name: two-head zip ${mb(twoHead.zipBytes)}, Instant-only zip ${mb(instant.zipBytes)}" in page, page)
            assertTrue("${mb(twoHead.bytes)}" in page, page)
        }
    }
}
