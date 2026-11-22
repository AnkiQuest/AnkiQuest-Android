// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONArray
import org.json.JSONObject

/** The shared fixture `tests/fixtures/companion_unlock.json` from the pinned server commit. */
internal object UnlockFixture {
    private const val PATH = "ankiquest/companion_unlock.json"

    val json: JSONObject by lazy {
        JSONObject(checkNotNull(UnlockFixture::class.java.classLoader?.getResource(PATH)) { "missing $PATH" }.readText())
    }

    /** The fixture's hint after a run of taps leaving [left], with `{0}` filled in. */
    fun hint(left: Int): String {
        val forms = json.getJSONObject("hint")
        return forms.getString(if (left == 1) "one" else "other").replace("{0}", left.toString())
    }

    fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.getJSONObject(it) }
}
