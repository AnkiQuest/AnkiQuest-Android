// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import anki.deck_config.DeckConfig
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Event
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Fsrs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Inputs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Quiet
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Scheduler
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Since
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.State
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Verdict
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Wording
import com.ichi2.anki.libanki.EpochMilliseconds
import com.ichi2.anki.libanki.EpochSeconds
import org.ankiquest.rules.OptimizeEvent
import org.ankiquest.rules.OptimizeInputs
import org.ankiquest.rules.OptimizeSince
import org.ankiquest.rules.OptimizeState
import org.ankiquest.rules.OptimizeVerdict
import org.ankiquest.rules.OptimizeWording
import org.ankiquest.rules.PresetFlags
import org.ankiquest.rules.PresetScheduler
import org.ankiquest.rules.optimizeNext
import org.ankiquest.rules.optimizeVerdict
import org.ankiquest.rules.optimizedAtMs
import org.ankiquest.rules.presetScheduler

/**
 * The rules of AnkiQuest-Server's `crates/rules`, the ones the server and the desktop add-on
 * compute too, through their UniFFI bindings (`org.ankiquest.rules`, from the newest rules
 * release; see docs/ankiquest-server-data.md). The only code that imports the bindings: it turns
 * the app's types into theirs and back, so the rest of the app never sees them.
 */
internal object AnkiquestSharedRules {
    /** The scheduler of a preset with [config], as Optimize All Presets sees it. */
    fun scheduler(
        fsrs: Fsrs,
        config: DeckConfig.Config,
    ): Scheduler {
        val flags =
            PresetFlags(
                fsrs = fsrs == Fsrs.ON,
                rwkvReview = config.rwkvReviewEnabled,
                rwkvInstantOrder = config.rwkvReviewInstantOrderEnabled,
            )
        return when (presetScheduler(flags)) {
            PresetScheduler.SM2 -> Scheduler.SM2
            PresetScheduler.FSRS -> Scheduler.FSRS
            PresetScheduler.RWKV_CURVE -> Scheduler.RWKV_CURVE
            PresetScheduler.RWKV_INSTANT -> Scheduler.RWKV_INSTANT
        }
    }

    /** When Optimize All Presets last ran, from the collection day it records; null when never. */
    fun optimizedAt(
        today: Int,
        dayCutoff: EpochSeconds,
        lastOptimizedDay: Int?,
    ): EpochMilliseconds? = optimizedAtMs(today, dayCutoff, lastOptimizedDay)

    /** Whether to remind the player now, and if not, why not. */
    fun verdict(
        inputs: Inputs,
        state: State,
    ): Verdict {
        val shared =
            OptimizeInputs(
                nowMs = inputs.nowMs,
                lastOptimizedMs = inputs.lastOptimizedMs,
                reviewsSince = inputs.reviewsSince.coerceAtLeast(0).toUInt(),
                presets = inputs.presets.map(::preset),
            )
        return when (val verdict = optimizeVerdict(shared, shared(state))) {
            OptimizeVerdict.Off -> Verdict.Silent(Quiet.OFF)
            OptimizeVerdict.NoFsrs -> Verdict.Silent(Quiet.NO_FSRS)
            OptimizeVerdict.Snoozed -> Verdict.Silent(Quiet.SNOOZED)
            OptimizeVerdict.Dismissed -> Verdict.Silent(Quiet.DISMISSED)
            OptimizeVerdict.TooSoon -> Verdict.Silent(Quiet.TOO_SOON)
            OptimizeVerdict.TooFewReviews -> Verdict.Silent(Quiet.TOO_FEW_REVIEWS)
            is OptimizeVerdict.Remind -> Verdict.Remind(since(verdict.since), wording(verdict.wording))
        }
    }

    /** The reminder's state after [event]. */
    fun next(
        state: State,
        event: Event,
        nowMs: EpochMilliseconds,
        lastOptimizedMs: EpochMilliseconds?,
    ): State {
        val shared =
            when (event) {
                Event.SNOOZE -> OptimizeEvent.SNOOZE
                Event.DISMISS -> OptimizeEvent.DISMISS
                Event.TURN_OFF -> OptimizeEvent.TURN_OFF
                Event.TURN_ON -> OptimizeEvent.TURN_ON
            }
        return state(optimizeNext(shared(state), shared, nowMs, lastOptimizedMs))
    }

    private fun preset(scheduler: Scheduler): PresetScheduler =
        when (scheduler) {
            Scheduler.SM2 -> PresetScheduler.SM2
            Scheduler.FSRS -> PresetScheduler.FSRS
            Scheduler.RWKV_CURVE -> PresetScheduler.RWKV_CURVE
            Scheduler.RWKV_INSTANT -> PresetScheduler.RWKV_INSTANT
        }

    private fun shared(state: State): OptimizeState =
        when (state) {
            State.On -> OptimizeState.On
            State.Off -> OptimizeState.Off
            is State.Snoozed -> OptimizeState.Snoozed(state.untilMs)
            is State.Dismissed -> OptimizeState.Dismissed(state.optimizedMs)
        }

    private fun state(state: OptimizeState): State =
        when (state) {
            OptimizeState.On -> State.On
            OptimizeState.Off -> State.Off
            is OptimizeState.Snoozed -> State.Snoozed(state.untilMs)
            is OptimizeState.Dismissed -> State.Dismissed(state.optimizedMs)
        }

    private fun since(since: OptimizeSince): Since =
        when (since) {
            OptimizeSince.STALE -> Since.STALE
            OptimizeSince.NEVER -> Since.NEVER
        }

    private fun wording(wording: OptimizeWording): Wording =
        when (wording) {
            OptimizeWording.FSRS -> Wording.FSRS
            OptimizeWording.RWKV -> Wording.RWKV
            OptimizeWording.MIXED -> Wording.MIXED
        }
}
