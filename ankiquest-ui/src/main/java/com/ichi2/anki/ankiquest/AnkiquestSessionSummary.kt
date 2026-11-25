// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.ankiquest.ui.R

/** One study session as the recap shows it: answers, minutes, cards still due and when it ended (Unix ms). */
data class AnkiquestSessionSummary(
    val reviews: Int,
    val minutes: Int,
    val remaining: Int,
    val finishedAt: Long,
) {
    fun duration(context: Context): String =
        if (minutes == 0) {
            context.getString(R.string.ankiquest_summary_under_minute)
        } else {
            context.resources.getQuantityString(R.plurals.ankiquest_summary_minutes, minutes, minutes)
        }
}
