// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

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
    /** The profile of the captured [account], so changed settings never borrow its credentials. */
    suspend fun profile(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            AnkiquestApi.call(AnkiquestApi.request(account, "api/profile/${account.encodedUser}").build()) {
                JSONObject(it.body.string())
            }
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
