// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import anki.deck_config.DeckConfig
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.EpochSeconds
import com.ichi2.anki.pages.DeckOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Reminds the player to run Anki's Optimize All Presets once the collection's FSRS parameters are
 * 30 days and 1,000 reviews old.
 *
 * AnkiQuest-Server's `tests/fixtures/optimize_reminder.json` pins the rule for this and the
 * desktop add-on (`addon/optimize_reminder.py`). Everything is read from the collection; the
 * reminder's state stays on this device and never reaches the server.
 */
internal object AnkiquestOptimizeReminder {
    const val DAYS = 30
    const val REVIEWS = 1000
    const val SNOOZE_DAYS = 7

    // The collection config key Optimize All Presets sets to the day it ran; 0 or missing is never.
    private const val LAST_OPTIMIZE_KEY = "lastFsrsOptimize"
    private const val PREF_STATE = "ankiquestOptimizeReminder"
    private const val TAG = "AnkiquestOptimizeReminder"
    private const val DAY_SECONDS = 86_400L
    private const val SECOND_MS = 1_000L

    // Answers only: manual (type 4) and rescheduled (type 5) entries are not reviews.
    private const val ANSWERS_SQL = "select count() from revlog where id >= ? and ease > 0 and type < 4"

    enum class Scheduler { SM2, FSRS, RWKV_CURVE, RWKV_INSTANT }

    /** Whether the collection uses FSRS; without it every preset is SM-2. */
    enum class Fsrs { ON, OFF }

    /** The reminder on this device: on, off by the settings switch, snoozed or dismissed. */
    sealed interface State {
        data object On : State

        data object Off : State

        /** Hidden while today is before [untilDay]. */
        data class Snoozed(
            val untilDay: Int,
        ) : State

        /** Hidden until Optimize All Presets runs again: while it last ran on [optimizedDay]. */
        data class Dismissed(
            val optimizedDay: Int?,
        ) : State
    }

    enum class Event { SNOOZE, DISMISS, TURN_OFF, TURN_ON }

    /** Why there is no reminder, in the order the rule checks. */
    enum class Quiet { OFF, NO_FSRS, SNOOZED, DISMISSED, TOO_SOON, TOO_FEW_REVIEWS }

    enum class Since { STALE, NEVER }

    /** What optimizing does for the presets: FSRS parameters, the RWKV presets' FSRS fallback, or both. */
    enum class Wording { FSRS, RWKV, MIXED }

    sealed interface Verdict {
        data class Silent(
            val quiet: Quiet,
        ) : Verdict

        data class Remind(
            val since: Since,
            val wording: Wording,
        ) : Verdict
    }

    /** What the rule reads from the collection. */
    data class Inputs(
        val today: Int,
        val lastOptimizedDay: Int?,
        val reviewsSince: Int,
        val presets: List<Scheduler>,
        val deck: DeckId,
    )

    fun scheduler(
        fsrs: Fsrs,
        config: DeckConfig.Config,
    ): Scheduler =
        when {
            fsrs == Fsrs.OFF -> Scheduler.SM2
            config.rwkvReviewInstantOrderEnabled -> Scheduler.RWKV_INSTANT
            config.rwkvReviewEnabled -> Scheduler.RWKV_CURVE
            else -> Scheduler.FSRS
        }

    fun verdict(
        inputs: Inputs,
        state: State,
    ): Verdict {
        val last = inputs.lastOptimizedDay
        val optimized = inputs.presets.filter { it != Scheduler.SM2 }

        // The first rule that holds names the verdict.
        val quiet =
            when {
                state == State.Off -> Quiet.OFF
                optimized.isEmpty() -> Quiet.NO_FSRS
                state is State.Snoozed && inputs.today < state.untilDay -> Quiet.SNOOZED
                state is State.Dismissed && state.optimizedDay == last -> Quiet.DISMISSED
                last != null && inputs.today - last < DAYS -> Quiet.TOO_SOON
                inputs.reviewsSince < REVIEWS -> Quiet.TOO_FEW_REVIEWS
                else -> null
            }
        if (quiet != null) {
            return Verdict.Silent(quiet)
        }

        val fsrs = optimized.count { it == Scheduler.FSRS }
        val wording =
            when (fsrs) {
                0 -> Wording.RWKV
                optimized.size -> Wording.FSRS
                else -> Wording.MIXED
            }
        return Verdict.Remind(if (last == null) Since.NEVER else Since.STALE, wording)
    }

    fun next(
        state: State,
        event: Event,
        today: Int,
        lastOptimizedDay: Int?,
    ): State =
        when {
            event == Event.TURN_OFF -> State.Off
            state == State.Off && event == Event.TURN_ON -> State.On
            state == State.Off || event == Event.TURN_ON -> state
            event == Event.SNOOZE -> State.Snoozed(today + SNOOZE_DAYS)
            else -> State.Dismissed(lastOptimizedDay)
        }

    /** Answers on the days after [lastOptimizedDay], or every answer when it never ran. */
    fun reviewsSince(
        col: Collection,
        today: Int,
        dayCutoff: EpochSeconds,
        lastOptimizedDay: Int?,
    ): Int {
        val start = lastOptimizedDay?.let { (dayCutoff - (today - it) * DAY_SECONDS) * SECOND_MS } ?: 0L
        return col.db.queryLongScalar(ANSWERS_SQL, start).toInt()
    }

    /** Call inside CollectionManager.withCol, away from the main thread. */
    fun load(col: Collection): Inputs {
        val today = col.sched.today
        val last = col.config.get<Int>(LAST_OPTIMIZE_KEY)?.takeIf { it > 0 }
        val deck = col.decks.selected()
        val configs = col.decks.getDeckConfigsForUpdate(deck)
        val fsrs = if (configs.fsrs) Fsrs.ON else Fsrs.OFF
        return Inputs(
            today = today,
            lastOptimizedDay = last,
            reviewsSince = reviewsSince(col, today, col.sched.dayCutoff, last),
            presets = configs.allConfigList.map { scheduler(fsrs, it.config.config) },
            deck = deck,
        )
    }

    fun state(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): State = decode(prefs.getString(PREF_STATE, null))

    fun remember(
        state: State,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = prefs.edit { putString(PREF_STATE, encode(state).toString()) }

    /** The stored form the fixture's states use; an unknown or missing one is on. */
    fun decode(stored: String?): State {
        if (stored == null) {
            return State.On
        }
        return try {
            val json = JSONObject(stored)
            when (json.optString("kind")) {
                "off" -> State.Off
                "snoozed" -> State.Snoozed(json.getInt("until_day"))
                "dismissed" -> State.Dismissed(if (json.isNull("optimized_day")) null else json.getInt("optimized_day"))
                else -> State.On
            }
        } catch (e: JSONException) {
            State.On
        }
    }

    fun encode(state: State): JSONObject =
        when (state) {
            State.On -> JSONObject().put("kind", "on")
            State.Off -> JSONObject().put("kind", "off")
            is State.Snoozed -> JSONObject().put("kind", "snoozed").put("until_day", state.untilDay)
            is State.Dismissed -> JSONObject().put("kind", "dismissed").put("optimized_day", state.optimizedDay ?: JSONObject.NULL)
        }

    /** Applies [event] to the stored state, with the collection's day and last optimization. */
    suspend fun record(event: Event) {
        val (today, last) =
            withCol { sched.today to config.get<Int>(LAST_OPTIMIZE_KEY)?.takeIf { it > 0 } }
        remember(next(state(), event, today, last))
    }

    /** The reminder's text for [since] and [wording], in the fixture's order. */
    fun message(
        context: Context,
        since: Since,
        wording: Wording,
    ): String {
        val sinceText =
            when (since) {
                Since.STALE -> R.string.aq_optimize_stale
                Since.NEVER -> R.string.aq_optimize_never
            }
        val wordingText =
            when (wording) {
                Wording.FSRS -> R.string.aq_optimize_fsrs
                Wording.RWKV -> R.string.aq_optimize_rwkv
                Wording.MIXED -> R.string.aq_optimize_mixed
            }
        return listOf(sinceText, wordingText, R.string.aq_optimize_where).joinToString(" ") { context.getString(it) }
    }

    /** Whether this run of the app already looked for a reminder. */
    private enum class Offer { PENDING, MADE }

    private var offer = Offer.PENDING

    /** Reminds the player once per run, over the deck list, when the rule says so. */
    fun offer(activity: FragmentActivity) {
        if (offer == Offer.MADE || state() == State.Off) {
            return
        }
        offer = Offer.MADE
        activity.lifecycleScope.launch {
            val inputs =
                try {
                    withCol { load(this) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest optimize reminder could not read the collection")
                    return@launch
                }
            val verdict = verdict(inputs, state()) as? Verdict.Remind ?: return@launch
            activity.lifecycle.withResumed { show(activity, inputs.deck, verdict) }
        }
    }

    private fun show(
        activity: FragmentActivity,
        deck: DeckId,
        verdict: Verdict.Remind,
    ) {
        val manager = activity.supportFragmentManager
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved) {
            return
        }

        // Upstream's dialogs, Aki's greeting and AnkiQuest's moments go first; the reminder waits
        // for the next start rather than covering them.
        if (!AnkiquestPostStudyPrompts.ready(activity) || manager.fragments.any { it is DialogFragment && it.dialog?.isShowing == true }) {
            return
        }
        AnkiquestOptimizeReminderDialog
            .create(deck, verdict)
            .showNow(manager, TAG)
    }
}

/** The reminder: what optimizing does here, then Open deck options, Remind me in a week and Dismiss. */
internal class AnkiquestOptimizeReminderDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val arguments = requireArguments()
        val since = AnkiquestOptimizeReminder.Since.valueOf(arguments.getString(ARG_SINCE)!!)
        val wording = AnkiquestOptimizeReminder.Wording.valueOf(arguments.getString(ARG_WORDING)!!)
        val deck = arguments.getLong(ARG_DECK)
        val scope = requireActivity().lifecycleScope
        return AlertDialog
            .Builder(context)
            .setTitle(R.string.aq_optimize_title)
            .setMessage(AnkiquestOptimizeReminder.message(context, since, wording))
            .setPositiveButton(R.string.aq_optimize_open) { _, _ ->
                startActivity(DeckOptions.getIntent(context, deck))
            }.setNeutralButton(R.string.aq_optimize_snooze) { _, _ ->
                scope.launch { AnkiquestOptimizeReminder.record(AnkiquestOptimizeReminder.Event.SNOOZE) }
            }.setNegativeButton(R.string.aq_optimize_dismiss) { _, _ ->
                scope.launch { AnkiquestOptimizeReminder.record(AnkiquestOptimizeReminder.Event.DISMISS) }
            }.create()
    }

    override fun onStart() {
        super.onStart()
        // Moments and other AnkiQuest prompts wait until the reminder is closed.
        AnkiquestPostStudyPrompts.track(requireDialog())
    }

    companion object {
        private const val ARG_SINCE = "since"
        private const val ARG_WORDING = "wording"
        private const val ARG_DECK = "deck"

        fun create(
            deck: DeckId,
            verdict: AnkiquestOptimizeReminder.Verdict.Remind,
        ) = AnkiquestOptimizeReminderDialog().apply {
            arguments =
                Bundle().apply {
                    putString(ARG_SINCE, verdict.since.name)
                    putString(ARG_WORDING, verdict.wording.name)
                    putLong(ARG_DECK, deck)
                }
        }
    }
}
