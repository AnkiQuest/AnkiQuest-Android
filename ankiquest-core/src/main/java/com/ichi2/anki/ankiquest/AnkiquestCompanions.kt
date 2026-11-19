// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The server's record of the player's companion: the one chosen and the ones unlocked. */
object AnkiquestCompanions {
    /** Sent with every companion save: `docs/mobile-api.md`, study companion, in float3/ankiquest. */
    private const val COMPANIONS_HEADER = "X-AnkiQuest-Companions"
    private const val SHOWS_LOCKS = "milestones"

    /** The companion the server has for [account], as it stores it. */
    suspend fun choice(account: HomeAccount): String = request(account, null)

    /** Saves [choice] for [account]; answers the companion the server then has. */
    suspend fun choose(
        account: HomeAccount,
        choice: String,
    ): String = request(account, choice)

    private suspend fun request(
        account: HomeAccount,
        choice: String?,
    ): String =
        withContext(Dispatchers.IO) {
            check(account.token.isNotEmpty()) { "Set your AnkiQuest token to choose a companion." }
            val request =
                AnkiquestApi
                    .request(account, "api/companion/${account.encodedUser}")
                    .apply {
                        // The header says this app shows locked companions, so the server refuses one still locked.
                        if (choice != null) {
                            header(COMPANIONS_HEADER, SHOWS_LOCKS)
                            post(AnkiquestApi.body(JSONObject().put("companion", choice)))
                        }
                    }.build()
            // A request queued behind another, or answered, after the account changed must not touch the old account.
            AnkiquestApi.call(account, request) {
                JSONObject(it.body.string()).getString("companion")
            }
        }

    /** The companions [account] has unlocked and its progress toward the others (`GET /api/unlocks/{user}`). */
    suspend fun unlocks(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            val request = AnkiquestApi.request(account, "api/unlocks/${account.encodedUser}").build()
            AnkiquestApi.call(account, request) { AnkiquestApi.jsonObject(it) }
        }

    /** Tells the server [account] unlocked [unlock] (`POST /api/unlocks/{user}`); answers as [unlocks]. */
    suspend fun unlock(
        account: HomeAccount,
        unlock: String,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                AnkiquestApi
                    .request(account, "api/unlocks/${account.encodedUser}")
                    .post(AnkiquestApi.body(JSONObject().put("unlock", unlock)))
                    .build()
            AnkiquestApi.call(account, request) { AnkiquestApi.jsonObject(it) }
        }
}
