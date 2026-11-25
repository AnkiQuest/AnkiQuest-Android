// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.R
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.utils.ext.getParcelableCompat
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** Deck choices belong to their original account, outside Today's refreshed content. */
internal class AnkiquestDeckChoices(
    private val host: FragmentActivity,
    private val focus: (Long, String?) -> Unit,
    private val copy: (HomeAccount, SelectableDeck.Deck, List<String>) -> Unit,
) : DefaultLifecycleObserver {
    private sealed interface FocusState : Parcelable {
        @Parcelize
        data class Choosing(
            val scope: String?,
        ) : FocusState

        @Parcelize
        data object Closed : FocusState
    }

    private val fragments get() = host.supportFragmentManager
    private var focusState =
        host.savedStateRegistry
            .consumeRestoredStateForKey(STATE_KEY)
            ?.getParcelableCompat<FocusState>(STATE_FOCUS) ?: FocusState.Closed

    init {
        // This controller owns the focus phase; the native picker's arguments remain immutable.
        host.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply { putParcelable(STATE_FOCUS, focusState) }
        }
        fragments.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDetached(
                    fm: FragmentManager,
                    fragment: Fragment,
                ) {
                    if (fragment is DeckSelectionDialog && fragment.tag == DeckSelectionDialog.TAG) {
                        focusState = FocusState.Closed
                    }
                }
            },
            false,
        )
        fragments.setFragmentResultListener(REQUEST_FOCUS, host) { _, result ->
            val choice = focusState as? FocusState.Choosing ?: return@setFragmentResultListener
            val picker = fragments.findFragmentByTag(DeckSelectionDialog.TAG) as? DeckSelectionDialog
            if (picker?.dialog?.isShowing != true) {
                return@setFragmentResultListener
            }
            focusState = FocusState.Closed
            picker.dismissAllowingStateLoss()
            val deck = result.getParcelableCompat<SelectableDeck>(DeckSelectionDialog.RESULT_SELECTED_DECK) as? SelectableDeck.Deck
            if (deck != null && owns(choice.scope)) {
                focus(deck.deckId, choice.scope)
            }
        }
        fragments.setFragmentResultListener(AnkiquestCopyDialog.REQUEST_COPY, host) { _, result ->
            val current = AnkiquestHomeData.account() ?: return@setFragmentResultListener
            val deck = result.getParcelableCompat<SelectableDeck.Deck>(AnkiquestCopyDialog.RESULT_DECK)
            val recipients = result.getStringArrayList(AnkiquestCopyDialog.RESULT_RECIPIENTS).orEmpty()
            if (deck != null && recipients.isNotEmpty() && result.getString(AnkiquestCopyDialog.RESULT_SCOPE) == current.scope &&
                owns(current.scope)
            ) {
                copy(current, deck, recipients)
            }
        }
        host.lifecycle.addObserver(this)
    }

    fun focus(decks: List<HomeDeck>) {
        if (!available() || decks.isEmpty()) {
            return
        }
        focusState = FocusState.Choosing(AnkiquestHomeData.account()?.scope)
        DeckSelectionDialog
            .newInstance(
                title = host.getString(R.string.aq_home_change_deck),
                decks = decks.map { SelectableDeck.Deck(it.id, it.name) },
                requestKey = REQUEST_FOCUS,
                allowAll = false,
                allowFiltered = false,
            ).showNow(fragments, DeckSelectionDialog.TAG)
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
        val picker = fragments.findFragmentByTag(DeckSelectionDialog.TAG) as? DeckSelectionDialog ?: return
        val choice = focusState as? FocusState.Choosing
        if (choice == null || !owns(choice.scope)) {
            focusState = FocusState.Closed
            picker.dismissAllowingStateLoss()
            fragments.clearFragmentResult(REQUEST_FOCUS)
        }
    }

    companion object {
        const val ARG_SCOPE = "ankiquest.deck_choice.scope"
        private const val REQUEST_FOCUS = "ankiquest.focus_deck"
        private const val STATE_KEY = "ankiquest.deck_choices"
        private const val STATE_FOCUS = "ankiquest.focus_choice"
    }
}
