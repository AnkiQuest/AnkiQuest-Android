// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.utils.LanguageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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

    /** What to do after importing settings. Shared folders already contain the collection. */
    enum class Next {
        /** An AnkiWeb login moved: sync. */
        SYNC,

        /** No AnkiWeb login: show how to export and import if the apps use separate folders. */
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

    /** A provider reply has no side effects until the active setup screen accepts it. */
    private sealed interface Payload {
        data class Ready(
            val settings: Map<String, Any>,
        ) : Payload

        data class Failed(
            val reason: Reason,
        ) : Payload
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
    ): Result = apply(load(source), prefs, exclusions)

    /** Read and validate on the worker thread; do not write preferences there. */
    private fun load(source: Source): Payload {
        val text =
            try {
                source.read()
            } catch (e: SecurityException) {
                return Payload.Failed(Reason.REFUSED)
            } catch (e: Exception) {
                // The provider is gone or crashed; its message carries nothing of the payload.
                Timber.w(e, "ankiquest move: old app unavailable")
                return Payload.Failed(Reason.UNAVAILABLE)
            }
        if (text == null) {
            return Payload.Failed(Reason.UNAVAILABLE)
        }

        val settings =
            when (val decoded = AnkiquestMovePayload.decode(text)) {
                is AnkiquestMovePayload.Decoded.Read -> decoded.settings
                is AnkiquestMovePayload.Decoded.UnknownVersion -> return Payload.Failed(Reason.UNKNOWN_VERSION)
                AnkiquestMovePayload.Decoded.Malformed -> return Payload.Failed(Reason.MALFORMED)
            }
        return Payload.Ready(settings)
    }

    /** Commit the in-memory settings and result together, after the cancelled-read boundary. */
    private fun apply(
        payload: Payload,
        prefs: SharedPreferences,
        exclusions: AnkiquestMovePayload.Exclusions,
    ): Result {
        val settings =
            when (payload) {
                is Payload.Failed -> return Result.Failed(payload.reason)
                is Payload.Ready -> payload.settings
            }
        AnkiquestMovePayload.apply(prefs, settings, exclusions)
        val next = if (prefs.getString(PREF_HKEY, null).isNullOrEmpty()) Next.EXPORT else Next.SYNC
        return Result.Imported(next)
    }

    private const val PREF_HKEY = "hkey"

    private val state = MutableStateFlow<State?>(null)

    /**
     * Called by the first setup screen when it is created. Shows the prompt while the flow is
     * [State.Offered], and on success calls [onDone] once the user has read how the collection
     * follows. Declining or failing leaves the screen to the usual setup.
     */
    fun offer(
        activity: AppCompatActivity,
        onDone: (Next) -> Unit,
    ) {
        if (state.value == null) {
            state.value = begin(activity)
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.filterNotNull().collectLatest { current ->
                    val dialog =
                        when (current) {
                            State.Offered -> ask(activity)
                            State.Importing -> waiting(activity)
                            is State.Done -> guide(activity, current.next, onDone)
                            is State.Failed -> failed(activity)
                            State.NotNeeded -> null
                        }
                    // Each visible screen owns its dialogs; recreation observes the latest state.
                    try {
                        awaitCancellation()
                    } finally {
                        dialog?.dismiss()
                    }
                }
            }
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

    private fun ask(activity: AppCompatActivity): AlertDialog =
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(R.string.ankiquest_move_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ankiquest_move_accept) { _, _ -> import(activity) }
            .setNegativeButton(R.string.ankiquest_move_decline) { _, _ -> state.value = decline(requireNotNull(state.value)) }
            .show()

    /** Keep the setup buttons covered while importing, so they cannot start another login. */
    private fun waiting(activity: AppCompatActivity): AlertDialog =
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(R.string.ankiquest_move_loading)
            .setCancelable(false)
            .show()

    private fun import(activity: AppCompatActivity) {
        state.value = accept(requireNotNull(state.value))
        activity.lifecycleScope.launch {
            val payload =
                try {
                    withContext(Dispatchers.IO) {
                        load(source(activity.applicationContext))
                    }
                } catch (e: CancellationException) {
                    state.value = interrupt(requireNotNull(state.value))
                    throw e
                }
            // withContext checks cancellation before returning to main. A closed screen never
            // applies a late provider reply; applying and publishing the result do not suspend.
            val result = apply(payload, AnkiDroidApp.sharedPrefs(), AnkiquestMovePayload.Exclusions.of(activity))
            state.value = finish(requireNotNull(state.value), result)
            Timber.i("ankiquest move: %s", state.value)
            if (result is Result.Imported) {
                AnkiquestPoll.refreshNow(activity)
            }
        }
    }

    /** Says how the collection follows, then continues the setup. */
    private fun guide(
        activity: AppCompatActivity,
        next: Next,
        onDone: (Next) -> Unit,
    ): AlertDialog {
        val message =
            when (next) {
                Next.SYNC -> R.string.ankiquest_move_done_sync
                Next.EXPORT -> R.string.ankiquest_move_done_export
            }
        return AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                state.value = State.NotNeeded
                onDone(next)
                applyLanguage()
            }.show()
    }

    private fun failed(activity: AppCompatActivity): AlertDialog =
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankiquest_move_title)
            .setMessage(R.string.ankiquest_move_failed)
            .setPositiveButton(android.R.string.ok) { _, _ -> state.value = State.NotNeeded }
            .show()

    /** The moved app language takes effect as when it is chosen in the settings. */
    private fun applyLanguage() {
        val language = AnkiDroidApp.sharedPrefs().getString(PREF_LANGUAGE, null) ?: return
        LanguageUtil.setDefaultBackendLanguages(language)
        val tag = language.takeIf { it != LanguageUtil.SYSTEM_LANGUAGE_TAG }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private const val PREF_LANGUAGE = "language"

    private fun source(context: Context): Source =
        Source {
            context.contentResolver.call(AnkiquestMove.uri, AnkiquestMove.METHOD_EXPORT, null, null)?.getString(AnkiquestMove.KEY_PAYLOAD)
        }
}
