// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdown
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdowns
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Days
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Pace
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.State
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.QueueType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Countdowns follow `tests/fixtures/countdowns.json` from AnkiQuest-Server, which the add-on tests against too. */
@RunWith(AndroidJUnit4::class)
class AnkiquestCountdownsTest : RobolectricTest() {
    private val fixture = JSONObject(resource("ankiquest/countdowns.json"))
    private val settings = JSONObject(resource("ankiquest/synced_settings.json"))
    private val wording = fixture.getJSONObject("wording")

    private fun resource(name: String): String = checkNotNull(javaClass.classLoader?.getResource(name)) { "missing $name" }.readText()

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
    }

    @After
    fun stop() {
        SharedPreferencesTestHook.override = null
    }

    private fun cases(name: String): List<JSONObject> = fixture.getJSONArray(name).objects()

    private fun events(array: JSONArray): List<Countdown> = checkNotNull(AnkiquestCountdowns.parse(array)).events

    private fun date(text: String): LocalDate = checkNotNull(AnkiquestCountdowns.date(text))

    @Test
    fun `days follow the shared cases`() {
        for (case in cases("days")) {
            val now = LocalDateTime.parse(case.getString("now"))
            val today = AnkiquestCountdowns.ankiToday(now, case.getInt("day_start_hour"))
            val found = AnkiquestCountdowns.days(today, date(case.getString("date")))
            val expected = case.getJSONObject("expected")
            assertEquals(expected.getString("state"), found.state.name.lowercase(), case.getString("name"))
            assertEquals(expected.getLong("days"), found.days, case.getString("name"))
        }
    }

    @Test
    fun `the nearest event follows the shared cases`() {
        for (case in cases("nearest")) {
            val found = AnkiquestCountdowns.nearest(events(case.getJSONArray("events")), date(case.getString("today")))
            assertEquals(case.optString("expected").takeIf { !case.isNull("expected") }, found?.name, case.getString("name"))
        }
    }

    @Test
    fun `the list order follows the shared cases`() {
        for (case in cases("order")) {
            val expected = case.getJSONArray("expected").let { array -> (0 until array.length()).map { array.getString(it) } }
            assertEquals(expected, AnkiquestCountdowns.ordered(events(case.getJSONArray("events"))).map { it.name }, case.getString("name"))
        }
    }

    @Test
    fun `searches follow the shared cases`() {
        for (case in cases("search")) {
            assertEquals(
                case.getString("expected"),
                AnkiquestCountdowns.search(case.getString("deck"), case.getLong("days")),
                case.getString("name"),
            )
        }
    }

    @Test
    fun `pace follows the shared cases`() {
        for (case in cases("pace")) {
            val remaining = if (case.isNull("remaining")) null else case.getLong("remaining")
            val expected = case.getJSONObject("expected")
            val want =
                when (expected.getString("state")) {
                    "per_day" -> Pace.PerDay(expected.getLong("cards"))
                    "done" -> Pace.Done
                    else -> Pace.None
                }
            assertEquals(want, AnkiquestCountdowns.pace(case.getLong("days"), remaining), case.getString("name"))
        }
    }

    @Test
    fun `the app accepts exactly the lists the server stores`() {
        for (case in settings.getJSONArray("server_merge").objects()) {
            val incoming = case.getJSONObject("incoming").optJSONObject("countdowns") ?: continue
            val accepted = AnkiquestCountdowns.parse(incoming.get("value")) != null
            assertEquals(!case.optBoolean("refused"), accepted, case.getString("name"))
        }
        val row = settings.getJSONArray("keys").objects().single { it.getString("key") == "countdowns" }
        assertEquals(row.getInt("max_events"), AnkiquestCountdowns.MAX_EVENTS)
        assertEquals(row.getInt("max_name_chars"), AnkiquestCountdowns.MAX_NAME_CHARS)
        assertEquals(row.getInt("max_deck_chars"), AnkiquestCountdowns.MAX_DECK_CHARS)
        assertEquals(row.getString("android"), AnkiquestCountdowns.PREF)
    }

    @Test
    fun `the API example's list is read as it was saved`() {
        val answer = JSONObject(resource("ankiquest-api/settings_countdowns.json"))
        val entry = AnkiquestSettingsSync.entries(answer).getValue("countdowns")
        val parsed = AnkiquestSettingsSync.Setting.COUNTDOWNS.decode(entry.value)
        assertEquals(
            Countdowns(
                listOf(
                    Countdown("Spanish exam", LocalDate.of(2026, 11, 9), "Spanish::A1"),
                    Countdown("Driving theory", LocalDate.of(2026, 12, 1), null),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun `a list survives the preference it is kept in`() {
        val list = Countdowns(listOf(Countdown("Spanish exam", LocalDate.of(2026, 11, 25), "Spanish::A1")))
        AnkiquestCountdowns.write(targetContext.sharedPrefs(), list)
        assertEquals(list, AnkiquestCountdowns.read(targetContext.sharedPrefs()))
    }

    @Test
    fun `imports follow the shared cases`() {
        for (case in cases("import")) {
            val found = AnkiquestCountdowns.imported(events(case.getJSONArray("account")), events(case.getJSONArray("device")))
            val expected = case.getJSONObject("expected")
            assertEquals(events(expected.getJSONArray("account")), found.account, case.getString("name"))
            assertEquals(events(expected.getJSONArray("left")), found.left, case.getString("name"))
        }
    }

    @Test
    fun `the import wording is the shared English`() {
        fun text(
            key: String,
            count: Int,
        ) = wording.getString(key).replace("{count}", count.toString()).replace("{user}", "hill")
        assertEquals(text("import_one", 1), AnkiquestCountdownTexts.importQuestion(targetContext, 1, "hill"))
        assertEquals(text("import_other", 3), AnkiquestCountdownTexts.importQuestion(targetContext, 3, "hill"))
        assertEquals(text("left_one", 1), AnkiquestCountdownTexts.left(targetContext, 1))
        assertEquals(text("left_other", 2), AnkiquestCountdownTexts.left(targetContext, 2))
        assertEquals(wording.getString("import_add"), targetContext.getString(R.string.ankiquest_countdowns_import_add))
        assertEquals(wording.getString("import_keep"), targetContext.getString(R.string.ankiquest_countdowns_import_keep))
    }

    @Test
    fun `the wording is the shared English`() {
        val spanish = Countdown("Spanish exam", LocalDate.of(2026, 12, 1), "Spanish::A1")

        fun line(
            state: State,
            days: Long,
        ) = AnkiquestCountdownTexts.headline(targetContext, spanish, Days(state, days))
        assertEquals(wording.getString("upcoming").replace("{name}", "Spanish exam").replace("{days}", "12"), line(State.UPCOMING, 12))
        assertEquals(wording.getString("tomorrow").replace("{name}", "Spanish exam"), line(State.TOMORROW, 1))
        assertEquals(wording.getString("today").replace("{name}", "Spanish exam"), line(State.TODAY, 0))
        assertEquals(wording.getString("past").replace("{name}", "Spanish exam"), line(State.PAST, -1))

        // The deck shows by its display name, as in the deck_names fixture.
        fun pace(key: String) = wording.getString(key).replace("{deck}", "Spanish › A1")
        assertEquals(pace("pace_one").replace("{cards}", "1"), AnkiquestCountdownTexts.pace(targetContext, Pace.PerDay(1), "Spanish::A1"))
        assertEquals(
            pace("pace_other").replace("{cards}", "25"),
            AnkiquestCountdownTexts.pace(targetContext, Pace.PerDay(25), "Spanish::A1"),
        )
        assertEquals(pace("done"), AnkiquestCountdownTexts.pace(targetContext, Pace.Done, "Spanish::A1"))
        assertNull(AnkiquestCountdownTexts.pace(targetContext, Pace.None, "Spanish::A1"))
        assertNull(AnkiquestCountdownTexts.pace(targetContext, Pace.PerDay(3), null))

        val labels =
            mapOf(
                "section" to R.string.ankiquest_countdowns,
                "empty" to R.string.ankiquest_countdowns_empty,
                "add" to R.string.ankiquest_countdown_add,
                "edit" to R.string.ankiquest_countdown_edit,
                "manage" to R.string.ankiquest_countdowns_manage,
                "name" to R.string.ankiquest_countdown_name,
                "date" to R.string.ankiquest_countdown_date,
                "deck" to R.string.ankiquest_countdown_deck,
                "no_deck" to R.string.ankiquest_countdown_no_deck,
                "save" to R.string.ankiquest_countdown_save,
                "delete" to R.string.ankiquest_countdown_delete,
                "name_missing" to R.string.ankiquest_countdown_name_missing,
                "full" to R.string.ankiquest_countdowns_full,
                "private" to R.string.ankiquest_countdowns_private,
            )
        for ((key, id) in labels) {
            assertEquals(wording.getString(key), targetContext.getString(id), key)
        }
    }

    @Test
    fun `the search counts new, learning and review cards due before the event`() {
        val deck = addDeck("Spanish::A1")
        val today = col.sched.today

        fun card(
            queue: QueueType,
            due: Int,
        ) = addBasicNote().firstCard().apply {
            did = deck
            this.queue = queue
            type = if (queue == QueueType.New) CardType.New else CardType.Rev
            if (queue != QueueType.New) {
                this.due = due
                ivl = 1
            }
            col.updateCards(listOf(this), skipUndoEntry = true)
        }
        card(QueueType.New, 0)
        card(QueueType.Rev, today - 3)
        card(QueueType.Rev, today + 11)
        card(QueueType.Rev, today + 12)
        card(QueueType.Suspended, today)
        addBasicNote()

        // New, overdue and due the day before count; due on the day, suspended and other decks do not.
        assertEquals(3, col.findCards(AnkiquestCountdowns.search("Spanish", 12)).size)
    }
}
