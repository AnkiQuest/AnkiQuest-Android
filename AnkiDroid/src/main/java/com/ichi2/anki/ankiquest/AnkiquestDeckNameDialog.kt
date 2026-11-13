// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/** Both conquest screens edit the same local choice, with one account and foreground owner. */
internal object AnkiquestDeckNameDialog {
    fun show(
        host: FragmentActivity,
        account: HomeAccount,
        at: Long,
        saved: (String?) -> Unit,
    ) {
        val owner = AnkiquestDialogOwner(host, account)
        host.lifecycleScope.launch {
            try {
                val deck = AnkiquestDeckNames.find(at)
                if (deck == null || !owner.isCurrent()) {
                    owner.close()
                    return@launch
                }
                showPicker(host, owner, deck, at, saved)
            } catch (error: CancellationException) {
                owner.close()
                throw error
            } catch (error: Exception) {
                fail(host, owner, error)
                owner.close()
            }
        }
    }

    private fun showPicker(
        host: FragmentActivity,
        owner: AnkiquestDialogOwner,
        deck: AnkiquestDeckNames.Deck,
        at: Long,
        saved: (String?) -> Unit,
    ) {
        val language = AnkiquestLanguage.context(host)
        val field =
            MaterialAutoCompleteTextView(host).apply {
                setSingleLine(true)
                filters = arrayOf(nameLimit())
                threshold = 0
                setAdapter(ArrayAdapter(host, android.R.layout.simple_dropdown_item_1line, deck.picks.filter(String::isNotEmpty)))
                setText(deck.name, false)
                setOnClickListener { showDropDown() }
            }
        val padding = (20 * host.resources.displayMetrics.density).toInt()
        val content =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(TextView(host).apply { text = language.getString(R.string.aq_share_deck_help) })
                addView(
                    TextInputLayout(host).apply {
                        hint = language.getString(R.string.aq_share_deck_title)
                        addView(field)
                    },
                )
            }

        fun save(
            dialog: android.content.DialogInterface,
            name: String,
        ) {
            if (!owner.take(dialog)) {
                return
            }
            host.lifecycleScope.launch {
                try {
                    if (owner.isCurrent()) {
                        deck.save(name)
                        val choice = AnkiquestDeckNames.find(at)?.name?.ifEmpty { null }
                        if (owner.isCurrent()) {
                            saved(choice)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(host, owner, error)
                } finally {
                    owner.close()
                }
            }
        }
        owner.show(
            AlertDialog
                .Builder(host)
                .setTitle(language.getString(R.string.aq_share_deck_title))
                .setView(content)
                .setPositiveButton(CommonString.save) { dialog, _ -> save(dialog, field.text.toString()) }
                .setNeutralButton(language.getString(R.string.aq_share_deck_omit)) { dialog, _ -> save(dialog, "") }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    /** Count characters like the shared cleaner, including when replacing a selection. */
    private fun nameLimit() =
        InputFilter { source, start, end, destination, replacedStart, replacedEnd ->
            val remaining =
                AnkiquestShare.MAX_LABEL - Character.codePointCount(destination, 0, destination.length) +
                    Character.codePointCount(destination, replacedStart, replacedEnd)
            when {
                Character.codePointCount(source, start, end) <= remaining -> null
                remaining <= 0 -> ""
                else -> source.subSequence(start, Character.offsetByCodePoints(source, start, remaining))
            }
        }

    private fun fail(
        host: FragmentActivity,
        owner: AnkiquestDialogOwner,
        error: Exception,
    ) {
        Timber.w(error, "AnkiQuest deck name unavailable")
        if (owner.isCurrent()) {
            showThemedToast(host, AnkiquestLanguage.context(host).getString(R.string.aq_share_deck_failed), shortLength = false)
        }
    }
}
