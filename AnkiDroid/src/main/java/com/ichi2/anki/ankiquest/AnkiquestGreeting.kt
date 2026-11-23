// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.isRobolectric
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import kotlinx.coroutines.launch

/**
 * Aki introduces herself once per device, over the deck list, after upstream's own first-run screens.
 * The state stays on this device: it is not an AnkiQuest setting and never reaches the server.
 */
internal object AnkiquestGreeting {
    /** Whether this device still owes the player Aki's greeting. */
    enum class State { PENDING, SHOWN }

    private const val PREF_STATE = "ankiquestGreeting"
    private const val TAG = "AnkiquestGreeting"

    fun state(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): State =
        prefs.getString(PREF_STATE, null)?.let { stored -> State.entries.firstOrNull { it.name == stored } } ?: unset()

    fun remember(
        state: State,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = prefs.edit { putString(PREF_STATE, state.name) }

    // Unit tests open the deck list constantly; a test asks for the greeting by remembering PENDING.
    private fun unset() = if (isRobolectric) State.SHOWN else State.PENDING

    /** Greets the player once [activity] is in front, unless this device already did. */
    fun offer(activity: FragmentActivity) {
        if (state() != State.PENDING) {
            return
        }
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed { show(activity) }
        }
    }

    private fun show(activity: FragmentActivity) {
        val manager = activity.supportFragmentManager
        if (state() != State.PENDING || manager.findFragmentByTag(TAG) != null || manager.isStateSaved) {
            return
        }

        // Upstream's dialogs (the analytics question, a load failure) and AnkiQuest's moments go first;
        // the greeting stays pending for the next start rather than covering them.
        if (!AnkiquestPostStudyPrompts.ready(activity) || manager.fragments.any { it is DialogFragment && it.dialog?.isShowing == true }) {
            return
        }

        // Remembered before showing: a restored dialog survives rotation, a second one never appears.
        remember(State.SHOWN)
        AnkiquestGreetingDialog().showNow(manager, TAG)
    }
}

/** Aki's greeting card. Always Aki, whatever the companion setting: it introduces her. */
internal class AnkiquestGreetingDialog : DialogFragment() {
    private enum class Account { SIGNED_IN, SIGNED_OUT }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_ankiquest_greeting, null)
        val image = view.findViewById<ImageView>(R.id.ankiquest_greeting_image)
        showArt(image, R.drawable.aki_welcome)

        // Signed-out players are offered the sign-in; signed-in players go straight to their decks.
        val builder = AlertDialog.Builder(context).setView(view)
        when (account()) {
            Account.SIGNED_OUT ->
                builder
                    .setPositiveButton(R.string.ankiquest_sign_in_submit) { _, _ ->
                        startActivity(AnkiquestSettingsFragment.signInIntent(context))
                    }.setNegativeButton(R.string.ankiquest_greeting_later, null)
            Account.SIGNED_IN -> builder.setPositiveButton(R.string.ankiquest_greeting_study, null)
        }
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        // Moments and other AnkiQuest prompts wait until the greeting is closed.
        AnkiquestPostStudyPrompts.track(requireDialog())
    }

    private fun account(): Account = if (AnkiquestHomeData.account()?.token?.isNotEmpty() == true) Account.SIGNED_IN else Account.SIGNED_OUT

    private fun showArt(
        image: ImageView,
        @DrawableRes art: Int,
    ) {
        val bitmap = AnkiquestArt.bitmap(image.context, art, image.layoutParams.width)
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
        } else {
            image.setImageResource(art)
        }
    }
}
