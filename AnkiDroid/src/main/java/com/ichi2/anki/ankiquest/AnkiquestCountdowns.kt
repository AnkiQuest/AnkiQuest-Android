// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Countdowns to the player's events and exams, kept in the synced setting `countdowns`.
 *
 * The rules (days from the Anki day, the nearest event, the list order, the Anki search for the
 * cards left, cards a day) are the cases in `tests/fixtures/countdowns.json` in AnkiQuest-Server,
 * which the desktop add-on tests against too. The limits are the key's entry in
 * `tests/fixtures/synced_settings.json`.
 */
internal object AnkiquestCountdowns {
    /** The preference holding the list as JSON; the synced setting's Android name. */
    const val PREF = "ankiquestCountdowns"

    /** Servers that keep the list; an older one refuses an unknown value over 1024 bytes. */
    const val FEATURE = "countdowns"

    const val MAX_EVENTS = 20
    const val MAX_NAME_CHARS = 60
    const val MAX_DECK_CHARS = 200

    private const val DATE_LEN = 10

    /** `YYYY-MM-DD`, strict, so `2026-02-30` is no date. */
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** Characters Anki's search treats as special inside a quoted deck name. */
    private const val SEARCH_SPECIAL = "\\\"*_"

    private val FIELDS = setOf("name", "date", "deck")

    /** One event: a name, a date and optionally the full name of a deck to finish by then. */
    data class Countdown(
        val name: String,
        val date: LocalDate,
        val deck: String?,
    )

    /** The whole list, as it is synced: one value, newest change wins. */
    data class Countdowns(
        val events: List<Countdown>,
    ) {
        fun toJson(): JSONArray =
            JSONArray().apply {
                for (event in events) {
                    put(
                        JSONObject()
                            .put("name", event.name)
                            .put("date", DATE.format(event.date))
                            .put("deck", event.deck ?: JSONObject.NULL),
                    )
                }
            }

        companion object {
            val EMPTY = Countdowns(emptyList())
        }
    }

    enum class State { UPCOMING, TOMORROW, TODAY, PAST }

    data class Days(
        val state: State,
        val days: Long,
    )

    /** Cards a day to finish a linked deck by the event. */
    sealed interface Pace {
        data class PerDay(
            val cards: Long,
        ) : Pace

        /** Nothing left in the deck before the event. */
        data object Done : Pace

        /** No deck, a deck the collection lacks, or no study day left. */
        data object None : Pace
    }

    /** The date of a `YYYY-MM-DD` string, or null. */
    fun date(text: String?): LocalDate? {
        if (text == null || text.length != DATE_LEN) {
            return null
        }
        return try {
            LocalDate.parse(text, DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun format(date: LocalDate): String = DATE.format(date)

    /** The list in [raw] (a JSON array, or its text), or null when the server would not store it. */
    fun parse(raw: Any?): Countdowns? {
        val array =
            when (raw) {
                is JSONArray -> raw
                is String ->
                    try {
                        JSONArray(raw)
                    } catch (_: JSONException) {
                        return null
                    }
                else -> return null
            }
        if (array.length() > MAX_EVENTS) {
            return null
        }
        val events = (0 until array.length()).map { event(array.opt(it)) ?: return null }
        return Countdowns(events)
    }

    private fun event(raw: Any?): Countdown? {
        val found = raw as? JSONObject ?: return null
        if (!FIELDS.containsAll(found.keys().asSequence().toList())) {
            return null
        }
        val name = found.opt("name") as? String ?: return null
        if (name.isBlank() || name.codePointCount(0, name.length) > MAX_NAME_CHARS) {
            return null
        }
        val value = found.opt("deck")
        val deck =
            when {
                value == null || value === JSONObject.NULL -> null
                value is String -> value.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_DECK_CHARS } ?: return null
                else -> return null
            }
        val date = date(found.opt("date") as? String) ?: return null
        return Countdown(name, date, deck)
    }

    /** The stored list; an unusable one counts as empty. */
    fun read(prefs: SharedPreferences): Countdowns =
        try {
            parse(prefs.getString(PREF, null)) ?: Countdowns.EMPTY
        } catch (_: ClassCastException) {
            Countdowns.EMPTY
        }

    /** Saves the list; settings sync sees the change and sends it. */
    fun write(
        prefs: SharedPreferences,
        countdowns: Countdowns,
    ) = prefs.edit { putString(PREF, countdowns.toJson().toString()) }

    /** The Anki day of local time [now]: its date once the day start hour is taken off. */
    fun ankiToday(
        now: LocalDateTime,
        dayStartHour: Int,
    ): LocalDate = now.minusHours(dayStartHour.toLong()).toLocalDate()

    fun days(
        today: LocalDate,
        date: LocalDate,
    ): Days {
        val left = date.toEpochDay() - today.toEpochDay()
        val state =
            when {
                left < 0 -> State.PAST
                left == 0L -> State.TODAY
                left == 1L -> State.TOMORROW
                else -> State.UPCOMING
            }
        return Days(state, left)
    }

    /** The event with the fewest days that is not past; on a tie the first in the list. */
    fun nearest(
        events: List<Countdown>,
        today: LocalDate,
    ): Countdown? =
        events
            .filter { days(today, it.date).state != State.PAST }
            .minByOrNull { it.date }

    /** The list in settings: by date, on a tie in stored order. */
    fun ordered(events: List<Countdown>): List<Countdown> = events.sortedBy { it.date }

    /** The Anki search for the cards [deck] still has before an event [days] away. */
    fun search(
        deck: String,
        days: Long,
    ): String {
        val escaped =
            buildString {
                for (c in deck) {
                    if (c in SEARCH_SPECIAL) {
                        append('\\')
                    }
                    append(c)
                }
            }
        return "deck:\"$escaped\" -is:suspended (is:new OR is:learn OR prop:due<$days)"
    }

    /** Cards a day up to the day before the event; [remaining] is null without a usable deck. */
    fun pace(
        days: Long,
        remaining: Long?,
    ): Pace {
        if (remaining == null || days < 1) {
            return Pace.None
        }
        if (remaining == 0L) {
            return Pace.Done
        }
        return Pace.PerDay((remaining + days - 1) / days)
    }
}
