// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The player's wins shared through `/api/share`: with their circle, with friends, or as a public link. */
object AnkiquestWins {
    /** A win's public link, its card and the server's invitation text, which includes the link. */
    data class Link(
        val url: String,
        val image: String,
        val text: String,
    )

    /**
     * Shares a win with everyone in the player's circle through `/api/share`: one of their own
     * [notification]s, or their current [place] on this week's board. Answers how many friends got it.
     *
     * @param expectedScope the [HomeAccount.scope] the win belongs to; another signed-in account never shares it
     */
    suspend fun share(
        notification: Long?,
        place: Int?,
        expectedScope: String,
    ): Int =
        post(
            JSONObject().apply {
                notification?.let { put("notification", it) }
                place?.let { put("place", it) }
            },
            expectedScope,
        ).optInt("recipients")

    /** Sends the server's [win] to everyone in the player's circle; answers how many friends got it. */
    suspend fun withFriends(
        win: Long,
        expectedScope: String,
    ): Int = post(JSONObject().put("win", win).put("to", "friends"), expectedScope).optInt("recipients")

    /** The public link for the server's [win], with its card and invitation text; the server makes it once and reuses it. */
    suspend fun link(
        win: Long,
        expectedScope: String,
    ): Link {
        val answer = post(JSONObject().put("win", win).put("to", "link"), expectedScope)
        return Link(answer.getString("url"), answer.getString("image"), answer.getString("text"))
    }

    private suspend fun post(
        body: JSONObject,
        expectedScope: String,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val account = HomeAccount.current()?.takeIf { it.scope == expectedScope } ?: throw HomeAccountChanged()
            check(account.token.isNotEmpty()) { "Set your ankiquest token to share a win." }
            AnkiquestApi.json(
                account,
                AnkiquestApi
                    .request(account, "api/share/${account.encodedUser}")
                    .post(AnkiquestApi.body(body))
                    .build(),
            )
        }
}
