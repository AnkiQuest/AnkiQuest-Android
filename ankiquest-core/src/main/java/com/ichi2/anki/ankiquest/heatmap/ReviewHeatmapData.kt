// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import com.ichi2.anki.ankiquest.StudySource
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Collection history: counts are answers, not distinct cards or server points.
 *
 * [verdicts] are AnkiQuest's judgement of the days it covers: whether each reached the streak
 * minimum, fell short of it, or was kept by a streak freeze. Where AnkiQuest judged a day, the
 * heatmap colours it and counts it toward the streaks by that verdict only; a day it never
 * judged counts when it has a review, as Anki has it.
 *
 * [protectedDays] are the ended days AnkiQuest kept the streak through, with a streak freeze or
 * because moving the clock east skipped them, whether or not they had reviews. Both streaks
 * count them as AnkiQuest does: such a day keeps a streak going and does not lengthen it.
 */
data class ReviewHeatmapData(
    val today: LocalDate,
    val reviews: Map<LocalDate, Int>,
    val scheduled: Map<LocalDate, Int>,
    val currentStreak: Int,
    val longestStreak: Int,
    val totalReviews: Long,
    val daysStudied: Int,
    val firstReviewDate: LocalDate?,
    val dailyAverage: Double,
    val protectedDays: Set<LocalDate> = emptySet(),
    val verdicts: Map<LocalDate, DayVerdict> = emptyMap(),
) {
    val forecastEndDate: LocalDate get() = today.plusDays(ReviewHeatmap.FORECAST_DAYS)
}

/** The server's verdict on one day, as a heatmap cell or study day gives it. */
enum class DayState(
    val key: String,
) {
    /** Reached the streak minimum: the day counts toward the streak. */
    MET("met"),

    /** Reviews short of the minimum. Today stays short until it is met or ends. */
    SHORT("short"),

    /** A streak freeze or an admin kept the streak through the day, reviewed or not. */
    FROZEN("frozen"),

    /** Moving the clock east stepped over the day; it keeps the streak like a frozen day. */
    SKIPPED("skipped"),

    /** No reviews, and nothing kept the streak. */
    NONE("none"),
    ;

    val keepsStreak: Boolean get() = this == FROZEN || this == SKIPPED

    companion object {
        /** The state the server named, or null from servers that do not judge days yet. */
        fun of(key: String?): DayState? = entries.firstOrNull { it.key == key }
    }
}

/** What the server says of a day: its [state], the [minutes] studied and the [streakMinutes] it needed. */
data class DayVerdict(
    val state: DayState,
    val minutes: Int = 0,
    val streakMinutes: Int = 0,
) {
    companion object {
        /** The verdict in a heatmap cell or study day, or null from servers that do not send one. */
        fun parse(json: JSONObject): DayVerdict? {
            val state = DayState.of(json.optString("state").ifEmpty { null }) ?: return null
            val minutes = json.optInt("minutes", (json.optLong("time_ms") / 60_000).toInt())
            return DayVerdict(state, minutes, json.optInt("streak_minutes"))
        }
    }
}

object ReviewHeatmap {
    /** Colour level relative to the daily average, shared with the desktop heatmap fixture. */
    fun intensity(
        count: Int,
        dailyAverage: Double,
    ): Int =
        when {
            count <= 0 -> 0
            count <= dailyAverage.coerceAtLeast(1.0) / 2 -> 1
            count <= dailyAverage.coerceAtLeast(1.0) -> 2
            count <= dailyAverage.coerceAtLeast(1.0) * 2 -> 3
            else -> 4
        }

    const val FORECAST_DAYS = 90L

    /** The heatmap for [source]'s history; reading it can take a while, so stay off the main thread. */
    fun load(source: StudySource): ReviewHeatmapData {
        val history = source.history()
        return summarize(history.today, history.reviews, history.scheduled)
    }

    /** Native search keeps a card that was answered on this day and on other days. */
    fun searchForDay(
        data: ReviewHeatmapData,
        date: LocalDate,
    ): String? {
        val offset = ChronoUnit.DAYS.between(data.today, date)
        return when {
            offset <= 0 && (data.reviews[date] ?: 0) > 0 -> searchForOffset(offset)
            offset in 1..FORECAST_DAYS && (data.scheduled[date] ?: 0) > 0 -> searchForOffset(offset)
            else -> null
        }
    }

    /** Refresh the search's day offset at tap time without scanning [source]'s history again. */
    fun searchForDay(
        source: StudySource,
        date: LocalDate,
    ): String? = searchForOffset(ChronoUnit.DAYS.between(source.today(), date))

    private fun searchForOffset(offset: Long): String? =
        when {
            offset <= 0 -> "prop:rated=$offset"
            offset <= FORECAST_DAYS -> "prop:due=$offset -is:new"
            else -> null
        }

    /** The scheduler cutoff is tomorrow's rollover, including before today's rollover. */
    fun studyDate(
        nextDayAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate =
        Instant
            .ofEpochSecond(nextDayAt)
            .atZone(zone)
            .toLocalDate()
            .minusDays(1)

    /**
     * What an AnkiQuest profile's heatmap says of each day: its reviews, whether it was
     * frozen, and the server's [verdicts]. [uploadedUntil] is the time of the last review the
     * server has, whose id is the millisecond it was made at.
     */
    data class ServerDays(
        val reviews: Map<LocalDate, Int>,
        val protectedDays: Set<LocalDate>,
        val uploadedUntil: Instant? = null,
        val verdicts: Map<LocalDate, DayVerdict> = emptyMap(),
    )

    fun serverDays(profile: JSONObject): ServerDays {
        val last = profile.optLong("last_review_id").takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val cells = profile.optJSONArray("heatmap") ?: return ServerDays(emptyMap(), emptySet(), last)
        val reviews = mutableMapOf<LocalDate, Int>()
        val protectedDays = mutableSetOf<LocalDate>()
        val verdicts = mutableMapOf<LocalDate, DayVerdict>()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(cell.optString("date")) }.getOrNull() ?: continue
            reviews[date] = cell.optInt("reviews")
            if (cell.optBoolean("frozen")) protectedDays += date
            DayVerdict.parse(cell)?.let { verdicts[date] = it }
        }
        return ServerDays(reviews, protectedDays, last, verdicts)
    }

    /**
     * The same history with AnkiQuest's numbering of the days it holds.
     *
     * The revlog records when a review happened but not under which clock, so the
     * collection can only number its days by the clock the device has now: a flight
     * through time zones moves the reviews studied on the plane onto a neighbouring
     * day and leaves an empty one. AnkiQuest records each clock as it is reported and
     * numbers every review by the one in force when it was made. Its counts are used
     * from the first day it has reviews for up to the day before the last review it was
     * sent, and never for today: those days may hold reviews not uploaded yet, so they
     * stay the collection's, as do days the server does not cover.
     */
    fun withServerDays(
        data: ReviewHeatmapData,
        server: ServerDays,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReviewHeatmapData {
        val first =
            server.reviews
                .filterValues { it > 0 }
                .keys
                .minOrNull()
        val uploaded = server.uploadedUntil?.atZone(zone)?.toLocalDate() ?: data.today
        val until = minOf(data.today, uploaded)
        val reviews =
            if (first == null || first >= until) {
                data.reviews
            } else {
                data.reviews.filterKeys { it < first || it >= until } +
                    server.reviews.filter { (date, count) -> date >= first && date < until && count > 0 }
            }
        return summarize(data.today, reviews, data.scheduled, server.protectedDays, server.verdicts)
    }

    fun summarize(
        today: LocalDate,
        reviews: Map<LocalDate, Int>,
        scheduled: Map<LocalDate, Int>,
        protectedDays: Set<LocalDate> = emptySet(),
        verdicts: Map<LocalDate, DayVerdict> = emptyMap(),
    ): ReviewHeatmapData {
        val history = reviews.filter { (date, count) -> date <= today && count > 0 }.toSortedMap()
        val forecastEnd = today.plusDays(FORECAST_DAYS)
        val forecast = scheduled.filter { (date, count) -> date > today && date <= forecastEnd && count > 0 }.toSortedMap()
        val judged = verdicts.filterKeys { it <= today }
        // A frozen day keeps the streak whether or not it had reviews: short of the minimum, they
        // did not count on their own. Today is still open, so nothing has kept it yet.
        val kept =
            (protectedDays + judged.filterValues { it.state.keepsStreak }.keys)
                .filter { it < today }
                .toSortedSet()
        // Where the server judged a day, only its verdict says whether the day counted.
        val counted =
            history.keys.filter { it !in kept && (judged[it] ?: return@filter true).state == DayState.MET }.toSet() +
                judged.filterValues { it.state == DayState.MET }.keys
        var currentStreak = 0
        var day = if (today in counted) today else today.minusDays(1)
        while (day in counted || day in kept) {
            if (day in counted) currentStreak++
            day = day.minusDays(1)
        }
        var longestStreak = 0
        var run = 0
        var previousDay: LocalDate? = null
        for (studiedDay in (counted + kept).sorted()) {
            val continues = previousDay?.plusDays(1) == studiedDay
            if (studiedDay in kept) {
                if (!continues) run = 0
            } else {
                run = if (continues) run + 1 else 1
            }
            longestStreak = maxOf(longestStreak, run)
            previousDay = studiedDay
        }
        val firstReviewDate = history.keys.firstOrNull()
        val totalReviews = history.values.sumOf { it.toLong() }
        return ReviewHeatmapData(
            today = today,
            reviews = history,
            scheduled = forecast,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            totalReviews = totalReviews,
            daysStudied = history.size,
            firstReviewDate = firstReviewDate,
            dailyAverage = if (history.isNotEmpty()) totalReviews.toDouble() / history.size else 0.0,
            protectedDays = kept,
            verdicts = judged,
        )
    }
}
