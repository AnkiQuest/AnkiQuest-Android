// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.ichi2.anki.ankiquest.AnkiquestBoards.ClosingWeek
import com.ichi2.anki.ankiquest.AnkiquestBoards.NextWeek
import com.ichi2.anki.ankiquest.AnkiquestBoards.OwnWeek
import com.ichi2.anki.ankiquest.AnkiquestBoards.Period
import com.ichi2.anki.ankiquest.ui.R

// How the app words AnkiquestBoards from :ankiquest-core: the period tabs and the week today counts toward.

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

/**
 * "Last week still counting: 1,234 XP · closes at 04:00" or "Next week already counting:
 * 1,234 XP · the board switches at 13:00", or null once [week] has stopped counting at [now].
 */
fun AnkiquestBoards.ownWeekLine(
    context: Context,
    week: OwnWeek?,
    now: Long,
): String? {
    if (week == null || !week.counting(now)) return null
    val at = DateFormat.getTimeFormat(context).format(java.util.Date(week.until))
    val xp =
        java.text.NumberFormat
            .getIntegerInstance(AnkiquestLanguage.locale())
            .format(week.xp)
    val line =
        when (week) {
            is ClosingWeek -> R.string.aq_closing_week
            is NextWeek -> R.string.aq_next_week
        }
    return context.getString(line, xp, at)
}
