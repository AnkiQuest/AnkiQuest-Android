// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
import androidx.core.view.children
import androidx.preference.Preference
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.AnkiquestChibi
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestCompanions
import com.ichi2.anki.ankiquest.AnkiquestPreferences
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.anki.ankiquest.AnkiquestWidget
import com.ichi2.anki.ankiquest.HomeAccountChanged
import com.ichi2.anki.ankiquest.ui.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.ichi2.anki.R as AnkiDroidR

/** The companion screen with the server's unlocks example: Bluma unlocked, Ankilope locked at a level. */
@RunWith(AndroidJUnit4::class)
class AnkiquestCompanionFragmentTest : RobolectricTest() {
    /** `GET /api/unlocks/{user}` as recorded from the pinned server: `unlocks.json`. */
    private val unlocksExample =
        checkNotNull(javaClass.classLoader?.getResource("ankiquest-api/unlocks.json")) { "missing unlocks.json" }.readText()

    /** Ankilope's goal in the shared fixture `tests/fixtures/companion_unlock.json`. */
    private val ankilopeGoal: Int =
        JSONObject(checkNotNull(javaClass.classLoader?.getResource("ankiquest/companion_unlock.json")).readText())
            .getJSONArray("companions")
            .let { all -> (0 until all.length()).map { all.getJSONObject(it) } }
            .first { it.getString("companion") == Choice.ANKILOPE.id }
            .getInt("goal")

    /** Ankilope's name and the lines its locked card shows for the example's progress. */
    private val lockedLines: List<String>
        get() {
            val progress = JSONObject(unlocksExample).getJSONObject("progress").getJSONObject(Choice.ANKILOPE.id)
            assertEquals(ankilopeGoal, progress.getInt("goal"), "the example's goal is the fixture's")
            return listOf(
                "Ankilope",
                targetContext.getString(R.string.ankiquest_companion_level_goal, ankilopeGoal),
                targetContext.getString(R.string.ankiquest_companion_level_progress, progress.getInt("value"), ankilopeGoal),
            )
        }

    /** The locked Ankilope card's description: its name and both lines. */
    private val lockedAnkilope get() = lockedLines.joinToString(", ")

    private lateinit var controller: ActivityController<PreferencesActivity>

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(AnkiquestPreferences.URL, "https://quest.example")
            putString(AnkiquestPreferences.USER, "member")
            putString(AnkiquestPreferences.TOKEN, "test-token")
        }
        mockkObject(AnkiquestCompanions, AnkiquestWidget, AnkiquestCapabilities)
        every { AnkiquestWidget.requestUpdate(any()) } just runs
        coEvery { AnkiquestCapabilities.current(any()) } returns setOf(AnkiquestUnlocks.FEATURE, "companion_milestones")
        coEvery { AnkiquestCompanions.unlocks(any()) } answers { JSONObject(unlocksExample) }
        coEvery { AnkiquestCompanions.choice(any()) } returns "aki"
        coEvery { AnkiquestCompanions.choose(any(), any()) } answers { secondArg() }
    }

    @After
    fun clearMocks() {
        unmockkObject(AnkiquestCompanions, AnkiquestWidget, AnkiquestCapabilities)
    }

    @Test
    fun `each companion is a card and the chosen one is checked`() {
        val fragment = open()
        assertEquals(listOf("Aki", lockedAnkilope, "Bluma", "No companion"), fragment.cards().map { it.contentDescription })
        assertEquals("Aki", fragment.chosen())

        fragment.card("Bluma").performClick()
        idle()

        assertEquals(Choice.COW, AnkiquestCompanion.choice())
        assertEquals("Bluma", fragment.chosen())
        coVerify { AnkiquestCompanions.choose(any(), "cow") }
    }

    @Test
    fun `a locked companion says how to earn it and cannot be chosen`() {
        val fragment = open()
        val locked = fragment.card(lockedAnkilope)
        assertFalse(locked.isEnabled)
        assertFalse(locked.isClickable)
        val lines =
            (locked as LinearLayout)
                .children
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
                .toList()
        assertEquals(lockedLines, lines)

        locked.performClick()
        idle()

        assertEquals(Choice.AKI, AnkiquestCompanion.choice())
        coVerify(exactly = 0) { AnkiquestCompanions.choose(any(), any()) }
    }

    @Test
    fun `Ankilope chosen before milestones stays a choice`() {
        coEvery { AnkiquestCompanions.choice(any()) } returns "ankilope"
        val fragment = open()
        assertEquals(listOf("Aki", "Ankilope", "Bluma", "No companion"), fragment.cards().map { it.contentDescription })
        assertEquals("Ankilope", fragment.chosen())
    }

    @Test
    fun `tapping a companion without a live chibi shows no hint and reports no unlock`() {
        val fragment = open()

        // More taps than a chibi needs, on an unlocked companion: none has a live chibi yet.
        repeat(2 * AnkiquestChibi.TAPS) {
            fragment.card("Bluma").performClick()
            idle()
        }

        assertNull(ShadowToast.getLatestToast())
        coVerify(exactly = 0) { AnkiquestCompanions.unlock(any(), any()) }
    }

    @Test
    fun `a save refused for a changed account shows no error`() {
        coEvery { AnkiquestCompanions.choose(any(), any()) } answers {
            signIn("another-member")
            throw HomeAccountChanged()
        }
        val fragment = open()

        fragment.card("Bluma").performClick()
        idle()

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals("Aki", fragment.chosen())
        assertFalse(fragment.status().isVisible)
    }

    @Test
    fun `the previous account's error is gone when another account returns to the screen`() {
        coEvery { AnkiquestCompanions.choice(any()) } throws IOException("offline")
        val fragment = open()
        assertTrue(fragment.status().isVisible)

        coEvery { AnkiquestCompanions.choice(any()) } returns "aki"
        signIn("another-member")
        controller.pause().resume()
        idle()

        assertFalse(fragment.status().isVisible)
        coVerify { AnkiquestCompanions.choice(match { it.user == "another-member" }) }
    }

    private fun signIn(user: String) =
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(AnkiquestPreferences.USER, user)
            putString(AnkiquestPreferences.TOKEN, "token-$user")
        }

    private fun AnkiquestCompanionFragment.status() = requirePreference<Preference>(R.string.ankiquest_companion_status_key)

    private fun open(): AnkiquestCompanionFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestCompanionFragment::class)
        controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment =
            (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(
                AnkiDroidR.id.settings_container,
            )
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
        const val PHONE_WIDTH = 1080
    }
}
