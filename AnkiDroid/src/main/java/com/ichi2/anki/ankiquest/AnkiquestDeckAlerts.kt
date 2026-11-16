// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import timber.log.Timber

/** Stop a sender's deck alerts from their alert, without changing other preferences. */
internal class AnkiquestDeckAlerts(
    private val host: FragmentActivity,
    saved: Bundle? = null,
    private val undo: AnkiquestUndo = AnkiquestUndo(host),
    private val changed: () -> Unit = {},
) : DefaultLifecycleObserver {
    private sealed interface State {
        data object Ready : State

        data class Working(
            val job: Job,
            val request: Request,
            val phase: Phase,
        ) : State

        data class Paused(
            val request: Request,
        ) : State

        data class Settled(
            val request: Request,
            val result: Result,
        ) : State
    }

    private enum class Phase { READING, WRITING }

    private enum class Action { STOP, RESTORE }

    private enum class Result { CHANGED, ALREADY_STOPPED, UNAVAILABLE, FAILED }

    private enum class Entry { PENDING, CONSUMED }

    private data class Request(
        val account: HomeAccount,
        val sender: String,
        val action: Action,
        val finished: () -> Unit,
    )

    private sealed interface Known {
        data object Unknown : Known

        data class Settings(
            val account: HomeAccount,
            val value: IncomingSubscriptions,
        ) : Known
    }

    private var state: State = State.Ready
    private var known: Known = Known.Unknown
    private var entry = Entry.entries.firstOrNull { it.name == saved?.getString(STATE_ENTRY) } ?: Entry.PENDING

    init {
        host.lifecycle.addObserver(this)
    }

    fun busy(): Boolean = state is State.Working

    /** Hide an action once a subscription response says it cannot change anything. */
    fun canStop(
        account: HomeAccount,
        sender: String,
    ): Boolean {
        if (sender.isBlank() || sender == account.user) {
            return false
        }
        val settings = (known as? Known.Settings)?.takeIf { it.account == account }?.value ?: return true
        return sender !in settings.unsubscribedSenders && settings.senders.any { it.user == sender }
    }

    /** A notification action is explicit consent, once, for its original account. */
    private fun openRequest() {
        val intent = host.intent
        if (entry != Entry.PENDING || intent.action != ACTION_STOP ||
            !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }
        entry = Entry.CONSUMED
        val account = AnkiquestHomeData.account()
        if (account == null || account.scope != intent.getStringExtra(EXTRA_SCOPE) ||
            account.notificationAccount != intent.getStringExtra(EXTRA_ACCOUNT)
        ) {
            say(R.string.aq_home_account_route_changed)
            return
        }
        if (intent.getLongExtra(EXTRA_NOTICE, 0) <= 0) {
            return
        }
        stop(account, intent.getStringExtra(AnkiquestProfileActivity.EXTRA_USER).orEmpty())
    }

    fun save(out: Bundle) {
        out.putString(STATE_ENTRY, entry.name)
    }

    fun stop(
        account: HomeAccount,
        sender: String,
        finished: () -> Unit = {},
    ) {
        if (busy() || !owns(account) || sender.isBlank() || sender == account.user) {
            return
        }
        undo.clear()
        change(Request(account, sender, Action.STOP, finished))
    }

    private fun owns(account: HomeAccount): Boolean =
        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && AnkiquestHomeData.account() == account

    private fun change(request: Request) {
        val (account, sender, action) = request
        if (busy() || !owns(account)) {
            return
        }
        val job =
            host.lifecycleScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!owns(account)) {
                        return@launch
                    }
                    val repository = AnkiquestSubscriptions.repository
                    val current = repository.load(account)
                    if (!owns(account)) {
                        return@launch
                    }
                    known = Known.Settings(account, current)

                    // An old alert may outlive its subscription. It must not create an Undo
                    // that subscribes someone who had already stopped these alerts.
                    val result =
                        when {
                            current.senders.none { it.user == sender } -> Result.UNAVAILABLE
                            action == Action.STOP && sender in current.unsubscribedSenders -> Result.ALREADY_STOPPED
                            else -> {
                                // Once sent, let the write settle through pause. Its result is
                                // presented only when this screen owns the same account again.
                                state = State.Working(currentCoroutineContext()[Job]!!, request, Phase.WRITING)
                                val saved = repository.setSubscribed(account, sender, action == Action.RESTORE)
                                if (AnkiquestHomeData.account() == account) {
                                    known = Known.Settings(account, saved)
                                }
                                Result.CHANGED
                            }
                        }
                    settle(request, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest deck alert change failed")
                    settle(request, Result.FAILED)
                } finally {
                    if ((state as? State.Working)?.job == currentCoroutineContext()[Job]) {
                        state = State.Ready
                        if (owns(account)) {
                            changed()
                        }
                    }
                }
            }
        state = State.Working(job, request, Phase.READING)
        // Both Stop and its inverse must disable competing controls before their read starts.
        changed()
        job.start()
    }

    private fun settle(
        request: Request,
        result: Result,
    ) {
        state = State.Ready
        if (AnkiquestHomeData.account() != request.account) {
            return
        }
        if (!owns(request.account)) {
            state = State.Settled(request, result)
            return
        }
        present(request, result)
    }

    private fun present(
        request: Request,
        result: Result,
    ) {
        val (account, sender, action, finished) = request
        if (!owns(account)) {
            return
        }
        when (result) {
            Result.CHANGED -> {
                if (action == Action.STOP) {
                    // Start a fresh snackbar after success, including a write settled while paused.
                    undo.apply(
                        account,
                        host.getString(R.string.aq_deck_alerts_stopped, sender),
                        perform = { done -> done() },
                        inverse = { change(request.copy(action = Action.RESTORE)) },
                    )
                } else {
                    say(R.string.aq_deck_alerts_restored, sender)
                }
            }
            Result.ALREADY_STOPPED -> say(R.string.aq_deck_alerts_already_stopped, sender)
            Result.UNAVAILABLE -> say(R.string.aq_deck_alerts_unavailable, sender)
            Result.FAILED -> say(R.string.aq_deck_alerts_failed)
        }
        finished()
        if (owns(account)) {
            changed()
        }
    }

    private fun say(
        message: Int,
        vararg args: Any,
    ) = showThemedToast(host, host.getString(message, *args), shortLength = false)

    override fun onPause(owner: LifecycleOwner) {
        val work = state as? State.Working ?: return
        if (work.phase == Phase.WRITING) {
            return
        }
        state = State.Paused(work.request)
        work.job.cancel()
    }

    override fun onResume(owner: LifecycleOwner) {
        when (val pending = state) {
            is State.Paused -> {
                state = State.Ready
                if (owns(pending.request.account)) {
                    pending.request.finished()
                    changed()
                }
            }
            is State.Settled -> {
                state = State.Ready
                present(pending.request, pending.result)
            }
            else -> Unit
        }
        openRequest()
    }

    companion object {
        private const val ACTION_STOP = "com.ichi2.anki.ankiquest.STOP_DECK_ALERTS"
        private const val EXTRA_SCOPE = "ankiquest.stop_deck_scope"
        private const val EXTRA_ACCOUNT = "ankiquest.stop_deck_account"
        private const val EXTRA_NOTICE = "ankiquest.stop_deck_notice"
        private const val STATE_ENTRY = "ankiquest.stop_deck_entry"

        fun intent(
            context: Context,
            account: HomeAccount,
            sender: String,
            notification: Long,
        ): Intent =
            AnkiquestProfileActivity
                .intent(context, sender)
                .setAction(ACTION_STOP)
                .setData(
                    Uri
                        .Builder()
                        .scheme("ankiquest")
                        .authority("deck-alerts")
                        .appendPath(account.scope)
                        .appendPath(notification.toString())
                        .build(),
                ).putExtra(EXTRA_SCOPE, account.scope)
                .putExtra(EXTRA_ACCOUNT, account.notificationAccount)
                .putExtra(EXTRA_NOTICE, notification)

        fun action(
            context: Context,
            notification: Long,
            tag: Int,
            account: String?,
            scope: String?,
            sender: String?,
            kind: String,
        ): NotificationCompat.Action? {
            val current = AnkiquestHomeData.account() ?: return null
            if (kind != "completion" || notification <= 0 || sender.isNullOrBlank() || sender == current.user ||
                current.token.isEmpty() || current.scope != scope || current.notificationAccount != account
            ) {
                return null
            }
            return NotificationCompat.Action
                .Builder(
                    R.drawable.ic_star_notify,
                    AnkiquestLanguage.context(context).getString(R.string.aq_deck_alerts_stop, sender),
                    PendingIntent.getActivity(
                        context,
                        tag,
                        intent(context, current, sender, notification),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
        }
    }
}
