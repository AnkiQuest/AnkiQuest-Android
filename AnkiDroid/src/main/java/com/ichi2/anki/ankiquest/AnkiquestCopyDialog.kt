// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.ankiquest.ui.R
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.utils.ext.getParcelableCompat
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/** One explicit deck-copy form. Restoring its choices never restores a send action. */
internal class AnkiquestCopyDialog : DialogFragment() {
    private sealed interface State : Parcelable {
        @Parcelize
        data class Choosing(
            val deck: SelectableDeck.Deck?,
            val recipients: Set<String>,
        ) : State

        @Parcelize
        data object Closed : State
    }

    private var state: State = State.Closed
    private lateinit var deckName: TextView
    private val people get() = requireArguments().getStringArrayList(ARG_PEOPLE).orEmpty()
    private val decks get() = BundleCompat.getParcelableArrayList(requireArguments(), ARG_DECKS, SelectableDeck.Deck::class.java).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = savedInstanceState?.getParcelableCompat<State>(STATE_CHOICE)
            ?: State.Choosing(requireArguments().getParcelableCompat(ARG_DECK), emptySet())
        childFragmentManager.setFragmentResultListener(REQUEST_CHANGE, this) { _, result ->
            val choice = state as? State.Choosing ?: return@setFragmentResultListener
            val selected = result.getParcelableCompat<SelectableDeck>(DeckSelectionDialog.RESULT_SELECTED_DECK) as? SelectableDeck.Deck
            if (selected != null && owns()) {
                state = choice.copy(deck = selected)
                render()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val choice = state as? State.Choosing
        val heading =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, 0)
            }
        heading.addView(
            TextView(requireContext()).apply {
                setText(R.string.aq_copies_share)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                ViewCompat.setAccessibilityHeading(this, true)
            },
        )
        deckName = TextView(requireContext())
        heading.addView(deckName)
        heading.addView(
            MaterialButton(requireContext()).apply {
                setText(R.string.aq_copies_choose_deck)
                setOnClickListener { changeDeck() }
            },
        )
        heading.addView(
            TextView(requireContext()).apply {
                setText(if (people.isEmpty()) R.string.aq_copies_no_friends else R.string.aq_copies_choose_one)
            },
        )
        return MaterialAlertDialogBuilder(requireContext())
            .setCustomTitle(heading)
            .setMultiChoiceItems(
                requireArguments().getStringArrayList(ARG_NAMES).orEmpty().toTypedArray(),
                BooleanArray(people.size) { people[it] in choice?.recipients.orEmpty() },
            ) { _, index, checked ->
                val current = state as? State.Choosing ?: return@setMultiChoiceItems
                val user = people.getOrNull(index) ?: return@setMultiChoiceItems
                state = current.copy(recipients = if (checked) current.recipients + user else current.recipients - user)
                render()
            }.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aq_copies_share, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { send() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // The fragment can resume before its host lifecycle reaches RESUMED.
        // Restore the owned form now; sending still requires the resumed host.
        if (state == State.Closed || !sameAccount()) {
            close()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        state = State.Closed
        super.onDismiss(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The form's choices and phase are one value across recreation and app switches.
        outState.putParcelable(STATE_CHOICE, state)
    }

    private fun render() {
        val choice = state as? State.Choosing
        deckName.text = choice?.deck?.name ?: getString(R.string.aq_copies_choose_deck)
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = choice?.deck != null && choice.recipients.isNotEmpty()
    }

    private fun changeDeck() {
        if (!owns() || state !is State.Choosing || childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag(DeckSelectionDialog.TAG) != null
        ) {
            return
        }
        DeckSelectionDialog
            .newInstance(
                title = getString(R.string.aq_copies_choose_deck),
                decks = decks,
                requestKey = REQUEST_CHANGE,
                allowAll = false,
                allowFiltered = false,
            ).showNow(childFragmentManager, DeckSelectionDialog.TAG)
    }

    private fun send() {
        val choice = state as? State.Choosing ?: return
        if (!owns() || choice.deck == null || choice.recipients.isEmpty()) {
            return
        }
        // Consume the form before delivering its result, including for a retained button.
        state = State.Closed
        parentFragmentManager.setFragmentResult(
            REQUEST_COPY,
            Bundle().apply {
                putString(RESULT_SCOPE, requireArguments().getString(AnkiquestDeckChoices.ARG_SCOPE))
                putParcelable(RESULT_DECK, choice.deck)
                putStringArrayList(RESULT_RECIPIENTS, ArrayList(people.filter { it in choice.recipients }))
            },
        )
        dismiss()
    }

    private fun owns(): Boolean = isAdded && activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true && sameAccount()

    private fun sameAccount(): Boolean = HomeAccount.current()?.scope == requireArguments().getString(AnkiquestDeckChoices.ARG_SCOPE)

    private fun close() {
        state = State.Closed
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "ankiquest.copy"
        const val REQUEST_COPY = "ankiquest.copy.result"
        const val RESULT_SCOPE = "ankiquest.deck_choice.scope"
        const val RESULT_DECK = "deck"
        const val RESULT_RECIPIENTS = "recipients"
        private const val ARG_DECKS = "decks"
        private const val ARG_DECK = "deck"
        private const val ARG_PEOPLE = "people"
        private const val ARG_NAMES = "names"
        private const val STATE_CHOICE = "ankiquest.copy.choice"
        private const val REQUEST_CHANGE = "ankiquest.copy.deck"

        fun newInstance(
            account: HomeAccount,
            decks: List<HomeDeck>,
            selected: Long?,
            friends: List<JSONObject>,
        ): AnkiquestCopyDialog =
            AnkiquestCopyDialog().apply {
                val choices = decks.map { SelectableDeck.Deck(it.id, it.name) }
                val people =
                    friends
                        .filter {
                            it.optString("user").isNotBlank() && it.optString("user") != account.user
                        }.distinctBy { it.optString("user") }
                arguments =
                    Bundle().apply {
                        putString(AnkiquestDeckChoices.ARG_SCOPE, account.scope)
                        putParcelableArrayList(ARG_DECKS, ArrayList(choices))
                        putParcelable(ARG_DECK, choices.firstOrNull { it.deckId == selected })
                        putStringArrayList(ARG_PEOPLE, ArrayList(people.map { it.getString("user") }))
                        putStringArrayList(ARG_NAMES, ArrayList(people.map { it.optString("display").ifBlank { it.getString("user") } }))
                    }
            }
    }
}
