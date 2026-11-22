// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject

/*
 * The progress stages from AnkiQuest-Server#53, mirrored here until the snapshot bump brings its
 * streak_moods.json. At that bump, delete this file and read `stages` and the mood cases from the
 * fixture. thawing and almost are moods that show the frozen and hopeful poses (#45).
 */

private const val MINIMUM = 20

/** The pinned fixture's cases that #53 replaces; [stagedMoodCases] has their new versions. */
private val supersededCases = setOf("some study with a streak", "study after a freeze is hopeful")

/** #53's `stages` block: the stage starts and the stage cases. */
internal val stagedStages: JSONObject =
    JSONObject()
        .put(
            "starts",
            JSONArray(
                listOf("started" to 0, "quarter" to 25, "half" to 50, "three_quarters" to 75).map { (stage, percent) ->
                    JSONObject().put("stage", stage).put("from_percent", percent)
                },
            ),
        ).put(
            "cases",
            JSONArray(
                listOf(
                    Triple(0L, 20, "started"),
                    Triple(299_999L, 20, "started"),
                    Triple(300_000L, 20, "quarter"),
                    Triple(599_999L, 20, "quarter"),
                    Triple(600_000L, 20, "half"),
                    Triple(899_999L, 20, "half"),
                    Triple(900_000L, 20, "three_quarters"),
                    Triple(1_199_999L, 20, "three_quarters"),
                    Triple(1_200_000L, 20, "three_quarters"),
                    Triple(5_000_000L, 20, "three_quarters"),
                    Triple(-1L, 20, "started"),
                    Triple(0L, 0, "started"),
                    Triple(15_000L, 1, "quarter"),
                    Triple(44_999L, 1, "half"),
                    Triple(45_000L, 1, "three_quarters"),
                ).map { (time, minutes, stage) ->
                    JSONObject().put("time_ms", time).put("streak_minutes", minutes).put("expected", stage)
                },
            ),
        )

/** One of #53's mood cases, in the fixture's shape. */
private fun case(
    name: String,
    state: String,
    reviews: Int,
    timeMs: Long,
    streak: Int,
    frozenDays: Int,
    mood: String,
    pose: String = mood,
): JSONObject =
    JSONObject()
        .put("name", name)
        .put(
            "today",
            JSONObject()
                .put("state", state)
                .put("reviews", reviews)
                .put("time_ms", timeMs)
                .put("streak_minutes", MINIMUM)
                .put("streak", streak)
                .put("frozen_days", frozenDays)
                .put("goal_reached", false),
        ).put("expected", JSONObject().put("mood", mood).put("pose", pose))

/** #53's new and changed mood cases. */
private val stagedCases =
    listOf(
        case("a little study with a streak stays sad", "short", 3, 60_000, 64, 0, "sad"),
        case("half the minimum after two frozen days is hopeful", "protected", 2, 600_000, 10, 2, "hopeful"),
        case("one review after a freeze stays frozen", "protected", 1, 30_000, 10, 1, "frozen_1", "frozen"),
        case("a freeze holds just under a quarter", "protected", 6, 299_999, 10, 1, "frozen_1", "frozen"),
        case("a review keeps three frozen days frozen", "protected", 1, 30_000, 10, 3, "frozen_3", "frozen_3"),
        case("a quarter of the minimum after a freeze thaws", "protected", 6, 300_000, 10, 1, "thawing", "frozen"),
        case("a quarter of the minimum thaws three frozen days", "protected", 6, 300_000, 10, 3, "thawing", "frozen"),
        case("a freeze thaws just under half", "protected", 12, 599_999, 10, 1, "thawing", "frozen"),
        case("half the minimum after a freeze is hopeful", "protected", 12, 600_000, 10, 1, "hopeful"),
        case("a freeze stays hopeful just under three quarters", "protected", 18, 899_999, 10, 1, "hopeful"),
        case("three quarters of the minimum after a freeze is almost", "protected", 18, 900_000, 10, 1, "almost", "hopeful"),
        case("a freeze is almost just under the minimum", "protected", 24, 1_199_999, 10, 1, "almost", "hopeful"),
        case("frozen days before a short day thaw at a quarter", "short", 6, 300_000, 10, 2, "thawing", "frozen"),
        case("a streak stays sad just under a quarter", "short", 6, 299_999, 64, 0, "sad"),
        case("a streak stays sad at a quarter", "short", 6, 300_000, 64, 0, "sad"),
        case("a streak stays sad just under half", "short", 12, 599_999, 64, 0, "sad"),
        case("half the minimum with a streak is hopeful", "short", 12, 600_000, 64, 0, "hopeful"),
        case("a streak stays hopeful just under three quarters", "short", 18, 899_999, 64, 0, "hopeful"),
        case("three quarters of the minimum with a streak is almost", "short", 18, 900_000, 64, 0, "almost", "hopeful"),
        case("a streak is almost just under the minimum", "short", 24, 1_199_999, 64, 0, "almost", "hopeful"),
        case("without a streak half the minimum is hopeful", "short", 12, 600_000, 0, 0, "hopeful"),
        case("without a streak just under three quarters is hopeful", "short", 18, 899_999, 0, 0, "hopeful"),
        case("without a streak three quarters of the minimum is almost", "short", 18, 900_000, 0, 0, "almost", "hopeful"),
    )

/**
 * The pinned streak_moods.json's mood cases without those #53 replaces, then #53's. The pinned
 * cases have no `time_ms` or `streak_minutes`; read them with opt and these defaults.
 */
internal fun stagedMoodCases(fixtures: JSONObject): List<JSONObject> =
    fixtures.getJSONArray("moods").objects().filter { it.getString("name") !in supersededCases } + stagedCases

/** The streak minimum the pinned cases leave out. */
internal const val PINNED_MINIMUM = 10
