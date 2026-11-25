// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestMood.HopefulCount
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import com.ichi2.anki.ankiquest.AnkiquestMood.Rules
import org.json.JSONObject

/**
 * The secret debug menu's overrides for today's mood (in developer options): a forced mood or pose,
 * and mood thresholds other than `streak_moods.json`'s. They change only what this phone shows for
 * the player's own day (Home, the streak widget, the launcher icon, the heatmap and streak
 * reminders); the day verdict, the streak and everything sent to the server stay as computed. They
 * apply only while developer options are on, and every place that can say so marks them.
 */
internal object AnkiquestDebug {
    private const val PREF_TODAY = "ankiquestDebugToday"
    private const val PREF_RULES = "ankiquestDebugRules"

    /** Upstream's `developer_options_enabled_by_user_key`: the overrides follow developer options. */
    private const val PREF_DEVELOPER_OPTIONS = "devOptionsEnabledByUser"

    private const val MOOD_PREFIX = "mood:"
    private const val POSE_PREFIX = "pose:"

    /** What today shows: the computed mood, or one the debug menu forces. */
    sealed interface Today {
        /** The mood rule decides, as without the debug menu. */
        data object Computed : Today

        /** [mood] shows everywhere, with its own pose, flame and Home line. */
        data class ForcedMood(
            val mood: Mood,
        ) : Today

        /** The computed mood stays; only the companion's picture is [pose]. */
        data class ForcedPose(
            val pose: AnkiquestPose,
        ) : Today
    }

    /** Every override at once. [NONE] is the state without the debug menu. */
    data class Overrides(
        val today: Today,
        val rules: Rules,
    ) {
        /** Whether anything differs from what the app computes; the places that can say so mark it. */
        val active: Boolean get() = today != Today.Computed || rules != Rules.SHARED

        /** [computed], or the forced mood. */
        fun mood(computed: Mood): Mood =
            when (today) {
                is Today.ForcedMood -> today.mood
                Today.Computed, is Today.ForcedPose -> computed
            }

        /** The forced pose, or null when the mood decides the pose. */
        fun pose(): AnkiquestPose? = (today as? Today.ForcedPose)?.pose

        /** Forces [mood]; a forced pose is replaced. */
        fun forceMood(mood: Mood): Overrides = copy(today = Today.ForcedMood(mood))

        /** Forces [pose]; a forced mood is replaced. */
        fun forcePose(pose: AnkiquestPose): Overrides = copy(today = Today.ForcedPose(pose))

        /** The mood rule decides today again; the thresholds stay. */
        fun computeToday(): Overrides = copy(today = Today.Computed)

        /** Uses [rules]; null when they are not valid, so a typo never reaches the mood rule. */
        fun withRules(rules: Rules): Overrides? = if (rules.valid()) copy(rules = rules) else null

        /** Back to `streak_moods.json`'s thresholds; a forced mood or pose stays. */
        fun sharedRules(): Overrides = copy(rules = Rules.SHARED)

        companion object {
            val NONE = Overrides(Today.Computed, Rules.SHARED)
        }
    }

    /** The overrides in [prefs], or [Overrides.NONE] without them or while developer options are off. */
    fun current(prefs: SharedPreferences? = AnkiDroidApp.sharedPrefsOrNull()): Overrides {
        if (prefs == null || !developerOptions(prefs)) {
            return Overrides.NONE
        }
        return Overrides(today(prefs.getString(PREF_TODAY, null)), rules(prefs.getString(PREF_RULES, null)))
    }

    /** Saves [overrides]; [Overrides.NONE] removes every key. */
    fun save(
        overrides: Overrides,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        prefs.edit {
            // Each part is stored only while it differs from the computed state.
            when (val today = overrides.today) {
                Today.Computed -> remove(PREF_TODAY)
                is Today.ForcedMood -> putString(PREF_TODAY, MOOD_PREFIX + today.mood.name)
                is Today.ForcedPose -> putString(PREF_TODAY, POSE_PREFIX + today.pose.id)
            }
            if (overrides.rules == Rules.SHARED) {
                remove(PREF_RULES)
            } else {
                putString(PREF_RULES, json(overrides.rules).toString())
            }
        }
    }

    /** "Debug override", or null while nothing is overridden; Home and the widget show it. */
    fun marker(
        context: Context,
        overrides: Overrides = current(),
    ): String? = if (overrides.active) context.getString(R.string.ankiquest_debug_marker) else null

    private fun developerOptions(prefs: SharedPreferences): Boolean = BuildConfig.DEBUG || prefs.getBoolean(PREF_DEVELOPER_OPTIONS, false)

    /** The stored [Today]; anything unreadable, such as a mood a later version removed, is [Today.Computed]. */
    private fun today(value: String?): Today {
        if (value == null) {
            return Today.Computed
        }
        if (value.startsWith(MOOD_PREFIX)) {
            val name = value.removePrefix(MOOD_PREFIX)
            return Mood.entries.firstOrNull { it.name == name }?.let { Today.ForcedMood(it) } ?: Today.Computed
        }
        if (value.startsWith(POSE_PREFIX)) {
            val id = value.removePrefix(POSE_PREFIX)
            return AnkiquestPose.entries.firstOrNull { it.id == id }?.let { Today.ForcedPose(it) } ?: Today.Computed
        }
        return Today.Computed
    }

    /** The stored thresholds; missing or invalid ones are the shared ones. */
    private fun rules(value: String?): Rules {
        if (value == null) {
            return Rules.SHARED
        }
        val stored = runCatching { JSONObject(value) }.getOrNull() ?: return Rules.SHARED
        val shared = Rules.SHARED
        val rules =
            Rules(
                hopefulReviews = stored.optInt(KEY_HOPEFUL, shared.hopefulReviews),
                frozen2Days = stored.optInt(KEY_FROZEN_2, shared.frozen2Days),
                frozen3Days = stored.optInt(KEY_FROZEN_3, shared.frozen3Days),
                hotDays = stored.optInt(KEY_HOT, shared.hotDays),
                blazingDays = stored.optInt(KEY_BLAZING, shared.blazingDays),
                legendDays = stored.optInt(KEY_LEGEND, shared.legendDays),
                hopefulCount = hopefulCount(stored.optString(KEY_HOPEFUL_COUNT), shared.hopefulCount),
            )
        return rules.takeIf { it.valid() } ?: shared
    }

    private fun json(rules: Rules): JSONObject =
        JSONObject()
            .put(KEY_HOPEFUL, rules.hopefulReviews)
            .put(KEY_FROZEN_2, rules.frozen2Days)
            .put(KEY_FROZEN_3, rules.frozen3Days)
            .put(KEY_HOT, rules.hotDays)
            .put(KEY_BLAZING, rules.blazingDays)
            .put(KEY_LEGEND, rules.legendDays)
            .put(KEY_HOPEFUL_COUNT, rules.hopefulCount.name)

    /** The stored [HopefulCount]; a missing or unknown name is [shared]. */
    private fun hopefulCount(
        name: String,
        shared: HopefulCount,
    ): HopefulCount = HopefulCount.entries.firstOrNull { it.name == name } ?: shared

    private const val KEY_HOPEFUL = "hopeful_reviews"
    private const val KEY_FROZEN_2 = "frozen_2_days"
    private const val KEY_FROZEN_3 = "frozen_3_days"
    private const val KEY_HOT = "hot_days"
    private const val KEY_BLAZING = "blazing_days"
    private const val KEY_LEGEND = "legend_days"
    private const val KEY_HOPEFUL_COUNT = "hopeful_count"
}
