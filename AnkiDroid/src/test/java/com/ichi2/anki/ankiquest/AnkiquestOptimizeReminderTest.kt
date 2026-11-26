// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.deck_config.DeckConfig
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Event
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Fsrs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Inputs
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Scheduler
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Since
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.State
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Verdict
import com.ichi2.anki.ankiquest.AnkiquestOptimizeReminder.Wording
import com.ichi2.anki.pages.DeckOptions
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks [AnkiquestOptimizeReminder] against `tests/fixtures/optimize_reminder.json` from
 * AnkiQuest-Server, which the desktop add-on also follows.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestOptimizeReminderTest : RobolectricTest() {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/optimize_reminder.json")) { "missing optimize reminder" }.readText(),
        )

    private fun cases(section: String): List<JSONObject> {
        val array = fixture.getJSONArray(section)
        assertTrue(array.length() > 0, section)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.day(key: String): Int? = if (isNull(key)) null else getInt(key)

    private fun state(json: JSONObject): State = AnkiquestOptimizeReminder.decode(json.toString())

    @Test
    fun `the limits are the shared ones`() {
        val limits = fixture.getJSONObject("limits")
        assertEquals(
            listOf(limits.getInt("days"), limits.getInt("reviews"), limits.getInt("snooze_days")),
            listOf(AnkiquestOptimizeReminder.DAYS, AnkiquestOptimizeReminder.REVIEWS, AnkiquestOptimizeReminder.SNOOZE_DAYS),
        )
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
            val found =
                AnkiquestOptimizeReminder.reviewsSince(
                    col,
                    case.getInt("today"),
                    case.getLong("day_cutoff"),
                    case.day("last_optimized_day"),
                )
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
            val inputs = Inputs(case.getInt("today"), case.day("last_optimized_day"), case.getInt("reviews_since"), presets, deck = 1)
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
                    case.getInt("today"),
                    case.day("last_optimized_day"),
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
        assertEquals(null, AnkiquestOptimizeReminder.load(col).lastOptimizedDay)
        col.config.set("lastFsrsOptimize", 0)
        assertEquals(null, AnkiquestOptimizeReminder.load(col).lastOptimizedDay, "0 is never")
        col.config.set("lastFsrsOptimize", 5)
        assertEquals(5, AnkiquestOptimizeReminder.load(col).lastOptimizedDay)

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
    fun `the state is kept on this device`() {
        val prefs = targetContext.getSharedPreferences("optimize-reminder-test", 0)
        assertEquals(State.On, AnkiquestOptimizeReminder.state(prefs))
        for (state in listOf(State.Off, State.Snoozed(437), State.Dismissed(null), State.Dismissed(400), State.On)) {
            AnkiquestOptimizeReminder.remember(state, prefs)
            assertEquals(state, AnkiquestOptimizeReminder.state(prefs))
        }
        assertEquals(State.On, AnkiquestOptimizeReminder.decode("not json"))
        assertEquals(State.On, AnkiquestOptimizeReminder.decode("""{"kind": "snoozed"}"""))
    }

    @Test
    fun `the dialog opens deck options, snoozes and dismisses`() {
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        col.config.set("lastFsrsOptimize", 5)
        val today = col.sched.today
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

        dialog = show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        advanceRobolectricLooperUntil { AnkiquestOptimizeReminder.state() != State.On }
        assertEquals(State.Snoozed(today + AnkiquestOptimizeReminder.SNOOZE_DAYS), AnkiquestOptimizeReminder.state())

        dialog = show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advanceRobolectricLooperUntil { AnkiquestOptimizeReminder.state() is State.Dismissed }
        assertEquals(State.Dismissed(5), AnkiquestOptimizeReminder.state())
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
