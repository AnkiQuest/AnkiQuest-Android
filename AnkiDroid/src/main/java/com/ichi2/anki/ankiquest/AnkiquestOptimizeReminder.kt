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
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Event
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Fsrs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Inputs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Preset
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Scheduler
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Since
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.State
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Verdict
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Wording
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.EpochMilliseconds
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
 * The rule is AnkiQuest-Server's `crates/rules` (`optimize_verdict`, `optimize_next`), which the
 * server and the desktop add-on follow too, called through [AnkiquestSharedRules] and tested
 * against `tests/fixtures/optimize_reminder.json`. The inputs come from the collection. The state is the synced setting
 * `optimize_reminder`, one for all the player's devices ([AnkiquestSettingsSync]). Its times are
 * epoch milliseconds, never collection day numbers: collections on different devices can number
 * their days differently.
 */
internal object AnkiquestOptimizeReminder {
    // The collection config key Optimize All Presets sets to the day it ran; 0 or missing is never.
    private const val LAST_OPTIMIZE_KEY = "lastFsrsOptimize"

    /** The preference that holds the state; [AnkiquestSettingsSync] syncs it as `optimize_reminder`. */
    const val PREF_STATE = "ankiquestOptimizeReminder"
    private const val TAG = "AnkiquestOptimizeReminder"

    // The stored state's fields.
    private const val KIND = "kind"
    private const val UNTIL_MS = "until_ms"
    private const val OPTIMIZED_MS = "optimized_ms"

    // Answers only: manual (type 4) and rescheduled (type 5) entries are not reviews.
    private const val ANSWERS_SQL = "select count() from revlog where id >= ? and ease > 0 and type < 4"

    /** The scheduler of a preset with [config]. */
    fun scheduler(
        fsrs: Fsrs,
        config: DeckConfig.Config,
    ): Scheduler = AnkiquestSharedRules.scheduler(Preset(fsrs, config.rwkvReviewEnabled, config.rwkvReviewInstantOrderEnabled))

    fun verdict(
        inputs: Inputs,
        state: State,
    ): Verdict = AnkiquestSharedRules.verdict(inputs, state)

    fun next(
        state: State,
        event: Event,
        nowMs: EpochMilliseconds,
        lastOptimizedMs: EpochMilliseconds?,
    ): State = AnkiquestSharedRules.next(state, event, nowMs, lastOptimizedMs)

    /**
     * When Optimize All Presets last ran: the end of the collection day [lastOptimizedDay], since
     * Anki records only the day. Null when it never ran.
     */
    fun optimizedAt(
        today: Int,
        dayCutoff: EpochSeconds,
        lastOptimizedDay: Int?,
    ): EpochMilliseconds? = AnkiquestSharedRules.optimizedAt(today, dayCutoff, lastOptimizedDay)

    /** Answers since [lastOptimizedMs], or every answer when it never ran. */
    fun reviewsSince(
        col: Collection,
        lastOptimizedMs: EpochMilliseconds?,
    ): Int = col.db.queryLongScalar(ANSWERS_SQL, lastOptimizedMs ?: 0L).toInt()

    /** When Optimize All Presets last ran in [col]'s collection, by [optimizedAt]. */
    private fun lastOptimized(col: Collection): EpochMilliseconds? {
        val day = col.config.get<Int>(LAST_OPTIMIZE_KEY)?.takeIf { it > 0 }
        return optimizedAt(col.sched.today, col.sched.dayCutoff, day)
    }

    /** Call inside CollectionManager.withCol, away from the main thread. */
    fun load(
        col: Collection,
        nowMs: EpochMilliseconds = TimeManager.time.intTimeMS(),
    ): Inputs {
        val last = lastOptimized(col)
        val configs = col.decks.getDeckConfigsForUpdate(col.decks.selected())
        val fsrs = if (configs.fsrs) Fsrs.ON else Fsrs.OFF
        return Inputs(
            nowMs = nowMs,
            lastOptimizedMs = last,
            reviewsSince = reviewsSince(col, last),
            presets = configs.allConfigList.map { scheduler(fsrs, it.config.config) },
        )
    }

    fun state(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): State = decode(prefs.getString(PREF_STATE, null))

    fun remember(
        state: State,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = prefs.edit { putString(PREF_STATE, encode(state).toString()) }

    /** The stored state; one that is missing or not exactly a state is on. */
    fun decode(stored: String?): State {
        if (stored == null) {
            return State.On
        }
        val json =
            try {
                JSONObject(stored)
            } catch (_: JSONException) {
                return State.On
            }
        return parse(json) ?: State.On
    }

    /**
     * [raw] as a state when it is one exactly as [encode] writes it, with times in epoch ms: the
     * form the server accepts for the synced setting. Null otherwise.
     */
    fun parse(raw: Any?): State? {
        val json = raw as? JSONObject ?: return null
        val kind = json.opt(KIND) as? String ?: return null
        val fields = json.keys().asSequence().toSet() - KIND
        return when (kind) {
            "on" -> State.On.takeIf { fields.isEmpty() }
            "off" -> State.Off.takeIf { fields.isEmpty() }
            "snoozed" -> ms(json, UNTIL_MS, 0)?.takeIf { fields == setOf(UNTIL_MS) }?.let { State.Snoozed(it) }
            "dismissed" -> {
                if (fields != setOf(OPTIMIZED_MS)) {
                    return null
                }
                if (json.isNull(OPTIMIZED_MS)) {
                    return State.Dismissed(null)
                }
                ms(json, OPTIMIZED_MS, 1)?.let { State.Dismissed(it) }
            }
            else -> null
        }
    }

    /** A whole-number time from [first]; null for any other value. */
    private fun ms(
        json: JSONObject,
        key: String,
        first: Long,
    ): EpochMilliseconds? {
        val value = json.opt(key)
        val ms = (value as? Int)?.toLong() ?: (value as? Long) ?: return null
        return ms.takeIf { it >= first }
    }

    fun encode(state: State): JSONObject =
        when (state) {
            State.On -> JSONObject().put(KIND, "on")
            State.Off -> JSONObject().put(KIND, "off")
            is State.Snoozed -> JSONObject().put(KIND, "snoozed").put(UNTIL_MS, state.untilMs)
            is State.Dismissed -> JSONObject().put(KIND, "dismissed").put(OPTIMIZED_MS, state.optimizedMs ?: JSONObject.NULL)
        }

    /** Applies [event] to the stored state, now and with the collection's last optimization. */
    suspend fun record(event: Event) {
        val last = withCol { lastOptimized(this) }
        remember(next(state(), event, TimeManager.time.intTimeMS(), last))
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
            // The rule's inputs and the deck whose options the reminder opens.
            val (inputs, deck) =
                try {
                    withCol { load(this) to decks.selected() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest optimize reminder could not read the collection")
                    return@launch
                } catch (e: LinkageError) {
                    // The shared rules' library did not load: no reminder, rather than no deck list.
                    Timber.w(e, "AnkiQuest optimize reminder could not load the shared rules")
                    return@launch
                }
            val verdict = verdict(inputs, state()) as? Verdict.Remind ?: return@launch
            activity.lifecycle.withResumed { show(activity, deck, verdict) }
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
        val since = Since.valueOf(arguments.getString(ARG_SINCE)!!)
        val wording = Wording.valueOf(arguments.getString(ARG_WORDING)!!)
        val deck = arguments.getLong(ARG_DECK)
        val scope = requireActivity().lifecycleScope
        return AlertDialog
            .Builder(context)
            .setTitle(R.string.aq_optimize_title)
            .setMessage(AnkiquestOptimizeReminder.message(context, since, wording))
            .setPositiveButton(R.string.aq_optimize_open) { _, _ ->
                startActivity(DeckOptions.getIntent(context, deck))
            }.setNeutralButton(R.string.aq_optimize_snooze) { _, _ ->
                scope.launch { AnkiquestOptimizeReminder.record(Event.SNOOZE) }
            }.setNegativeButton(R.string.aq_optimize_dismiss) { _, _ ->
                scope.launch { AnkiquestOptimizeReminder.record(Event.DISMISS) }
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
            verdict: Verdict.Remind,
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
