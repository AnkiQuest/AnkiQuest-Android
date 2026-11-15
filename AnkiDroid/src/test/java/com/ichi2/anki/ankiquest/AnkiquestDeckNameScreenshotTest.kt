// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnkiquestDeckNameScreenshotTest : ScreenshotTest() {
    private val account = HomeAccount("https://quest.example.test", "hill", "screenshot-token")
    private val name = "Mandarin — Modern Course › Mandarin modern 1"

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestDeckNames, AnkiquestShare, AnkiquestCommunity)
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { AnkiquestDeckNames.find(100) } returns
            AnkiquestDeckNames.Deck(
                name,
                listOf("Mandarin — Modern Course", "Mandarin modern 1", ""),
            ) {}
        coEvery { AnkiquestShare.conqueredCardDeck(any()) } returns name
        coEvery { AnkiquestShare.conqueredCardLabels(any()) } returns null
        val conquest =
            AnkiquestCommunity
                .parseConquest(
                    JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/conquest.json")).readText()),
                ).copy(win = null)
        coEvery { AnkiquestCommunity.conquest(any(), any(), any()) } returns conquest
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestDeckNames, AnkiquestShare, AnkiquestCommunity)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun deckName() = capture("deck_name")

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        capture("deck_name_200percent")
    }

    @Test
    fun smallPhone() {
        RuntimeEnvironment.setQualifiers("+w320dp-h568dp")
        capture("deck_name_small_phone")
    }

    @Test
    fun conquestControl() = conquest("conquest_deck")

    @Test
    fun conquestControlLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        conquest("conquest_deck_200percent")
    }

    @Test
    fun momentControl() = moment("moment_deck")

    @Test
    fun momentControlLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        moment("moment_deck_200percent")
    }

    private fun conquest(screenshot: String) {
        val host = startRegularActivity<AnkiquestConquestActivity>(AnkiquestConquestActivity.intent(targetContext, "hill", 100))
        advanceRobolectricLooperUntil {
            host.findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<TextView>().any {
                it.text.toString() == host.getString(R.string.aq_share_deck_edit, name)
            }
        }
        captureScreen("${screenshot}_top")
    }

    private fun moment(screenshot: String) {
        val host =
            startActivityNormallyOpenCollectionWithIntent(
                EmptyAnkiActivity::class.java,
                Intent(targetContext, EmptyAnkiActivity::class.java),
            )
        val moment =
            AnkiquestMoments.Moment(
                "conquered:100",
                AnkiquestMoments.Kind.CONQUERED,
                "Card conquered",
                "A card I had forgotten 4 times",
                1_793_183_600_000,
                conquest = 100,
                lapses = 4,
                deck = name,
            )
        val card = AnkiquestMomentCard.show(host, account, listOf(moment), AnkiDroidApp.sharedPrefs()).dialog
        advanceRobolectricLooperUntil { card.findViewById<View>(R.id.aq_moment_deck)?.isShown == true }
        val deck = assertNotNull(card.findViewById<View>(R.id.aq_moment_deck))
        reveal(deck)
        captureScreen(screenshot)
        val label = deck as TextView
        for (line in 0 until label.lineCount) {
            assertEquals(0, label.layout.getEllipsisCount(line), "The selected deck remains readable at large font scale")
        }
        for (id in listOf(R.id.aq_moment_open, R.id.aq_moment_tell, R.id.aq_moment_share)) {
            val action = assertNotNull(card.findViewById<TextView>(id))
            reveal(action)
            val widestWord = action.text.split(' ').maxOf { action.paint.measureText(it) }
            assertTrue(action.width - action.compoundPaddingLeft - action.compoundPaddingRight >= widestWord, action.text.toString())
        }
        assertEquals(1, assertNotNull(card.findViewById<TextView>(R.id.aq_moment_share)).lineCount, "Share must not wrap letter by letter")
        captureScreen("${screenshot}_actions")
    }

    private fun reveal(view: View) {
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        advanceRobolectricLooper()
        visible(view)
    }

    private fun capture(name: String) {
        val host =
            startActivityNormallyOpenCollectionWithIntent(
                EmptyAnkiActivity::class.java,
                Intent(targetContext, EmptyAnkiActivity::class.java),
            )
        AnkiquestDeckNameDialog.show(host, account, 100) {}
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            visible(dialog.getButton(which))
        }
        captureScreen(name)
    }

    private fun visible(view: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertEquals(view.height, bounds.height(), "Every action must remain fully visible")
        assertTrue(bounds.left >= 0 && bounds.right <= view.resources.displayMetrics.widthPixels)
    }
}
