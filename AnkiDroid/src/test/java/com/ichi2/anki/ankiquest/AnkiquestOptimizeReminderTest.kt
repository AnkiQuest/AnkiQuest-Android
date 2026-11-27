// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.deck_config.DeckConfig
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Event
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Fsrs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Inputs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Scheduler
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Since
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.State
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Verdict
import com.ichi2.anki.ankiquest.AnkiquestOptimizeRule.Wording
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.pages.DeckOptions
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks [AnkiquestOptimizeReminder] against `tests/fixtures/optimize_reminder.json` from
 * AnkiQuest-Server, which the desktop add-on also follows, and its state against the
 * `optimize_reminder` row of `tests/fixtures/synced_settings.json`. The rule's cases run through
 * [AnkiquestSharedRules], so they test the shared rules library itself.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestOptimizeReminderTest : RobolectricTest() {
    private val dayMs = 86_400_000L

    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/optimize_reminder.json")) { "missing optimize reminder" }.readText(),
        )

    private val settings =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/synced_settings.json")) { "missing synced settings" }.readText(),
        )

    private fun cases(section: String): List<JSONObject> =
        fixture.getJSONArray(section).objects().also { assertTrue(it.isNotEmpty(), section) }

    private fun JSONObject.day(key: String): Int? = if (isNull(key)) null else getInt(key)

    private fun JSONObject.ms(key: String): Long? = if (isNull(key)) null else getLong(key)

    private fun state(json: JSONObject): State = AnkiquestOptimizeReminder.decode(json.toString())

    @Test
    fun `every optimization time`() {
        for (case in cases("optimized_at")) {
            val found =
                AnkiquestOptimizeReminder.optimizedAt(
                    case.getInt("today"),
                    case.getLong("day_cutoff"),
                    case.day("last_optimized_day"),
                )
            assertEquals(case.ms("expected"), found, case.getString("name"))
        }
    }

    @Test
    fun `every preset scheduler`() {
        for (case in cases("schedulers")) {
            val config =
                DeckConfig.Config
                    .newBuilder()
                    .setRwkvReviewEnabled(case.getBoolean("rwkvReviewEnabled"))
                    .setRwkvReviewInstantOrderEnabled(case.getBoolean("rwkvReviewInstantOrderEnabled"))
                    .build()
            val fsrs = if (case.getBoolean("fsrs")) Fsrs.ON else Fsrs.OFF
            assertEquals(
                case.getString("expected"),
                AnkiquestOptimizeReminder.scheduler(fsrs, config).name.lowercase(),
                case.getString("name"),
            )
        }
    }

    @Test
    fun `every review count runs the real query`() {
        for (case in cases("reviews")) {
            // Only this case's rows in the review log.
            col.db.execute("delete from revlog")
            val reviews = case.getJSONArray("reviews")
            for (j in 0 until reviews.length()) {
                val review = reviews.getJSONObject(j)
                col.db.execute(
                    "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, -1, ?, 1, 1, 2500, 1000, ?)",
                    review.getLong("id"),
                    review.getInt("ease"),
                    review.getInt("type"),
                )
            }
            val found = AnkiquestOptimizeReminder.reviewsSince(col, case.ms("last_optimized_ms"))
            assertEquals(case.getInt("expected"), found, case.getString("name"))
        }
    }

    @Test
    fun `every verdict`() {
        for (case in cases("cases")) {
            val presets =
                case.getJSONArray("presets").let { list ->
                    (0 until list.length()).map { Scheduler.valueOf(list.getString(it).uppercase()) }
                }
            val inputs = Inputs(case.getLong("now_ms"), case.ms("last_optimized_ms"), case.getInt("reviews_since"), presets)
            val found =
                when (val verdict = AnkiquestOptimizeReminder.verdict(inputs, state(case.getJSONObject("state")))) {
                    is Verdict.Silent -> JSONObject().put("verdict", verdict.quiet.name.lowercase())
                    is Verdict.Remind ->
                        JSONObject()
                            .put("verdict", "remind")
                            .put("since", verdict.since.name.lowercase())
                            .put("scheduler", verdict.wording.name.lowercase())
                }
            assertEquals(case.getJSONObject("expected").toMap(), found.toMap(), case.getString("name"))
        }
    }

    @Test
    fun `every transition`() {
        for (case in cases("transitions")) {
            val found =
                AnkiquestOptimizeReminder.next(
                    state(case.getJSONObject("state")),
                    Event.valueOf(case.getString("event").uppercase()),
                    case.getLong("now_ms"),
                    case.ms("last_optimized_ms"),
                )
            assertEquals(case.getJSONObject("expected").toMap(), AnkiquestOptimizeReminder.encode(found).toMap(), case.getString("name"))
            assertEquals(found, state(AnkiquestOptimizeReminder.encode(found)), "${case.getString("name")} stored")
        }
    }

    @Test
    fun `the wording is the shared English`() {
        val wording = fixture.getJSONObject("wording")
        val strings =
            mapOf(
                "title" to R.string.aq_optimize_title,
                "stale" to R.string.aq_optimize_stale,
                "never" to R.string.aq_optimize_never,
                "fsrs" to R.string.aq_optimize_fsrs,
                "rwkv" to R.string.aq_optimize_rwkv,
                "mixed" to R.string.aq_optimize_mixed,
                "where" to R.string.aq_optimize_where,
                "open" to R.string.aq_optimize_open,
                "snooze" to R.string.aq_optimize_snooze,
                "dismiss" to R.string.aq_optimize_dismiss,
                "setting" to R.string.aq_optimize_setting,
                "setting_summary" to R.string.aq_optimize_setting_summary,
            )
        assertEquals(wording.keys().asSequence().toList(), strings.keys.toList())
        for ((key, string) in strings) {
            assertEquals(wording.getString(key), targetContext.getString(string), key)
        }

        // The message: the since line, the scheduler line, then where the button is.
        assertEquals(
            listOf("stale", "mixed", "where").joinToString(" ") { wording.getString(it) },
            AnkiquestOptimizeReminder.message(
                targetContext,
                Since.STALE,
                Wording.MIXED,
            ),
        )
    }

    @Test
    fun `the collection gives the last optimization and the preset schedulers`() {
        assertEquals(null, AnkiquestOptimizeReminder.load(col).lastOptimizedMs)
        col.config.set("lastFsrsOptimize", 0)
        assertEquals(null, AnkiquestOptimizeReminder.load(col).lastOptimizedMs, "0 is never")

        // The end of that collection day, by the collection's day cutoff.
        col.config.set("lastFsrsOptimize", 5)
        assertEquals((col.sched.dayCutoff - (col.sched.today - 5) * 86_400L) * 1_000, AnkiquestOptimizeReminder.load(col).lastOptimizedMs)
        assertEquals(42L, AnkiquestOptimizeReminder.load(col, nowMs = 42).nowMs)

        // FSRS off: every preset is SM-2, whatever its RWKV keys say.
        val config = col.decks.configDictForDeckId(col.decks.selected())
        config.jsonObject.put("rwkvReviewEnabled", true)
        col.decks.save(config)
        col.config.set("fsrs", false)
        assertEquals(listOf(Scheduler.SM2), AnkiquestOptimizeReminder.load(col).presets.distinct())
        col.config.set("fsrs", true)
        assertTrue(Scheduler.RWKV_CURVE in AnkiquestOptimizeReminder.load(col).presets)
    }

    @Test
    fun `the state is kept in its preference`() {
        val prefs = targetContext.getSharedPreferences("optimize-reminder-test", 0)
        assertEquals(State.On, AnkiquestOptimizeReminder.state(prefs))
        val states =
            listOf(
                State.Off,
                State.Snoozed(1_793_788_140_000),
                State.Dismissed(null),
                State.Dismissed(1_786_334_400_000),
                State.On,
            )
        for (state in states) {
            AnkiquestOptimizeReminder.remember(state, prefs)
            assertEquals(state, AnkiquestOptimizeReminder.state(prefs))
        }
        val broken =
            listOf(
                "not json",
                """{"kind": "snoozed"}""",
                """{"kind": "dismissed"}""",
                """{"kind": "snoozed", "until_ms": "x"}""",
                """{"kind": "off", "until_ms": 3}""",
                """{"kind": "dismissed", "optimized_ms": 0}""",
                """{"kind": "snoozed", "until_day": 437}""",
                """{"kind": "dismissed", "optimized_day": 400}""",
            )
        for (stored in broken) {
            assertEquals(State.On, AnkiquestOptimizeReminder.decode(stored), stored)
        }
    }

    @Test
    fun `the state is the synced setting`() {
        val row = settings.getJSONArray("keys").objects().single { it.getString("key") == "optimize_reminder" }
        val setting = AnkiquestSettingsSync.Setting.OPTIMIZE_REMINDER
        assertEquals(row.getString("android"), AnkiquestOptimizeReminder.PREF_STATE)
        assertEquals(setting.pref, AnkiquestOptimizeReminder.PREF_STATE)
        assertEquals(State.On, setting.decode(row.get("default")))

        // Every state the cases name is one the setting stores, and reads back the same.
        val states = cases("cases").map { it.getJSONObject("state") } + cases("transitions").map { it.getJSONObject("expected") }
        for (stored in states) {
            val state = setting.decode(stored)
            assertTrue(setting.usable(state), stored.toString())
            assertEquals(stored.toMap(), (setting.encode(state) as JSONObject).toMap())
        }
    }

    @Test
    fun `only what the server stores is usable`() {
        val setting = AnkiquestSettingsSync.Setting.OPTIMIZE_REMINDER
        val merges = settings.getJSONArray("server_merge").objects().filter { it.getJSONObject("incoming").has("optimize_reminder") }
        assertTrue(merges.isNotEmpty())
        for (case in merges) {
            val value = case.getJSONObject("incoming").getJSONObject("optimize_reminder").get("value")
            assertEquals(!case.optBoolean("refused"), setting.usable(setting.decode(value)), case.getString("name"))
        }
        for (raw in listOf("""{"kind": "snoozed", "until_ms": true}""", """{"kind": "snoozed", "until_ms": 9223372036854775808}""")) {
            assertEquals(null, AnkiquestOptimizeReminder.parse(JSONObject(raw)), raw)
        }
        val latest = JSONObject().put("kind", "snoozed").put("until_ms", Long.MAX_VALUE)
        assertEquals(State.Snoozed(Long.MAX_VALUE), AnkiquestOptimizeReminder.parse(latest))
    }

    @Test
    fun `the API example's state is read as it was saved`() {
        val answer =
            JSONObject(
                checkNotNull(javaClass.classLoader?.getResource("ankiquest-api/settings_optimize_reminder.json")) { "missing example" }
                    .readText(),
            )
        val entry = AnkiquestSettingsSync.entries(answer).getValue("optimize_reminder")
        assertEquals(State.Snoozed(1_793_788_140_000), AnkiquestSettingsSync.Setting.OPTIMIZE_REMINDER.decode(entry.value))
    }

    @Test
    fun `a snooze is a change to sync`() {
        AnkiquestSettingsSync.listen(ApplicationProvider.getApplicationContext<Application>())
        val snoozed = State.Snoozed(1_793_788_140_000)
        val before = TimeManager.time.intTimeMS()
        AnkiquestOptimizeReminder.remember(snoozed)

        // The write itself stamps the change, once: noting it again finds nothing new.
        val changed = JSONObject(assertNotNull(AnkiDroidApp.sharedPrefs().getString(AnkiquestSettingsSync.PREF_CHANGED, null)))
        val record = changed.getJSONObject("optimize_reminder")
        assertEquals(AnkiquestOptimizeReminder.encode(snoozed).toMap(), record.getJSONObject("value").toMap())
        assertTrue(record.getLong("at") in before..TimeManager.time.intTimeMS(), record.toString())
        assertFalse(AnkiquestSettingsSync.note(AnkiquestSettingsSync.Setting.OPTIMIZE_REMINDER), "already noted")
    }

    @Test
    fun `the dialog opens deck options, snoozes and dismisses`() {
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        col.config.set("lastFsrsOptimize", 5)
        val optimized = (col.sched.dayCutoff - (col.sched.today - 5) * 86_400L) * 1_000
        val host =
            startActivityControllerNormallyOpenCollectionWithIntent(
                DeckPicker::class.java,
                Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true),
            ).get()
        advanceRobolectricLooper()

        fun show(): AlertDialog {
            AnkiquestOptimizeReminderDialog
                .create(1, Verdict.Remind(Since.STALE, Wording.RWKV))
                .showNow(host.supportFragmentManager, "optimize")
            return host.supportFragmentManager.fragments
                .filterIsInstance<AnkiquestOptimizeReminderDialog>()
                .single()
                .dialog as AlertDialog
        }

        // The message and the three buttons, in the fixture's order.
        val wording = fixture.getJSONObject("wording")
        var dialog = show()
        assertEquals(
            listOf("stale", "rwkv", "where").joinToString(" ") { wording.getString(it) },
            dialog.findViewById<TextView>(android.R.id.message)?.text.toString(),
        )
        assertEquals(
            listOf("open", "snooze", "dismiss").map { wording.getString(it) },
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE).map {
                dialog.getButton(it).text.toString()
            },
        )

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        assertEquals(DeckOptions.getIntent(targetContext, 1).component, shadowOf(host).nextStartedActivity.component)
        assertEquals(State.On, AnkiquestOptimizeReminder.state())

        // A snooze counts from the button press.
        dialog = show()
        val pressed = TimeManager.time.intTimeMS()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        advanceRobolectricLooperUntil { AnkiquestOptimizeReminder.state() != State.On }
        val snoozed = AnkiquestOptimizeReminder.state() as State.Snoozed
        val week = fixture.getJSONObject("limits").getInt("snooze_days") * dayMs
        assertTrue(snoozed.untilMs in pressed + week..TimeManager.time.intTimeMS() + week, snoozed.toString())

        dialog = show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advanceRobolectricLooperUntil { AnkiquestOptimizeReminder.state() is State.Dismissed }
        assertEquals(State.Dismissed(optimized), AnkiquestOptimizeReminder.state())
        BackupManagerTestUtilities.reset()
    }

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key ->
            when (val value = get(key)) {
                JSONObject.NULL -> null
                is JSONArray -> value.toString()
                else -> value
            }
        }
}
