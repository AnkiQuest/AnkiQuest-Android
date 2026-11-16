// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.R
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.utils.ext.getParcelableCompat
import org.json.JSONObject

/** Deck choices belong to their original account, outside Today's refreshed content. */
internal class AnkiquestDeckChoices(
    private val host: FragmentActivity,
    private val focus: (Long, String?) -> Unit,
    private val copy: (HomeAccount, SelectableDeck.Deck, List<String>) -> Unit,
) : DefaultLifecycleObserver {
    private enum class Phase { CHOOSING, CLOSED }

    private val fragments get() = host.supportFragmentManager

    init {
        fragments.setFragmentResultListener(FOCUS, host) { _, result ->
            val picker = fragments.findFragmentByTag(DeckSelectionDialog.TAG) as? DeckSelectionDialog
            val args = picker?.arguments
            if (args?.getString(PHASE) != Phase.CHOOSING.name) {
                return@setFragmentResultListener
            }
            args.putString(PHASE, Phase.CLOSED.name)
            picker.dismissAllowingStateLoss()
            val scope = args.getString(SCOPE)
            val deck = result.getParcelableCompat<SelectableDeck>(DeckSelectionDialog.RESULT_SELECTED_DECK) as? SelectableDeck.Deck
            if (deck != null && owns(scope)) {
                focus(deck.deckId, scope)
            }
        }
        fragments.setFragmentResultListener(AnkiquestCopyDialog.RESULT, host) { _, result ->
            val current = AnkiquestHomeData.account() ?: return@setFragmentResultListener
            val deck = result.getParcelableCompat<SelectableDeck.Deck>(AnkiquestCopyDialog.DECK)
            val recipients = result.getStringArrayList(AnkiquestCopyDialog.RECIPIENTS).orEmpty()
            if (deck != null && recipients.isNotEmpty() && result.getString(SCOPE) == current.scope && owns(current.scope)) {
                copy(current, deck, recipients)
            }
        }
        host.lifecycle.addObserver(this)
    }

    fun focus(decks: List<HomeDeck>) {
        if (!available() || decks.isEmpty()) {
            return
        }
        DeckSelectionDialog
            .newInstance(
                title = host.getString(R.string.aq_home_change_deck),
                decks = decks.map { SelectableDeck.Deck(it.id, it.name) },
                requestKey = FOCUS,
                allowAll = false,
                allowFiltered = false,
            ).apply {
                requireArguments().putString(SCOPE, AnkiquestHomeData.account()?.scope)
                requireArguments().putString(PHASE, Phase.CHOOSING.name)
            }.showNow(fragments, DeckSelectionDialog.TAG)
    }

    fun copy(
        account: HomeAccount,
        decks: List<HomeDeck>,
        selected: Long?,
        friends: List<JSONObject>,
    ) {
        if (!available() || !owns(account.scope)) {
            return
        }
        AnkiquestCopyDialog.newInstance(account, decks, selected, friends).showNow(fragments, AnkiquestCopyDialog.TAG)
    }

    private fun available(): Boolean =
        owns(AnkiquestHomeData.account()?.scope) && !fragments.isStateSaved &&
            fragments.findFragmentByTag(DeckSelectionDialog.TAG) == null && fragments.findFragmentByTag(AnkiquestCopyDialog.TAG) == null

    private fun owns(scope: String?): Boolean =
        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.isFinishing &&
            AnkiquestHomeData.account()?.scope == scope

    override fun onResume(owner: LifecycleOwner) {
        for (tag in listOf(DeckSelectionDialog.TAG, AnkiquestCopyDialog.TAG)) {
            val picker = fragments.findFragmentByTag(tag) as? DialogFragment ?: continue
            if (!owns(picker.arguments?.getString(SCOPE))) {
                picker.arguments?.putString(PHASE, Phase.CLOSED.name)
                picker.dismissAllowingStateLoss()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (host.isChangingConfigurations) {
            return
        }
        // Rotation retains choices; leaving Today ends this attempt and drops late results.
        for (tag in listOf(DeckSelectionDialog.TAG, AnkiquestCopyDialog.TAG)) {
            val picker = fragments.findFragmentByTag(tag) as? DialogFragment ?: continue
            picker.arguments?.putString(PHASE, Phase.CLOSED.name)
            picker.dismissAllowingStateLoss()
        }
        fragments.clearFragmentResult(FOCUS)
        fragments.clearFragmentResult(AnkiquestCopyDialog.RESULT)
    }

    companion object {
        const val SCOPE = "ankiquest.deck_choice.scope"
        private const val FOCUS = "ankiquest.focus_deck"
        private const val PHASE = "ankiquest.deck_choice.phase"
    }
}
