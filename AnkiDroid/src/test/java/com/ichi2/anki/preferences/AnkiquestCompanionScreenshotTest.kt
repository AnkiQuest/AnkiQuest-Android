// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestUnlocks
import com.ichi2.testutils.launchForFullHeightScreenshot
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull

/** The study companion screen of a signed-in player, before and after unlocking Bluma. */
class AnkiquestCompanionScreenshotTest : ScreenshotTest() {
    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://ankiquest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        mockkObject(AnkiquestHomeData)
        coEvery { AnkiquestHomeData.companion(any()) } returns "ankilope"
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData)
    }

    @Test
    fun ankilope() = capture("ankilope")

    @Test
    fun bluma() {
        // Unlocked and chosen while offline: the server's older answer does not undo the cow it does not know yet.
        val account = assertNotNull(AnkiquestHomeData.account())
        AnkiquestUnlocks.unlock(account, AnkiquestUnlocks.Unlock.COW)
        AnkiquestCompanion.remember(account, AnkiquestCompanion.Choice.COW.id)
        capture("bluma")
    }

    private fun capture(name: String) {
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
