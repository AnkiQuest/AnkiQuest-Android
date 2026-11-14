// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.anki.ankiquest.AnkiquestWidget
import com.ichi2.anki.ankiquest.UnlockTapRule
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AnkiquestCompanionFragmentTest : RobolectricTest() {
    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        TimeManager.resetWith(MockTime(START_MS))
        mockkObject(AnkiquestHomeData, AnkiquestWidget)
        every { AnkiquestWidget.requestUpdate(any()) } just runs
        coEvery { AnkiquestHomeData.companion(any()) } returns "aki"
        coEvery { AnkiquestHomeData.saveCompanion(any(), any()) } answers { secondArg() }
    }

    @After
    fun clearMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestWidget)
    }

    @Test
    fun `each companion is a card and the chosen one is checked`() {
        val fragment = open()
        assertEquals(listOf("Aki", "Ankilope", "No companion"), fragment.cards().map { it.contentDescription })
        assertEquals("Aki", fragment.chosen())

        fragment.card("Ankilope").performClick()
        idle()

        assertEquals(Choice.ANKILOPE, AnkiquestCompanion.choice())
        assertEquals("Ankilope", fragment.chosen())
        coVerify { AnkiquestHomeData.saveCompanion(any(), "ankilope") }
    }

    @Test
    fun `tapping Ankilope gives no hint until the last tap`() {
        val fragment = open()
        repeat(UnlockTapRule.COW.taps - 1) {
            fragment.card("Ankilope").performClick()
            idle()
        }
        assertEquals(listOf("Aki", "Ankilope", "No companion"), fragment.cards().map { it.contentDescription })
    }

    @Test
    fun `nine taps on Ankilope bring Bluma and choose her`() {
        val fragment = open()
        repeat(UnlockTapRule.COW.taps) {
            fragment.card("Ankilope").performClick()
            idle()
        }

        assertEquals(listOf("Aki", "Ankilope", "Bluma", "No companion"), fragment.cards().map { it.contentDescription })
        assertEquals("Bluma", fragment.chosen())
        assertEquals(Choice.COW, AnkiquestCompanion.choice())

        // The server here has no unlocks, so the cow stays on this phone.
        val account = requireNotNull(AnkiquestHomeData.account())
        assertEquals(AnkiquestUnlocks.State.LOCAL, AnkiquestUnlocks.state(account, AnkiquestUnlocks.Unlock.COW))
        coVerify(exactly = 0) { AnkiquestHomeData.saveCompanion(any(), "cow") }
    }

    private fun open(): AnkiquestCompanionFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestCompanionFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment = (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(R.id.settings_container)
        return fragment as AnkiquestCompanionFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AnkiquestCompanionFragment.cards(): List<View> {
        // Lays the list out now, so the cards show the latest binding.
        val width = listView.width.takeIf { it > 0 } ?: PHONE_WIDTH
        listView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        listView.layout(0, 0, width, listView.measuredHeight)
        return requireView().findViewById<LinearLayout>(R.id.ankiquest_companion_cards).children.toList()
    }

    private fun AnkiquestCompanionFragment.card(name: String) = cards().first { it.contentDescription == name }

    /** The card drawn with the thick outline, read through its accessibility state. */
    private fun AnkiquestCompanionFragment.chosen(): CharSequence? =
        cards()
            .first { AccessibilityNodeInfoCompat.wrap(it.createAccessibilityNodeInfo()).checked == CHECKED_STATE_TRUE }
            .contentDescription

    private companion object {
        const val START_MS = 1_750_000_000_000
        const val PHONE_WIDTH = 1080
    }
}
