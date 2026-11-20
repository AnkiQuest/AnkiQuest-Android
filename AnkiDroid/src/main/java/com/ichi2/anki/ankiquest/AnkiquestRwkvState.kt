// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/**
 * Why the RWKV scheduler cannot run (#433), in the order [RwkvChecks] tests them: a check that
 * fails hides the ones after it.
 */
internal enum class RwkvBlocker {
    /** This build has no RWKV engine. The engine needs a forked Anki backend that does not exist yet. */
    NO_ENGINE,

    /** The engine has no native library for any of this phone's ABIs. */
    UNSUPPORTED_ABI,

    /** The phone has too little RAM for this collection's model state; see [RwkvMemory]. */
    NOT_ENOUGH_MEMORY,

    /** No model file is chosen. The weights have no redistributable licence, so the app cannot bundle one. */
    NO_MODEL,
}

/** The player's RWKV switch in AnkiQuest's settings. Off by default. */
internal enum class RwkvSwitch {
    ON,
    OFF,
}

/**
 * The RWKV scheduler on this phone. Only [Ready] may change scheduling; [Off] and [Unavailable]
 * leave Anki's own scheduler (FSRS or SM-2) in charge. With [NoRwkvEngine] the state never reaches
 * [Ready].
 */
internal sealed interface RwkvState {
    /** The player has not turned it on. */
    data object Off : RwkvState

    /** Turned on, but [reason] stops it from running. */
    data class Unavailable(
        val reason: RwkvBlocker,
    ) : RwkvState

    /** Turned on and every check passed. */
    data object Ready : RwkvState

    /** The player turns it on: the checks decide whether it can run. */
    fun enable(checks: RwkvChecks): RwkvState = checks.verdict()

    /** The player turns it off. */
    fun disable(): RwkvState = Off

    /** Runs the checks again, as when the settings open or a model is chosen. [Off] stays off. */
    fun recheck(checks: RwkvChecks): RwkvState =
        when (this) {
            Off -> Off
            is Unavailable, Ready -> checks.verdict()
        }

    companion object {
        /** The state for the saved [switch], as when the app or the settings screen starts. */
        fun restore(
            switch: RwkvSwitch,
            checks: RwkvChecks,
        ): RwkvState =
            when (switch) {
                RwkvSwitch.ON -> Off.enable(checks)
                RwkvSwitch.OFF -> Off
            }
    }
}

/** What the RWKV checks look at, read from the phone and the collection by the caller. */
internal data class RwkvChecks(
    val engine: RwkvEngine,
    val deviceAbis: List<String>,
    val totalRamBytes: Long,
    val cards: Int,
    val model: RwkvModel?,
) {
    /** [RwkvState.Ready], or [RwkvState.Unavailable] with the first check that fails. */
    fun verdict(): RwkvState {
        val reason = blocker() ?: return RwkvState.Ready
        return RwkvState.Unavailable(reason)
    }

    private fun blocker(): RwkvBlocker? {
        if (engine.availability == RwkvEngine.Availability.ABSENT) {
            return RwkvBlocker.NO_ENGINE
        }
        if (deviceAbis.none { it in engine.abis }) {
            return RwkvBlocker.UNSUPPORTED_ABI
        }
        if (!RwkvMemory.fits(totalRamBytes, cards)) {
            return RwkvBlocker.NOT_ENOUGH_MEMORY
        }
        if (model == null) {
            return RwkvBlocker.NO_MODEL
        }
        return null
    }
}

/**
 * Whether the phone has RAM for the RWKV state of a collection. The only published phone figure
 * is kuma3 14 (#433): the int8 Small model on a Pixel 8a used 505 MB of RAM for a 22,000-card
 * collection. State grows per card and note, so the estimate scales that figure by card count.
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
     * The scheduler may use at most this fraction (1/n) of the phone's RAM. Not measured: Android
     * kills background apps and then the foreground app as free memory runs out, and Anki and the
     * reviewer need room beside the model. An eighth keeps 22,000 cards off 4 GB phones.
     */
    const val RAM_SHARE = 8L

    /** Estimated RAM for a collection of [cards]. */
    fun needed(cards: Int): Long = cards.coerceAtLeast(0) * BYTES_PER_CARD

    fun fits(
        totalRamBytes: Long,
        cards: Int,
    ): Boolean = needed(cards) * RAM_SHARE <= totalRamBytes
}
