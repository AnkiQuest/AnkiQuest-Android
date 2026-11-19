// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.ichi2.anki.ankiquest.AnkiquestMood.Flame
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.ankiquest.AnkiquestMood.Heat
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import com.ichi2.anki.ankiquest.ui.R

// How the app draws and words AnkiquestMood's rules: the companion's art, the heat colours and the
// lines under the streak number. The rules themselves are in :ankiquest-core.

private const val DAY_MS = 86_400_000L

/**
 * The top of the share picture's and moment card's gradient for this heat. White text on both
 * gradient ends meets WCAG AA (4.5:1); the gold number meets the large-text ratio (3:1).
 */
@get:ColorRes
val Heat.top: Int
    get() =
        when (this) {
            Heat.WARM -> R.color.aq_heat_warm_top
            Heat.HOT -> R.color.aq_heat_hot_top
            Heat.BLAZING -> R.color.aq_heat_blazing_top
            Heat.LEGEND -> R.color.aq_heat_legend_top
        }

/** The bottom of the gradient [top] starts. */
@get:ColorRes
val Heat.bottom: Int
    get() =
        when (this) {
            Heat.WARM -> R.color.aq_heat_warm_bottom
            Heat.HOT -> R.color.aq_heat_hot_bottom
            Heat.BLAZING -> R.color.aq_heat_blazing_bottom
            Heat.LEGEND -> R.color.aq_heat_legend_bottom
        }

/**
 * The companion's pose today for the signed-in player, from what the server and the collection
 * last said: the same mood the streak widget shows.
 */
@DrawableRes
fun AnkiquestMood.ownPose(now: Long): Int {
    val day = AnkiquestDay.ownCarried(now)
    // The last profile's goal and frozen days only count while its day is the one judged.
    val endsAt = AnkiquestDay.last()?.dayEndsAt?.takeIf { it > now }
    val last = AnkiquestIcon.lastKnown()?.takeIf { endsAt != null && it.optLong("day_ends_at") == endsAt }
    val goal = if (last?.optBoolean("goal_reached") == true) Goal.REACHED else Goal.OPEN
    return pose(of(day, last?.optInt("frozen_days") ?: 0, goal), day?.streak ?: 0)
}

/**
 * [choice]'s art for [mood] with a [streak] of that many days. It is resolved from the pose, not
 * from Aki's picture, because one of Aki's pictures stands for two poses (welcome for hopeful).
 */
@DrawableRes
fun AnkiquestMood.pose(
    mood: Mood,
    streak: Int,
    choice: AnkiquestCompanion.Choice = AnkiquestCompanion.choice(),
): Int = AnkiquestCompanion.art(named(mood, streak), choice)

/** The poses the big widget rotates through for [mood], all of the same mood, each once. */
fun AnkiquestMood.poses(
    mood: Mood,
    streak: Int,
    choice: AnkiquestCompanion.Choice = AnkiquestCompanion.choice(),
): List<Int> {
    val pose = pose(mood, streak, choice)
    if (mood == Mood.FIRE && Heat.of(streak) == Heat.LEGEND) {
        return listOf(pose, AnkiquestCompanion.art(AnkiquestPose.STREAK_MILESTONE, choice)).distinct()
    }
    return listOf(pose)
}

/** One of [poses] for the day ending at [dayEndsAt], the same all day and changing from day to day. */
@DrawableRes
fun AnkiquestMood.bigPose(
    mood: Mood,
    streak: Int,
    dayEndsAt: Long,
): Int {
    val choices = poses(mood, streak)
    return choices[Math.floorMod(Math.floorDiv(dayEndsAt, DAY_MS), choices.size.toLong()).toInt()]
}

/** "36 days to 100 🔥", or null without a streak or past the last milestone. */
fun AnkiquestMood.nextLine(
    context: Context,
    streak: Int,
): String? {
    if (streak <= 0) {
        return null
    }
    val next = next(streak) ?: return null
    val remaining = next - streak
    return context.resources.getQuantityString(R.plurals.ankiquest_streak_milestone, remaining, remaining, next)
}

/**
 * The line under the streak number for [flame]: the next milestone once today counts, a nudge to
 * study while it does not, or null without a streak.
 */
fun AnkiquestMood.line(
    context: Context,
    flame: Flame,
    streak: Int,
): String? {
    if (streak <= 0) {
        return null
    }
    return when (flame) {
        Flame.NONE -> null
        Flame.LIT -> nextLine(context, streak)
        Flame.UNLIT, Flame.ICY -> context.getString(R.string.ankiquest_streak_nudge)
    }
}
