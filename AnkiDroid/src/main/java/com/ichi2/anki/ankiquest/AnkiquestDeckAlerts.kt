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
) : DefaultLifecycleObserver {
    private sealed interface State {
        data object Ready : State

        data class Working(
            val job: Job,
            val finished: () -> Unit,
        ) : State

        data class Paused(
            val refresh: () -> Unit,
        ) : State
    }

    private enum class Action { STOP, RESTORE }

    private enum class Result { CHANGED, ALREADY_STOPPED }

    private enum class Entry { PENDING, CONSUMED }

    private var state: State = State.Ready
    private val undo = AnkiquestUndo(host)
    private var entry = Entry.entries.firstOrNull { it.name == saved?.getString(STATE_ENTRY) } ?: Entry.PENDING

    init {
        host.lifecycle.addObserver(this)
    }

    fun busy(): Boolean = state is State.Working

    /** A notification action is explicit consent, once, for its original account. */
    private fun openRequest() {
        val intent = host.intent
        if (entry != Entry.PENDING || intent.action != ACTION || !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        entry = Entry.CONSUMED
        val account = AnkiquestHomeData.account()
        if (account == null || account.scope != intent.getStringExtra(EXTRA_SCOPE) ||
            account.notificationAccount != intent.getStringExtra(EXTRA_ACCOUNT)
        ) {
            say(R.string.aq_home_account_route_changed)
            return
        }
        if (intent.getLongExtra(EXTRA_NOTICE, 0) <= 0) return
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
        if (busy() || !owns(account) || sender.isBlank() || sender == account.user) return
        undo.apply(
            account,
            host.getString(R.string.aq_deck_alerts_stopped),
            perform = { done ->
                change(account, sender, Action.STOP, finished) { result ->
                    if (result == Result.CHANGED) done() else say(R.string.aq_deck_alerts_already_stopped)
                }
            },
            inverse = {
                change(account, sender, Action.RESTORE, finished) { say(R.string.aq_deck_alerts_restored) }
            },
        )
    }

    private fun owns(account: HomeAccount): Boolean =
        host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && AnkiquestHomeData.account() == account

    private fun change(
        account: HomeAccount,
        sender: String,
        action: Action,
        finished: () -> Unit,
        done: (Result) -> Unit,
    ) {
        if (busy() || !owns(account)) return
        val job =
            host.lifecycleScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!owns(account)) return@launch
                    val repository = AnkiquestSubscriptions.repository
                    val current = repository.load(account)
                    if (!owns(account)) return@launch

                    // An old alert may outlive its subscription. It must not create an Undo
                    // that subscribes someone who had already stopped these alerts.
                    val stopped = sender in current.unsubscribedSenders || current.senders.none { it.user == sender }
                    val result =
                        if (action == Action.STOP && stopped) {
                            Result.ALREADY_STOPPED
                        } else {
                            repository.setSubscribed(account, sender, action == Action.RESTORE)
                            Result.CHANGED
                        }
                    if (owns(account)) done(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest deck alert change failed")
                    if (owns(account)) say(R.string.aq_deck_alerts_failed)
                } finally {
                    if ((state as? State.Working)?.job == currentCoroutineContext()[Job]) {
                        state = State.Ready
                        if (owns(account)) finished()
                    }
                }
            }
        state = State.Working(job, finished)
        job.start()
    }

    private fun say(message: Int) = showThemedToast(host, host.getString(message), shortLength = false)

    override fun onPause(owner: LifecycleOwner) {
        val work = state as? State.Working ?: return
        state = State.Paused(work.finished)
        work.job.cancel()
    }

    override fun onResume(owner: LifecycleOwner) {
        (state as? State.Paused)?.let { paused ->
            state = State.Ready
            paused.refresh()
        }
        openRequest()
    }

    companion object {
        private const val ACTION = "com.ichi2.anki.ankiquest.STOP_DECK_ALERTS"
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
                .setAction(ACTION)
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
                    AnkiquestLanguage.context(context).getString(R.string.aq_deck_alerts_stop),
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
