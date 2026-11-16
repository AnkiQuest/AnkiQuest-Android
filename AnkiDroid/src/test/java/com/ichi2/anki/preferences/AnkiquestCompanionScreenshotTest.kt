// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.testutils.launchForFullHeightScreenshot
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * The study companion screen of a signed-in player with the server's unlocks example: Bluma
 * unlocked and Ankilope locked, or chosen before milestones.
 */
class AnkiquestCompanionScreenshotTest : ScreenshotTest() {
    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://ankiquest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        mockkObject(AnkiquestHomeData, AnkiquestCapabilities)
        coEvery { AnkiquestCapabilities.current(any()) } returns setOf(AnkiquestUnlocks.FEATURE, "companion_milestones")
        coEvery { AnkiquestHomeData.unlocks(any()) } answers {
            JSONObject(checkNotNull(javaClass.classLoader?.getResource("ankiquest-api/unlocks.json")).readText())
        }
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestCapabilities)
    }

    /** Ankilope chosen before milestones: it stays a choice. */
    @Test
    fun ankilope() = capture("ankilope", Choice.ANKILOPE)

    /** Bluma chosen, Ankilope locked with its condition and progress. */
    @Test
    fun bluma() = capture("bluma", Choice.COW)

    private fun capture(
        name: String,
        chosen: Choice,
    ) {
        coEvery { AnkiquestHomeData.companion(any()) } returns chosen.id
        val account = assertNotNull(AnkiquestHomeData.account())
        AnkiquestCompanion.remember(account, chosen.id)
        runBlocking { AnkiquestUnlocks.sync(account) }
        launch(name)
    }

    private fun launch(name: String) {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestCompanionFragment::class)
        launchForFullHeightScreenshot<PreferencesActivity>(
            intent,
            listView = { activity ->
                val preferences = activity.fragment as PreferencesFragment
                (preferences.childFragmentManager.findFragmentById(R.id.settings_container) as AnkiquestCompanionFragment).listView
            },
            setup = { advanceRobolectricLooper() },
        ) { captureScreen(name) }
    }
}
