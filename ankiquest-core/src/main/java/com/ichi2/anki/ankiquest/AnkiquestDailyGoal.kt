// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Today's two targets:
 * - the streak minimum: the review minutes the server needs for today to count toward the streak
 *   (`today.streak_minutes`; 0 means any review counts). Whether today met it is the server's
 *   verdict, with reviews not uploaded yet added the server's way by [AnkiquestDay], and
 *   never the collection's own count, and
 * - the daily goal, which the server keeps for every client of the player: a number of cards or
 *   minutes, which the server judges, or a plan to clear the overdue backlog in a number of days,
 *   which only this device can count, so it reports when that goal is reached. See [AnkiquestGoalPlan].
 *
 * Goals chosen on this device before the server kept them are moved there once. The server's goal is
 * cached per account, so it is shown offline.
 */
object AnkiquestDailyGoal {
    const val PREF_MODE = "ankiquestGoalMode"
    const val PREF_BACKLOG_PERCENT = "ankiquestGoalBacklogPercent"
    const val PREF_CARDS = "ankiquestGoalCards"
    const val PREF_MINUTES = "ankiquestGoalMinutes"
    private const val PREF_STREAK_MINUTES = "ankiquestStreakMinutes"
    private const val PREF_DAY_GOAL = "ankiquestDayGoal"
    private const val PREF_SERVER_GOAL = "ankiquestServerGoal"
    private const val PREF_MIGRATED = "ankiquestGoalMigrated"
    private const val PREF_REPORTED_DAY = "ankiquestGoalReportedDay"

    const val DEFAULT_BACKLOG_DAYS = 30
    const val DEFAULT_CARDS = 100
    const val DEFAULT_MINUTES = 20

    enum class Mode(
        val key: String,
    ) {
        BACKLOG("backlog"),
        CARDS("cards"),
        MINUTES("minutes"),
        OFF("off"),
        ;

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: BACKLOG
        }
    }

    /** The goal as the server keeps it: a [mode] and its [value], cards, minutes or the days to clear the backlog in. */
    data class Choice(
        val mode: Mode,
        val value: Int,
    ) {
        val valid: Boolean get() = range(mode)?.contains(value) ?: true

        /** The body the server takes; a goal that is off has no value, and [restart] starts a backlog plan again from today. */
        fun json(restart: Boolean = false): JSONObject =
            JSONObject().put("mode", mode.key).apply {
                if (mode != Mode.OFF) put("value", value)
                if (restart) put("restart", true)
            }

        companion object {
            val DEFAULT = Choice(Mode.BACKLOG, DEFAULT_BACKLOG_DAYS)

            /** The values the server accepts for [mode]; any for [Mode.OFF]. */
            fun range(mode: Mode): IntRange? =
                when (mode) {
                    Mode.CARDS -> 1..5000
                    Mode.MINUTES -> 1..600
                    Mode.BACKLOG -> 1..365
                    Mode.OFF -> null
                }

            /** The server's value for a goal switched to [mode]. */
            fun defaultValue(mode: Mode): Int =
                when (mode) {
                    Mode.BACKLOG -> DEFAULT_BACKLOG_DAYS
                    Mode.CARDS -> DEFAULT_CARDS
                    Mode.MINUTES -> DEFAULT_MINUTES
                    Mode.OFF -> DEFAULT_CARDS
                }

            fun parse(json: JSONObject?): Choice? {
                if (json == null) return null
                val mode = Mode.entries.firstOrNull { it.key == json.optString("mode") } ?: return null
                return Choice(mode, json.optInt("value", defaultValue(mode)))
            }
        }
    }

    /**
     * The goal as the server answers it: the [choice], the server's Anki day [since] its backlog plan
     * started, and whether the player ever [chosen] a goal. Profiles and the cache do not say whether
     * one was chosen, so [chosen] is null there.
     */
    data class ServerGoal(
        val choice: Choice,
        val since: Long? = null,
        val chosen: Boolean? = null,
    ) {
        /** What the cache keeps: the goal and the day its plan started. */
        fun json(): JSONObject = choice.json().apply { since?.let { put("since", it) } }

        companion object {
            fun parse(json: JSONObject?): ServerGoal? {
                if (json == null) return null
                val choice = Choice.parse(json) ?: return null
                val since = if (json.isNull("since")) null else json.optLong("since")
                val chosen = if (json.isNull("chosen")) null else json.optBoolean("chosen")
                return ServerGoal(choice, since, chosen)
            }
        }
    }

    /** What the collection says about today, before any goal is chosen. */
    data class Load(
        val day: Int,
        val studiedCards: Int,
        val minutes: Int,
        /** Cards still to study today that are not overdue: reviews due today, learning and new cards. */
        val dueToday: Int,
        /** Review cards overdue from earlier days, including those past the daily review limit. */
        val backlog: Int,
        /** Today as the server numbers Anki days, which backlog plans are counted in. */
        val studyDay: Long = 0,
        /** What the server last said of today, for the streak minimum. */
        val server: AnkiquestDay.Server? = null,
        /** The collection's reviews after the server's last one. */
        val unsent: List<AnkiquestDay.Review> = emptyList(),
    )

    data class Settings(
        val mode: Mode = Mode.BACKLOG,
        val backlogDays: Int = DEFAULT_BACKLOG_DAYS,
        val cards: Int = DEFAULT_CARDS,
        val minutes: Int = DEFAULT_MINUTES,
        /** The server's Anki day the backlog plan started; unknown until the server's goal is seen. */
        val since: Long? = null,
    ) {
        /** A changed goal works out today's target again; a plan starting again does not. */
        val signature: String get() = "${mode.key}:$backlogDays:$cards:$minutes"
    }

    /** The goal for one day. It is fixed when first worked out, so studying never moves it. */
    data class Goal(
        val target: Int,
        val backlog: Int = 0,
        /** Overdue cards to study today on top of the due ones. */
        val extra: Int = 0,
        /** Days left in the backlog plan, today included. */
        val daysLeft: Int = 0,
    )

    data class Status(
        val day: Int,
        val minutes: Int,
        val streakMinutes: Int,
        val studiedCards: Int,
        val mode: Mode,
        val goal: Goal,
        /** Reviews or minutes so far as the server counts them, for goals the server judges. */
        val serverProgress: Int? = null,
        /** The server says today's goal is reached. */
        val reached: Boolean = false,
        /** Today's streak verdict from [AnkiquestDay]; null while nothing current is known from the server. */
        val streakDay: AnkiquestDay.Verdict? = null,
        /** The collection's reviews after the server's last one, to judge a fresher profile with. */
        val unsent: List<AnkiquestDay.Review> = emptyList(),
        /** The account has no token, so the server stores none of its reviews and only this device can judge. */
        val deviceOnly: Boolean = false,
        /** Why the last upload failed, while reviews wait for one that works. */
        val uploadProblem: AnkiquestUploadProblem? = null,
    ) {
        val progress: Int get() = serverProgress ?: if (mode == Mode.MINUTES) minutes else studiedCards

        /** Today counts toward the streak by this device's reviews, by the same rule as the server. */
        val minimumDone: Boolean get() = studiedCards > 0 && minutes >= streakMinutes

        /**
         * The streak is secured: the server says so, or reviews it has not received yet provably make it
         * ([awaitingUpload]); for an account the server stores nothing for, this device's reviews.
         */
        val secured: Boolean get() = streakDay?.met == true || (deviceOnly && minimumDone)

        /** Secured only by reviews the server has not received yet: say so until the upload lands. */
        val awaitingUpload: Boolean get() = streakDay?.awaitingUpload == true

        /** This device's reviews count for today, but the server has not counted them, nor can they be shown to. */
        val syncing: Boolean get() = minimumDone && !secured

        /** Minutes toward the streak minimum: the server's, with reviews not uploaded yet; the collection's while unknown. */
        val streakProgress: Int get() = streakDay?.minutes ?: minutes

        /** [streakDay] for [profile] when it says anything of today, as an upload or the server just answered. */
        fun withServerDay(
            profile: JSONObject?,
            now: Long,
        ): Status {
            if (profile?.optJSONObject("today") == null) {
                return this
            }
            val server = AnkiquestDay.Server.profile(profile) ?: return this
            val day = AnkiquestDay.judge(server, unsent, now) ?: return this
            return copy(streakDay = day, streakMinutes = day.streakMinutes)
        }

        val goalDone: Boolean get() = mode != Mode.OFF && (reached || (progress > 0 && progress >= goal.target))
    }

    /**
     * The goal for [load] under [settings]; cards already studied today stay part of it. A backlog
     * goal is today's due cards plus the backlog spread over the days left in the plan.
     */
    fun goal(
        load: Load,
        settings: Settings,
    ): Goal =
        when (settings.mode) {
            Mode.CARDS -> Goal(settings.cards.coerceAtLeast(1))
            Mode.MINUTES -> Goal(settings.minutes.coerceAtLeast(1))
            Mode.BACKLOG -> {
                val days = AnkiquestGoalPlan.daysLeft(settings.backlogDays, settings.since, load.studyDay)
                val extra = AnkiquestGoalPlan.extra(load.backlog, days)
                Goal(load.studiedCards + load.dueToday + extra, load.backlog, extra, days)
            }
            Mode.OFF -> Goal(0)
        }

    /** The configured account's goal: the server's, as last seen, or until then the one chosen on this device. */
    fun settings(prefs: SharedPreferences = AnkiquestStore.preferences()): Settings {
        val local = localSettings(prefs)
        val server = cached(prefs, HomeAccount.current(prefs)?.scope) ?: return local
        val choice = server.choice
        val settings =
            when (choice.mode) {
                Mode.BACKLOG -> local.copy(mode = Mode.BACKLOG, backlogDays = choice.value)
                Mode.CARDS -> local.copy(mode = Mode.CARDS, cards = choice.value)
                Mode.MINUTES -> local.copy(mode = Mode.MINUTES, minutes = choice.value)
                Mode.OFF -> local.copy(mode = Mode.OFF)
            }
        return settings.copy(since = server.since)
    }

    /** The goal older versions kept on this device; a daily backlog percentage becomes the closest plan length. */
    private fun localSettings(prefs: SharedPreferences): Settings =
        Settings(
            Mode.of(prefs.getString(PREF_MODE, null)),
            prefs.getString(PREF_BACKLOG_PERCENT, null)?.toIntOrNull()?.let(AnkiquestGoalPlan::daysForPercent)
                ?: DEFAULT_BACKLOG_DAYS,
            prefs.intOr(PREF_CARDS, DEFAULT_CARDS),
            prefs.intOr(PREF_MINUTES, DEFAULT_MINUTES),
        )

    /** The goal chosen on this device by older versions, in the server's limits; null when none was ever chosen. */
    fun localChoice(prefs: SharedPreferences): Choice? {
        if (listOf(PREF_MODE, PREF_BACKLOG_PERCENT, PREF_CARDS, PREF_MINUTES).none { prefs.contains(it) }) return null
        val choice = localSettings(prefs).asChoice()
        return Choice.range(choice.mode)?.let { choice.copy(value = choice.value.coerceIn(it)) } ?: choice
    }

    /** The goal to show for the configured account, as [settings] has it. */
    fun choice(prefs: SharedPreferences = AnkiquestStore.preferences()): Choice = settings(prefs).asChoice()

    private fun Settings.asChoice(): Choice =
        when (mode) {
            Mode.BACKLOG -> Choice(Mode.BACKLOG, backlogDays)
            Mode.CARDS -> Choice(Mode.CARDS, cards)
            Mode.MINUTES -> Choice(Mode.MINUTES, minutes)
            Mode.OFF -> Choice(Mode.OFF, cards)
        }

    /** The server's goal for the account with [scope], as last seen. */
    fun cached(
        prefs: SharedPreferences,
        scope: String?,
    ): ServerGoal? =
        prefs
            .getString(scopedKey(PREF_SERVER_GOAL, scope), null)
            ?.let { runCatching { ServerGoal.parse(JSONObject(it)) }.getOrNull() }

    private fun cache(
        prefs: SharedPreferences,
        scope: String,
        goal: ServerGoal,
    ) {
        val value = goal.json().toString()
        if (prefs.getString(scopedKey(PREF_SERVER_GOAL, scope), null) != value) {
            prefs.edit { putString(scopedKey(PREF_SERVER_GOAL, scope), value) }
        }
    }

    private fun migrated(
        prefs: SharedPreferences,
        scope: String?,
    ): Boolean = prefs.getBoolean(scopedKey(PREF_MIGRATED, scope), false)

    /**
     * Keeps the server's streak minimum, so it is known offline and right after studying, and its goal
     * once any goal chosen on this device has moved to the server.
     */
    fun remember(
        profile: JSONObject,
        prefs: SharedPreferences = AnkiquestStore.preferences(),
    ) {
        AnkiquestDay.remember(profile, prefs)
        profile.optJSONObject("today")?.let { today ->
            val minutes = today.optInt("streak_minutes", 0).coerceAtLeast(0)
            val key = streakMinutesKey(prefs)
            if (prefs.getInt(key, -1) != minutes) prefs.edit { putInt(key, minutes) }
        }
        val scope = HomeAccount.current(prefs)?.scope ?: return
        val goal = ServerGoal.parse(profile.optJSONObject("goal")) ?: return
        if (!migrated(prefs, scope)) {
            if (localChoice(prefs) != null) return
            prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, scope), true) }
        }
        cache(prefs, scope, goal)
    }

    /** The streak minimum of the configured account; another account's minimum is never assumed. */
    fun streakMinutes(prefs: SharedPreferences = AnkiquestStore.preferences()): Int = prefs.getInt(streakMinutesKey(prefs), 0)

    private fun streakMinutesKey(prefs: SharedPreferences) = scopedKey(PREF_STREAK_MINUTES, HomeAccount.current(prefs)?.scope)

    /** The goal [account] keeps on the server, after moving there the goal chosen on this device. */
    suspend fun fetch(
        account: HomeAccount,
        prefs: SharedPreferences = AnkiquestStore.preferences(),
    ): ServerGoal {
        val server = ServerGoal.parse(AnkiquestSharing.goal(account)) ?: throw IOException("The server sent no goal")
        val goal = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, goal)
        return goal
    }

    /**
     * Stores [choice] as [account]'s goal and answers what the server keeps. A backlog plan saved with
     * [restart] starts again from today even when its length is unchanged.
     */
    suspend fun save(
        account: HomeAccount,
        choice: Choice,
        restart: Boolean = false,
        prefs: SharedPreferences = AnkiquestStore.preferences(),
    ): ServerGoal {
        require(choice.valid)
        val saved =
            ServerGoal.parse(AnkiquestSharing.saveGoal(account, choice.json(restart)))
                ?: ServerGoal(choice, chosen = true)
        prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, account.scope), true) }
        cache(prefs, account.scope, saved)
        return saved
    }

    /**
     * A goal chosen on this device replaces the server's only while the server still has its
     * default, so a goal set on another client is never overwritten. It happens once per account.
     */
    private suspend fun migrate(
        account: HomeAccount,
        server: ServerGoal,
        prefs: SharedPreferences,
    ): ServerGoal {
        val local = localChoice(prefs)
        val goal =
            if (server.choice == Choice.DEFAULT && server.chosen != true && local != null && local != server.choice) {
                ServerGoal.parse(AnkiquestSharing.saveGoal(account, local.json())) ?: ServerGoal(local, chosen = true)
            } else {
                server
            }
        prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, account.scope), true) }
        return goal
    }

    /** What [afterUpload] reads from the player's study data. */
    interface Study {
        /** The current study day. */
        suspend fun day(): Long

        /** Today's [Status] as [status] reads it with [prefs], and the study day it was read on. */
        suspend fun status(prefs: SharedPreferences): Pair<Status, Long>
    }

    /**
     * After an upload for [account]: moves a goal chosen on this device to the server, starts a backlog
     * plan that ran out again, and reports a backlog goal reached today once, since only this device
     * knows how many cards are due.
     */
    suspend fun afterUpload(
        account: HomeAccount,
        profile: JSONObject,
        study: Study,
        prefs: SharedPreferences = AnkiquestStore.preferences(),
    ) {
        if (HomeAccount.current(prefs)?.scope == account.scope) AnkiquestDay.remember(profile, prefs)
        val goal = profile.optJSONObject("goal") ?: return
        val server = ServerGoal.parse(goal) ?: return
        val current = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, current)
        val choice = current.choice
        if (choice.mode != Mode.BACKLOG) return
        val today = study.day()
        if (AnkiquestGoalPlan.planEnded(choice.value, current.since, today)) restart(account, current, today, prefs)
        if (choice == server.choice && goal.optBoolean("reached")) return
        val (status, day) = study.status(prefs)
        val reportedKey = scopedKey(PREF_REPORTED_DAY, account.scope)
        if (!status.goalDone || prefs.getLong(reportedKey, Long.MIN_VALUE) == day) return
        AnkiquestSharing.goalReached(account, day)
        prefs.edit { putLong(reportedKey, day) }
    }

    /**
     * Starts [goal]'s backlog plan again from [today] on the server. Until the server takes it,
     * [AnkiquestGoalPlan.daysLeft] already counts the plan as starting today.
     */
    private suspend fun restart(
        account: HomeAccount,
        goal: ServerGoal,
        today: Long,
        prefs: SharedPreferences,
    ) {
        try {
            val saved = ServerGoal.parse(AnkiquestSharing.saveGoal(account, goal.choice.json(restart = true)))
            cache(prefs, account.scope, if (saved?.since == null) goal.copy(since = today) else saved)
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest backlog plan could not start again")
        }
    }

    /** Today's targets and progress for [load]; the goal is fixed per account at the first call of the day. */
    fun status(
        load: Load,
        prefs: SharedPreferences,
    ): Status {
        val settings = settings(prefs)
        val key = scopedKey(PREF_DAY_GOAL, HomeAccount.current(prefs)?.scope)
        val stored = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val goal =
            if (stored != null && stored.optInt("day", -1) == load.day && stored.optString("settings") == settings.signature) {
                Goal(stored.optInt("target"), stored.optInt("backlog"), stored.optInt("extra"), stored.optInt("days"))
            } else {
                goal(load, settings).also { fresh ->
                    prefs.edit {
                        putString(
                            key,
                            JSONObject()
                                .put("day", load.day)
                                .put("settings", settings.signature)
                                .put("target", fresh.target)
                                .put("backlog", fresh.backlog)
                                .put("extra", fresh.extra)
                                .put("days", fresh.daysLeft)
                                .toString(),
                        )
                    }
                }
            }
        val day = AnkiquestDay.judge(load.server ?: AnkiquestDay.last(prefs), load.unsent, TimeManager.time.intTimeMS())
        val account = HomeAccount.current(prefs)
        return Status(
            load.day,
            load.minutes,
            day?.streakMinutes ?: streakMinutes(prefs),
            load.studiedCards,
            settings.mode,
            goal,
            streakDay = day,
            unsent = load.unsent,
            deviceOnly = account == null || account.token.isEmpty(),
            uploadProblem = AnkiquestUploadProblem.last(prefs, account?.scope),
        )
    }

    /**
     * [status] with what the server says in the owner's [profile] about today's goal: for cards and
     * minutes the server's count and verdict, for a backlog goal whether a reach was reported. A profile
     * from a day that has ended, or from before a goal chosen on this device moved to the server, says nothing.
     * The streak minimum follows the [profile]'s verdict on today, as [Status.withServerDay] judges it.
     */
    fun withProfile(
        current: Status,
        profile: JSONObject?,
        prefs: SharedPreferences = AnkiquestStore.preferences(),
        now: Long = TimeManager.time.intTimeMS(),
    ): Status {
        if (profile == null) return current
        return withGoal(current.withServerDay(profile, now), profile, prefs, now)
    }

    private fun withGoal(
        status: Status,
        profile: JSONObject,
        prefs: SharedPreferences,
        now: Long,
    ): Status {
        if (!migrated(prefs, HomeAccount.current(prefs)?.scope)) return status
        if (profile.optLong("day_ends_at") in 1..now) return status
        val goal = profile.optJSONObject("goal") ?: return status
        val choice = Choice.parse(goal) ?: return status
        val reached = goal.optBoolean("reached")
        return when (choice.mode) {
            Mode.OFF -> status.copy(mode = Mode.OFF)
            Mode.BACKLOG -> if (status.mode == Mode.BACKLOG) status.copy(reached = reached) else status
            Mode.CARDS, Mode.MINUTES ->
                status.copy(
                    mode = choice.mode,
                    goal = Goal(choice.value.coerceAtLeast(1)),
                    serverProgress = if (goal.isNull("progress")) null else goal.optInt("progress"),
                    reached = reached,
                )
        }
    }

    private fun SharedPreferences.intOr(
        key: String,
        default: Int,
    ): Int =
        when (val value = all[key]) {
            is Int -> value
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
}
