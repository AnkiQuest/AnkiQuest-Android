// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import org.json.JSONArray

/**
 * How Aki feels about today's streak: the one rule for every place the companion shows today, from
 * the streak widget and the Home card to the launcher icon, the heatmap and streak reminders. Until
 * today counts, the mood follows today's [Stage] toward the streak minimum: after a freeze frozen,
 * thawing, hopeful, almost; with a streak sad, sad, hopeful, almost. On fire once today counts.
 * Each mood is an [AnkiquestPose]; [AnkiquestCompanion.art] draws it as the chosen companion.
 */
internal object AnkiquestMood {
    private const val MINUTE_MS = 60_000L
    private const val PERCENT = 100L

    /** Ends the milestone line on moment cards and share pictures. */
    private const val FLAME = "🔥"

    /** The streak lengths the widget and pictures count down to. */
    private val milestones = listOf(7, 30, 100, 365)

    /** Today's mood. The frozen levels count the frozen days in a row ending yesterday; 3 means 3 or more. */
    enum class Mood {
        /** Nothing is known about today. */
        UNKNOWN,

        /** No streak to lose. */
        ASLEEP,

        /** A streak and no study yet today. */
        SAD,

        /** A freeze kept yesterday, and under a quarter of today's minimum studied. */
        FROZEN_1,
        FROZEN_2,
        FROZEN_3,

        /** A freeze kept yesterday, and a quarter of today's minimum studied. */
        THAWING,

        /** Half of today's minimum studied; without a streak, any study. */
        HOPEFUL,

        /** Three quarters of today's minimum studied. */
        ALMOST,

        /** Today counts, or today's goal is reached. */
        FIRE,
    }

    /** The flame beside the streak number: lit only once today counts, as Duolingo's. */
    enum class Flame {
        /** No streak, or nothing known about today. */
        NONE,

        /** Today counts: the tier's colour. */
        LIT,

        /** A streak that today has not counted for yet. */
        UNLIT,

        /** A freeze kept the days before today, and today has not counted yet. */
        ICY,
    }

    /** Whether today's daily goal is reached. */
    enum class Goal { OPEN, REACHED }

    /**
     * How far today's review time has come toward the streak minimum. Each stage starts at
     * [percent] of the minimum, as `stages` in streak_moods.json says.
     */
    enum class Stage(
        val percent: Int,
    ) {
        STARTED(0),
        QUARTER(25),
        HALF(50),
        THREE_QUARTERS(75),
        ;

        companion object {
            /**
             * The stage of [timeMs] toward [streakMinutes], compared in whole numbers so no rounding
             * moves a boundary. Without a minimum the first review counts the day, so it is [STARTED].
             */
            fun of(
                timeMs: Long,
                streakMinutes: Int,
            ): Stage {
                if (streakMinutes <= 0) {
                    return STARTED
                }
                val goalMs = streakMinutes * MINUTE_MS
                val time = timeMs.coerceAtLeast(0) * PERCENT
                return entries.last { time >= it.percent * goalMs }
            }
        }
    }

    /**
     * How hot a streak burns, from the day it starts at: its pose, and the share picture's and moment
     * card's colours. White text on both gradient ends meets WCAG AA (4.5:1); the gold number meets
     * the large-text ratio (3:1).
     */
    enum class Heat(
        val days: Int,
        val pose: AnkiquestPose,
        @ColorRes val top: Int,
        @ColorRes val bottom: Int,
    ) {
        WARM(1, AnkiquestPose.STREAK, R.color.aq_heat_warm_top, R.color.aq_heat_warm_bottom),
        HOT(7, AnkiquestPose.STREAK_HOT, R.color.aq_heat_hot_top, R.color.aq_heat_hot_bottom),
        BLAZING(30, AnkiquestPose.STREAK_BLAZING, R.color.aq_heat_blazing_top, R.color.aq_heat_blazing_bottom),
        LEGEND(100, AnkiquestPose.STREAK_LEGEND, R.color.aq_heat_legend_top, R.color.aq_heat_legend_bottom),
        ;

        companion object {
            /** The heat of a [streak] that counts today; an unknown or zero streak burns as day one. */
            fun of(streak: Int): Heat = Rules.SHARED.heat(streak)
        }
    }

    /**
     * The thresholds of the mood rule. [SHARED] holds the ones `streak_moods.json` pins; only the
     * secret debug menu ([AnkiquestDebug]) uses others, on this phone.
     */
    data class Rules(
        /** A changed debug review threshold for reaching hopeful. */
        val hopefulReviews: Int = 1,
        /** Frozen days in a row that show the second frozen level. */
        val frozen2Days: Int = 2,
        /** Frozen days in a row that show the third frozen level. */
        val frozen3Days: Int = 3,
        val hotDays: Int = Heat.HOT.days,
        val blazingDays: Int = Heat.BLAZING.days,
        val legendDays: Int = Heat.LEGEND.days,
    ) {
        /** Whether each threshold is reachable and in the order of its levels. */
        fun valid(): Boolean =
            hopefulReviews >= 1 &&
                frozen2Days in 2 until frozen3Days &&
                hotDays in 2 until blazingDays &&
                blazingDays < legendDays

        /** The heat of a [streak] that counts today; an unknown or zero streak burns as day one. */
        fun heat(streak: Int): Heat =
            when {
                streak >= legendDays -> Heat.LEGEND
                streak >= blazingDays -> Heat.BLAZING
                streak >= hotDays -> Heat.HOT
                else -> Heat.WARM
            }

        companion object {
            val SHARED = Rules()
        }
    }

    /**
     * The mood for today's [state] with [reviews] so far at [stage] toward the minimum, a [streak]
     * of that many days and [frozenDays] frozen days in a row ending yesterday.
     */
    fun of(
        state: State,
        reviews: Int,
        stage: Stage,
        streak: Int,
        frozenDays: Int,
        goal: Goal,
        rules: Rules = Rules.SHARED,
    ): Mood {
        // Today counts, on exact or observed answers, or the goal says the player did what they planned.
        if (state == State.STUDIED || state == State.UNVERIFIED || goal == Goal.REACHED) {
            return Mood.FIRE
        }
        if (state == State.UNKNOWN) {
            return Mood.UNKNOWN
        }
        // A custom review threshold moves the hopeful boundary without changing the shared stages.
        val progress =
            when {
                rules.hopefulReviews == Rules.SHARED.hopefulReviews -> stage
                reviews >= rules.hopefulReviews -> maxOf(stage, Stage.HALF)
                else -> minOf(stage, Stage.QUARTER)
            }

        // A protected day means a freeze kept at least yesterday, even without the heatmap.
        val frozen = maxOf(frozenDays, if (state == State.PROTECTED) 1 else 0)
        if (frozen > 0) {
            return when (progress) {
                Stage.STARTED -> frozen(frozen, rules)
                Stage.QUARTER -> Mood.THAWING
                Stage.HALF -> Mood.HOPEFUL
                Stage.THREE_QUARTERS -> Mood.ALMOST
            }
        }

        // Without a streak there is nothing to lose: any study is hopeful.
        if (streak == 0) {
            return when {
                reviews < rules.hopefulReviews -> Mood.ASLEEP
                progress == Stage.THREE_QUARTERS -> Mood.ALMOST
                else -> Mood.HOPEFUL
            }
        }
        return when (progress) {
            Stage.STARTED, Stage.QUARTER -> Mood.SAD
            Stage.HALF -> Mood.HOPEFUL
            Stage.THREE_QUARTERS -> Mood.ALMOST
        }
    }

    /** The frozen mood for [days] frozen days in a row, using [rules]' thresholds. */
    private fun frozen(
        days: Int,
        rules: Rules,
    ): Mood =
        when {
            days >= rules.frozen3Days -> Mood.FROZEN_3
            days >= rules.frozen2Days -> Mood.FROZEN_2
            else -> Mood.FROZEN_1
        }

    /**
     * The mood for today's [day] verdict; [Mood.UNKNOWN] without one. [frozenDays] and [goal] must
     * be for the same day.
     */
    fun of(
        day: AnkiquestDay.Verdict?,
        frozenDays: Int,
        goal: Goal,
        rules: Rules = Rules.SHARED,
    ): Mood {
        if (day == null) {
            return Mood.UNKNOWN
        }
        return of(day.label, day.reviews, Stage.of(day.timeMs, day.streakMinutes), day.streak ?: 0, frozenDays, goal, rules)
    }

    /**
     * The player's own mood today: [of] with the debug menu's [debug] overrides. Every place that
     * shows the player's own day uses it; other players' days use [of].
     */
    fun today(
        day: AnkiquestDay.Verdict?,
        frozenDays: Int,
        goal: Goal,
        debug: AnkiquestDebug.Overrides = AnkiquestDebug.current(),
    ): Mood = debug.mood(of(day, frozenDays, goal, debug.rules))

    /**
     * The player's own pose for [mood] with a [streak] of that many days: [pose], unless [debug]
     * forces a mood or a pose. Streak reminders pass the mood they stand for.
     */
    @DrawableRes
    fun todayPose(
        mood: Mood,
        streak: Int,
        debug: AnkiquestDebug.Overrides = AnkiquestDebug.current(),
        choice: AnkiquestCompanion.Choice = AnkiquestCompanion.choice(),
    ): Int {
        val shown = debug.pose() ?: named(debug.mood(mood), streak, debug.rules)
        return AnkiquestCompanion.art(shown, choice)
    }

    /**
     * The companion's pose today for the signed-in player, from what the server and the collection
     * last said: the same mood the streak widget shows.
     */
    @DrawableRes
    fun ownPose(now: Long): Int {
        val day = AnkiquestDay.ownCarried(now)
        // The last profile's goal and frozen days only count while its day is the one judged.
        val endsAt = AnkiquestDay.last()?.dayEndsAt?.takeIf { it > now }
        val last = AnkiquestIcon.lastKnown()?.takeIf { endsAt != null && it.optLong("day_ends_at") == endsAt }
        val goal = if (last?.optBoolean("goal_reached") == true) Goal.REACHED else Goal.OPEN
        val debug = AnkiquestDebug.current()
        return todayPose(today(day, last?.optInt("frozen_days") ?: 0, goal, debug), day?.streak ?: 0, debug)
    }

    /** The flame for [mood], so the flame says what Aki would even with no companion shown. */
    fun flame(mood: Mood): Flame =
        when (mood) {
            Mood.UNKNOWN, Mood.ASLEEP -> Flame.NONE
            Mood.SAD, Mood.HOPEFUL, Mood.ALMOST -> Flame.UNLIT
            Mood.FROZEN_1, Mood.FROZEN_2, Mood.FROZEN_3, Mood.THAWING -> Flame.ICY
            Mood.FIRE -> Flame.LIT
        }

    /** The pose for [mood] with a [streak] of that many days. */
    private fun named(
        mood: Mood,
        streak: Int,
        rules: Rules = Rules.SHARED,
    ): AnkiquestPose =
        when (mood) {
            Mood.UNKNOWN -> AnkiquestPose.FACE
            Mood.ASLEEP -> AnkiquestPose.ASLEEP
            Mood.SAD -> AnkiquestPose.SAD
            // thawing and almost are moods with no pose of their own (AnkiQuest-Server#45).
            Mood.FROZEN_1, Mood.THAWING -> AnkiquestPose.FROZEN
            Mood.FROZEN_2 -> AnkiquestPose.FROZEN_2
            Mood.FROZEN_3 -> AnkiquestPose.FROZEN_3
            Mood.HOPEFUL, Mood.ALMOST -> AnkiquestPose.HOPEFUL
            Mood.FIRE -> rules.heat(streak).pose
        }

    /**
     * [choice]'s art for [mood] with a [streak] of that many days. It is resolved from the pose, not
     * from Aki's picture, because one of Aki's pictures stands for two poses (welcome for hopeful).
     */
    @DrawableRes
    fun pose(
        mood: Mood,
        streak: Int,
        choice: AnkiquestCompanion.Choice = AnkiquestCompanion.choice(),
    ): Int = AnkiquestCompanion.art(named(mood, streak), choice)

    /**
     * The poses the big widget rotates through for [mood], all of the same mood, each once. [debug]
     * is the player's own overrides; other players' widgets pass [AnkiquestDebug.Overrides.NONE].
     */
    fun poses(
        mood: Mood,
        streak: Int,
        choice: AnkiquestCompanion.Choice = AnkiquestCompanion.choice(),
        debug: AnkiquestDebug.Overrides = AnkiquestDebug.Overrides.NONE,
    ): List<Int> {
        val pose = todayPose(mood, streak, debug, choice)
        // A forced pose is the only picture; the legend rotation belongs to the computed fire mood.
        if (debug.pose() != null) {
            return listOf(pose)
        }
        if (debug.mood(mood) == Mood.FIRE && debug.rules.heat(streak) == Heat.LEGEND) {
            return listOf(pose, AnkiquestCompanion.art(AnkiquestPose.STREAK_MILESTONE, choice)).distinct()
        }
        return listOf(pose)
    }

    /** One of [poses] for the day ending at [dayEndsAt], the same all day and changing from day to day. */
    @DrawableRes
    fun bigPose(
        mood: Mood,
        streak: Int,
        dayEndsAt: Long,
        debug: AnkiquestDebug.Overrides = AnkiquestDebug.Overrides.NONE,
    ): Int {
        val choices = poses(mood, streak, debug = debug)
        return choices[Math.floorMod(Math.floorDiv(dayEndsAt, DAY_MS), choices.size.toLong()).toInt()]
    }

    /**
     * The frozen days in a row ending yesterday in a profile's [heatmap], whose last cell is today.
     * Skipped days count as frozen, as the server's `frozen` flag says.
     */
    fun frozenRun(heatmap: JSONArray?): Int {
        if (heatmap == null) {
            return 0
        }
        var days = 0
        for (index in heatmap.length() - 2 downTo 0) {
            if (heatmap.optJSONObject(index)?.optBoolean("frozen") != true) {
                break
            }
            days++
        }
        return days
    }

    /** The next milestone after [streak], or null past the last one. */
    fun next(streak: Int): Int? = milestones.firstOrNull { it > streak }

    /** "36 days to 100 🔥" for cards, or null without a streak or past the last milestone. */
    fun nextLine(
        context: Context,
        streak: Int,
    ): String? = milestone(context, streak)?.let { "$it $FLAME" }

    /** "36 days to 100", or null without a streak or past the last milestone. */
    private fun milestone(
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
     * study while it does not, or null without a streak. No emoji: the number beside it has the flame.
     */
    fun line(
        context: Context,
        flame: Flame,
        streak: Int,
    ): String? {
        if (streak <= 0) {
            return null
        }
        return when (flame) {
            Flame.NONE -> null
            Flame.LIT -> milestone(context, streak)
            Flame.UNLIT, Flame.ICY -> context.getString(R.string.ankiquest_streak_nudge)
        }
    }
}
