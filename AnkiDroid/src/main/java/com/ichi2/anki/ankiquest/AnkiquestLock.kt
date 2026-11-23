// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.annotation.StringRes
import com.ichi2.anki.R
import okhttp3.Response
import org.json.JSONObject

/**
 * Why the server left other players' study out of an answer (docs/mobile-api.md, "seeing other
 * players: upload first"). Locked answers keep their shape, so the app says why they are empty.
 */
internal enum class AnkiquestLock(
    private val wire: String,
    /** What the app says in place of the others it cannot show. */
    @StringRes val text: Int,
) {
    /** Until the server holds one of the player's reviews. */
    UPLOAD_FIRST("upload_first", R.string.aq_upload_first),
    ;

    companion object {
        /** The header every locked answer carries; list answers have no other place to say it. */
        const val HEADER = "X-Ankiquest-Locked"

        /** The lock [value] names, or null for none or one this app does not know. */
        fun of(value: String?): AnkiquestLock? = entries.firstOrNull { it.wire == value }

        /** The lock [response]'s header names. */
        fun of(response: Response): AnkiquestLock? = of(response.header(HEADER))

        /** The lock an object answer names in its `locked` field. */
        fun of(answer: JSONObject): AnkiquestLock? = of(answer.optString("locked"))

        /** The lock stored under its [name], or null for none or one this app no longer knows. */
        fun named(name: String?): AnkiquestLock? = entries.firstOrNull { it.name == name }
    }
}
