// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * The companion's mood on the app icon, like a pet waiting on the home screen. On by default where the build
 * has the moods; launchers redraw the icon when it changes, and some briefly drop it from the home
 * screen while they do.
 *
 * Each [Icon] aliases AnkiquestLauncher, which forwards into DeckPicker's stable task so disabling
 * an alias cannot finish a review. Exactly one is enabled. Builds without the aliases keep
 * IntentHandler's launcher entry.
 */
internal object AnkiquestIcon : DefaultLifecycleObserver {
    const val PREF_ENABLED = "ankiquestMoodIcon"
    private const val AKI = "aki"
    private const val ANKI_TAN = "anki_tan"
    private const val PREF_CURRENT = "ankiquestMoodIconCurrent"
    private const val PREF_LAST_PROFILE = "ankiquestMoodIconProfile"

    private val lock = Any()
    private val changes = Any()
    private var visibility: Visibility = Visibility.Background

    private sealed interface Visibility {
        data object Background : Visibility

        data class Visible(
            val pending: Pending?,
        ) : Visibility
    }

    private data class Pending(
        val context: Context,
        val prefs: SharedPreferences,
    )

    enum class Mood {
        DEFAULT,
        HAPPY,
        WORRIED,
        FROZEN,
        SLEEPY,
    }

    /** A launcher alias: one companion's face for one mood. Companions without their own faces use Aki's. */
    enum class Icon(
        val alias: String,
        val companion: String,
        val mood: Mood,
    ) {
        AKI_DEFAULT("AnkiquestIconDefault", AKI, Mood.DEFAULT),
        AKI_HAPPY("AnkiquestIconHappy", AKI, Mood.HAPPY),
        AKI_WORRIED("AnkiquestIconWorried", AKI, Mood.WORRIED),
        AKI_FROZEN("AnkiquestIconFrozen", AKI, Mood.FROZEN),
        AKI_SLEEPY("AnkiquestIconSleepy", AKI, Mood.SLEEPY),

        // Shigeyuki's Anki-tan has no frozen face, so her frozen icon is her default one.
        ANKI_TAN_DEFAULT("AnkiquestIconAnkiTanDefault", ANKI_TAN, Mood.DEFAULT),
        ANKI_TAN_HAPPY("AnkiquestIconAnkiTanHappy", ANKI_TAN, Mood.HAPPY),
        ANKI_TAN_WORRIED("AnkiquestIconAnkiTanWorried", ANKI_TAN, Mood.WORRIED),
        ANKI_TAN_SLEEPY("AnkiquestIconAnkiTanSleepy", ANKI_TAN, Mood.SLEEPY),
        ;

        companion object {
            /** [companion]'s face for [mood], its default face when it has none for [mood], or Aki's. */
            fun of(
                companion: String,
                mood: Mood,
            ): Icon {
                val faces = entries.filter { it.companion == companion }.ifEmpty { entries.filter { it.companion == AKI } }
                return faces.firstOrNull { it.mood == mood } ?: faces.first { it.mood == Mood.DEFAULT }
            }
        }
    }

    /** The mood for your [profile] at [now], as [AnkiquestMood] has it: worried from the start of a day not studied yet. */
    fun mood(
        profile: JSONObject?,
        now: Long,
    ): Mood {
        val server = AnkiquestDay.Server.profile(profile) ?: return Mood.DEFAULT
        return mood(AnkiquestDay.judge(AnkiquestDay.carried(server, now), emptyList(), now))
    }

    private fun ownMood(
        server: AnkiquestDay.Server?,
        now: Long,
        prefs: SharedPreferences,
    ): Mood {
        if (server == null) return Mood.DEFAULT
        return mood(AnkiquestDay.judgeOwn(AnkiquestDay.carried(server, now), now, prefs))
    }

    /** The icon for [day]'s mood; the icon has one frozen face, also for thawing, and none for hopeful or almost. */
    private fun mood(day: AnkiquestDay.Verdict?): Mood =
        when (AnkiquestMood.of(day, 0, AnkiquestMood.Goal.OPEN)) {
            AnkiquestMood.Mood.FIRE -> Mood.HAPPY
            AnkiquestMood.Mood.SAD -> Mood.WORRIED
            AnkiquestMood.Mood.FROZEN_1,
            AnkiquestMood.Mood.FROZEN_2,
            AnkiquestMood.Mood.FROZEN_3,
            AnkiquestMood.Mood.THAWING,
            -> Mood.FROZEN
            AnkiquestMood.Mood.ASLEEP -> Mood.SLEEPY
            AnkiquestMood.Mood.HOPEFUL, AnkiquestMood.Mood.ALMOST, AnkiquestMood.Mood.UNKNOWN -> Mood.DEFAULT
        }

    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_ENABLED, true)

    /** Whether this build has the mood aliases; only the quest build does. */
    fun available(context: Context): Boolean =
        try {
            context.packageManager.getActivityInfo(component(context, Icon.AKI_DEFAULT), PackageManager.MATCH_DISABLED_COMPONENTS)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Requests the mood for [profile], or the plain icon when off, and remembers [profile] for [showLastKnown]. */
    fun update(
        context: Context,
        profile: JSONObject?,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (profile != null) remember(profile, prefs)
        val mood = if (enabled(prefs)) ownMood(AnkiquestDay.Server.profile(profile), now, prefs) else Mood.DEFAULT
        show(context, mood, prefs)
    }

    /** Shows the mood, or the plain icon when off, after the setting changed outside the settings screen. */
    fun refresh(
        context: Context,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (enabled(prefs)) {
            showLastKnown(context, now, prefs)
        } else {
            show(context, Mood.DEFAULT, prefs)
        }
    }

    /**
     * Requests the mood for the last profile without waiting for the next poll, as when the setting
     * is turned on. Until a profile has been seen that is the plain icon.
     */
    fun showLastKnown(
        context: Context,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = show(context, lastMood(now, prefs), prefs)

    private fun lastMood(
        now: Long,
        prefs: SharedPreferences,
    ): Mood {
        val server = AnkiquestDay.last(prefs) ?: AnkiquestDay.Server.profile(lastKnown(prefs))
        return ownMood(server, now, prefs)
    }

    /**
     * Queue a refresh on resume. The current account and day are read again when the app stops.
     */
    fun onAppStart(
        context: Context,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        try {
            if (enabled(prefs)) {
                showLastKnown(context, TimeManager.time.intTimeMS(), prefs)
            }
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest icon could not catch up")
        }
    }

    /**
     * The parts of the configured account's last profile that the mood and the streak widget read;
     * another account's are never used.
     */
    internal fun lastKnown(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): JSONObject? =
        prefs.getString(lastProfileKey(prefs), null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun remember(
        profile: JSONObject,
        prefs: SharedPreferences,
    ) {
        val kept =
            JSONObject()
                .put("streak", profile.optLong("streak"))
                .put("streak_state", profile.optString("streak_state"))
                .put("day_ends_at", profile.optLong("day_ends_at"))
                .put("goal_reached", profile.optJSONObject("goal")?.optBoolean("reached") == true)
                // The streak widget's frozen mood needs the run of frozen days before today.
                .put("frozen_days", AnkiquestMood.frozenRun(profile.optJSONArray("heatmap")))
                .put(
                    "goal",
                    profile.optJSONObject("goal")?.let { goal ->
                        JSONObject().apply {
                            for (key in listOf("mode", "value", "progress", "reached")) {
                                if (goal.has(key)) put(key, goal.get(key))
                            }
                        }
                    },
                ).toString()
        val key = lastProfileKey(prefs)
        if (prefs.getString(key, null) != kept) prefs.edit { putString(key, kept) }
    }

    private fun lastProfileKey(prefs: SharedPreferences) = scopedKey(PREF_LAST_PROFILE, AnkiquestHomeData.account(prefs)?.scope)

    /**
     * Requests [companion]'s face for [mood]. Android can finish tasks when an alias is disabled,
     * so wait until the last activity stops.
     */
    fun show(
        context: Context,
        mood: Mood,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
        companion: String = AnkiquestCompanion.selected(prefs),
    ) {
        val apply =
            synchronized(lock) {
                when (visibility) {
                    Visibility.Background -> true
                    is Visibility.Visible -> {
                        visibility = Visibility.Visible(Pending(context.applicationContext, prefs))
                        false
                    }
                }
            }
        if (apply) {
            synchronized(changes) { applyIcon(context, Icon.of(companion, mood), prefs) }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(lock) {
            visibility = Visibility.Visible(null)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val pending =
            synchronized(lock) {
                val pending = (visibility as? Visibility.Visible)?.pending
                visibility = Visibility.Background
                pending
            } ?: return
        applicationScope.launch(Dispatchers.IO) { applyPending(pending) }
    }

    private fun applyPending(pending: Pending) {
        synchronized(changes) {
            synchronized(lock) {
                val visible = visibility as? Visibility.Visible
                if (visible != null) {
                    // A quick return keeps the refresh pending until the next stop.
                    if (visible.pending == null) {
                        visibility = Visibility.Visible(pending)
                    }
                    return
                }
            }
            // Serialize launcher writes without blocking lifecycle callbacks on binder calls.
            // A switch already started can finish safely because the task uses the stable launcher.
            val prefs = pending.prefs
            val mood = if (enabled(prefs)) lastMood(TimeManager.time.intTimeMS(), prefs) else Mood.DEFAULT
            applyIcon(pending.context, Icon.of(AnkiquestCompanion.selected(prefs), mood), prefs)
        }
    }

    /** Check the actual components so a failed or partial switch can be retried. */
    private fun applyIcon(
        context: Context,
        icon: Icon,
        prefs: SharedPreferences,
    ) {
        if (!available(context)) {
            return
        }
        val packageManager = context.packageManager
        try {
            val wrong = Icon.entries.filter { enabled(packageManager, context, it) != (it == icon) }
            if (wrong.isEmpty()) {
                if (prefs.getString(PREF_CURRENT, null) != icon.name) prefs.edit { putString(PREF_CURRENT, icon.name) }
                return
            }
            // Enable the new icon before disabling the old one, so there is never no launcher entry.
            for (other in wrong.sortedBy { it != icon }) {
                packageManager.setComponentEnabledSetting(
                    component(context, other),
                    wanted(other, other == icon),
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (Icon.entries.all { enabled(packageManager, context, it) == (it == icon) }) {
                prefs.edit { putString(PREF_CURRENT, icon.name) }
            } else {
                prefs.edit { remove(PREF_CURRENT) }
                Timber.w("AnkiQuest icon did not change to %s", icon)
            }
        } catch (e: Exception) {
            prefs.edit { remove(PREF_CURRENT) }
            Timber.w(e, "AnkiQuest icon could not change to %s", icon)
        }
    }

    /** The icon last switched to completely, or null when unknown or a switch failed. */
    internal fun current(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Icon? =
        Icon.entries.firstOrNull { it.name == prefs.getString(PREF_CURRENT, null) }

    /** The setting that makes [icon]'s alias [on]: Aki's plain icon is enabled in the manifest, the others are not. */
    private fun wanted(
        icon: Icon,
        on: Boolean,
    ): Int =
        when {
            on == (icon == Icon.AKI_DEFAULT) -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

    /** Whether [icon]'s alias is a launcher entry now. */
    private fun enabled(
        packageManager: PackageManager,
        context: Context,
        icon: Icon,
    ): Boolean =
        when (packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Icon.AKI_DEFAULT
            else -> false
        }

    private fun component(
        context: Context,
        icon: Icon,
    ) = ComponentName(context.packageName, "com.ichi2.anki.ankiquest.${icon.alias}")
}
