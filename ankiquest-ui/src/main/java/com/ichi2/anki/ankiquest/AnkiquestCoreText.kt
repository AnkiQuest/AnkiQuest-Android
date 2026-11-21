// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.StringRes
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import com.ichi2.anki.ankiquest.AnkiquestDay.Verdict
import com.ichi2.anki.ankiquest.ui.R

// How the app words the rules in :ankiquest-core: AnkiquestDay's verdicts and AnkiquestLock's locks.

/** The status line for today's [verdict]. */
fun AnkiquestDay.words(
    context: Context,
    verdict: Verdict,
): String =
    when {
        verdict.awaitingUpload -> context.getString(R.string.aq_goal_streak_safe_unsent)
        verdict.met -> context.getString(R.string.aq_goal_streak_safe)
        else -> words(context, verdict.label, verdict.minutes.toLong(), verdict.streakMinutes.toLong())
    }

/** One status line for a streak [state] from [Verdict.label], as every screen words it. */
fun AnkiquestDay.words(
    context: Context,
    state: State,
    minutes: Long,
    streakMinutes: Long,
): String =
    when (state) {
        State.STUDIED -> context.getString(R.string.ankiquest_streak_studied)
        State.PROTECTED -> context.getString(R.string.ankiquest_streak_protected)
        State.SHORT -> context.getString(R.string.ankiquest_streak_short, minOf(minutes, streakMinutes), streakMinutes)
        State.PENDING -> context.getString(R.string.ankiquest_streak_pending)
        State.UNKNOWN -> context.getString(R.string.ankiquest_streak_unknown)
    }

/** What the app says in place of the others a lock keeps it from showing. */
@get:StringRes
val AnkiquestLock.text: Int
    get() =
        when (this) {
            AnkiquestLock.UPLOAD_FIRST -> R.string.aq_upload_first
        }
