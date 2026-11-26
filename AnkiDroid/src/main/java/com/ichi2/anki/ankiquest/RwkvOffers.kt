// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/** A download under way or finished badly, which the settings remember for the dialog's next showing. */
internal sealed interface RwkvAttempt {
    /** The file is downloaded and its size and SHA-256 are being checked. */
    data object Verifying : RwkvAttempt

    /** The last download or import of the entry was not kept, because of [error]. */
    data class Failed(
        val error: RwkvImportError,
    ) : RwkvAttempt
}

/** What the Download list shows for one lock entry. Only [Fits] and [Failed] can be tapped. */
internal sealed interface RwkvOfferState {
    /** Not stored, and this phone has room for it. */
    data object Fits : RwkvOfferState

    /** This phone allows [allowedBytes] for the scheduler, less than the [neededBytes] the entry needs here. */
    data class TooBig(
        val neededBytes: Long,
        val allowedBytes: Long,
    ) : RwkvOfferState

    /** Stored and checked by size. */
    data object Downloaded : RwkvOfferState

    /** Downloaded, its size and SHA-256 being checked. */
    data object Verifying : RwkvOfferState

    /** Fits, but the last attempt was not kept because of [error]; tapping it tries again. */
    data class Failed(
        val error: RwkvImportError,
    ) : RwkvOfferState
}

/** Whether an entry is the one the list recommends. */
internal enum class RwkvPick {
    RECOMMENDED,
    OTHER,
}

/** One row of the Download list: a lock entry, the RAM it needs for this collection, its state and whether it is recommended. */
internal data class RwkvOffer(
    val entry: RwkvLockEntry,
    val neededBytes: Long,
    val state: RwkvOfferState,
    val pick: RwkvPick,
) {
    /** Whether tapping the row downloads the entry. */
    val tappable: Boolean get() = state is RwkvOfferState.Fits || state is RwkvOfferState.Failed
}

/** The phone and collection an offer is computed for. */
internal data class RwkvDevice(
    val totalRamBytes: Long,
    val collection: RwkvCollection,
)

/** The Download list and the phone and collection it was computed for. */
internal data class RwkvOfferList(
    val device: RwkvDevice,
    val offers: List<RwkvOffer>,
)

/**
 * The Download list: every model in the lock, with the memory it needs on this collection, greyed
 * out where [RwkvMemory] says the phone has too little, and the most accurate model that fits marked
 * as recommended.
 */
internal object RwkvOffers {
    /** One offer per entry of [lock], biggest size first and two-head before Instant-only within a size. */
    fun list(
        device: RwkvDevice,
        stored: List<RwkvLockEntry>,
        attempts: Map<String, RwkvAttempt>,
        lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
    ): List<RwkvOffer> {
        val recommended = recommend(device, lock)
        return lock
            .sortedWith(compareBy({ it.size.ordinal }, { it.head.ordinal }))
            .map { entry ->
                val needed = RwkvMemory.needed(runsWith(entry, lock), device.collection)
                val pick = if (entry == recommended) RwkvPick.RECOMMENDED else RwkvPick.OTHER
                RwkvOffer(entry, needed, state(entry, needed, device, stored, attempts[entry.id]), pick)
            }
    }

    /**
     * The most accurate two-head model that fits: the lowest of the release's own LogLoss scores.
     * Only two-head models are compared: the engine needs one, and an Instant-only model is scored on
     * another head. Null when none fits.
     */
    fun recommend(
        device: RwkvDevice,
        lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
    ): RwkvLockEntry? =
        lock
            .filter { it.head == RwkvHead.TWO_HEAD }
            .filter { RwkvMemory.fits(device.totalRamBytes, runsWith(it, lock), device.collection) }
            .minByOrNull { it.logLoss }

    /**
     * The models running when [entry] does: a two-head model alone, an Instant-only model beside its
     * size's two-head model, as the engine runs them when presets use both schedulers.
     */
    fun runsWith(
        entry: RwkvLockEntry,
        lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
    ): List<RwkvLockEntry> {
        if (entry.head == RwkvHead.TWO_HEAD) {
            return listOf(entry)
        }
        val twoHead = lock.firstOrNull { it.size == entry.size && it.head == RwkvHead.TWO_HEAD }
        return listOfNotNull(twoHead, entry)
    }

    private fun state(
        entry: RwkvLockEntry,
        needed: Long,
        device: RwkvDevice,
        stored: List<RwkvLockEntry>,
        attempt: RwkvAttempt?,
    ): RwkvOfferState {
        // A check under way and a stored file come first: they say what happened to this entry.
        if (attempt == RwkvAttempt.Verifying) {
            return RwkvOfferState.Verifying
        }
        if (entry in stored) {
            return RwkvOfferState.Downloaded
        }

        // An entry that does not fit cannot be tried again, whatever its last attempt.
        if (!RwkvMemory.fits(device.totalRamBytes, needed)) {
            return RwkvOfferState.TooBig(needed, RwkvMemory.allowed(device.totalRamBytes))
        }
        if (attempt is RwkvAttempt.Failed) {
            return RwkvOfferState.Failed(attempt.error)
        }
        return RwkvOfferState.Fits
    }
}
