// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestDay.State
import org.json.JSONArray

/**
 * How Aki feels about today's streak: the one rule for every place the companion shows today, from
 * the streak widget and the Home card to the launcher icon, the heatmap and streak reminders. Sad
 * before any study, hopeful after some, on fire once today counts, and frozen after days a freeze
 * kept. Each mood is an [AnkiquestPose], which the app draws as the chosen companion.
 */
object AnkiquestMood {
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

        /** A freeze kept yesterday and no study yet today. */
        FROZEN_1,
        FROZEN_2,
        FROZEN_3,

        /** Some study today, short of the streak minimum. */
        HOPEFUL,

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
     * How hot a streak burns, from the day it starts at: its pose, and the share picture's and moment
     * card's colours (`top` and `bottom`, which the app defines).
     */
    enum class Heat(
        val days: Int,
        val pose: AnkiquestPose,
    ) {
        WARM(1, AnkiquestPose.STREAK),
        HOT(7, AnkiquestPose.STREAK_HOT),
        BLAZING(30, AnkiquestPose.STREAK_BLAZING),
        LEGEND(100, AnkiquestPose.STREAK_LEGEND),
        ;

        companion object {
            /** The heat of a [streak] that counts today; an unknown or zero streak burns as day one. */
            fun of(streak: Int): Heat = entries.lastOrNull { streak >= it.days } ?: WARM
        }
    }

    /**
     * The mood for today's [state] with [reviews] so far, a [streak] of that many days and
     * [frozenDays] frozen days in a row ending yesterday. Once the player studies, the freeze no
     * longer decides the mood.
     */
    fun of(
        state: State,
        reviews: Int,
        streak: Int,
        frozenDays: Int,
        goal: Goal,
    ): Mood {
        // Today counts, or the goal says the player did what they planned.
        if (state == State.STUDIED || goal == Goal.REACHED) {
            return Mood.FIRE
        }
        if (state == State.UNKNOWN) {
            return Mood.UNKNOWN
        }
        if (reviews > 0) {
            return Mood.HOPEFUL
        }

        // A protected day means a freeze kept at least yesterday, even without the heatmap.
        val frozen = maxOf(frozenDays, if (state == State.PROTECTED) 1 else 0)
        return when {
            frozen >= 3 -> Mood.FROZEN_3
            frozen == 2 -> Mood.FROZEN_2
            frozen == 1 -> Mood.FROZEN_1
            streak == 0 -> Mood.ASLEEP
            else -> Mood.SAD
        }
    }

    /**
     * The mood for today's [day] verdict; [Mood.UNKNOWN] without one. [frozenDays] and [goal] must
     * be for the same day.
     */
    fun of(
        day: AnkiquestDay.Verdict?,
        frozenDays: Int,
        goal: Goal,
    ): Mood {
        if (day == null) {
            return Mood.UNKNOWN
        }
        return of(day.label, day.reviews, day.streak ?: 0, frozenDays, goal)
    }

    /** The flame for [mood], so the flame says what Aki would even with no companion shown. */
    fun flame(mood: Mood): Flame =
        when (mood) {
            Mood.UNKNOWN, Mood.ASLEEP -> Flame.NONE
            Mood.SAD, Mood.HOPEFUL -> Flame.UNLIT
            Mood.FROZEN_1, Mood.FROZEN_2, Mood.FROZEN_3 -> Flame.ICY
            Mood.FIRE -> Flame.LIT
        }

    /** The pose for [mood] with a [streak] of that many days. */
    fun named(
        mood: Mood,
        streak: Int,
    ): AnkiquestPose =
        when (mood) {
            Mood.UNKNOWN -> AnkiquestPose.FACE
            Mood.ASLEEP -> AnkiquestPose.ASLEEP
            Mood.SAD -> AnkiquestPose.SAD
            Mood.FROZEN_1 -> AnkiquestPose.FROZEN
            Mood.FROZEN_2 -> AnkiquestPose.FROZEN_2
            Mood.FROZEN_3 -> AnkiquestPose.FROZEN_3
            Mood.HOPEFUL -> AnkiquestPose.HOPEFUL
            Mood.FIRE -> Heat.of(streak).pose
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
}
