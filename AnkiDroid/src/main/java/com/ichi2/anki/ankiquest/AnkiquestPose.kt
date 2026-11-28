// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * A companion pose, named as in the pinned AnkiQuest-Art release's `poses.json` (see
 * [AnkiquestPoseTest]). Core poses have no [fallback]: every companion has them. An extension a
 * companion has no art for shows its [fallback], which is a core pose or another extension that
 * leads to one.
 */
internal enum class AnkiquestPose(
    val id: String,
    private val fallback: AnkiquestPose?,
) {
    FACE("face", null),
    ASLEEP("asleep", null),
    HAPPY("happy", null),
    STREAK("streak", null),
    SAD("sad", null),
    HOPEFUL("hopeful", null),
    FROZEN("frozen", null),
    NEGLECTED("neglected", null),
    STREAK_HOT("streak_hot", STREAK),
    STREAK_BLAZING("streak_blazing", STREAK_HOT),
    STREAK_LEGEND("streak_legend", STREAK_BLAZING),
    STREAK_MILESTONE("streak_milestone", STREAK_LEGEND),
    FROZEN_2("frozen_2", FROZEN),
    FROZEN_3("frozen_3", FROZEN_2),
    WELCOME("welcome", HAPPY),
    REVIEW("review", HAPPY),
    HURT("hurt", SAD),
    ABANDONED("abandoned", NEGLECTED),
    DEAD("dead", NEGLECTED),
    LEECH_DEFEATED("leech_defeated", HAPPY),
    WINNER("winner", HAPPY),
    FREEZE("freeze", FROZEN),
    FREEZE_USED("freeze_used", FROZEN),
    ;

    /** This pose, then each fallback in turn, ending at a core pose. */
    fun chain(): Sequence<AnkiquestPose> = generateSequence(this) { it.fallback }
}
