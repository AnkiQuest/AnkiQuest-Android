// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads pending reviews and fetches the leaderboard and profile in the background, redraws the
 * widget and raises rank and streak notifications, keeps the server's capabilities current and syncs
 * settings ([AnkiquestSettingsSync]).
 * Runs every 15 minutes and on demand.
 */
object AnkiquestPoll {
    private const val PERIODIC_NAME = "ankiquestPoll"
    private const val NOW_NAME = "ankiquestPollNow"
    private const val DAY_END_NAME = "ankiquestPollDayEnd"
    private const val LAST_SUCCESS = "ankiquestLastSuccessfulPoll"
    private const val UNKNOWN_HOUR = -1

    /** How long after a study day ends the day-end poll runs, so the server already answers for the new day. */
    private const val DAY_END_DELAY_MS = 60_000L

    internal fun lastSuccessfulPoll(
        prefs: SharedPreferences,
        scope: String,
    ): Long? = prefs.getLong(scopedKey(LAST_SUCCESS, scope), 0L).takeIf { it > 0 }

    fun schedule(context: Context) {
        try {
            val request =
                PeriodicWorkRequestBuilder<AnkiquestPollWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not schedule polling")
        }
    }

    @Volatile
    private var dayEndQueued = 0L

    /**
     * Polls once just after [dayEndsAt], without waiting for a network, so the streak widgets show
     * the new day even when the periodic poll comes late. Offline, the poll draws the cached day.
     */
    internal fun atDayEnd(
        context: Context,
        dayEndsAt: Long,
    ) {
        // Every redraw asks again; only a new day end replaces the queued poll.
        if (dayEndsAt == dayEndQueued) {
            return
        }
        val delay = dayEndsAt + DAY_END_DELAY_MS - TimeManager.time.intTimeMS()
        if (delay <= 0) {
            return
        }
        try {
            val request =
                OneTimeWorkRequestBuilder<AnkiquestPollWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(DAY_END_NAME, ExistingWorkPolicy.REPLACE, request)
            dayEndQueued = dayEndsAt
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not schedule the day-end poll")
        }
    }

    fun refreshNow(context: Context) {
        try {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AnkiquestPollWorker>().build())
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not refresh")
        }
    }

    /**
     * Reviews not yet on the server are uploaded first, so the scores fetched next include them.
     * Presentation failures must not skip the private inbox; transient inbox failures need a retry.
     * Optional pictures come after the inbox, so unavailable photos cannot delay messages.
     */
    suspend fun run(context: Context): Result {
        Ankiquest.uploadPending()
        val prefs = AnkiDroidApp.sharedPrefs()
        val configured = Ankiquest.dashboardUrl() != null
        val pollingAccount = HomeAccount.current(prefs)
        val cacheScope = pollingAccount?.scope
        val cacheKey = scopedKey(AnkiquestWidget.PREF_CACHE, cacheScope)
        val cacheAtKey = scopedKey(AnkiquestWidget.PREF_CACHE_AT, cacheScope)
        val cacheLockKey = scopedKey(AnkiquestWidget.PREF_CACHE_LOCK, cacheScope)
        val photoAccount = runCatching { AnkiquestAvatars.account() }.getOrNull()
        val photoScope = photoAccount?.scope
        var photoBoard: JSONArray? = null
        var photoFetchedAt = 0L
        var photoOffline = false
        var photoLock: AnkiquestLock? = null
        try {
            if (!configured) {
                AnkiquestWidget.render(context, null, 0, offline = false, lock = null)
            } else {
                val board =
                    try {
                        AnkiquestBoards.weekly()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "ankiquest leaderboard fetch failed")
                        null
                    }
                if (board != null) {
                    val now = TimeManager.time.intTimeMS()
                    val lock = board.lock
                    prefs.edit {
                        putString(cacheKey, board.rows.toString())
                        putLong(cacheAtKey, now)
                        if (lock == null) {
                            remove(cacheLockKey)
                        } else {
                            putString(cacheLockKey, lock.name)
                        }
                    }
                    AnkiquestWidget.render(context, board.rows, now, offline = false, lock)
                    photoBoard = board.rows
                    photoFetchedAt = now
                    photoLock = board.lock
                    AnkiquestNotifier.onLeaderboard(context)
                } else {
                    // The cached board keeps its lock, so an offline widget still says why others are missing.
                    val cached = prefs.getString(cacheKey, null)?.let { JSONArray(it) } ?: JSONArray()
                    photoLock = AnkiquestWidget.cachedLock(cacheScope)
                    AnkiquestWidget.render(context, cached, prefs.getLong(cacheAtKey, 0), offline = true, photoLock)
                    photoBoard = cached
                    photoFetchedAt = prefs.getLong(cacheAtKey, 0)
                    photoOffline = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest leaderboard update failed")
        }
        if (!configured) return Result.success()
        try {
            AnkiquestWidget.refreshOwnBoards(context, photoBoard, photoLock)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest widget standings update failed")
        }
        try {
            val profile = Ankiquest.profile()
            if (HomeAccount.current(prefs)?.scope == cacheScope) {
                // A fresh board and profile confirm a background poll for this account.
                if (photoBoard != null && !photoOffline && cacheScope != null) {
                    prefs.edit { putLong(scopedKey(LAST_SUCCESS, cacheScope), TimeManager.time.intTimeMS()) }
                }
                val delivery = pollingAccount?.let { streakDelivery(it, profile) } ?: StreakDelivery.HOLD
                // Reading reminders can outlive an account or credential change.
                if (HomeAccount.current(prefs)?.scope == cacheScope) {
                    AnkiquestNotifier.onProfile(context, profile, delivery)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest profile fetch failed")
        }

        val result =
            try {
                Ankiquest.completionNotifications()?.let { (account, notifications, scope) ->
                    AnkiquestNotificationHealth.recordSuccessfulSync(scope, TimeManager.time.intTimeMS(), prefs)
                    AnkiquestNotifier.onDeckCompletions(context, account, notifications, scope)
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest completion inbox fetch failed")
                val transient =
                    e is IOException &&
                        (e !is AnkiquestHttpException || e.code == 408 || e.code == 429 || e.code in 500..599)
                if (transient) Result.retry() else Result.success()
            }
        photoBoard?.let { board ->
            val stillCurrent = {
                photoScope != null &&
                    photoScope == runCatching { AnkiquestAvatars.account()?.scope }.getOrNull() &&
                    prefs.getLong(cacheAtKey, 0) == photoFetchedAt &&
                    prefs.getString(cacheKey, null) == board.toString()
            }
            if (!stillCurrent()) return@let
            try {
                AnkiquestAvatars.refresh(
                    board.objects().map { it.getString("user") },
                    photoAccount,
                )
                if (stillCurrent()) AnkiquestWidget.render(context, board, photoFetchedAt, photoOffline, photoLock)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "ankiquest optional photos could not refresh")
            }
        }
        HomeAccount.current(prefs)?.let { AnkiquestCapabilities.current(it) }

        // After the features are current, so a server that just gained settings sync is used.
        AnkiquestSettingsSync.sync(context, AnkiquestSettingsSync.Trigger.POLL)

        // Unlocks reach a new sign-in and a server that has just gained them without opening Today;
        // after the features are current, so this reads them instead of asking early.
        try {
            HomeAccount.current(prefs)?.let { AnkiquestUnlocks.sync(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest unlocks could not be synced")
        }
        return result
    }

    private suspend fun streakDelivery(
        account: HomeAccount,
        profile: JSONObject,
    ): StreakDelivery {
        if (profile.optJSONObject("streak_warning") == null || !AnkiquestNotifier.streakReminder()) {
            return StreakDelivery.HOLD
        }
        return try {
            val reminders = AnkiquestCommunity.reminders(account)
            if (reminders.holds(profile.optInt("local_hour", UNKNOWN_HOUR))) {
                StreakDelivery.HOLD
            } else {
                StreakDelivery.ALLOW
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A failed preference read must not bypass quiet hours or consume today's warning.
            Timber.w(error, "ankiquest could not read quiet hours")
            StreakDelivery.HOLD
        }
    }
}

class AnkiquestPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = AnkiquestPoll.run(applicationContext)
}
