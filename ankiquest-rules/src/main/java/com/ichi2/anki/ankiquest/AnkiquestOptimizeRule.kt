// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * What the optimize reminder's rule (`optimize_verdict`, `optimize_next`, `preset_scheduler` and
 * `optimized_at_ms` in AnkiQuest-Server's `crates/rules`) reads and answers, in the app's own types;
 * [AnkiquestSharedRules] computes it. Times are epoch milliseconds, never collection day numbers:
 * collections on different devices can number their days differently.
 */
object AnkiquestOptimizeRule {
    enum class Scheduler { SM2, FSRS, RWKV_CURVE, RWKV_INSTANT }

    /** Whether the collection uses FSRS; without it every preset is SM-2. */
    enum class Fsrs { ON, OFF }

    /** What a preset's options say about its scheduler, as Optimize All Presets sees them. */
    data class Preset(
        val fsrs: Fsrs,
        val rwkvReview: Boolean,
        val rwkvInstantOrder: Boolean,
    )

    /** The reminder on every device: on, off by the settings switch, snoozed or dismissed. */
    sealed interface State {
        data object On : State

        data object Off : State

        /** Hidden while now is before [untilMs]. */
        data class Snoozed(
            val untilMs: Long,
        ) : State

        /**
         * Hidden until Optimize All Presets runs again: until a run a day or more after
         * [optimizedMs], the last one when it was dismissed (null: it had never run).
         */
        data class Dismissed(
            val optimizedMs: Long?,
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

    /** What the rule reads from the collection, and the time now. */
    data class Inputs(
        val nowMs: Long,
        val lastOptimizedMs: Long?,
        val reviewsSince: Int,
        val presets: List<Scheduler>,
    )
}
