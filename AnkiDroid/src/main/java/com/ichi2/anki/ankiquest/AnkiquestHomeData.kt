// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class HomeDeck(
    val id: Long,
    val name: String,
    val new: Int,
    val learning: Int,
    val review: Int,
    val daily: DailyDeckState = DailyDeckState((new + learning + review).toLong(), (new + learning + review).toLong(), 0),
) {
    val due: Int get() = new + learning + review
}

internal data class HomeLocal(
    val decks: List<HomeDeck>,
    val selected: Long,
    val nextUpdateAt: Long? = null,
    val goal: AnkiquestDailyGoal.Status? = null,
    /** The average answer time, for the time left toward [goal]. */
    val secondsPerCard: Double = AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD,
) {
    val focus: HomeDeck? get() =
        decks.firstOrNull { it.id == selected && it.daily.status == DailyDeckStatus.REVIEW_NOW }
            ?: decks.firstOrNull { it.daily.status == DailyDeckStatus.REVIEW_NOW }
            ?: decks.firstOrNull { it.id == selected }
            ?: decks.firstOrNull()
}

internal object AnkiquestHomeData {
    /** Sent with every companion save: `docs/mobile-api.md`, study companion, in float3/ankiquest. */
    private const val COMPANIONS_HEADER = "X-AnkiQuest-Companions"
    private const val SHOWS_LOCKS = "milestones"

    fun account(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? = HomeAccount.current(preferences)

    /** The profile of the captured [account], so changed settings never borrow its credentials. */
    suspend fun profile(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            AnkiquestApi.call(AnkiquestApi.request(account, "api/profile/${account.encodedUser}").build()) {
                JSONObject(it.body.string())
            }
        }

    suspend fun companion(account: HomeAccount): String = companionRequest(account, null)

    suspend fun saveCompanion(
        account: HomeAccount,
        choice: String,
    ): String {
        require(AnkiquestCompanion.normalize(choice) == choice)
        return companionRequest(account, choice)
    }

    private suspend fun companionRequest(
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
                AnkiquestCompanion.normalize(JSONObject(it.body.string()).getString("companion"))
            }
        }

    /** The companions [account] has unlocked and its progress toward the others (`GET /api/unlocks/{user}`). */
    suspend fun unlocks(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            val request = AnkiquestApi.request(account, "api/unlocks/${account.encodedUser}").build()
            AnkiquestApi.call(account, request) { AnkiquestApi.jsonObject(it) }
        }

    /** Tells the server [account] unlocked [unlock] (`POST /api/unlocks/{user}`); answers as [unlocks]. */
    suspend fun saveUnlock(
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

    suspend fun local(): HomeLocal =
        CollectionManager.withCol {
            val now = TimeManager.time.intTimeMS()
            val tree = sched.deckDueTree()
            val daily = AnkiquestDecks.dailyStates(this, now, tree)
            val rows =
                tree.filter { it.did != 0L }.map {
                    HomeDeck(
                        it.did,
                        it.fullDeckName,
                        it.newCount,
                        it.lrnCount,
                        it.revCount,
                        daily[it.did]
                            ?: DailyDeckState(
                                (it.newCount + it.lrnCount + it.revCount).toLong(),
                                (it.newCount + it.lrnCount + it.revCount).toLong(),
                                0,
                            ),
                    )
                }
            val cutoff = sched.dayCutoff * 1000L
            val next = (daily.values.mapNotNull { it.nextLearningAt } + cutoff).filter { it > now }.minOrNull()
            val goal = runCatching { AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), AnkiDroidApp.sharedPrefs()) }.getOrNull()
            val secondsPerCard =
                runCatching { AnkiquestGoalPlan.secondsPerCard(this, now) }.getOrDefault(AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD)
            HomeLocal(rows, decks.selected(), next, goal, secondsPerCard)
        }
}
