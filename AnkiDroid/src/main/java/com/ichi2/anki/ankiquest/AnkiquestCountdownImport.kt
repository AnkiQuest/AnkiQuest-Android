// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdowns
import com.ichi2.anki.ankiquest.AnkiquestSettingsSync.Setting

/**
 * Countdowns made while signed out stay on this phone. After sign-in, once the account's settings
 * have synced, Home offers to add them to the account; declined, they stay here and show whenever
 * no one is signed in. The lists combine by the `import` cases in `tests/fixtures/countdowns.json`.
 */
internal object AnkiquestCountdownImport {
    /**
     * The question for [user]: [count] countdowns to add, combined as [imported] from the lists
     * [account] and [device] of the account [scope].
     */
    data class Pending(
        val user: String,
        val count: Int,
        val imported: AnkiquestCountdowns.Imported,
        val scope: String,
        val account: List<AnkiquestCountdowns.Countdown>,
        val device: List<AnkiquestCountdowns.Countdown>,
    )

    /** What to ask, once per sign-in, or null: not yet, already asked, or nothing to add. */
    fun pending(prefs: SharedPreferences): Pending? {
        val signedIn = AnkiquestHomeData.account(prefs) ?: return null
        if (!AnkiquestSettingsSync.takeOffer()) {
            return null
        }
        val account = AnkiquestCountdowns.read(prefs).events
        val device = (AnkiquestSettingsSync.signedOut(Setting.COUNTDOWNS, prefs) as Countdowns).events
        val imported = AnkiquestCountdowns.imported(account, device)
        val added = imported.account.size - account.size
        if (added == 0) {
            return null
        }
        return Pending(signedIn.user, added + imported.left.size, imported, signedIn.scope, account, device)
    }

    /**
     * Adds them: what fits joins the account's list and syncs; what does not stays on this phone.
     * While the question was open the account or either list may have changed: then nothing is
     * written, another account is not asked, and the same account is asked again after its next
     * sync. Returns whether they were added.
     */
    fun accept(
        prefs: SharedPreferences,
        pending: Pending,
    ): Boolean {
        if (AnkiquestHomeData.account(prefs)?.scope != pending.scope) {
            return false
        }
        val account = AnkiquestCountdowns.read(prefs).events
        val device = (AnkiquestSettingsSync.signedOut(Setting.COUNTDOWNS, prefs) as Countdowns).events
        if (account != pending.account || device != pending.device) {
            AnkiquestSettingsSync.askAgain()
            return false
        }
        AnkiquestSettingsSync.keepSignedOut(Setting.COUNTDOWNS, Countdowns(pending.imported.left), prefs)
        AnkiquestCountdowns.write(prefs, Countdowns(pending.imported.account))
        return true
    }
}
