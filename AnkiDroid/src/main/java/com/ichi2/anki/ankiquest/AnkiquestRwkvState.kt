// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * Why the RWKV scheduler cannot run (#433). [RwkvChecks] tests the first two in this order, and a
 * check that fails hides the ones after it; [BACKEND_FAILED] comes from the engine once they pass.
 */
internal enum class RwkvBlocker {
    /** No verified two-head model is imported. The weights have no redistributable licence, so the app ships none. */
    NO_MODEL,

    /** The phone has too little RAM for the imported models' state on this collection; see [RwkvMemory]. */
    NOT_ENOUGH_MEMORY,

    /** The backend refused the models (`RwkvPrepareOffline` failed). */
    BACKEND_FAILED,
}

/** The player's RWKV switch in AnkiQuest's settings. Off by default. */
internal enum class RwkvSwitch {
    ON,
    OFF,
}

/**
 * The RWKV scheduler on this phone. In [Ready] the backend orders the review queues of presets set
 * to RWKV-Instant and gives RWKV-Curve presets their intervals; [Off] and [Refused] leave Anki's own
 * scheduler (FSRS or SM-2) in charge: the engine drops its model in every state but [Ready].
 *
 * A blocked scheduler is refused rather than left on (#442): turning it on while a check fails
 * keeps the switch off and reports why.
 */
internal sealed interface RwkvState {
    /** The switch this state shows and saves. */
    val switch: RwkvSwitch

    /** The player has not turned it on, or turned it off. */
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

    /** The player turns it on: [Ready] if every check passes, otherwise [Refused] with the first failure. */
    fun enable(checks: RwkvChecks): RwkvState = checks.verdict()

    /** The player turns it off. */
    fun disable(): RwkvState = Off

    /** The backend refused the models: a [Ready] scheduler is refused; the off states stay. */
    fun fail(): RwkvState =
        when (this) {
            Ready -> Refused(RwkvBlocker.BACKEND_FAILED)
            Off, is Refused -> this
        }

    companion object {
        /** The state for the saved [switch], as when the app starts or the settings open. A saved "on" that is now blocked is refused. */
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

/** The collection the model state is kept for: one state per card and one per note. */
internal data class RwkvCollection(
    val cards: Int,
    val notes: Int,
)

/** What the RWKV checks look at, read from the phone, the collection and the model store by the caller. */
internal data class RwkvChecks(
    val totalRamBytes: Long,
    val collection: RwkvCollection,
    val models: RwkvModels?,
) {
    /** [RwkvState.Ready], or [RwkvState.Refused] with the first check that fails. */
    fun verdict(): RwkvState {
        val reason = blocker() ?: return RwkvState.Ready
        return RwkvState.Refused(reason)
    }

    private fun blocker(): RwkvBlocker? {
        // The memory a model needs depends on its size, so the model is looked at first.
        val models = models ?: return RwkvBlocker.NO_MODEL
        val entries = listOfNotNull(models.twoHead.entry, models.instant?.entry)
        if (!RwkvMemory.fits(totalRamBytes, entries, collection)) {
            return RwkvBlocker.NOT_ENOUGH_MEMORY
        }
        return null
    }
}

/** One stream of a model's per-entity state: its layers, and how many of them have a channel mixer. */
internal data class RwkvStream(
    val layers: Int,
    val mixers: Int,
)

/** The dimensions that size a model's card and note state, from the release's `architecture.json`. */
internal data class RwkvShape(
    val dModel: Int,
    val headSize: Int,
    val card: RwkvStream,
    val note: RwkvStream,
) {
    /** One card's state, int8, in the backend's layout. */
    val cardBytes: Long get() = bytes(card)

    /** One note's state, int8, in the backend's layout. */
    val noteBytes: Long get() = bytes(note)

    /**
     * The backend's int8 layout (AnkiQuest-Anki `rslib/src/rwkv/precision.rs`, as the release's
     * INTEGRATION.md section 8.4 gives it): a header, then per layer three length fields, the time
     * shift as float16, the WKV matrix as d_model rows of a float32 scale and head-size int8 codes,
     * and the channel shift as float16 in layers with a channel mixer.
     */
    private fun bytes(stream: RwkvStream): Long {
        val shift = F16_BYTES * dModel
        val matrix = dModel.toLong() * (SCALE_BYTES + headSize)
        val layer = LENGTH_FIELDS * LENGTH_BYTES + shift + matrix
        return HEADER_BYTES + stream.layers * layer + stream.mixers.toLong() * shift
    }

    private companion object {
        const val HEADER_BYTES = 19L
        const val LENGTH_FIELDS = 3
        const val LENGTH_BYTES = 4
        const val F16_BYTES = 2
        const val SCALE_BYTES = 4
    }
}

/**
 * The RAM the RWKV models need for a collection, as the release's INTEGRATION.md section 11.1
 * computes it: a fixed part, the weights, and a card and note state for each model. The formula
 * lands within 28 MB of the fork's measured RAM for the big model; kuma3 14's phone figure (505 MB
 * for 22,000 cards, #433) is the big model's too. The backend keeps int8 state, its default.
 */
internal object RwkvMemory {
    /** The release's mean non-state memory of the fork's four measurements (101 to 144 MB). */
    const val FIXED_BYTES = 129_000_000L

    /**
     * The scheduler may use at most this fraction (1/n) of the phone's RAM: a quarter (#442). Not
     * measured; Anki and the reviewer need room beside the model state.
     */
    const val RAM_SHARE = 4L

    /** RAM for running [models] side by side on [collection]: one fixed part, and each model's weights and state. */
    fun needed(
        models: List<RwkvLockEntry>,
        collection: RwkvCollection,
    ): Long {
        val cards = collection.cards.coerceAtLeast(0).toLong()
        val notes = collection.notes.coerceAtLeast(0).toLong()
        return FIXED_BYTES + models.sumOf { it.bytes + cards * it.size.shape.cardBytes + notes * it.size.shape.noteBytes }
    }

    /** The most the scheduler may use on a phone with [totalRamBytes]. */
    fun allowed(totalRamBytes: Long): Long = totalRamBytes / RAM_SHARE

    fun fits(
        totalRamBytes: Long,
        models: List<RwkvLockEntry>,
        collection: RwkvCollection,
    ): Boolean = fits(totalRamBytes, needed(models, collection))

    /** Whether [neededBytes] is within [RAM_SHARE] of [totalRamBytes]; the boundary is inclusive. */
    fun fits(
        totalRamBytes: Long,
        neededBytes: Long,
    ): Boolean = neededBytes * RAM_SHARE <= totalRamBytes
}
