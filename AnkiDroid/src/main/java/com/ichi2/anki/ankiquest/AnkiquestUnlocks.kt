// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.json.JSONException
import org.json.JSONObject

/**
 * Companions a player unlocks with an easter egg. A server with [FEATURE] keeps them per account
 * (`/api/unlocks/{user}`), so every device shows them. They are not settings: the settings-sync
 * switch does not apply. A server without the feature keeps nothing, so the unlock stays on this
 * phone and is sent once the server reports the feature.
 */
internal object AnkiquestUnlocks {
    const val FEATURE = "companion_unlocks"
    private const val PREF_UNLOCKS = "ankiquestUnlocks"

    /** What can be unlocked; [id] is the server's name for it. */
    enum class Unlock(
        val id: String,
    ) {
        COW("cow"),
    }

    /** Where an unlock is known. */
    enum class State(
        val id: String,
    ) {
        LOCKED("locked"),

        /** Unlocked on this phone; the server does not know yet. */
        LOCAL("local"),

        /** Unlocked and kept by the server. */
        SYNCED("synced"),
    }

    fun state(
        account: HomeAccount,
        unlock: Unlock,
    ): State {
        val id = stored(account).optString(unlock.id)
        return State.entries.firstOrNull { it.id == id } ?: State.LOCKED
    }

    fun has(
        account: HomeAccount,
        unlock: Unlock,
    ) = state(account, unlock) != State.LOCKED

    /** Unlocks [unlock] on this phone; [sync] takes it to the server. */
    fun unlock(
        account: HomeAccount,
        unlock: Unlock,
    ) {
        if (has(account, unlock)) {
            return
        }
        write(account, mapOf(unlock to State.LOCAL))
    }

    /**
     * Sends this phone's unlocks to [account]'s server and takes the ones it keeps, when the server
     * last reported [FEATURE]. A cow chosen while only this phone knew it is then saved there too.
     */
    suspend fun sync(account: HomeAccount) {
        if (account.token.isEmpty() || !AnkiquestCapabilities.has(account, FEATURE)) {
            return
        }

        // Unlocks made while the server could not keep them, or before it could be reached.
        val pending = Unlock.entries.filter { state(account, it) == State.LOCAL }
        for (unlock in pending) {
            AnkiquestHomeData.saveUnlock(account, unlock.id)
        }

        // The server's list is the whole truth for this account, unlocks from other devices included.
        val known = AnkiquestHomeData.unlocks(account)
        write(account, Unlock.entries.filter { it.id in known }.associateWith { State.SYNCED })

        // The server refuses a cow it does not know is unlocked, so the choice follows the unlock.
        if (Unlock.COW in pending && state(account, Unlock.COW) == State.SYNCED && AnkiquestCompanion.choice() == Choice.COW) {
            AnkiquestCompanion.choose(account, Choice.COW)
        }
    }

    private fun key(account: HomeAccount) = scopedKey(PREF_UNLOCKS, account.notificationAccount)

    private fun stored(account: HomeAccount): JSONObject =
        try {
            JSONObject(AnkiDroidApp.sharedPrefs().getString(key(account), null) ?: return JSONObject())
        } catch (_: JSONException) {
            JSONObject()
        } catch (_: ClassCastException) {
            JSONObject()
        }

    private fun write(
        account: HomeAccount,
        states: Map<Unlock, State>,
    ) {
        if (states.isEmpty()) {
            return
        }
        val value = stored(account)
        states.forEach { (unlock, state) -> value.put(unlock.id, state.id) }
        AnkiDroidApp.sharedPrefs().edit { putString(key(account), value.toString()) }
    }
}

/**
 * How many quick taps on Ankilope unlock the cow. The same values are the shared fixture
 * `tests/fixtures/companion_unlock.json` in float3/ankiquest, which the desktop add-on reads.
 */
internal data class UnlockTapRule(
    val taps: Int,
    val maxGapMs: Long,
) {
    companion object {
        val COW = UnlockTapRule(taps = 9, maxGapMs = 1_000)
    }
}

/** Counts a run of taps on Ankilope's card; any other card, or a pause longer than the rule allows, starts over. */
internal class UnlockTaps(
    private val rule: UnlockTapRule = UnlockTapRule.COW,
) {
    enum class Progress { COUNTING, UNLOCKED }

    private var count = 0
    private var last = 0L

    fun tap(
        choice: Choice,
        now: Long,
    ): Progress {
        val quick = count > 0 && now - last in 0..rule.maxGapMs
        last = now
        if (choice != Choice.ANKILOPE) {
            count = 0
            return Progress.COUNTING
        }
        count = if (quick) count + 1 else 1
        if (count < rule.taps) {
            return Progress.COUNTING
        }
        count = 0
        return Progress.UNLOCKED
    }
}
