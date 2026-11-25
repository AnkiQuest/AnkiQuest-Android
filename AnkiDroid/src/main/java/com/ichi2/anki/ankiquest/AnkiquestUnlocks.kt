// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.json.JSONException
import org.json.JSONObject

/**
 * Companions earned at milestones: Ankilope at a level, the cow (Bluma) by hours of study, Anki-tan
 * by a streak. The server decides and keeps every unlock per account (`GET /api/unlocks/{user}`);
 * the app shows what it last answered. They are not settings: the settings-sync switch does not apply. The
 * conditions are the shared fixture `tests/fixtures/companion_unlock.json` in float3/ankiquest.
 */
internal object AnkiquestUnlocks {
    private const val PREF_UNLOCKS = "ankiquestUnlocks"

    /** Companions earned at a milestone. */
    private val EARNED = setOf(Choice.ANKILOPE, Choice.COW, Choice.ANKI_TAN)

    /** What a milestone counts; [id] is the fixture's and the server's `kind`. */
    enum class Milestone(
        val id: String,
    ) {
        LEVEL("level"),
        STUDY_HOURS("study_hours"),

        /** Days in a row, as the player's streak stands now. */
        STREAK("streak"),
        ;

        companion object {
            fun of(id: String): Milestone? = entries.firstOrNull { it.id == id }
        }
    }

    /** How far the player is toward a locked companion; [milestone] is null for a kind this app does not know. */
    data class Progress(
        val milestone: Milestone?,
        val value: Int,
        val goal: Int,
    )

    /**
     * The server's answer: the [ids] of everything the player has unlocked, including unlocks that
     * are not choices here (a chibi or its base), and progress toward each locked companion that shows.
     */
    data class Found(
        val ids: Set<String>,
        val progress: Map<Choice, Progress>,
    ) {
        /** The unlocked companions this app can show, read from [ids]. */
        val unlocks: Set<Choice> get() = ids.mapNotNullTo(mutableSetOf()) { known(it) }

        companion object {
            /** Nothing unlocked, no progress. */
            val NONE = Found(emptySet(), emptyMap())

            /** A `GET /api/unlocks/{user}` answer, leaving out what this app cannot read or show. */
            fun parse(answer: JSONObject): Found {
                val listedIds = answer.optJSONArray("unlocks")
                val ids = (0 until (listedIds?.length() ?: 0)).mapNotNull { listedIds?.opt(it) as? String }.toSet()
                val listed = answer.optJSONObject("progress") ?: return Found(ids, emptyMap())
                val progress =
                    listed
                        .keys()
                        .asSequence()
                        .mapNotNull { id -> known(id)?.let { choice -> progress(listed.optJSONObject(id))?.let { choice to it } } }
                        .toMap()
                return Found(ids, progress)
            }

            private fun known(id: String?): Choice? = Choice.entries.firstOrNull { it.id == id }

            private fun progress(entry: JSONObject?): Progress? {
                val kind = entry?.opt("kind") as? String ?: return null
                val value = entry.opt("value") as? Int ?: return null
                val goal = entry.opt("goal") as? Int ?: return null
                return Progress(Milestone.of(kind), value, goal)
            }
        }
    }

    /** How the picker offers a companion. */
    sealed interface Offer {
        /** Anyone can choose it. */
        data object Free : Offer

        /** Earned, or already chosen: it can be chosen. */
        data object Unlocked : Offer

        /** Shown as a silhouette with its condition and [progress]; it cannot be chosen. */
        data class Locked(
            val progress: Progress,
        ) : Offer

        /** Secret until earned, or not known yet. */
        data object Hidden : Offer
    }

    /**
     * How the picker offers [choice] to a player whose companion is [current], given the server's
     * [found] answer (null until it first answers). A chosen companion stays a choice, so a player
     * who chose Ankilope before milestones keeps it.
     */
    fun offer(
        choice: Choice,
        found: Found?,
        current: Choice,
    ): Offer {
        if (choice !in EARNED) {
            return Offer.Free
        }
        if (choice == current || (found != null && choice in found.unlocks)) {
            return Offer.Unlocked
        }
        val progress = found?.progress?.get(choice)
        if (progress != null) {
            return Offer.Locked(progress)
        }
        return Offer.Hidden
    }

    /** The server's last answer for [account], or null when it has not answered yet. */
    fun found(account: HomeAccount): Found? {
        val stored = AnkiDroidApp.sharedPrefs().getString(key(account), null) ?: return null
        return try {
            Found.parse(JSONObject(stored))
        } catch (_: JSONException) {
            null
        }
    }

    /** Reads [account]'s unlocks and progress from its server and keeps them for the picker. */
    suspend fun sync(account: HomeAccount) {
        if (account.token.isEmpty()) {
            return
        }
        keep(account, AnkiquestHomeData.unlocks(account))
    }

    /**
     * Tells [account]'s server the player tapped [chibi] free and keeps its answer. Only the result
     * is sent: the server trusts the app's count.
     */
    suspend fun claim(
        account: HomeAccount,
        chibi: AnkiquestChibi.Chibi,
    ) {
        keep(account, AnkiquestHomeData.saveUnlock(account, chibi.id))
    }

    private fun keep(
        account: HomeAccount,
        answer: JSONObject,
    ) {
        AnkiDroidApp.sharedPrefs().edit { putString(key(account), answer.toString()) }
    }

    private fun key(account: HomeAccount) = scopedKey(PREF_UNLOCKS, account.notificationAccount)
}
