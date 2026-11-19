// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import java.time.LocalDate

/**
 * The study data AnkiQuest's rules read, kept apart from where it comes from: the fork reads its
 * collection's review log, an app beside stock AnkiDroid reads AnkiDroid's provider. The rules call
 * this, never a collection or a content resolver.
 */
interface StudySource {
    /** The answers after review [after], oldest first, as the upload sends them; revlog ids are answer times in ms. */
    fun reviewsAfter(after: Long): List<AnkiquestDay.Review>

    /** Answers per study day up to today and reviews scheduled for each day ahead, for the heatmap. */
    fun history(): StudyHistory

    /** The study day it is now, by the collection's own day boundaries. */
    fun today(): LocalDate
}

/**
 * Answers per study day up to [today], and reviews scheduled per day after it. Counts are answers,
 * not distinct cards.
 */
data class StudyHistory(
    val today: LocalDate,
    val reviews: Map<LocalDate, Int>,
    val scheduled: Map<LocalDate, Int>,
)
