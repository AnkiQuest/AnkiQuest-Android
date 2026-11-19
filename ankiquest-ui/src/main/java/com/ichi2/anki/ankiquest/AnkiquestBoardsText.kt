// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.ichi2.anki.ankiquest.AnkiquestBoards.ClosingWeek
import com.ichi2.anki.ankiquest.AnkiquestBoards.Period
import com.ichi2.anki.ankiquest.ui.R

// How the app words AnkiquestBoards from :ankiquest-core: the period tabs and the closing week.

/** The tab title of this period, in the order the website's tabs show them. */
@get:StringRes
val Period.label: Int
    get() =
        when (this) {
            Period.HOUR -> R.string.aq_board_hour
            Period.DAY -> R.string.ankiquest_nav_today
            Period.WEEK -> R.string.aq_board_week
            Period.MONTH -> R.string.aq_board_month
            Period.YEAR -> R.string.aq_board_year
            Period.ALL -> R.string.aq_board_all
        }

/** "Last week still counting: 1,234 XP · closes at 04:00", or null once [closing] has closed at [now]. */
fun AnkiquestBoards.closingLine(
    context: Context,
    closing: ClosingWeek?,
    now: Long,
): String? {
    if (closing == null || !closing.counting(now)) return null
    val at = DateFormat.getTimeFormat(context).format(java.util.Date(closing.endsAt))
    return context.getString(
        R.string.aq_closing_week,
        java.text.NumberFormat
            .getIntegerInstance(AnkiquestLanguage.locale())
            .format(closing.xp),
        at,
    )
}
