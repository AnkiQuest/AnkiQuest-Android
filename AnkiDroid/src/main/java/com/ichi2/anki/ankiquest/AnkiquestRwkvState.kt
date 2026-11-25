// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * Why the RWKV scheduler cannot run (#433). [RwkvChecks] tests the first two in this order, and a
 * check that fails hides the ones after it; [BACKEND_FAILED] comes from the engine once they pass.
 */
internal enum class RwkvBlocker {
    /** The phone has too little RAM for this collection's model state; see [RwkvMemory]. */
    NOT_ENOUGH_MEMORY,

    /** No verified two-head model is imported. The weights have no redistributable licence, so the app ships none. */
    NO_MODEL,

    /** The backend refused the models (`RwkvPrepareOffline` failed). */
    BACKEND_FAILED,
}

/** The player's RWKV switch in AnkiQuest's settings. Off by default. */
internal enum class RwkvSwitch {
    ON,
    OFF,
}

/**
 * Whether this process handed the backend a model. The backend keeps a model it was given until the
 * process ends: it has no call to drop one.
 */
internal enum class RwkvLoad {
    LOADED,
    NOT_LOADED,
}

/**
 * The RWKV scheduler on this phone. In [Ready] the backend orders the review queues of presets set
 * to RWKV-Instant and gives RWKV-Curve presets their intervals; [Off] and [Refused] leave Anki's own
 * scheduler (FSRS or SM-2) in charge. [Stopping] is off, but a model loaded earlier in this process
 * keeps scheduling until the app restarts.
 *
 * A blocked scheduler is refused rather than left on (#442): turning it on while a check fails
 * keeps the switch off and reports why.
 */
internal sealed interface RwkvState {
    /** The switch this state shows and saves. */
    val switch: RwkvSwitch

    /** The player has not turned it on, or turned it off, and no model is loaded. */
    data object Off : RwkvState {
        override val switch = RwkvSwitch.OFF
    }

    /** Off: the last attempt to turn it on, or a check of a saved "on", failed because of [reason]. */
    data class Refused(
        val reason: RwkvBlocker,
    ) : RwkvState {
        override val switch = RwkvSwitch.OFF
    }

    /** On: every check passed, and the backend accepted the models. */
    data object Ready : RwkvState {
        override val switch = RwkvSwitch.ON
    }

    /** Off, but the model loaded in this process schedules until the app restarts. */
    data object Stopping : RwkvState {
        override val switch = RwkvSwitch.OFF
    }

    /** The player turns it on: [Ready] if every check passes, otherwise [Refused] with the first failure. */
    fun enable(checks: RwkvChecks): RwkvState = checks.verdict()

    /** The player turns it off. */
    fun disable(load: RwkvLoad): RwkvState =
        when (load) {
            RwkvLoad.LOADED -> Stopping
            RwkvLoad.NOT_LOADED -> Off
        }

    /** The backend refused the models: a [Ready] scheduler is refused; the off states stay. */
    fun fail(): RwkvState =
        when (this) {
            Ready -> Refused(RwkvBlocker.BACKEND_FAILED)
            Off, Stopping, is Refused -> this
        }

    companion object {
        /** The state for the saved [switch], as when the app starts or the settings open. A saved "on" that is now blocked is refused. */
        fun restore(
            switch: RwkvSwitch,
            checks: RwkvChecks,
            load: RwkvLoad,
        ): RwkvState =
            when (switch) {
                RwkvSwitch.ON -> Off.enable(checks)
                RwkvSwitch.OFF -> Off.disable(load)
            }
    }
}

/** What the RWKV checks look at, read from the phone, the collection and the model store by the caller. */
internal data class RwkvChecks(
    val totalRamBytes: Long,
    val cards: Int,
    val models: RwkvModels?,
) {
    /** [RwkvState.Ready], or [RwkvState.Refused] with the first check that fails. */
    fun verdict(): RwkvState {
        val reason = blocker() ?: return RwkvState.Ready
        return RwkvState.Refused(reason)
    }

    private fun blocker(): RwkvBlocker? {
        if (!RwkvMemory.fits(totalRamBytes, cards)) {
            return RwkvBlocker.NOT_ENOUGH_MEMORY
        }
        if (models == null) {
            return RwkvBlocker.NO_MODEL
        }
        return null
    }
}

/**
 * Whether the phone has RAM for the RWKV state of a collection. The only published phone figure
 * is kuma3 14 (#433): the int8 Small model on a Pixel 8a used 505 MB of RAM for a 22,000-card
 * collection. State grows per card and note, so the estimate scales that figure by card count.
 * The app accepts only Small and Tiny models ([RwkvLock]), for which this figure holds.
 */
internal object RwkvMemory {
    private const val MIB = 1024L * 1024L

    /** kuma3 14's RAM use, read as MiB so the estimate errs high. */
    const val MEASURED_BYTES = 505L * MIB

    /** The collection size kuma3 14 measured with. */
    const val MEASURED_CARDS = 22_000L

    /** RAM per card, rounded up: about 24 KB. */
    const val BYTES_PER_CARD = (MEASURED_BYTES + MEASURED_CARDS - 1) / MEASURED_CARDS

    /**
     * The scheduler may use at most this fraction (1/n) of the phone's RAM: a quarter (#442). Not
     * measured; Anki and the reviewer need room beside the model state. A quarter allows kuma3's
     * 22,000 cards on a 4 GB phone but not on a 2 GB one.
     */
    const val RAM_SHARE = 4L

    /** Estimated RAM for a collection of [cards]. */
    fun needed(cards: Int): Long = cards.coerceAtLeast(0) * BYTES_PER_CARD

    fun fits(
        totalRamBytes: Long,
        cards: Int,
    ): Boolean = needed(cards) * RAM_SHARE <= totalRamBytes
}
