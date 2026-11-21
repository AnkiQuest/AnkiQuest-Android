// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestAki.Message
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestAkiTest : RobolectricTest() {
    private fun profile(
        state: String,
        reviews: Int,
    ) = JSONObject()
        .put("streak_state", state)
        .put("day_ends_at", Long.MAX_VALUE)
        .put("today", JSONObject().put("reviews", reviews).put("streak_minutes", 10))

    @Test
    fun `no history or empty decks never earns a celebration`() {
        assertEquals(Message.WELCOME, AnkiquestAki.mood(null, null).message)
        assertEquals(Message.WELCOME, AnkiquestAki.mood(HomeLocal(emptyList(), 0), null).message)
        assertEquals(Message.WELCOME, AnkiquestAki.mood(HomeLocal(listOf(HomeDeck(1, "Empty", 0, 0, 0)), 1), null).message)
    }

    @Test
    fun `cards due locally keep the focus on studying after remote progress`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        assertEquals(Message.REVIEW, AnkiquestAki.mood(local, profile("studied", 20)).message)
    }

    @Test
    fun `finishing selected deck does not claim all decks are done`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0), HomeDeck(2, "Geography", 0, 0, 2)), 1)
        assertEquals(Message.REVIEW, AnkiquestAki.mood(local, profile("studied", 20)).message)
    }

    @Test
    fun `celebration needs a real study day and no remaining local cards`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1)
        assertEquals(Message.CELEBRATE, AnkiquestAki.mood(local, profile("studied", 20)).message)
        assertEquals(Message.WELCOME, AnkiquestAki.mood(local, profile("pending", 0)).message)
    }

    @Test
    fun `the companion celebrates unsent study that secures today`() {
        val dayStarts = 10_000L
        val profile =
            profile("pending", 0)
                .put("day_ends_at", dayStarts + 86_400_000)
                .put("today", JSONObject().put("starts_at", dayStarts).put("reviews", 0).put("streak_minutes", 10))
        val goal =
            AnkiquestDailyGoal.Status(
                day = 1,
                minutes = 10,
                streakMinutes = 10,
                studiedCards = 1,
                mode = AnkiquestDailyGoal.Mode.OFF,
                goal = AnkiquestDailyGoal.Goal(0),
                unsent = listOf(AnkiquestDay.Review(dayStarts + 1, 600_000)),
            )
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1, goal = goal)
        assertEquals(Message.CELEBRATE, AnkiquestAki.mood(local, profile, now = dayStarts + 2).message)
    }

    @Test
    fun `only an actually protected streak gets the freeze pose`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        assertEquals(Message.FREEZE, AnkiquestAki.mood(local, profile("protected", 0)).message)
        assertEquals(Message.REVIEW, AnkiquestAki.mood(local, profile("pending", 0).put("freezes", 3)).message)
    }

    @Test
    fun `expired progress never promises today's protection or completion`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1)
        assertEquals(Message.WELCOME, AnkiquestAki.mood(local, profile("protected", 0).put("day_ends_at", 1000), now = 1000).message)
        assertEquals(Message.WELCOME, AnkiquestAki.mood(local, profile("studied", 20).put("day_ends_at", 1000), now = 1001).message)
    }

    @Test
    fun `a streak with no study today shows the sad pose`() {
        val waiting = profile("pending", 0).put("streak", 12)
        val sad = AnkiquestMood.pose(AnkiquestMood.Mood.SAD, 12)
        val due = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        val done = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1)
        assertEquals(sad, AnkiquestAki.mood(due, waiting).image, "cards due")
        assertEquals(sad, AnkiquestAki.mood(done, waiting).image, "no cards due")
        assertEquals(sad, AnkiquestAki.mood(null, waiting).image, "decks not loaded")

        // The words say the streak is waiting, not that cards are due.
        val card = AnkiquestAki.mood(due, waiting)
        assertEquals(Message.WAITING, card.message)
        assertEquals("You haven’t studied today. Your 12-day streak is still waiting.", card.text(targetContext))
    }

    @Test
    fun `the waiting line reads as on the desktop add-on`() {
        // Wording and plural forms from the add-on's language.py (#356).
        val expected =
            mapOf(
                "de" to
                    listOf(
                        "Du hast heute noch nicht gelernt. Deine Lernserie von 1 Tag wartet noch.",
                        "Du hast heute noch nicht gelernt. Deine Lernserie von 12 Tagen wartet noch.",
                    ),
                "es" to
                    listOf(
                        "Hoy no has estudiado. Tu racha de 1 día sigue esperando.",
                        "Hoy no has estudiado. Tu racha de 12 días sigue esperando.",
                    ),
                "fr" to
                    listOf(
                        "Tu n’as pas encore étudié aujourd’hui. Ta série de 1 jour t’attend toujours.",
                        "Tu n’as pas encore étudié aujourd’hui. Ta série de 12 jours t’attend toujours.",
                    ),
                "pt" to
                    listOf(
                        "Ainda não estudaste hoje. A tua sequência de 1 dia continua à espera.",
                        "Ainda não estudaste hoje. A tua sequência de 12 dias continua à espera.",
                    ),
            )

        // Each language gets its own "one" and "other" line.
        val due = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        for ((language, lines) in expected) {
            val config = android.content.res.Configuration(targetContext.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            val context = targetContext.createConfigurationContext(config)
            for ((streak, line) in listOf(1, 12).zip(lines)) {
                val card = AnkiquestAki.mood(due, profile("pending", 0).put("streak", streak))
                assertEquals(line, card.text(context), "$language, $streak days")
            }
        }
    }

    @Test
    fun `studying keeps the deck messages`() {
        val due = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        val studied = AnkiquestAki.mood(due, profile("studied", 20).put("streak", 13))
        assertEquals(AnkiquestMood.Mood.FIRE, studied.mood)
        assertEquals(AnkiquestMood.pose(AnkiquestMood.Mood.FIRE, 13), studied.image)
        assertEquals(Message.REVIEW, studied.message)
    }

    @Test
    fun `cards due keep the pose but bring no greeting`() {
        // The next-step card already says to study (#349), as on the desktop add-on.
        val due = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        val card = AnkiquestAki.mood(due, profile("studied", 20).put("streak", 13))
        assertEquals(Message.REVIEW, card.message)
        assertNull(card.text(targetContext))

        // The Home card shows only the pose.
        val view = AnkiquestAki.encouragement(targetContext, card)
        assertEquals(1, view.childCount)
        assertIs<ImageView>(view.getChildAt(0))
    }

    @Test
    fun `the home companion follows every shared streak mood`() {
        // The Home card and the widget show the same mood for the same day (#309), using the
        // cases in streak_moods.json that the desktop add-on also tests.
        val fixtures =
            JSONObject(
                checkNotNull(javaClass.classLoader?.getResource("ankiquest/streak_moods.json")) { "missing streak moods" }.readText(),
            )
        val cases = fixtures.getJSONArray("moods").objects()
        var checked = 0
        for (case in cases) {
            val name = case.getString("name")
            val today = case.getJSONObject("today")
            val expected = case.getJSONObject("expected")
            val profile = homeProfile(today) ?: continue
            val card = AnkiquestAki.mood(null, profile)
            assertEquals(enumValueOf<AnkiquestMood.Mood>(expected.getString("mood").uppercase()), card.mood, name)
            assertEquals(expected.getString("pose"), targetContext.resources.getResourceEntryName(card.image), name)
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `the home card shows Ankilope's pose for every shared streak mood`() {
        // hill's report: Ankilope studied on a day a freeze kept; each mood has her own pose.
        chooseCompanion(AnkiquestCompanion.Choice.ANKILOPE)
        val fixtures =
            JSONObject(
                checkNotNull(javaClass.classLoader?.getResource("ankiquest/streak_moods.json")) { "missing streak moods" }.readText(),
            )
        var checked = 0
        for (case in fixtures.getJSONArray("moods").objects()) {
            val profile = homeProfile(case.getJSONObject("today")) ?: continue
            val card = AnkiquestAki.mood(null, profile)
            val expected = ankilopeMoodPoses.getValue(card.mood)
            assertEquals(expected, AnkiquestCompanion.resource(card.image), case.getString("name"))
            checked++
        }
        assertTrue(checked > 0)
    }

    /**
     * A server profile that the Home card judges as the fixture's [today], or null for an unknown
     * day: Home learns the goal and frozen days from the same profile, so it has neither then.
     */
    private fun homeProfile(today: JSONObject): JSONObject? {
        val state = today.getString("state")
        if (state == "unknown") {
            return null
        }

        // A short day is pending on the server, with reviews short of the minimum.
        val serverState = if (state == "short") "pending" else state
        val frozen = today.getInt("frozen_days")
        val heatmap = JSONArray()
        repeat(frozen) { heatmap.put(JSONObject().put("frozen", true)) }
        heatmap.put(JSONObject())
        return JSONObject()
            .put("streak_state", serverState)
            .put("streak", today.getInt("streak"))
            .put("day_ends_at", Long.MAX_VALUE)
            .put("today", JSONObject().put("reviews", today.getInt("reviews")).put("streak_minutes", 10))
            .put("heatmap", heatmap)
            .put("goal", JSONObject().put("reached", today.getBoolean("goal_reached")))
    }

    @Test
    fun `notification art is downsampled and decorative art is not an extra control`() {
        val bitmap = assertNotNull(AnkiquestAki.notificationIcon(targetContext))
        assertTrue(bitmap.width <= 192 && bitmap.height <= 192)
        assertEquals(
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            AnkiquestAki.image(targetContext, com.ichi2.anki.R.drawable.aki_review).importantForAccessibility,
        )
    }
}
