// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.core.view.isVisible
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The copy form keeps its deck, recipients and explicit action reachable at 200% text. */
class AnkiquestCopyDialogScreenshotTest : ScreenshotTest() {
    private enum class TextSize { NORMAL, LARGE }

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepare() {
        val account = HomeAccount("https://quest.example.test", "sam", "visual-test-token")
        val deck = "Languages::Spanish vocabulary"
        val id = col.decks.id(deck)
        mockkObject(AnkiquestHomeData, AnkiquestDeckCopies)
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(id, deck, 3, 0, 0)), id)
        coEvery { AnkiquestHomeData.profile(any()) } throws IOException("offline")
        coEvery { AnkiquestHomeData.companion(any()) } throws IOException("offline")
        coEvery { AnkiquestDeckCopies.inbox(any()) } returns
            JSONObject().put(
                "friends",
                JSONArray()
                    .put(JSONObject().put("user", "cerro").put("display", "Cerro"))
                    .put(JSONObject().put("user", "ana").put("display", "Ana")),
            )
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestDeckCopies)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun form() = capture(TextSize.NORMAL)

    @Test
    fun formLarge() = capture(TextSize.LARGE)

    private fun capture(size: TextSize) {
        RuntimeEnvironment.setFontScale(if (size == TextSize.LARGE) 2f else 1f)
        val home = startRegularActivity<AnkiquestHomeActivity>(AnkiquestHomeActivity.intent(targetContext))
        advanceRobolectricLooperUntil {
            !home.findViewById<View>(R.id.aq_home_loading).isVisible &&
                home.window.decorView.allViews
                    .filterIsInstance<TextView>()
                    .any { it.text == home.getString(R.string.aq_copies_share) }
        }
        home.window.decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text == home.getString(R.string.aq_copies_share) }
            .performClick()
        home.supportFragmentManager.executePendingTransactions()
        advanceRobolectricLooper()
        val form = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        val send = form.getButton(AlertDialog.BUTTON_POSITIVE)
        assertFalse(send.isEnabled)
        val people = form.listView
        assertTrue(people.performItemClick(people.getChildAt(0), 0, people.getItemIdAtPosition(0)))
        advanceRobolectricLooper()
        assertTrue(send.isEnabled)
        assertTrue(send.getGlobalVisibleRect(Rect()), "Send must remain visible")
        assertTrue(people.getChildAt(0).getGlobalVisibleRect(Rect()), "Recipients must remain reachable")
        captureScreen("copy_form_${size.name.lowercase()}")
    }
}
