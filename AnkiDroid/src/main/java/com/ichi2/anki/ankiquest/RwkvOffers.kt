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

/** What the Download list shows for one lock entry. [Fits], [TooBigOverridden] and [Failed] can be tapped. */
internal sealed interface RwkvOfferState {
    /** Not stored, and this phone has room for it. */
    data object Fits : RwkvOfferState

    /** This phone allows [allowedBytes] for the scheduler, less than the [neededBytes] the entry needs here. */
    data class TooBig(
        val neededBytes: Long,
        val allowedBytes: Long,
    ) : RwkvOfferState

    /** As [TooBig], but the player allowed the entry anyway: it can be downloaded and started. */
    data class TooBigOverridden(
        val neededBytes: Long,
        val allowedBytes: Long,
    ) : RwkvOfferState

    /** Stored and checked by size. */
    data object Downloaded : RwkvOfferState

    /** Downloaded, its size and SHA-256 being checked. */
    data object Verifying : RwkvOfferState

    /** The last attempt was not kept because of [error]; tapping it tries again. */
    data class Failed(
        val error: RwkvImportError,
    ) : RwkvOfferState
}

/** Whether an entry is the one the list recommends. */
internal enum class RwkvPick {
    RECOMMENDED,
    OTHER,
}

/** One row of the Download list: a lock entry, how it fits this phone, its state and whether it is recommended. */
internal data class RwkvOffer(
    val entry: RwkvLockEntry,
    val fit: RwkvFit,
    val state: RwkvOfferState,
    val pick: RwkvPick,
) {
    /** The RAM the entry needs for this collection. */
    val neededBytes: Long get() = fit.neededBytes

    /** Whether tapping the row downloads the entry. */
    val tappable: Boolean
        get() =
            when (state) {
                RwkvOfferState.Fits, is RwkvOfferState.TooBigOverridden, is RwkvOfferState.Failed -> true
                is RwkvOfferState.TooBig, RwkvOfferState.Downloaded, RwkvOfferState.Verifying -> false
            }
}

/** The phone and collection an offer is computed for. */
internal data class RwkvDevice(
    val totalRamBytes: Long,
    val collection: RwkvCollection,
)

/** The Download list, and what it was computed from, so a changed override recomputes it at once. */
internal data class RwkvOfferList(
    val device: RwkvDevice,
    val stored: List<RwkvLockEntry>,
    val attempts: Map<String, RwkvAttempt>,
    val overrides: Set<String>,
) {
    val offers: List<RwkvOffer> = RwkvOffers.list(device, stored, attempts, overrides)

    /** The list with [entry]'s override set to [override]. */
    fun with(
        entry: RwkvLockEntry,
        override: RwkvOverride,
    ): RwkvOfferList =
        copy(
            overrides =
                when (override) {
                    RwkvOverride.ALLOWED -> overrides + entry.id
                    RwkvOverride.NOT_ALLOWED -> overrides - entry.id
                },
        )
}

/**
 * The Download list: every model in the lock, with the memory it needs on this collection, greyed
 * out where [RwkvMemory] says the phone has too little unless the player overrode it, and the most
 * accurate model that fits marked as recommended.
 */
internal object RwkvOffers {
    /** One offer per entry of [lock], biggest size first and two-head before Instant-only within a size. */
    fun list(
        device: RwkvDevice,
        stored: List<RwkvLockEntry>,
        attempts: Map<String, RwkvAttempt>,
        overrides: Set<String> = emptySet(),
        lock: List<RwkvLockEntry> = RwkvLock.ENTRIES,
    ): List<RwkvOffer> {
        val recommended = recommend(device, lock)
        return lock
            .sortedWith(compareBy({ it.size.ordinal }, { it.head.ordinal }))
            .map { entry ->
                val fit = RwkvMemory.fit(device.totalRamBytes, runsWith(entry, lock), device.collection, overrides)
                val pick = if (entry == recommended) RwkvPick.RECOMMENDED else RwkvPick.OTHER
                RwkvOffer(entry, fit, state(entry, fit, stored, attempts[entry.id]), pick)
            }
    }

    /**
     * The most accurate two-head model that fits: the lowest of the release's own LogLoss scores.
     * Only two-head models are compared: the engine needs one, and an Instant-only model is scored on
     * another head. An override never makes a model recommended. Null when none fits.
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
        fit: RwkvFit,
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

        // A failed attempt shows where the entry may be tried again: it fits, or its limit is overridden.
        val failed: RwkvOfferState? = (attempt as? RwkvAttempt.Failed)?.let { RwkvOfferState.Failed(it.error) }
        return when (fit) {
            is RwkvFit.TooBig -> RwkvOfferState.TooBig(fit.neededBytes, fit.allowedBytes)
            is RwkvFit.TooBigOverridden -> failed ?: RwkvOfferState.TooBigOverridden(fit.neededBytes, fit.allowedBytes)
            is RwkvFit.Fits -> failed ?: RwkvOfferState.Fits
        }
    }
}
