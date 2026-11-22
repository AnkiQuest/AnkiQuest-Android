// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

/**
 * Chibi companions: an Easter egg, like Android's developer mode. Tapping a companion quickly
 * enough times in a row turns it into its chibi, once the player has unlocked that companion.
 * The rule and the chibis are the shared fixture `tests/fixtures/companion_unlock.json` in
 * AnkiQuest/ankiquest (entries of kind `taps`); the app counts and only tells the server the
 * result (`POST /api/unlocks/{user}`).
 */
internal object AnkiquestChibi {
    /** Taps in a row that unlock a chibi: the fixture's `taps`. */
    const val TAPS = 9

    /** The longest gap between two taps of a run: the fixture's `max_gap_ms`. */
    const val MAX_GAP_MS = 2_000L

    /** The tap from which the player is told how many are left. */
    const val HINT_FROM = 4

    /** Whether a fixture companion can be earned yet; [id] is the fixture's `status`. */
    enum class Status(
        val id: String,
    ) {
        LIVE("live"),

        /** No art yet: the chibi stays hidden and taps on its base count nothing. */
        PLANNED("planned"),
    }

    /** The chibi [id] of the companion [base] (the fixture's `requires`). */
    data class Chibi(
        val id: String,
        val base: String,
        val status: Status,
    )

    /** Every chibi in the fixture, live or planned. */
    val ALL = listOf(Chibi(id = "anki_tan_chibi", base = "anki_tan", status = Status.PLANNED))

    /** What a tap leads to. */
    sealed interface Step {
        /** Nothing to show: not a chibi's unlocked base, or too early in the run for a hint. */
        data object Silent : Step

        /** [left] more taps unlock the chibi. */
        data class Hint(
            val left: Int,
        ) : Step

        /** This tap completed the run: [chibi] is unlocked. */
        data class Done(
            val chibi: Chibi,
        ) : Step
    }

    /**
     * Counts taps toward the chibi of each [chibis] entry (the live ones unless a test says
     * otherwise). Taps count only on a base the player has unlocked and whose chibi is still
     * locked; any other tap ends the run.
     */
    class Taps(
        private val chibis: List<Chibi> = ALL.filter { it.status == Status.LIVE },
    ) {
        private val run = TapRun()

        /** Records a tap on [companion] at [atMs], given the ids of the player's [unlocked] companions. */
        fun tap(
            companion: String,
            atMs: Long,
            unlocked: Set<String>,
        ): Step {
            // A locked base, an unlocked chibi or another companion counts nothing and ends the run.
            val chibi = chibis.firstOrNull { it.base == companion && it.base in unlocked && it.id !in unlocked }
            if (chibi == null) {
                run.reset()
                return Step.Silent
            }

            val count = run.tap(companion, atMs)
            if (count >= TAPS) {
                run.reset()
                return Step.Done(chibi)
            }
            return if (count >= HINT_FROM) Step.Hint(TAPS - count) else Step.Silent
        }
    }

    /**
     * The fixture's tap rule for one run: each tap on the same companion, not earlier than the one
     * before and at most [MAX_GAP_MS] after it, extends the run; any other tap starts a new one.
     */
    class TapRun {
        private sealed interface State {
            data object Idle : State

            data class Counting(
                val companion: String,
                val count: Int,
                val lastMs: Long,
            ) : State
        }

        private var state: State = State.Idle

        /** Records a tap on [companion] at [atMs]; answers how many taps the run now has. */
        fun tap(
            companion: String,
            atMs: Long,
        ): Int {
            val previous = state
            val count =
                if (previous is State.Counting && previous.companion == companion && quick(previous.lastMs, atMs)) {
                    previous.count + 1
                } else {
                    1
                }
            state = State.Counting(companion, count, atMs)
            return count
        }

        fun reset() {
            state = State.Idle
        }

        // With atMs not earlier, the true gap is 0 or more; a gap past Long.MAX_VALUE wraps negative
        // and so fails the range check instead of overflowing into it.
        private fun quick(
            lastMs: Long,
            atMs: Long,
        ) = atMs >= lastMs && atMs - lastMs in 0..MAX_GAP_MS
    }
}
