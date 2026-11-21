// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestDeckNameDialogTest : RobolectricTest() {
    private val identity = HomeAccount("https://quest.example.test", "hill", "synthetic-token")
    private var account = identity
    private var name = "Animals"
    private var saves = 0
    private val callbacks = mutableListOf<String?>()

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestDeckNames, HomeAccount.Companion)
        every { HomeAccount.current(any()) } answers { account }
        coEvery { AnkiquestDeckNames.find(100) } answers { deck() }
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestDeckNames, HomeAccount.Companion)
    }

    private fun deck() =
        AnkiquestDeckNames.Deck(name, listOf("Animals", "Spanish", "")) {
            name = it
            saves++
        }

    private fun host() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            EmptyAnkiActivity::class.java,
            Intent(targetContext, EmptyAnkiActivity::class.java),
        )

    private fun open(host: EmptyAnkiActivity): AlertDialog {
        AnkiquestDeckNameDialog.show(host, identity, 100) { callbacks.add(it) }
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun field(dialog: AlertDialog) =
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<MaterialAutoCompleteTextView>()
            .single()

    @Test
    fun `a missing deck explains why the name cannot be edited`() {
        coEvery { AnkiquestDeckNames.find(100) } returns null
        AnkiquestDeckNameDialog.show(host().get(), identity, 100) { callbacks.add(it) }
        advanceRobolectricLooper()
        assertEquals("The deck name is unavailable on this device.", ShadowToast.getTextOfLatestToast())
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(0, saves)
    }

    @Test
    fun `a missing deck lookup cannot notify a replacement account`() {
        val answer = CompletableDeferred<AnkiquestDeckNames.Deck?>()
        var operation: Job? = null
        coEvery { AnkiquestDeckNames.find(100) } coAnswers {
            operation = currentCoroutineContext()[Job]
            answer.await()
        }
        AnkiquestDeckNameDialog.show(host().get(), identity, 100) { callbacks.add(it) }
        advanceRobolectricLooperUntil { operation != null }
        account = identity.copy(user = "other")
        answer.complete(null)
        advanceRobolectricLooperUntil { assertNotNull(operation).isCompleted }
        assertEquals(null, ShadowToast.getTextOfLatestToast())
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `the picker offers meaningful parts and saves an edited name once`() {
        val dialog = open(host().get())
        val field = field(dialog)
        assertEquals(listOf("Animals", "Spanish"), (0 until field.adapter.count).map { field.adapter.getItem(it) })
        field.setText("My Spanish words", false)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.performClick()
        save.performClick()
        advanceRobolectricLooperUntil { callbacks.isNotEmpty() }
        assertEquals(1, saves)
        assertEquals(listOf<String?>("My Spanish words"), callbacks)
    }

    @Test
    fun `the picker preserves supplementary characters at the shared name limit`() {
        name = "語".repeat(119) + "🐼"
        val original = name
        val dialog = open(host().get())
        assertEquals(original, field(dialog).text.toString())
        field(dialog).append("x")
        assertEquals(original, field(dialog).text.toString())
        field(dialog).setText(original + "x", false)
        assertEquals(original, field(dialog).text.toString(), "pasting keeps the whole last character")
        field(dialog).text.replace(119, 121, "🐱x")
        assertEquals("語".repeat(119) + "🐱", field(dialog).text.toString(), "a selection frees one character of capacity")
        field(dialog).text.replace(119, 121, "🐼")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooperUntil { callbacks.isNotEmpty() }
        assertEquals(listOf<String?>(original), callbacks)
    }

    @Test
    fun `the omit choice removes the deck from sharing`() {
        val dialog = open(host().get())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        advanceRobolectricLooperUntil { callbacks.isNotEmpty() }
        assertEquals("", name)
        assertEquals(listOf<String?>(null), callbacks)
    }

    @Test
    fun `a retained Save cannot change the replacement account`() {
        val dialog = open(host().get())
        field(dialog).setText("must not save", false)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        account = identity.copy(user = "other")
        save.performClick()
        advanceRobolectricLooper()
        assertEquals(0, saves)
        assertEquals(emptyList(), callbacks)
    }

    @Test
    fun `a stopped picker cannot be revived with its retained Save`() {
        val controller = host()
        val dialog = open(controller.get())
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        controller.pause().stop()
        controller.start().resume()
        save.performClick()
        advanceRobolectricLooper()
        assertFalse(dialog.isShowing)
        assertEquals(0, saves)
    }

    @Test
    fun `a late deck lookup does not open a dialog after stopping`() {
        val answer = CompletableDeferred<AnkiquestDeckNames.Deck?>()
        var operation: Job? = null
        coEvery { AnkiquestDeckNames.find(100) } coAnswers {
            operation = currentCoroutineContext()[Job]
            answer.await()
        }
        val controller = host()
        AnkiquestDeckNameDialog.show(controller.get(), identity, 100) { callbacks.add(it) }
        advanceRobolectricLooperUntil { operation != null }
        controller.pause().stop()
        answer.complete(deck())
        advanceRobolectricLooperUntil { assertNotNull(operation).isCompleted }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(0, saves)
    }
}
