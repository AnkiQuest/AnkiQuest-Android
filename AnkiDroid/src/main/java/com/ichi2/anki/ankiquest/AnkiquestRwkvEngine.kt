// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/**
 * The seam a future RWKV engine plugs into (#433). The engine needs a forked Anki backend
 * (kuma3-anki-core's `RwkvPrepareOffline` and scoring RPCs); until one exists the app uses
 * [NoRwkvEngine]. No Android types, so the scheduler logic stays testable on the JVM.
 */
internal interface RwkvEngine {
    /** Whether this build contains the engine. */
    enum class Availability {
        PRESENT,
        ABSENT,
    }

    val availability: Availability

    /** The ABIs the engine has native libraries for, as in `Build.SUPPORTED_ABIS`. */
    val abis: Set<String>

    /**
     * Replays [reviews], oldest first, into fresh model state for [model]. Needed on first use,
     * after a model change and after a sync brings reviews from other clients.
     */
    suspend fun rebuild(
        model: RwkvModel,
        reviews: Sequence<RwkvReview>,
    )

    /** Predicted retrievability now, from 0 to 1, for each of [cardIds] that has model state. */
    suspend fun score(cardIds: List<Long>): Map<Long, Double>
}

/** One revlog row as the engine replays it. */
internal data class RwkvReview(
    val id: Long,
    val cardId: Long,
    val noteId: Long,
    val ease: Int,
    val type: Int,
    val takenMs: Long,
)

/** A model file the player chose. The app cannot bundle weights: they have no redistributable licence. */
internal data class RwkvModel(
    val path: String,
)

/** Where the chosen model file comes from. A file picker replaces [NoRwkvModel] later. */
internal fun interface RwkvModelSource {
    fun chosen(): RwkvModel?
}

/** No model file can be chosen yet. */
internal object NoRwkvModel : RwkvModelSource {
    override fun chosen(): RwkvModel? = null
}

/** The engine in this build: none. [RwkvChecks] stops at [RwkvBlocker.NO_ENGINE], so nothing calls it. */
internal object NoRwkvEngine : RwkvEngine {
    private const val ABSENT = "This build has no RWKV engine"

    override val availability = RwkvEngine.Availability.ABSENT
    override val abis = emptySet<String>()

    override suspend fun rebuild(
        model: RwkvModel,
        reviews: Sequence<RwkvReview>,
    ): Unit = throw UnsupportedOperationException(ABSENT)

    override suspend fun score(cardIds: List<Long>): Map<Long, Double> = throw UnsupportedOperationException(ABSENT)
}
