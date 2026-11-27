// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDebug.Overrides
import com.ichi2.anki.ankiquest.AnkiquestDebug.Today
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import com.ichi2.anki.ankiquest.AnkiquestMood.Rules
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast

/**
 * The secret debug menu for today's mood, in AnkiDroid's developer options (tap the logo in About
 * until they open). It edits [AnkiquestDebug]'s overrides and redraws every place that shows them.
 * Developer options are English only, as upstream's, so its texts are not translated.
 */
internal object AnkiquestDebugMenu {
    private const val PREFERENCE_KEY = "ankiquest_debug_menu"

    /** Adds the menu's row to [fragment], the developer options screen. */
    fun attach(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        val row =
            Preference(context).apply {
                key = PREFERENCE_KEY
                isPersistent = false
                title = context.getString(R.string.ankiquest_debug_title)
                summary = describe(context, AnkiquestDebug.current())
                setOnPreferenceClickListener {
                    show(context) { summary = describe(context, it) }
                    true
                }
            }
        fragment.preferenceScreen.addPreference(row)
    }

    /** The main menu; [onChanged] gets the overrides after each change. */
    private fun show(
        context: Context,
        onChanged: (Overrides) -> Unit,
    ) {
        val overrides = AnkiquestDebug.current()
        val items =
            arrayOf(
                context.getString(R.string.ankiquest_debug_mood, moodName(overrides.today)),
                context.getString(R.string.ankiquest_debug_pose, poseName(overrides.today)),
                context.getString(R.string.ankiquest_debug_thresholds),
                context.getString(R.string.ankiquest_debug_clear),
            )
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_debug_title)
            .setItems(items) { _, which ->
                // Each item edits one part of the overrides; the rest stay.
                val save = { next: Overrides -> store(context, next, onChanged) }
                when (which) {
                    0 -> chooseMood(context, overrides, save)
                    1 -> choosePose(context, overrides, save)
                    2 -> editRules(context, overrides, save)
                    else -> save(Overrides.NONE)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseMood(
        context: Context,
        overrides: Overrides,
        save: (Overrides) -> Unit,
    ) {
        // The first choice lets the mood rule decide again.
        val names = listOf(context.getString(R.string.ankiquest_debug_computed)) + Mood.entries.map { it.name.lowercase() }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_debug_mood_title)
            .setItems(names.toTypedArray()) { _, which ->
                save(if (which == 0) overrides.computeToday() else overrides.forceMood(Mood.entries[which - 1]))
            }.show()
    }

    private fun choosePose(
        context: Context,
        overrides: Overrides,
        save: (Overrides) -> Unit,
    ) {
        val names = listOf(context.getString(R.string.ankiquest_debug_computed)) + AnkiquestPose.entries.map { it.id }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_debug_pose_title)
            .setItems(names.toTypedArray()) { _, which ->
                save(if (which == 0) overrides.computeToday() else overrides.forcePose(AnkiquestPose.entries[which - 1]))
            }.show()
    }

    /** One number field per threshold, in [Rules]' order; invalid numbers are refused with a toast. */
    private fun editRules(
        context: Context,
        overrides: Overrides,
        save: (Overrides) -> Unit,
    ) {
        val rules = overrides.rules
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (PADDING_DP * context.resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, 0)
            }
        val fields =
            listOf(
                R.string.ankiquest_debug_hopeful_reviews to rules.hopefulReviews,
                R.string.ankiquest_debug_frozen_2_days to rules.frozen2Days,
                R.string.ankiquest_debug_frozen_3_days to rules.frozen3Days,
                R.string.ankiquest_debug_hot_days to rules.hotDays,
                R.string.ankiquest_debug_blazing_days to rules.blazingDays,
                R.string.ankiquest_debug_legend_days to rules.legendDays,
            ).map { (label, value) ->
                layout.addView(TextView(context).apply { setText(label) })
                EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(value.toString())
                    layout.addView(this)
                }
            }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_debug_thresholds)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val numbers = fields.map { it.text.toString().toIntOrNull() ?: 0 }
                val edited =
                    Rules(
                        hopefulReviews = numbers[0],
                        frozen2Days = numbers[1],
                        frozen3Days = numbers[2],
                        hotDays = numbers[3],
                        blazingDays = numbers[4],
                        legendDays = numbers[5],
                    )
                val next = overrides.withRules(edited)
                if (next == null) {
                    showThemedToast(context, R.string.ankiquest_debug_invalid, shortLength = false)
                    return@setPositiveButton
                }
                save(next)
            }.setNeutralButton(R.string.ankiquest_debug_shared) { _, _ -> save(overrides.sharedRules()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Saves [next] and redraws the widget and the launcher icon; Home reads it when it shows. */
    private fun store(
        context: Context,
        next: Overrides,
        onChanged: (Overrides) -> Unit,
    ) {
        AnkiquestDebug.save(next)
        AnkiquestWidget.redrawStreaks(context)
        AnkiquestIcon.refresh(context, TimeManager.time.intTimeMS())
        onChanged(next)
    }

    private fun describe(
        context: Context,
        overrides: Overrides,
    ): String {
        if (!overrides.active) {
            return context.getString(R.string.ankiquest_debug_summary_off)
        }
        val rules =
            if (overrides.rules == Rules.SHARED) {
                context.getString(R.string.ankiquest_debug_rules_shared)
            } else {
                context.getString(R.string.ankiquest_debug_rules_edited)
            }
        return context.getString(R.string.ankiquest_debug_summary_on, moodName(overrides.today), poseName(overrides.today), rules)
    }

    private fun moodName(today: Today): String = (today as? Today.ForcedMood)?.mood?.name?.lowercase() ?: COMPUTED

    private fun poseName(today: Today): String = (today as? Today.ForcedPose)?.pose?.id ?: COMPUTED

    private const val COMPUTED = "computed"
    private const val PADDING_DP = 20
}
