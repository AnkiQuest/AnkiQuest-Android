// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/** The first Today visit after a freeze, for one account and study day. */
internal sealed interface AnkiquestFreezeVisit {
    data object Closed : AnkiquestFreezeVisit

    data object Open : AnkiquestFreezeVisit

    data class Day(
        val scope: String,
        val endsAt: Long,
    )

    /** The view is ready to draw, but might be discarded before the first frame. */
    data class Ready(
        val day: Day,
    ) : AnkiquestFreezeVisit

    /** The first frame was shown. Keep the picture for this visit, including redraws. */
    data class Showing(
        val day: Day,
    ) : AnkiquestFreezeVisit

    val picture: AnkiquestPose?
        get() =
            when (this) {
                is Ready, is Showing -> AnkiquestPose.FREEZE_USED
                Closed, Open -> null
            }

    fun prepare(
        day: Day?,
        history: AnkiquestMood.History,
        seen: (Day) -> Boolean,
    ): AnkiquestFreezeVisit =
        when {
            this == Closed -> Closed
            day == null -> Open
            this is Showing && this.day == day -> this
            history.frozenDays == 0 || seen(day) -> Open
            this is Ready && this.day == day -> this
            else -> Ready(day)
        }

    fun displayed(expected: Ready): AnkiquestFreezeVisit = if (this === expected) Showing(expected.day) else this
}
