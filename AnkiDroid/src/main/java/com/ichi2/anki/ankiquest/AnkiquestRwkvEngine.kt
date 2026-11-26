// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.CollectionManager.withCol

/**
 * The RWKV engine: the backend's offline RWKV layer (AnkiQuest-Anki `rslib/src/scheduler/rwkv/offline.rs`,
 * kuma3's). Once [prepare] has handed it the models, the backend replays the review history on its
 * own thread and from then on scores review cards itself whenever a review queue or the deck counts
 * are built, and answers the stats panel's forecast. A seam so the transitions are testable without
 * a backend.
 */
internal interface RwkvEngine {
    /** Hands the backend [models]; cheap when nothing changed. Returns the reviews being replayed. Throws when the backend refuses them. */
    suspend fun prepare(models: RwkvModels): Long

    /** The backend's progress, which also installs a finished replay. */
    suspend fun progress(): RwkvProgress

    /** Drops the loaded model at once; the presets' fallback schedulers take over. Does nothing without one. */
    suspend fun unload()
}

/** Where the backend's model state is. */
internal sealed interface RwkvProgress {
    /** Not prepared, or the build stopped (its total stays 0). */
    data object Unavailable : RwkvProgress

    /** The review history is being replayed: [done] of [total] reviews. Until then the standard order applies. */
    data class Replaying(
        val done: Long,
        val total: Long,
    ) : RwkvProgress

    /** The model state is installed: Instant presets are ordered by predicted recall. */
    data object Running : RwkvProgress
}

/** The engine in the pinned backend, called as kuma3's `RwkvOffline.kt` calls it. */
internal object BackendRwkvEngine : RwkvEngine {
    /** Every model of the family uses the leak-free input rule that `"sm72"` selects. */
    private const val LEAK_FREE_KIND = "sm72"

    /** The backend's default state precision: int8, as the fork's desktop default and kuma3's. */
    private const val DEFAULT_PRECISION = ""

    private const val NO_INSTANT_MODEL = ""

    override suspend fun prepare(models: RwkvModels): Long {
        val replayed =
            withCol {
                backend.rwkvPrepareOffline(
                    modelPath = models.twoHead.file.absolutePath,
                    statePrecision = DEFAULT_PRECISION,
                    modelKind = LEAK_FREE_KIND,
                    instantModelPath = models.instant?.file?.absolutePath ?: NO_INSTANT_MODEL,
                    singleModel = false,
                )
            }
        return replayed
    }

    override suspend fun progress(): RwkvProgress {
        val status = withCol { backend.rwkvOfflineInstantPassStep(restart = false, statusOnly = true) }
        if (status.available) {
            return RwkvProgress.Running
        }
        if (status.replayTotal == 0L) {
            return RwkvProgress.Unavailable
        }
        return RwkvProgress.Replaying(status.replayDone, status.replayTotal)
    }

    /** `RwkvUnloadOffline`: the backend forgets the model, its state and its scores; the state file stays for the next start. */
    override suspend fun unload() {
        withCol { backend.rwkvUnloadOffline() }
    }
}
