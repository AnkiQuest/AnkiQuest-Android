// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestLinks.PlayListing
import com.ichi2.anki.ankiquest.AnkiquestLinks.RateTarget
import org.junit.Test
import kotlin.test.assertEquals

class AnkiquestLinksTest {
    @Test
    fun `Without a Play listing, Rate AnkiQuest opens the repository`() {
        val target = RateTarget.of(PlayListing.ABSENT)

        assertEquals(RateTarget.GITHUB, target)
        assertEquals("https://github.com/AnkiQuest/AnkiQuest-Android", target.url(PACKAGE))
    }

    @Test
    fun `With a Play listing, Rate AnkiQuest opens the app's Play page`() {
        val target = RateTarget.of(PlayListing.PUBLISHED)

        assertEquals(RateTarget.PLAY_STORE, target)
        assertEquals("https://play.google.com/store/apps/details?id=$PACKAGE", target.url(PACKAGE))
    }

    @Test
    fun `This build has no Play listing yet`() {
        assertEquals(RateTarget.GITHUB, RateTarget.of())
    }

    private companion object {
        const val PACKAGE = "com.ichi2.anki.quest"
    }
}
