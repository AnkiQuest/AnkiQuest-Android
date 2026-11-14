// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Unlock
import com.ichi2.anki.ankiquest.AnkiquestWidget
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.UnlockTaps
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The study companion, chosen from a card per companion that previews its poses. The choice is
 * kept per account on the server and shown here as soon as it is tapped; a failed save puts the
 * saved one back. Tapping Ankilope quickly enough times unlocks a hidden companion.
 */
class AnkiquestCompanionFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_companion
    override val analyticsScreenNameConstant = "prefs.ankiquest.companion"

    private val taps = UnlockTaps()

    /** Server reads and writes, one at a time, so an older answer never replaces a newer choice. */
    private val writes = Mutex()
    private var generation = 0

    /** A choice being saved, shown until the server answers. */
    private var pending: Choice? = null

    override fun initSubscreen() {
        picker().setOnTap(::tap)
        render()
        refresh()
    }

    private fun picker() = requirePreference<AnkiquestCompanionPicker>(R.string.ankiquest_companion_picker_key)

    private fun status() = requirePreference<Preference>(R.string.ankiquest_companion_status_key)

    private fun signedIn(): HomeAccount? = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }

    private fun render() {
        val account = signedIn()
        picker().isEnabled = account != null
        picker().show(AnkiquestCompanion.choices(account), pending ?: AnkiquestCompanion.choice())
        if (account == null) {
            showStatus(getString(R.string.ankiquest_companion_signed_out))
        }
    }

    /** [text] above the cards, or nothing when null. */
    private fun showStatus(text: String?) {
        status().summary = text
        status().isVisible = text != null
    }

    /** Reads the unlocks and the choice from the server. */
    private fun refresh() {
        val account = signedIn() ?: return
        val request = ++generation
        showStatus(getString(R.string.ankiquest_check_running))
        lifecycleScope.launch {
            val failure =
                try {
                    writes.withLock { AnkiquestCompanion.refresh(account) }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                }
            if (!isAdded || request != generation) {
                return@launch
            }
            showStatus(failure)
            render()
        }
    }

    private fun tap(choice: Choice) {
        val account = signedIn() ?: return

        // The cow stays hidden, with no hint, until Ankilope is tapped enough times in a row.
        val locked = !AnkiquestUnlocks.has(account, Unlock.COW)
        if (locked && taps.tap(choice, TimeManager.time.intTimeMS()) == UnlockTaps.Progress.UNLOCKED) {
            AnkiquestUnlocks.unlock(account, Unlock.COW)
            showSnackbar(getString(R.string.ankiquest_companion_joined, getString(Choice.COW.title)))
            choose(account, Choice.COW)
            return
        }
        if (choice != (pending ?: AnkiquestCompanion.choice())) {
            choose(account, choice)
        }
    }

    private fun choose(
        account: HomeAccount,
        choice: Choice,
    ) {
        val request = ++generation
        pending = choice
        render()
        lifecycleScope.launch {
            try {
                writes.withLock { AnkiquestCompanion.choose(account, choice) }
                if (isAdded) {
                    AnkiquestWidget.requestUpdate(requireContext())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded && request == generation) {
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(R.string.ankiquest_companion_title)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            if (request != generation) {
                return@launch
            }
            pending = null
            if (isAdded) {
                showStatus(null)
                render()
            }
        }
    }
}
