// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * A picture a companion pack can have, by its AnkiQuest-Art name (`poses.json`): every picture of a
 * companion is a pose. [CORE] and [EXTENSION] poses are those of `tests/fixtures/companion_poses.json`
 * from AnkiQuest-Server; [PICTURE] and [ICON] are the moment pictures and launcher icon moods. Core
 * poses have no [fallback]: every pack has them. Any other a pack has no picture for shows its
 * [fallback], which leads to a core pose. A pack's index can name more; the newest index installed
 * decides ([PoseNames]).
 */
internal enum class AnkiquestPose(
    val id: String,
    val group: Group,
    private val fallback: AnkiquestPose?,
) {
    FACE("face", Group.CORE, null),
    ASLEEP("asleep", Group.CORE, null),
    HAPPY("happy", Group.CORE, null),
    STREAK("streak", Group.CORE, null),
    SAD("sad", Group.CORE, null),
    HOPEFUL("hopeful", Group.CORE, null),
    FROZEN("frozen", Group.CORE, null),
    NEGLECTED("neglected", Group.CORE, null),
    STREAK_HOT("streak_hot", Group.EXTENSION, STREAK),
    STREAK_BLAZING("streak_blazing", Group.EXTENSION, STREAK_HOT),
    STREAK_LEGEND("streak_legend", Group.EXTENSION, STREAK_BLAZING),
    STREAK_MILESTONE("streak_milestone", Group.EXTENSION, STREAK_LEGEND),
    FROZEN_2("frozen_2", Group.EXTENSION, FROZEN),
    FROZEN_3("frozen_3", Group.EXTENSION, FROZEN_2),
    WELCOME("welcome", Group.EXTENSION, HAPPY),
    REVIEW("review", Group.EXTENSION, HAPPY),
    HURT("hurt", Group.EXTENSION, SAD),
    ABANDONED("abandoned", Group.EXTENSION, NEGLECTED),
    DEAD("dead", Group.EXTENSION, NEGLECTED),
    LEECH_DEFEATED("leech_defeated", Group.EXTENSION, HAPPY),
    WINNER("winner", Group.EXTENSION, HAPPY),
    ACHIEVEMENT("achievement", Group.PICTURE, HAPPY),
    AT_RISK("at_risk", Group.PICTURE, SAD),
    BACKLOG("backlog", Group.PICTURE, SAD),
    CHALLENGE("challenge", Group.PICTURE, HOPEFUL),
    CHALLENGE_LOST("challenge_lost", Group.PICTURE, SAD),
    CHALLENGE_WON("challenge_won", Group.PICTURE, WINNER),
    COMEBACK("comeback", Group.PICTURE, WELCOME),
    DECK_CONQUERED("deck_conquered", Group.PICTURE, HAPPY),
    FREEZE("freeze", Group.PICTURE, FROZEN),
    FREEZE_USED("freeze_used", Group.PICTURE, FROZEN),
    GOAL("goal", Group.PICTURE, HAPPY),
    LEVEL_UP("level_up", Group.PICTURE, HAPPY),
    MINIMUM("minimum", Group.PICTURE, REVIEW),
    NUDGE("nudge", Group.PICTURE, HOPEFUL),
    PLACE_1("place_1", Group.PICTURE, WINNER),
    PLACE_2("place_2", Group.PICTURE, HAPPY),
    PLACE_3("place_3", Group.PICTURE, HAPPY),
    ICON("icon", Group.ICON, FACE),
    ICON_HAPPY("icon_happy", Group.ICON, ICON),
    ICON_WORRIED("icon_worried", Group.ICON, ICON),
    ICON_FROZEN("icon_frozen", Group.ICON, ICON),
    ICON_SLEEPY("icon_sleepy", Group.ICON, ICON),
    ;

    /** The groups of `poses.json`. */
    enum class Group {
        CORE,
        EXTENSION,
        PICTURE,
        ICON,
    }

    /** This pose, then each fallback in turn, ending at a core pose. */
    fun chain(): Sequence<AnkiquestPose> = generateSequence(this) { it.fallback }
}
