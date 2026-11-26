// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the community's own rules: reminder settings, the competition calendar and conquered cards. */
class AnkiquestCommunityTest {
    @Test
    fun reminder_settings_default_to_what_the_server_documents() {
        val defaults = AnkiquestCommunity.Reminders.parse(JSONObject("{}"))
        assertEquals(emptySet(), defaults.enabled)
        assertEquals(20, defaults.hour)
        assertEquals(22, defaults.quietStart)
        assertEquals(9, defaults.quietEnd)
        assertEquals(2, defaults.dailyLimit)
        assertTrue(defaults.quiet)

        // Out-of-range answers are held to the documented range rather than refused.
        val coerced =
            AnkiquestCommunity.Reminders.parse(
                JSONObject(
                    """{"reminder_hour":99,"quiet_start":30,"quiet_end":-9,"daily_limit":0,"gentle_daily":true}""",
                ),
            )
        assertEquals(23, coerced.hour)
        assertEquals(23, coerced.quietStart)
        assertEquals(0, coerced.quietEnd)
        assertEquals(1, coerced.dailyLimit)
        assertEquals(setOf(AnkiquestCommunity.Reminder.GENTLE_DAILY), coerced.enabled)
    }

    @Test
    fun quiet_hours_hold_only_between_their_bounds() {
        val off = AnkiquestCommunity.Reminders(enabled = emptySet(), hour = 20, quietStart = 9, quietEnd = 9, dailyLimit = 2)
        assertFalse(off.quiet)
        assertFalse(off.holds(23))

        val nightly = AnkiquestCommunity.Reminders(enabled = emptySet(), hour = 20, quietStart = 9, quietEnd = 17, dailyLimit = 2)
        assertTrue(nightly.holds(9))
        assertTrue(nightly.holds(16))
        assertFalse(nightly.holds(17))
        assertFalse(nightly.holds(8))

        // Through midnight: 22 to 9 holds both sides of the night.
        val late = AnkiquestCommunity.Reminders(enabled = emptySet(), hour = 20, quietStart = 22, quietEnd = 9, dailyLimit = 2)
        assertTrue(late.holds(23))
        assertTrue(late.holds(3))
        assertFalse(late.holds(12))
    }

    @Test
    fun reminder_settings_survive_the_round_trip_through_the_server() {
        val saved =
            AnkiquestCommunity.Reminders(
                enabled = setOf(AnkiquestCommunity.Reminder.URGENT_STREAK),
                hour = 21,
                quietStart = 20,
                quietEnd = 20,
                dailyLimit = 4,
            )
        assertEquals(saved, AnkiquestCommunity.Reminders.parse(saved.json()))
    }

    @Test
    fun the_calendar_names_players_and_their_periods() {
        val calendar =
            AnkiquestCommunity.parseCalendar(
                JSONObject(
                    """
                    {
                      "meta": {"year": 2026, "month": 11, "time_zone": "Europe/Lisbon", "rollover_hour": 0},
                      "players": [
                        {"user": "hill", "display": "hill"},
                        {"user": "other", "display": "  "}
                      ],
                      "calendar": [
                        {"key": "2026-11-27", "start": "2026-11-27", "end": "2026-11-28", "status": "ongoing",
                         "standings": [
                           {"user": "hill", "display": "hill", "xp": 12, "reviews": 30, "time_ms": 900000, "new_cards": 2, "days_active": 11},
                           {"user": "other", "display": "   ", "xp": 0}
                         ]},
                        {"key": "2026-11-26", "status": "final", "winners": ["hill", "other"], "winning_margin": 3,
                         "standings": [], "reconstructed": true}
                      ],
                      "weeks": [{"key": "2026-W48"}],
                      "seasons": []
                    }
                    """,
                ),
            )
        assertEquals(2026, calendar.year)
        assertEquals(24, calendar.graceHours)
        assertEquals("Europe/Lisbon", calendar.timeZone)
        assertEquals("hill", calendar.name("hill"))
        assertEquals("other", calendar.name("other"), "a blank display falls back to the username")

        val day = calendar.days[0]
        assertEquals(AnkiquestCommunity.Kind.DAY, day.kind)
        assertFalse(day.final)
        assertEquals("ongoing", day.status)
        assertEquals("day:2026-11-27", day.reference)
        assertEquals(listOf("hill", "other"), day.standings.map { it.user })
        assertEquals(12L, day.standings[0].xp)
        assertEquals("other", day.standings[1].display)

        val ended = calendar.days[1]
        assertTrue(ended.final)
        assertEquals(listOf("hill", "other"), ended.winners)
        assertEquals(3L, ended.margin)
        assertTrue(ended.reconstructed)
        assertEquals("2026-11-26", ended.start, "an empty start falls back to the key")

        val week = calendar.weeks[0]
        assertEquals(AnkiquestCommunity.Kind.WEEK, week.kind)
        assertEquals("week:2026-W48", week.reference)
        assertEquals("2026-W48", week.start)
        assertEquals(week, calendar.find("week:2026-W48"))
        assertNull(calendar.find("day:2000-01-01"))
        assertNull(calendar.find("year:2026"))
    }

    @Test
    fun a_conquered_card_is_its_owner_s_to_tell() {
        val base =
            JSONObject(
                """
                {"user": "hill", "display": "  ", "conquest": {"at": 1767139200000, "since": 1764547200000},
                 "label": "  Mandarin modern 1  "}
                """,
            )
        val plain = AnkiquestCommunity.parseConquest(base)
        assertEquals("hill", plain.display, "a blank display falls back to the username")
        assertEquals("Mandarin modern 1", plain.label)
        assertEquals(1767139200000, plain.since, "an answer without history starts at its conquest")
        assertFalse(plain.mine)
        assertFalse(plain.undoable(1767139200000))

        val told =
            AnkiquestCommunity.parseConquest(
                JSONObject(
                    """
                    {"user": "hill", "display": "hill", "conquest": {"at": 1767139200000, "since": 1764547200000},
                     "told": true, "friends": 2, "undo_until": 1767225600000}
                    """,
                ),
            )
        assertTrue(told.mine)
        assertEquals(2, told.friends)
        assertTrue(told.undoable(1767225599000))
        assertFalse(told.undoable(1767225600000), "past the undo deadline telling cannot be taken back")

        // How long it took, at least one day, as the website counts it.
        assertEquals(30L, told.days)
    }

    @Test
    fun the_day_kind_is_the_fallback() {
        assertEquals(AnkiquestCommunity.Kind.DAY, AnkiquestCommunity.Kind.of(null))
        assertEquals(AnkiquestCommunity.Kind.DAY, AnkiquestCommunity.Kind.of("year"))
    }
}
