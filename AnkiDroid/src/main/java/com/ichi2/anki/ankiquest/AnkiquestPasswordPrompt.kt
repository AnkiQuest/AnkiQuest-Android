// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Methods
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.SignIn
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Home card that asks a player who signs in only with a device token to set a password
 * (`"sign_in": "token_only"` in `GET /api/sign-in/{user}`, the server's `docs/mobile-api.md`), so
 * they keep their account once the server operator removes their token from the configuration.
 * Servers without `sign_in` never show it. The add-on shows the same card with the same wording.
 */
internal object AnkiquestPasswordPrompt {
    /** How long Not now hides the card on this device. */
    const val SNOOZE_MS = 3 * 24 * 60 * 60 * 1000L

    private const val SNOOZED_KEY = "ankiquestPasswordPromptSnoozed"
    private val PASSWORD_LENGTH = 10..256

    /** Whether to show the card: the player signs in only with a token, and Not now has run out. */
    fun offered(
        methods: Methods,
        snoozedAt: Long?,
        now: Long,
    ): Boolean = methods.signIn == SignIn.TOKEN_ONLY && (snoozedAt == null || now - snoozedAt >= SNOOZE_MS)

    /** When the player last chose Not now for [account] on this device. */
    fun snoozedAt(
        prefs: SharedPreferences,
        account: HomeAccount,
    ): Long? = prefs.getLong(key(account), -1L).takeIf { it >= 0 }

    fun snooze(
        prefs: SharedPreferences,
        account: HomeAccount,
        now: Long,
    ) = prefs.edit { putLong(key(account), now) }

    private fun key(account: HomeAccount) = scopedKey(SNOOZED_KEY, account.scope)

    /**
     * Why the server would refuse [typed], as a message; null when it would take it. [repeat] is the
     * second entry, null in a form without one. Counts characters, as the server and the add-on do,
     * not UTF-16 units. Both Android password forms check with it before sending.
     */
    @StringRes
    fun problem(
        typed: String,
        repeat: String?,
    ): Int? {
        if (typed.codePointCount(0, typed.length) !in PASSWORD_LENGTH) {
            return R.string.ankiquest_password_length
        }
        if (repeat != null && repeat != typed) {
            return R.string.ankiquest_password_mismatch
        }
        return null
    }

    /**
     * The form: the new password twice, since there is no other to fall back on when it was mistyped.
     * [saved] runs after the server took it.
     */
    fun ask(
        activity: AppCompatActivity,
        account: HomeAccount,
        saved: () -> Unit,
    ) {
        fun field(hint: Int) =
            EditText(activity).apply {
                setHint(hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val password = field(R.string.ankiquest_password_new)
        val repeat = field(R.string.ankiquest_password_repeat)
        val message = TextView(activity)
        val column =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * activity.resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, 0)
                listOf(password, repeat, message).forEach(::addView)
            }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.ankiquest_password_set_title)
                .setView(column)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(CommonString.save, null)
                .show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            // Checked here first, as the server would, so a typo costs no request.
            val typed = password.text.toString()
            val problem = problem(typed, repeat.text.toString())
            if (problem != null) {
                message.setText(problem)
                return@setOnClickListener
            }

            submit.isEnabled = false
            message.setText(R.string.ankiquest_check_running)
            activity.lifecycleScope.launch {
                try {
                    AnkiquestAccountSettings.setPassword(account, current = null, password = typed, signOutOthers = false)
                    dialog.dismiss()
                    showThemedToast(activity, activity.getString(R.string.ankiquest_password_saved), shortLength = true)
                    saved()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    submit.isEnabled = true
                    message.text =
                        when (e) {
                            is AnkiquestHttpException -> e.explanation ?: activity.getString(R.string.ankiquest_check_http, e.code)
                            is HomeAccountChanged -> activity.getString(R.string.ankiquest_account_changed)
                            else -> activity.getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                        }
                }
            }
        }
    }
}
