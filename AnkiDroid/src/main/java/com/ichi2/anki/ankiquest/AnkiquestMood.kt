// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import org.json.JSONArray
import org.json.JSONObject

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

    /** The server's day verdicts (`streak_state`) a kept profile names. */
    private const val STATE_STUDIED = "studied"
    private const val STATE_UNVERIFIED = "unverified"
    private const val STATE_PROTECTED = "protected"

    /** How long before the study day ends a freeze about to keep the streak shows as one (`freeze_window_ms`). */
    const val FREEZE_WINDOW_MS = 7_200_000L

    /** The night rule's usual first study time: from the last [RECENT_DAYS] studied days, [DEFAULT_USUAL] with fewer than [MIN_DAYS]. */
    const val DEFAULT_USUAL = "08:00"
    const val RECENT_DAYS = 14
    const val MIN_DAYS = 3
    private const val DAY_MINUTES = 24 * 60
    private const val HOUR_MINUTES = 60

    /** Days away from which each away mood starts (`away_levels`), later levels first. */
    private val awayLevels = listOf(30 to Mood.DEAD, 7 to Mood.ABANDONED, 2 to Mood.NEGLECTED)

    /** Ends the milestone line on moment cards and share pictures. */
    private const val FLAME = "🔥"

    /** The streak lengths the widget and pictures count down to. */
    private val milestones = listOf(7, 30, 100, 365)

    /** Today's mood. The frozen levels count the frozen days in a row ending yesterday; 3 means 3 or more. */
    enum class Mood {
        /** Nothing is known about today. */
        UNKNOWN,

        /** No streak to lose; or, at night, a comfortable companion asleep until the player wakes ([Night]). */
        ASLEEP,

        /** A streak and no study yet today. */
        SAD,

        /** A freeze in effect: today protected with no frozen day before it, or about to keep the streak tonight. */
        FREEZE,

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

        /** No streak and no study today, after 2 to 6 days away. */
        NEGLECTED,

        /** No streak and no study today, after 7 to 29 days away. */
        ABANDONED,

        /** No streak and no study today, after 30 days or more away. */
        DEAD,
    }

    /** How the study day before today ended, as `night` in streak_moods.json names it. */
    enum class Yesterday {
        /** Studied, or the goal reached. */
        COUNTED,

        /** Kept by a freeze. */
        PROTECTED,
        MISSED,
        UNKNOWN,
    }

    /**
     * The night rule's inputs: the local time [now] (HH:MM), the study day's [rolloverHour], the
     * local times of the first review on recent studied days ([firstReviews], oldest first) and how
     * [yesterday] ended.
     */
    data class Night(
        val now: String,
        val rolloverHour: Int,
        val firstReviews: List<String>,
        val yesterday: Yesterday,
    ) {
        /** Whether [now] is from the rollover up to, not including, the usual first study time. */
        fun window(): Boolean = afterRollover(now, rolloverHour) < usualFirstStudy(firstReviews, rolloverHour)
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

    /** Whether the player has ever studied: the profile's `lifetime.reviews > 0` (streak_moods.json `away_run`). */
    enum class Studied {
        NEVER,
        EVER,
        ;

        companion object {
            fun of(profile: JSONObject?): Studied = if ((profile?.optJSONObject("lifetime")?.optLong("reviews") ?: 0) > 0) EVER else NEVER
        }
    }

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
     * of that many days and [frozenDays] frozen days in a row ending yesterday. [daysAway] counts
     * days without reviews in a row ending yesterday, [freezes] the freezes left, [dayLeftMs] the
     * time until the study day ends (null when unknown), [night] the night rule's inputs (null
     * outside it) and [rules] the thresholds.
     */
    fun of(
        state: State,
        reviews: Int,
        stage: Stage,
        streak: Int,
        frozenDays: Int,
        goal: Goal,
        daysAway: Int = 0,
        freezes: Int = 0,
        dayLeftMs: Long? = null,
        night: Night? = null,
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

        // At night a comfortable companion sleeps until the player's usual first study time.
        if (sleeps(state, streak, night)) {
            return Mood.ASLEEP
        }

        // A freeze covering today, with no frozen day before it, is in effect; frozen days show their level.
        if (frozenDays > 0 || state == State.PROTECTED) {
            return when (progress) {
                Stage.STARTED -> if (frozenDays == 0) Mood.FREEZE else frozen(frozenDays, rules)
                Stage.QUARTER -> Mood.THAWING
                Stage.HALF -> Mood.HOPEFUL
                Stage.THREE_QUARTERS -> Mood.ALMOST
            }
        }

        // Without a streak there is nothing to lose: any study is hopeful; days away show their level.
        if (streak == 0) {
            return when {
                reviews == 0 -> away(daysAway)
                reviews < rules.hopefulReviews -> Mood.ASLEEP
                progress == Stage.THREE_QUARTERS -> Mood.ALMOST
                else -> Mood.HOPEFUL
            }
        }

        // A freeze about to keep the streak tonight is in effect.
        if (reviews == 0 && freezes > 0 && dayLeftMs != null && dayLeftMs <= FREEZE_WINDOW_MS) {
            return Mood.FREEZE
        }
        return when (progress) {
            Stage.STARTED, Stage.QUARTER -> Mood.SAD
            Stage.HALF -> Mood.HOPEFUL
            Stage.THREE_QUARTERS -> Mood.ALMOST
        }
    }

    /** Whether the night rule puts a comfortable companion to sleep: no review yet, in the window, the streak alive and yesterday counted. */
    private fun sleeps(
        state: State,
        streak: Int,
        night: Night?,
    ): Boolean {
        if (night == null || state != State.PENDING) {
            return false
        }
        return streak > 0 && night.yesterday == Yesterday.COUNTED && night.window()
    }

    /** The mood for no streak and no review today after [days] away. */
    private fun away(days: Int): Mood = awayLevels.firstOrNull { days >= it.first }?.second ?: Mood.ASLEEP

    /** Minutes after the study day's rollover for a local `HH:MM` [time]. */
    fun afterRollover(
        time: String,
        rolloverHour: Int,
    ): Int {
        val (hours, minutes) = time.split(":").map(String::toInt)
        return Math.floorMod(hours * HOUR_MINUTES + minutes - rolloverHour * HOUR_MINUTES, DAY_MINUTES)
    }

    /**
     * The usual first study time in minutes after rollover: the median (lower middle for an even
     * count) of the last [RECENT_DAYS] [firstReviews], [DEFAULT_USUAL] with fewer than [MIN_DAYS].
     */
    fun usualFirstStudy(
        firstReviews: List<String>,
        rolloverHour: Int,
    ): Int {
        val recent = firstReviews.takeLast(RECENT_DAYS).map { afterRollover(it, rolloverHour) }.sorted()
        if (recent.size < MIN_DAYS) {
            return afterRollover(DEFAULT_USUAL, rolloverHour)
        }
        return recent[(recent.size - 1) / 2]
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
        daysAway: Int = 0,
        freezes: Int = 0,
        dayLeftMs: Long? = null,
        night: Night? = null,
        rules: Rules = Rules.SHARED,
    ): Mood {
        if (day == null) {
            return Mood.UNKNOWN
        }
        val stage = Stage.of(day.timeMs, day.streakMinutes)
        return of(day.label, day.reviews, stage, day.streak ?: 0, frozenDays, goal, daysAway, freezes, dayLeftMs, night, rules)
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
        daysAway: Int = 0,
        freezes: Int = 0,
        dayLeftMs: Long? = null,
        night: Night? = null,
    ): Mood = debug.mood(of(day, frozenDays, goal, daysAway, freezes, dayLeftMs, night, debug.rules))

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
        return todayPose(ownMood(day, AnkiquestIcon.lastKnown(), goal, now, debug), day?.streak ?: 0, debug)
    }

    /**
     * The signed-in player's mood for [day] with the [last] profile kept ([AnkiquestIcon.lastKnown]).
     * While [last] describes today it brings frozen days, days away, freezes, the day's end and how
     * yesterday ended; once its day has ended, its own verdict is how yesterday ended. The night
     * rule's other inputs come from [AnkiquestNight]; the debug menu's [debug] overrides apply, as in [today].
     */
    fun ownMood(
        day: AnkiquestDay.Verdict?,
        last: JSONObject?,
        goal: Goal,
        now: Long,
        debug: AnkiquestDebug.Overrides = AnkiquestDebug.current(),
    ): Mood {
        val endsAt = last?.optLong("day_ends_at")?.takeIf { it > 0 }
        val kept = last?.takeIf { endsAt != null && endsAt > now }
        return today(
            day,
            kept?.optInt("frozen_days") ?: 0,
            goal,
            debug,
            daysAway = kept?.optInt("days_away") ?: 0,
            freezes = kept?.optInt("freezes") ?: 0,
            dayLeftMs = endsAt?.takeIf { kept != null }?.let { it - now },
            night = AnkiquestNight.inputs(yesterday(last, now), now),
        )
    }

    /**
     * How yesterday ended for a server [profile] at [now]: while the profile describes today, from
     * its heatmap or, in a kept profile, its `yesterday`; once its day has ended (within a day), from
     * its own verdict.
     */
    fun yesterday(
        profile: JSONObject?,
        now: Long,
    ): Yesterday {
        if (profile == null) {
            return Yesterday.UNKNOWN
        }
        val endsAt = profile.optLong("day_ends_at").takeIf { it > 0 } ?: return Yesterday.UNKNOWN
        if (endsAt > now) {
            val kept = Yesterday.entries.firstOrNull { it.name.lowercase() == profile.optString("yesterday") }
            return kept ?: yesterday(profile.optJSONArray("heatmap"))
        }
        if (now - endsAt < DAY_MS) {
            return ended(profile.optString("streak_state"))
        }
        return Yesterday.UNKNOWN
    }

    /** How a day the server judged as [streakState] ended. */
    private fun ended(streakState: String): Yesterday =
        when (streakState) {
            STATE_STUDIED, STATE_UNVERIFIED -> Yesterday.COUNTED
            STATE_PROTECTED -> Yesterday.PROTECTED
            else -> Yesterday.MISSED
        }

    /** The flame for [mood], so the flame says what Aki would even with no companion shown. */
    fun flame(mood: Mood): Flame =
        when (mood) {
            Mood.UNKNOWN, Mood.ASLEEP, Mood.NEGLECTED, Mood.ABANDONED, Mood.DEAD -> Flame.NONE
            Mood.SAD, Mood.HOPEFUL, Mood.ALMOST -> Flame.UNLIT
            Mood.FREEZE, Mood.FROZEN_1, Mood.FROZEN_2, Mood.FROZEN_3, Mood.THAWING -> Flame.ICY
            Mood.FIRE -> Flame.LIT
        }

    /** The flame for [mood] with a [streak]: asleep with a streak is the night's, unlit (`night.flame`). */
    fun flame(
        mood: Mood,
        streak: Int,
    ): Flame = if (mood == Mood.ASLEEP && streak > 0) Flame.UNLIT else flame(mood)

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
            Mood.FREEZE -> AnkiquestPose.FREEZE
            Mood.NEGLECTED -> AnkiquestPose.NEGLECTED
            Mood.ABANDONED -> AnkiquestPose.ABANDONED
            Mood.DEAD -> AnkiquestPose.DEAD
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

    /** Days without reviews in a row ending yesterday in a [profile]'s heatmap, as [awayRun] counts them. */
    fun awayRun(profile: JSONObject?): Int = awayRun(profile?.optJSONArray("heatmap"), Studied.of(profile))

    /**
     * Days without reviews in a row ending yesterday in a profile's [heatmap], whose last cell is
     * today. Without a study day in the heatmap, a player who [studied] before it is away for
     * every day of it before today, and one who never studied (a new player) is not away.
     * Without a heatmap nothing is known: 0.
     */
    fun awayRun(
        heatmap: JSONArray?,
        studied: Studied,
    ): Int {
        if (heatmap == null) {
            return 0
        }
        val last = heatmap.length() - 2
        for (index in last downTo 0) {
            if (heatmap.optJSONObject(index)?.optString("state", "none") != "none") {
                return last - index
            }
        }

        // No study day in the heatmap: away since before it, or never studied at all.
        return when (studied) {
            Studied.EVER -> last + 1
            Studied.NEVER -> 0
        }
    }

    /** How yesterday ended, from a profile's [heatmap] whose last cell is today. */
    fun yesterday(heatmap: JSONArray?): Yesterday {
        val cell = heatmap?.takeIf { it.length() >= 2 }?.optJSONObject(heatmap.length() - 2) ?: return Yesterday.UNKNOWN
        return when {
            cell.optBoolean("frozen") -> Yesterday.PROTECTED
            cell.optString("state") == "met" -> Yesterday.COUNTED
            else -> Yesterday.MISSED
        }
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
