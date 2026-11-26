// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Messages to friends with `POST /api/messages/{user}` (docs/mobile-api.md, "messages between
 * friends"). A message arrives in the friend's activity as a `message` row, which they answer like
 * any other row.
 */
internal object AnkiquestMessages {
    /** The capability of servers that deliver messages between friends. */
    const val FEATURE = "messages"

    /** The answer when the sender used up their messages for the day. */
    private const val TOO_MANY_REQUESTS = 429

    /** What became of a message the server accepted for sending. */
    enum class Outcome {
        /** Sent; a friend who muted the player is told nothing, and the answer is the same. */
        SENT,

        /** The player sent as many messages as the server allows in a day. */
        DAILY_LIMIT,
    }

    /** Sends [text], cleaned as the server accepts it, to the friend [to]. */
    suspend fun send(
        account: HomeAccount,
        to: String,
        text: String,
    ): Outcome =
        withContext(Dispatchers.IO) {
            val message = AnkiquestReply.clean(text)
            require(message.isNotEmpty())
            val request =
                AnkiquestApi
                    .request(account, "api/messages/${account.encodedUser}")
                    .post(AnkiquestApi.body(body(to, message)))
                    .build()
            try {
                AnkiquestApi.json(account, request)
                Outcome.SENT
            } catch (e: AnkiquestHttpException) {
                if (e.code != TOO_MANY_REQUESTS) {
                    throw e
                }
                Outcome.DAILY_LIMIT
            }
        }

    fun body(
        to: String,
        message: String,
    ): JSONObject = JSONObject().put("to", to).put("message", message)
}
