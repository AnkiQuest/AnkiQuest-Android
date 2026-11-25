// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest.heatmap

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.rules.ExternalResource
import java.time.LocalDate

/**
 * Draws the deck list's review heatmap on one study date in every screenshot. The native scheduler
 * reads the host clock, not MockTime, so without this a capture changes from one day to the next.
 */
class FixedHeatmapRule : ExternalResource() {
    override fun before() {
        val data = ReviewHeatmap.summarize(STUDY_DATE, emptyMap(), emptyMap())
        mockkObject(ReviewHeatmap)
        every { ReviewHeatmap.load(any()) } returns data
    }

    override fun after() = unmockkObject(ReviewHeatmap)

    private companion object {
        /** The study date of the recorded deck-list baselines. */
        val STUDY_DATE: LocalDate = LocalDate.parse("2026-11-09")
    }
}
