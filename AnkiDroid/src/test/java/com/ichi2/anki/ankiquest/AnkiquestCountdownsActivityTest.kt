// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdown
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdowns
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The editor must not overwrite another account or a list changed by background sync. */
@RunWith(AndroidJUnit4::class)
class AnkiquestCountdownsActivityTest : RobolectricTest() {
    private var controller: ActivityController<AnkiquestCountdownsActivity>? = null
    private val exam = Countdown("Exam", LocalDate.of(2027, 1, 10), null)
    private val other = Countdown("Driving", LocalDate.of(2027, 2, 10), null)

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        targetContext.sharedPrefs().edit(commit = true) {
            putBoolean(AnkiquestSettingsSync.PREF_SWITCH, false)
            putString(Ankiquest.PREF_URL, "https://ankiquest.net")
            putString(Ankiquest.PREF_USER, "first")
            putString(Ankiquest.PREF_TOKEN, "first-token")
        }
    }

    @After
    fun stop() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        SharedPreferencesTestHook.override = null
    }

    private fun open(): AnkiquestCountdownsActivity {
        val opened =
            startActivityControllerNormallyOpenCollectionWithIntent(
                AnkiquestCountdownsActivity::class.java,
                AnkiquestCountdownsActivity.intent(targetContext),
            )
        controller = opened
        return opened.get()
    }

    private fun write(vararg events: Countdown) = AnkiquestCountdowns.write(targetContext.sharedPrefs(), Countdowns(events.toList()))

    private fun read(): List<Countdown> = AnkiquestCountdowns.read(targetContext.sharedPrefs()).events

    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return assertIs<AlertDialog>(ShadowDialog.getLatestDialog())
    }

    private fun press(
        dialog: AlertDialog,
        button: Int,
    ) {
        dialog.getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun name(dialog: AlertDialog): EditText =
        dialog
            .findViewById<ViewGroup>(android.R.id.content)!!
            .descendants
            .filterIsInstance<EditText>()
            .single()

    private fun edit(activity: AnkiquestCountdownsActivity): AlertDialog {
        activity.findViewById<LinearLayout>(R.id.aq_countdowns_list).getChildAt(1).performClick()
        return dialog()
    }

    @Test
    fun `a saved edit cannot replace a newer synced list`() {
        write(exam)
        val dialog = edit(open())
        name(dialog).setText("Changed")
        write(exam, other)

        press(dialog, AlertDialog.BUTTON_POSITIVE)

        assertEquals(listOf(exam, other), read())
    }

    @Test
    fun `deleting from an old form keeps a newer list`() {
        write(exam)
        val dialog = edit(open())
        write(exam, other)

        press(dialog, AlertDialog.BUTTON_NEUTRAL)

        assertEquals(listOf(exam, other), read())
    }

    @Test
    fun `a form opened for another account cannot save`() {
        open().findViewById<android.view.View>(R.id.aq_countdowns_add).performClick()
        val dialog = dialog()
        name(dialog).setText("Private exam")
        targetContext.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_USER, "second")
            putString(Ankiquest.PREF_TOKEN, "second-token")
        }

        press(dialog, AlertDialog.BUTTON_POSITIVE)

        assertEquals(emptyList(), read())
    }

    @Test
    fun `a name accepts sixty Unicode characters`() {
        open().findViewById<android.view.View>(R.id.aq_countdowns_add).performClick()
        val name = name(dialog())
        val full = "\uD83C\uDF93".repeat(AnkiquestCountdowns.MAX_NAME_CHARS)

        name.setText(full + "x")

        assertEquals(full, name.text.toString())
    }

    @Test
    fun `a current form saves its changes`() {
        write(exam)
        val dialog = edit(open())
        name(dialog).setText("Changed")

        press(dialog, AlertDialog.BUTTON_POSITIVE)

        assertEquals(listOf(exam.copy(name = "Changed")), read())
    }

    @Test
    fun `a stale row cannot open another event at the same index`() {
        write(exam)
        val activity = open()
        shadowOf(Looper.getMainLooper()).idle()
        write(other)

        activity.findViewById<LinearLayout>(R.id.aq_countdowns_list).getChildAt(1).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(listOf(other), read())
    }

    @Test
    fun `a new event cannot overflow a list filled while editing`() {
        val activity = open()
        activity.findViewById<android.view.View>(R.id.aq_countdowns_add).performClick()
        val dialog = dialog()
        name(dialog).setText("Too late")
        val full = List(AnkiquestCountdowns.MAX_EVENTS) { exam.copy(name = "Exam $it") }
        write(*full.toTypedArray())

        press(dialog, AlertDialog.BUTTON_POSITIVE)

        assertEquals(full, read())
    }
}
