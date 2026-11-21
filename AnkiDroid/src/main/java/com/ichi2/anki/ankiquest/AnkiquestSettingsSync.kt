// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Player preferences kept the same on every device through `/api/settings/{user}`, on servers that
 * list [FEATURE]. The keys and the merge cases are `tests/fixtures/synced_settings.json` in
 * AnkiQuest-Server, shared with the desktop add-on. Each synced preference remembers when it last
 * changed on this phone, and the newer side wins, key by key.
 */
internal object AnkiquestSettingsSync {
    private const val FEATURE = "settings_sync"

    /** This device's switch; never synced. */
    const val PREF_SWITCH = "ankiquestSyncSettings"

    /** Shared key -> the value last seen here and when it changed on this phone; 0 for a value chosen before sync existed. */
    @VisibleForTesting
    internal const val PREF_CHANGED = "ankiquestSyncedSettingsChanged"

    /** The regular poll syncs at most this often for one account. */
    private const val POLL_INTERVAL_MS = 60_000L

    /** How a preference keeps its value on this phone. */
    private enum class Storage {
        BOOLEAN,

        /** A whole number kept as text, as a ListPreference does. */
        NUMBER_TEXT,
    }

    /** The synced keys this app has, with the preferences that hold them. */
    enum class Setting(
        val key: String,
        val pref: String,
        val default: Any,
        private val storage: Storage,
        private val range: IntRange? = null,
    ) {
        NOTIFY_RANK("notify_rank", AnkiquestNotifier.PREF_RANK, true, Storage.BOOLEAN),
        STREAK_WARNING_HOURS(
            "streak_warning_hours",
            AnkiquestNotifier.PREF_STREAK_HOURS,
            AnkiquestNotifier.DEFAULT_STREAK_HOURS.toInt(),
            Storage.NUMBER_TEXT,
            0..12,
        ),
        SESSION_SUMMARY("session_summary", AnkiquestNavigation.PREF_SESSION_SUMMARY, true, Storage.BOOLEAN),
        MOOD_ICON("mood_icon", AnkiquestIcon.PREF_ENABLED, true, Storage.BOOLEAN),
        OPEN_TODAY("open_today", AnkiquestNavigation.PREF_OPEN_TODAY, false, Storage.BOOLEAN),
        ASK_TO_SHARE_DECKS("ask_to_share_decks", AnkiquestDeckShare.PREF_ASK, true, Storage.BOOLEAN),
        STREAK_CUE("streak_cue", AnkiquestStreakCue.PREF_ENABLED, true, Storage.BOOLEAN),
        ;

        /** Whether this app can use [value] for this setting. */
        fun usable(value: Any?): Boolean =
            when (storage) {
                Storage.BOOLEAN -> value is Boolean
                Storage.NUMBER_TEXT -> value is Int && range?.contains(value) != false
            }

        /** The value in [prefs]; one this app cannot use counts as the default. */
        fun read(prefs: SharedPreferences): Any {
            val value =
                try {
                    when (storage) {
                        Storage.BOOLEAN -> prefs.getBoolean(pref, default as Boolean)
                        Storage.NUMBER_TEXT -> prefs.getString(pref, null)?.toIntOrNull() ?: default
                    }
                } catch (_: ClassCastException) {
                    default
                }
            return if (usable(value)) value else default
        }

        fun write(
            editor: SharedPreferences.Editor,
            value: Any,
        ) {
            when (storage) {
                Storage.BOOLEAN -> editor.putBoolean(pref, value as Boolean)
                Storage.NUMBER_TEXT -> editor.putString(pref, value.toString())
            }
        }
    }

    /** What starts a sync. */
    enum class Trigger {
        /** The app started; asks the server for its features when they are old. */
        START,

        /** The regular poll, after sign-in too; skipped when one ran for this account within [POLL_INTERVAL_MS]. */
        POLL,

        /** A synced preference or the switch changed here; waits for a running sync instead of skipping. */
        CHANGE,
    }

    /** This phone's value of a setting and when it last changed here. */
    data class Local(
        val value: Any,
        val changedAt: Long,
    )

    /** A `{value, updated_at}` entry as the server sends and receives it. */
    data class Entry(
        val value: Any,
        val updatedAt: Long,
    )

    /** Whether a sync that began for an account may still send requests or apply an answer. */
    private enum class Gate {
        OPEN,

        /** The switch went off; values and change times stay here and go out once it is on again. */
        SWITCHED_OFF,

        /** Another account signed in, or none. */
        ACCOUNT_CHANGED,
    }

    /** This phone's value and change time after a merge, and what to send ([push]), if anything. */
    data class Merged(
        val value: Any,
        val changedAt: Long,
        val push: Entry?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private val lock = Any()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed(key) }

    @Volatile
    private var app: Application? = null

    /** The account scope and time of the last sync, for [Trigger.POLL]. */
    @Volatile
    private var last: Pair<String, Long>? = null

    /** Follows changes to synced preferences and syncs once for the app's start. */
    fun start(app: Application) {
        listen(app)
        scope.launch { sync(app, Trigger.START) }
    }

    /** Records the time of each change to a synced preference and syncs it. */
    @VisibleForTesting
    internal fun listen(app: Application) {
        this.app = app
        AnkiDroidApp.sharedPrefs().registerOnSharedPreferenceChangeListener(listener)
    }

    /** Whether this phone syncs its settings. */
    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean =
        try {
            prefs.getBoolean(PREF_SWITCH, true)
        } catch (_: ClassCastException) {
            true
        }

    /**
     * Merges one setting: [remote] is the server's entry, null when it has none. Cases:
     * `client_merge` in the shared fixture.
     */
    fun merge(
        setting: Setting,
        local: Local,
        remote: Entry?,
    ): Merged {
        // The server's value wins a tie, as the server keeps the stored value then.
        if (remote != null && remote.updatedAt >= local.changedAt) {
            if (!setting.usable(remote.value)) {
                return Merged(local.value, local.changedAt, null)
            }
            return Merged(remote.value, remote.updatedAt, null)
        }

        // A value chosen before sync existed goes up only when it differs from the default.
        if (remote == null && local.changedAt == 0L && local.value == setting.default) {
            return Merged(local.value, local.changedAt, null)
        }
        return Merged(local.value, local.changedAt, Entry(local.value, local.changedAt))
    }

    /** Reads the server's settings, sends newer ones from here and applies newer ones from there. Never throws. */
    suspend fun sync(
        context: Context,
        trigger: Trigger,
    ) {
        try {
            when (trigger) {
                Trigger.CHANGE -> running.withLock { once(context, trigger) }
                Trigger.START, Trigger.POLL -> {
                    if (!running.tryLock()) {
                        return
                    }
                    try {
                        once(context, trigger)
                    } finally {
                        running.unlock()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest settings sync failed")
        }
    }

    private suspend fun once(
        context: Context,
        trigger: Trigger,
    ) {
        val account = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() } ?: return
        if (gate(account) != Gate.OPEN || !supported(account, trigger) || throttled(account, trigger)) {
            return
        }
        last = account.scope to TimeManager.time.intTimeMS()

        // Merge every key against what the server has.
        val start = locals()
        val remote = read(account) ?: return
        val merged = Setting.entries.associateWith { merge(it, start.getValue(it), remote[it.key]) }
        val stamps = merged.mapValuesTo(mutableMapOf<Setting, Long>()) { it.value.changedAt }
        val push = merged.mapNotNull { (setting, result) -> result.push?.let { setting to it } }.toMap()

        // Keep the server's time for what it stored, so a fast clock here does not resend forever.
        if (push.isNotEmpty()) {
            val saved = write(account, push) ?: return
            for ((setting, sent) in push) {
                val stored = saved[setting.key] ?: continue
                if (stored.value == sent.value) {
                    stamps[setting] = stored.updatedAt
                }
            }
        }
        settle(context, account, start, merged, stamps)
    }

    /** Whether [account]'s server syncs settings; a change does not wait for the server to say so. */
    private suspend fun supported(
        account: HomeAccount,
        trigger: Trigger,
    ): Boolean =
        when (trigger) {
            Trigger.CHANGE -> AnkiquestCapabilities.has(account, FEATURE)
            Trigger.START, Trigger.POLL -> FEATURE in AnkiquestCapabilities.current(account)
        }

    private fun throttled(
        account: HomeAccount,
        trigger: Trigger,
    ): Boolean {
        if (trigger != Trigger.POLL) {
            return false
        }
        val (lastScope, at) = last ?: return false
        return lastScope == account.scope && TimeManager.time.intTimeMS() - at in 0 until POLL_INTERVAL_MS
    }

    /** Each setting's value here and its change time; a change made while nothing listened counts as made now. */
    private fun locals(): Map<Setting, Local> =
        synchronized(lock) {
            val prefs = AnkiDroidApp.sharedPrefs()
            val records = records(prefs)
            val now = TimeManager.time.intTimeMS()
            val result =
                Setting.entries.associateWith { setting ->
                    val value = setting.read(prefs)
                    val record = records[setting]
                    when {
                        record == null -> Local(value, 0)
                        record.value == value -> record
                        else -> Local(value, now)
                    }
                }
            if (result != records) {
                save(prefs, result)
            }
            result
        }

    /**
     * Applies a finished sync. Dropped unless [gate] is still open; a setting changed here meanwhile
     * keeps its value and time, and the next sync sends it.
     */
    private fun settle(
        context: Context,
        account: HomeAccount,
        start: Map<Setting, Local>,
        merged: Map<Setting, Merged>,
        stamps: Map<Setting, Long>,
    ) {
        val applied = mutableSetOf<Setting>()
        synchronized(lock) {
            val prefs = AnkiDroidApp.sharedPrefs()
            if (gate(account, prefs) != Gate.OPEN) {
                return
            }
            val records = records(prefs).toMutableMap()
            prefs.edit {
                for (setting in Setting.entries) {
                    val before = start.getValue(setting)
                    if (setting.read(prefs) != before.value || records[setting] != before) {
                        continue
                    }
                    val value = merged.getValue(setting).value
                    records[setting] = Local(value, stamps.getValue(setting))
                    if (value != before.value) {
                        setting.write(this, value)
                        applied += setting
                    }
                }
                putString(PREF_CHANGED, encode(records))
            }
        }

        // The launcher icon follows its setting at once, as it does when changed in settings.
        if (Setting.MOOD_ICON in applied) {
            AnkiquestIcon.refresh(context, TimeManager.time.intTimeMS())
        }
    }

    /** Checked before each request and before applying an answer, as the switch or account can change at every wait. */
    private fun gate(
        account: HomeAccount,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Gate =
        when {
            AnkiquestHomeData.account(prefs)?.scope != account.scope -> Gate.ACCOUNT_CHANGED
            !enabled(prefs) -> Gate.SWITCHED_OFF
            else -> Gate.OPEN
        }

    /** Records the time of a change made here and syncs it. */
    private fun changed(key: String?) {
        val context = app ?: return
        if (key == PREF_SWITCH) {
            if (enabled()) {
                scope.launch { sync(context, Trigger.CHANGE) }
            }
            return
        }
        val setting = Setting.entries.firstOrNull { it.pref == key } ?: return
        if (note(setting) && enabled()) {
            scope.launch { sync(context, Trigger.CHANGE) }
        }
    }

    /** Stamps [setting] with the current time unless its value is the one last seen, as after a sync applied it. */
    @VisibleForTesting
    internal fun note(setting: Setting): Boolean =
        synchronized(lock) {
            val prefs = AnkiDroidApp.sharedPrefs()
            val value = setting.read(prefs)
            val records = records(prefs)
            if (records[setting]?.value == value) {
                return false
            }
            save(prefs, records + (setting to Local(value, TimeManager.time.intTimeMS())))
            true
        }

    /** The server's entries; null when [gate] closed before the request went out. */
    private suspend fun read(account: HomeAccount): Map<String, Entry>? = call(account, "GET", null)?.let { entries(it) }

    /** The server's entries after storing [push]; null when [gate] closed before the request went out. */
    private suspend fun write(
        account: HomeAccount,
        push: Map<Setting, Entry>,
    ): Map<String, Entry>? {
        val settings = JSONObject()
        for ((setting, entry) in push) {
            settings.put(setting.key, JSONObject().put("value", entry.value).put("updated_at", entry.updatedAt))
        }
        return call(account, "PUT", JSONObject().put("settings", settings))?.let { entries(it) }
    }

    /**
     * One request for [account], which must still be the signed-in one when it is sent and when it
     * answers. Not sent, and null, unless [gate] is open.
     */
    private suspend fun call(
        account: HomeAccount,
        method: String,
        body: JSONObject?,
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            // Check after dispatch too: the switch can go off while this request waits for IO.
            if (gate(account) != Gate.OPEN) {
                return@withContext null
            }
            AnkiquestApi.call(account, method, "api/settings/${account.encodedUser}", body)
        }

    /** The well-formed entries of an answer's `settings`; values that are whole numbers become [Int] where they fit. */
    @VisibleForTesting
    internal fun entries(answer: JSONObject): Map<String, Entry> {
        val settings = answer.optJSONObject("settings") ?: return emptyMap()
        val result = mutableMapOf<String, Entry>()
        for (key in settings.keys()) {
            val entry = settings.optJSONObject(key) ?: continue
            val at = (entry.opt("updated_at") as? Number)?.takeIf { it is Int || it is Long }?.toLong() ?: continue
            if (at < 0 || !entry.has("value")) {
                continue
            }
            result[key] = Entry(number(entry.get("value")), at)
        }
        return result
    }

    private fun number(value: Any): Any = if (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value

    private fun records(prefs: SharedPreferences): Map<Setting, Local> {
        val stored =
            try {
                JSONObject(prefs.getString(PREF_CHANGED, null) ?: return emptyMap())
            } catch (_: JSONException) {
                return emptyMap()
            } catch (_: ClassCastException) {
                return emptyMap()
            }
        return Setting.entries
            .mapNotNull { setting ->
                val record = stored.optJSONObject(setting.key) ?: return@mapNotNull null
                val at = record.optLong("at", -1)
                if (at < 0 || !record.has("value")) {
                    return@mapNotNull null
                }
                setting to Local(number(record.get("value")), at)
            }.toMap()
    }

    private fun save(
        prefs: SharedPreferences,
        records: Map<Setting, Local>,
    ) = prefs.edit { putString(PREF_CHANGED, encode(records)) }

    private fun encode(records: Map<Setting, Local>): String {
        val result = JSONObject()
        for ((setting, local) in records) {
            result.put(setting.key, JSONObject().put("value", local.value).put("at", local.changedAt))
        }
        return result.toString()
    }
}
