// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/** The feed samples that AnkiquestFeedTest in :ankiquest-core uses, read from the test resources both modules share. */
object AnkiquestFeedSamples {
    /** The documented page shape (docs/mobile-api.md), with rows as the server's examples record them. */
    val PAGE = sample("feed_page.json")

    /** The older oldest-first notification array. */
    val LEGACY = sample("feed_legacy.json")

    private fun sample(name: String): String =
        checkNotNull(AnkiquestFeedSamples::class.java.classLoader?.getResource("ankiquest-samples/$name")).readText()
}
