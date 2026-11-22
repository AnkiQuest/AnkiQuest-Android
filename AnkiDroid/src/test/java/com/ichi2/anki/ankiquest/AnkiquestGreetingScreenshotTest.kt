// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog

/** Aki's greeting over the deck list, signed out and signed in, including large text. */
class AnkiquestGreetingScreenshotTest : ScreenshotTest() {
    @Before
    fun prepare() {
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        AnkiquestGreeting.remember(AnkiquestGreeting.State.PENDING)
    }

    @After
    fun cleanup() {
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        BackupManagerTestUtilities.reset()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun signedOut() {
        open()
        captureScreen("greeting_signed_out")
    }

    @Test
    fun signedIn() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        open()
        captureScreen("greeting_signed_in")
    }

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        captureScreen("greeting_200percent")
    }

    private fun open() {
        val host =
            startActivityNormallyOpenCollectionWithIntent(
                DeckPicker::class.java,
                Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true),
            )
        advanceRobolectricLooperUntil {
            host.supportFragmentManager.fragments
                .filterIsInstance<AnkiquestGreetingDialog>()
                .any { it.dialog?.isShowing == true }
        }
    }
}
