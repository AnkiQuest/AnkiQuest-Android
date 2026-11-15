// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared fixture `tests/fixtures/companion_unlock.json` from the pinned server commit, with
 * the chibi fields and cases it does not have yet taken from the local copy
 * `ankiquest-pending/companion_unlock.json`. Pinned data wins: a pending field or case is used only
 * while the pinned fixture lacks it, so the server bump replaces the copy without changing a test.
 */
internal object ChibiFixture {
    private const val PINNED = "ankiquest/companion_unlock.json"
    private const val PENDING = "ankiquest-pending/companion_unlock.json"

    val json: JSONObject by lazy { merged(read(PINNED), read(PENDING)) }

    /** The fixture's hint after a run of taps leaving [left], with `{0}` filled in. */
    fun hint(left: Int): String {
        val forms = json.getJSONObject("hint")
        return forms.getString(if (left == 1) "one" else "other").replace("{0}", left.toString())
    }

    fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }

    private fun read(path: String) =
        JSONObject(checkNotNull(ChibiFixture::class.java.classLoader?.getResource(path)) { "missing $path" }.readText())

    private fun merged(
        pinned: JSONObject,
        pending: JSONObject,
    ): JSONObject {
        // Fields the pinned fixture lacks come from the copy.
        for (key in pending.keys()) {
            if (key != "_about" && key != "cases" && !pinned.has(key)) {
                pinned.put(key, pending.get(key))
            }
        }

        // Cases are added by name, so a pinned case is never replaced.
        val cases = pinned.getJSONArray("cases")
        val names = objects(cases).map { it.getString("name") }.toSet()
        for (case in objects(pending.getJSONArray("cases"))) {
            if (case.getString("name") !in names) {
                cases.put(case)
            }
        }
        return pinned
    }
}
