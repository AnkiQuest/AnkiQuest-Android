// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestChibi
import com.ichi2.anki.ankiquest.AnkiquestChibi.Step
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.anki.ankiquest.AnkiquestWidget
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.HomeAccountChanged
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The study companion, chosen from a card per companion that previews its poses. The choice is
 * kept per account on the server and shown here as soon as it is tapped; a failed save puts the
 * saved one back. Companions earned at milestones show locked, with their condition and progress,
 * until the server unlocks them. The screen belongs to one account: when another signs in, it drops
 * the old account's pending choice and status and reads the new one's. Tapping an unlocked companion
 * quickly enough times turns it into its chibi ([AnkiquestChibi]).
 */
class AnkiquestCompanionFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_companion
    override val analyticsScreenNameConstant = "prefs.ankiquest.companion"

    /** Server reads and writes, one at a time, so an older answer never replaces a newer choice. */
    private val writes = Mutex()
    private var generation = 0

    /** A choice being saved, shown until the server answers. */
    private var pending: Choice? = null

    /** The scope of the account whose choice and status the screen shows; null when signed out. */
    private var owner: String? = null

    /** Taps toward a chibi, and the hint shown for the last one, replaced by the next. */
    private var chibiTaps = AnkiquestChibi.Taps()
    private var hint: Toast? = null

    override fun initSubscreen() {
        picker().setOnTap(::tap)
        owner = signedIn()?.scope
        render()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (stale()) {
            restart()
        }
    }

    /** Whether another account signed in, or the player signed out, since the screen last looked. */
    private fun stale() = signedIn()?.scope != owner

    /**
     * Shows the signed-in account from scratch. Answers still running for the old account are
     * ignored, and their pending choice, chibi taps and status go.
     */
    private fun restart() {
        owner = signedIn()?.scope
        ++generation
        pending = null
        chibiTaps = AnkiquestChibi.Taps()
        showStatus(null)
        render()
        refresh()
    }

    private fun picker() = requirePreference<AnkiquestCompanionPicker>(R.string.ankiquest_companion_picker_key)

    private fun status() = requirePreference<Preference>(R.string.ankiquest_companion_status_key)

    private fun signedIn(): HomeAccount? = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }

    private fun render() {
        val account = signedIn()
        picker().isEnabled = account != null
        val chosen = pending ?: AnkiquestCompanion.choice()
        picker().show(AnkiquestCompanion.offers(account, chosen), chosen)
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
                } catch (_: HomeAccountChanged) {
                    // Not an error to report: the screen moves to the account now signed in.
                    if (isAdded && request == generation) {
                        restart()
                    }
                    return@launch
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

    /** A tap on a card that can be chosen; locked cards report none. */
    private fun tap(choice: Choice) {
        // The cards were drawn for another account, so the tap chose nothing for this one.
        if (stale()) {
            restart()
            return
        }
        val account = signedIn() ?: return
        countTap(account, choice)
        if (choice != (pending ?: AnkiquestCompanion.choice())) {
            choose(account, choice)
        }
    }

    /** Counts [choice]'s tap toward its chibi: a hint near the end, the chibi on the last tap. */
    private fun countTap(
        account: HomeAccount,
        choice: Choice,
    ) {
        val unlocked = AnkiquestUnlocks.found(account)?.ids.orEmpty()
        when (val step = chibiTaps.tap(choice.id, TimeManager.time.intTimeMS(), unlocked)) {
            Step.Silent -> {}
            is Step.Hint -> showHint(step.left)
            is Step.Done -> claim(account, step.chibi, choice)
        }
    }

    // showThemedToast does not return the Toast, which each hint needs to replace the last.
    @SuppressLint("DirectToastMakeTextUsage")
    private fun showHint(left: Int) {
        hint?.cancel()
        hint =
            Toast
                .makeText(
                    requireContext(),
                    resources.getQuantityString(R.plurals.ankiquest_companion_chibi_hint, left, left),
                    Toast.LENGTH_SHORT,
                ).apply { show() }
    }

    /** Tells the server [chibi] is unlocked, then shows [base] growing into it. */
    private fun claim(
        account: HomeAccount,
        chibi: AnkiquestChibi.Chibi,
        base: Choice,
    ) {
        hint?.cancel()
        lifecycleScope.launch {
            try {
                writes.withLock { AnkiquestUnlocks.claim(account, chibi) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: HomeAccountChanged) {
                // The taps were another account's; the screen moves to the account now signed in.
                if (isAdded) {
                    restart()
                }
                return@launch
            } catch (e: Exception) {
                if (isAdded) {
                    showStatus(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                }
                return@launch
            }
            if (isAdded) {
                picker().grow(base)
                render()
            }
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
            } catch (_: HomeAccountChanged) {
                // Not an error to report: the screen moves to the account now signed in.
                if (isAdded && request == generation) {
                    restart()
                }
                return@launch
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
