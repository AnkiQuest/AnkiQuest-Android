// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject

/*
 * The review_row cases from AnkiQuest-Server#89, mirrored here until the snapshot bump brings its
 * clients.json. They replace every pinned review_row case: each row gains `ease`, the button
 * pressed. At that bump, delete this file and read `review_row` from the fixture.
 */

/** (id, cid, lastIvl, ivl, time, type, ease) as the revlog holds them. */
private val rows =
    listOf(
        listOf(1789000000123, 1700000000000, -600, -60, 4200, 0, 3),
        listOf(1789000000456, 1700000000001, 12, 30, 9000, 1, 3),
        // An intraday pass, as forks with intraday review intervals log it.
        listOf(1789000000789, 1700000000002, 1, -3600, 6000, 1, 3),
        // Again into relearning.
        listOf(1789000000999, 1700000000003, 30, -600, 7000, 1, 1),
    )

private val names = listOf("id", "cid", "last_ivl", "ivl", "time_ms", "kind", "ease")

/** #89's `review_row` cases: a revlog `row` and the `review` object the server reads. */
internal val easeReviewRows: List<JSONObject> =
    rows.map { row ->
        val review = JSONObject()
        names.zip(row).forEach { (name, value) -> review.put(name, value) }
        JSONObject().put("row", JSONArray(row)).put("review", review)
    }
