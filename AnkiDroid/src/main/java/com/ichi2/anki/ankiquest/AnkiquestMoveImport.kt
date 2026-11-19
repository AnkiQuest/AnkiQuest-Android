// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.utils.LanguageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The new app's side of [AnkiquestMove]: before the setup screens, when the old app is installed
 * and this app has no AnkiQuest account yet, asks once whether to move the settings and logins,
 * then reads and applies them and continues with the usual post-login path.
 */
internal object AnkiquestMoveImport {
    /** The flow, kept for the process so a recreated setup screen picks it up where it was. */
    sealed interface State {
        /** Not the new app, no old app to import from, already signed in, or declined. */
        data object NotNeeded : State

        /** The prompt is up. */
        data object Offered : State

        /** Reading and applying the payload. */
        data object Importing : State

        /** Applied; [next] says how the collection follows. */
        data class Done(
            val next: Next,
        ) : State

        data class Failed(
            val reason: Reason,
        ) : State
    }

    /** How the collection, which stays in the old app's storage, reaches the new app. */
    enum class Next {
        /** An AnkiWeb login moved: sync. */
        SYNC,

        /** No AnkiWeb login: export the collection in the old app and import the file here. */
        EXPORT,
    }

    enum class Reason {
        /** The old app did not answer. */
        UNAVAILABLE,

        /** The old app refused the caller. */
        REFUSED,

        /** The payload is of a version this app does not read. */
        UNKNOWN_VERSION,

        MALFORMED,
    }

    /** Whether this app already has an AnkiQuest account. */
    enum class Account {
        NONE,
        SIGNED_IN,
    }

    /** What reading the payload gave. */
    sealed interface Result {
        data class Imported(
            val next: Next,
        ) : Result

        data class Failed(
            val reason: Reason,
        ) : Result
    }

    /** The old app's provider, behind an interface so tests can stand in for it. */
    fun interface Source {
        /** The payload text, or null when the old app gave none. @throws SecurityException when refused */
        fun read(): String?
    }

    // Transitions. Each leaves a state it does not apply to unchanged.

    fun start(
        role: AnkiquestMove.Role,
        oldApp: AnkiquestMove.OldApp,
        account: Account,
    ): State {
        if (role != AnkiquestMove.Role.NEW || oldApp != AnkiquestMove.OldApp.READY || account != Account.NONE) {
            return State.NotNeeded
        }
        return State.Offered
    }

    fun accept(state: State): State = if (state == State.Offered) State.Importing else state

    fun decline(state: State): State = if (state == State.Offered) State.NotNeeded else state

    /** An import cut short (the screen went away) can be offered again. */
    fun interrupt(state: State): State = if (state == State.Importing) State.Offered else state

    fun finish(
        state: State,
        result: Result,
    ): State {
        if (state != State.Importing) {
            return state
        }
        return when (result) {
            is Result.Imported -> State.Done(result.next)
            is Result.Failed -> State.Failed(result.reason)
        }
    }

    /** Reads the payload from [source] and applies it to [prefs]. */
    fun read(
        source: Source,
        prefs: SharedPreferences,
        exclusions: AnkiquestMovePayload.Exclusions,
    ): Result {
        val text =
            try {
                source.read()
            } catch (e: SecurityException) {
                return Result.Failed(Reason.REFUSED)
            } catch (e: Exception) {
                // The provider is gone or crashed; its message carries nothing of the payload.
                Timber.w(e, "ankiquest move: old app unavailable")
                return Result.Failed(Reason.UNAVAILABLE)
            }
        if (text == null) {
            return Result.Failed(Reason.UNAVAILABLE)
        }

        val settings =
            when (val decoded = AnkiquestMovePayload.decode(text)) {
                is AnkiquestMovePayload.Decoded.Read -> decoded.settings
                is AnkiquestMovePayload.Decoded.UnknownVersion -> return Result.Failed(Reason.UNKNOWN_VERSION)
                AnkiquestMovePayload.Decoded.Malformed -> return Result.Failed(Reason.MALFORMED)
            }
        AnkiquestMovePayload.apply(prefs, settings, exclusions)
        val next = if (prefs.getString(HKEY, null).isNullOrEmpty()) Next.EXPORT else Next.SYNC
        return Result.Imported(next)
    }

    private const val HKEY = "hkey"

    private var state: State? = null

    /**
     * Called by the first setup screen when it is created. Shows the prompt while the flow is
     * [State.Offered], and on success calls [onDone] once the user has read how the collection
     * follows. Declining or failing leaves the screen to the usual setup.
     */
    fun offer(
        activity: AppCompatActivity,
        onDone: (Next) -> Unit,
    ) {
        val current = state ?: begin(activity)
        state = current
        when (current) {
            State.Offered -> ask(activity, onDone)
            is State.Done -> guide(activity, current.next, onDone)
            State.NotNeeded, State.Importing, is State.Failed -> Unit
        }
    }

    /** Asks the package manager only in the new app; every other build is done at once. */
    private fun begin(context: Context): State {
        val role = AnkiquestMove.Role.of(context.packageName)
        if (role != AnkiquestMove.Role.NEW) {
            return State.NotNeeded
        }
        return start(role, AnkiquestMove.oldApp(context), account())
    }

    private fun account(): Account = if (Ankiquest.isConfigured()) Account.SIGNED_IN else Account.NONE

    private fun ask(
        activity: AppCompatActivity,
        onDone: (Next) -> Unit,
    ) {
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(R.string.ankiquest_move_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ankiquest_move_accept) { _, _ -> import(activity, onDone) }
            .setNegativeButton(R.string.ankiquest_move_decline) { _, _ -> state = decline(requireNotNull(state)) }
            .show()
    }

    private fun import(
        activity: AppCompatActivity,
        onDone: (Next) -> Unit,
    ) {
        state = accept(requireNotNull(state))
        activity.lifecycleScope.launch {
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        read(source(activity.applicationContext), AnkiDroidApp.sharedPrefs(), AnkiquestMovePayload.Exclusions.of(activity))
                    }
                } catch (e: CancellationException) {
                    state = interrupt(requireNotNull(state))
                    throw e
                }
            state = finish(requireNotNull(state), result)
            Timber.i("ankiquest move: %s", state)
            when (result) {
                is Result.Imported -> {
                    // The usual post-login path: capabilities and the first AnkiQuest refresh.
                    AnkiquestPoll.refreshNow(activity)
                    guide(activity, result.next, onDone)
                }
                is Result.Failed -> failed(activity)
            }
        }
    }

    /** Says how the collection follows, then continues the setup. */
    private fun guide(
        activity: AppCompatActivity,
        next: Next,
        onDone: (Next) -> Unit,
    ) {
        val message =
            when (next) {
                Next.SYNC -> R.string.ankiquest_move_done_sync
                Next.EXPORT -> R.string.ankiquest_move_done_export
            }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onDone(next)
                applyLanguage()
            }.show()
    }

    private fun failed(activity: AppCompatActivity) {
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(R.string.ankiquest_move_failed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** The moved app language takes effect as when it is chosen in the settings. */
    private fun applyLanguage() {
        val language = AnkiDroidApp.sharedPrefs().getString(LANGUAGE, null) ?: return
        LanguageUtil.setDefaultBackendLanguages(language)
        val tag = language.takeIf { it != LanguageUtil.SYSTEM_LANGUAGE_TAG }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private const val LANGUAGE = "language"

    private fun source(context: Context): Source =
        Source {
            context.contentResolver.call(AnkiquestMove.uri, AnkiquestMove.METHOD_EXPORT, null, null)?.getString(AnkiquestMove.KEY_PAYLOAD)
        }
}
