// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.Card
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestDeckNamesTest : RobolectricTest() {
    private val at = 1_793_183_600_000L

    @Before
    fun configure() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        account("hill")
    }

    @After
    fun cleanup() {
        SharedPreferencesTestHook.override = null
    }

    private fun account(user: String) {
        AnkiDroidApp.sharedPrefs().edit {
            putString(AnkiquestPreferences.PREF_URL, "https://quest.example.test")
            putString(AnkiquestPreferences.PREF_USER, user)
            putString(AnkiquestPreferences.PREF_TOKEN, "synthetic-$user-token")
        }
    }

    @Test
    fun `the reported archived path offers its meaningful deck name`() =
        runTest {
            val card = addBasicNote().firstCard()
            card.did = col.decks.id("5 Archive::Mandarin — Modern Course::Mandarin modern 1")
            col.updateCard(card)
            col.db.execute(
                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, 0, 3, 25, 8, 2500, 7000, 1)",
                at,
                card.id,
            )
            assertEquals("Mandarin — Modern Course › Mandarin modern 1", AnkiquestShare.conqueredCardDeck(at))
        }

    private fun reviewed(path: String): Card {
        val card = addBasicNote().firstCard()
        card.did = col.decks.id(path)
        col.updateCard(card)
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, 0, 3, 25, 8, 2500, 7000, 1)",
            at,
            card.id,
        )
        return card
    }

    private fun conquests() = JSONArray().put(JSONObject().put("at", at))

    @Test
    fun `the first captured home deck survives later moves and snapshots`() =
        runTest {
            val card = reviewed("Spanish::Animals")
            val source = AnkiquestDeckNames.source(assertNotNull(HomeAccount.current()))
            AnkiquestDeckNames.capture(source, conquests())
            card.did = col.decks.id("5 Archive::Spanish::Old words")
            col.updateCard(card)
            AnkiquestDeckNames.capture(source, conquests())
            assertEquals("Animals", AnkiquestShare.conqueredCardDeck(at))
        }

    @Test
    fun `an unavailable card cannot discard other snapshots from the same upload`() =
        runTest {
            val card = reviewed("Spanish::Animals")
            col.db.execute(
                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, 0, 3, 25, 8, 2500, 7000, 1)",
                at + 1,
                card.id + 10_000,
            )
            val source = AnkiquestDeckNames.source(assertNotNull(HomeAccount.current()))
            AnkiquestDeckNames.capture(source, JSONArray().put(JSONObject().put("at", at + 1)).put(JSONObject().put("at", at)))
            card.did = col.decks.id("Spanish::New words")
            col.updateCard(card)
            assertEquals("Animals", AnkiquestShare.conqueredCardDeck(at))
        }

    @Test
    fun `filtered reviews use the original deck and remember an editable or omitted name`() =
        runTest {
            val card = reviewed("Spanish::Animals")
            card.oDid = card.did
            card.did = col.decks.id("Custom Study Session")
            col.updateCard(card)
            val deck = assertNotNull(AnkiquestDeckNames.find(at))
            assertEquals("Animals", deck.name)
            assertEquals(listOf("Animals", "Spanish", ""), deck.picks)
            deck.save("  My Spanish\nwords  ")
            assertEquals("My Spanish words", AnkiquestShare.conqueredCardDeck(at))
            deck.save("🐼".repeat(121))
            assertEquals("🐼".repeat(120), AnkiquestShare.conqueredCardDeck(at), "saved choices use the shared character limit")
            deck.save("")
            assertEquals("", AnkiquestDeckNames.find(at)?.name)
            assertNull(AnkiquestShare.conqueredCardDeck(at), "an omitted deck adds no empty caption")
        }

    @Test
    fun `choices and retained callbacks stay with their account`() =
        runTest {
            reviewed("Spanish::Animals")
            val old = assertNotNull(AnkiquestDeckNames.find(at))
            old.save("hill's deck")
            account("other")
            assertEquals("Animals", AnkiquestDeckNames.find(at)?.name)
            assertFailsWith<HomeAccountChanged> { old.save("must not save") }
            account("hill")
            assertEquals("hill's deck", AnkiquestDeckNames.find(at)?.name)
        }

    @Test
    fun `a stale collection or account upload cannot record the new collection's deck`() =
        runTest {
            val card = reviewed("Spanish::Animals")
            val source = AnkiquestDeckNames.source(assertNotNull(HomeAccount.current()))
            AnkiquestDeckNames.capture(source.copy(collection = "another collection"), conquests())
            account("other")
            AnkiquestDeckNames.capture(source, conquests())
            card.did = col.decks.id("Spanish::New words")
            col.updateCard(card)
            assertEquals("New words", AnkiquestDeckNames.find(at)?.name)
            account("hill")
            assertEquals("New words", AnkiquestDeckNames.find(at)?.name)
        }

    @Test
    fun `a recorded name survives deletion while an unknown review invents nothing`() =
        runTest {
            val card = reviewed("Spanish::Animals")
            AnkiquestDeckNames.capture(AnkiquestDeckNames.source(assertNotNull(HomeAccount.current())), conquests())
            col.removeCardsAndOrphanedNotes(listOf(card.id))
            assertEquals("Animals", AnkiquestDeckNames.find(at)?.name)
            assertNull(AnkiquestDeckNames.find(at + 1))
            assertTrue(AnkiquestDeckPaths.clean("word ".repeat(100)).length <= AnkiquestCardLabels.MAX_LABEL)
        }
}
