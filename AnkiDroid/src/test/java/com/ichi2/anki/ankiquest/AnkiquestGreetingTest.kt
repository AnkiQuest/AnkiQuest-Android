// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.IntroductionActivity
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestGreetingTest : RobolectricTest() {
    private val prefs get() = AnkiDroidApp.sharedPrefs()

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
    }

    @Test
    fun `tests never see the greeting unless they ask for it`() {
        prefs.edit { remove("ankiquestGreeting") }

        val host = open().get()

        assertEquals(AnkiquestGreeting.State.SHOWN, AnkiquestGreeting.state())
        assertNull(greeting(host))
    }

    @Test
    fun `shows once on the first open`() {
        val first = open()
        val dialog = awaitGreeting(first.get())
        assertEquals(AnkiquestGreeting.State.SHOWN, AnkiquestGreeting.state())
        val image = assertNotNull(dialog.findViewById<ImageView>(R.id.ankiquest_greeting_image))
        assertEquals(targetContext.getString(R.string.ankiquest_greeting_image), image.contentDescription)
        first.pause().stop().destroy()

        assertNull(greeting(open().get()))
    }

    @Test
    fun `never again after dismissal`() {
        val first = open()
        awaitGreeting(first.get()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advanceRobolectricLooper()
        assertNull(greeting(first.get()))
        first.pause().stop().destroy()

        assertNull(greeting(open().get()))
        assertEquals(AnkiquestGreeting.State.SHOWN, AnkiquestGreeting.state())
    }

    @Test
    fun `signed out players are offered the sign-in`() {
        val host = open().get()
        val dialog = awaitGreeting(host)

        val signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(targetContext.getString(R.string.ankiquest_sign_in_submit), signIn.text.toString())
        val later = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(targetContext.getString(R.string.ankiquest_greeting_later), later.text.toString())

        signIn.performClick()
        val started = assertNotNull(shadowOf(host).nextStartedActivity)
        assertEquals(PreferencesActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra("ankiquest.sign_in", false))
    }

    @Test
    fun `signed in players only get Let's study`() {
        signIn()

        val dialog = awaitGreeting(open().get())

        val study = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals(targetContext.getString(R.string.ankiquest_greeting_study), study.text.toString())
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility == View.VISIBLE)
    }

    @Test
    fun `Aki greets even when the companion is off`() {
        signIn()
        val account = assertNotNull(AnkiquestHomeData.account())
        assertTrue(AnkiquestCompanion.remember(account, "none"))

        val image = assertNotNull(awaitGreeting(open().get()).findViewById<ImageView>(R.id.ankiquest_greeting_image))

        assertEquals(View.VISIBLE, image.visibility)
        assertNotNull(image.drawable)
    }

    @Test
    fun `waits for the app introduction`() {
        setIntroductionSlidesShown(false)

        val host = open().get()

        assertEquals(IntroductionActivity::class.java.name, shadowOf(host).nextStartedActivity?.component?.className)
        assertNull(greeting(host))
        assertEquals(AnkiquestGreeting.State.PENDING, AnkiquestGreeting.state())
    }

    @Test
    fun `survives rotation without showing twice`() {
        val controller = open()
        awaitGreeting(controller.get())

        controller.recreate()
        advanceRobolectricLooper()

        val greetings =
            controller
                .get()
                .supportFragmentManager.fragments
                .filterIsInstance<AnkiquestGreetingDialog>()
        assertEquals(1, greetings.size)
        assertTrue(greetings.single().dialog?.isShowing == true)
    }

    private fun open(): ActivityController<DeckPicker> {
        val controller =
            startActivityControllerNormallyOpenCollectionWithIntent(
                DeckPicker::class.java,
                Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true),
            )
        advanceRobolectricLooper()
        return controller
    }

    private fun signIn() =
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "member-token")
        }

    private fun greeting(host: DeckPicker): AlertDialog? =
        host.supportFragmentManager.fragments
            .filterIsInstance<AnkiquestGreetingDialog>()
            .firstOrNull()
            ?.dialog as? AlertDialog

    private fun awaitGreeting(host: DeckPicker): AlertDialog {
        advanceRobolectricLooperUntil { greeting(host)?.isShowing == true }
        return assertNotNull(greeting(host))
    }
}
