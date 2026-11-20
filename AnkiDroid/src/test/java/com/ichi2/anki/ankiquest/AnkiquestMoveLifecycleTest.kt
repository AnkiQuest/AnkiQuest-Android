// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.IntroductionActivity
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMoveImport.State
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMoveLifecycleTest : RobolectricTest() {
    private val prefs get() = AnkiDroidApp.sharedPrefs()
    private val provider = PendingProvider()

    @Before
    fun prepareMove() {
        ReflectionHelpers.setStaticField(AnkiquestMoveImport::class.java, "state", null)
        mockkObject(AnkiquestMove, AnkiquestMove.Role.Companion, AnkiquestPoll)
        every { AnkiquestMove.Role.of(any()) } returns AnkiquestMove.Role.NEW
        every { AnkiquestMove.oldApp(any()) } returns AnkiquestMove.OldApp.READY
        every { AnkiquestPoll.refreshNow(any()) } returns Unit
        provider.payload =
            AnkiquestMovePayload.encode(
                mapOf(Ankiquest.PREF_TOKEN to "synthetic-moved-token"),
                AnkiquestMovePayload.Exclusions.of(targetContext),
            )
        provider.attachInfo(targetContext, ProviderInfo().apply { authority = AnkiquestMove.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AnkiquestMove.AUTHORITY, provider)
    }

    @After
    fun cleanMove() {
        provider.release.countDown()
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        unmockkObject(AnkiquestMove, AnkiquestMove.Role.Companion, AnkiquestPoll)
        ReflectionHelpers.setStaticField(AnkiquestMoveImport::class.java, "state", null)
    }

    @Test
    fun `closing setup before the old app replies does not import settings`() {
        val controller = host()
        accept()
        controller.pause().stop().destroy()
        provider.release.countDown()
        awaitRead()

        assertNull(prefs.getString(Ankiquest.PREF_TOKEN, null), "A reply to a closed setup screen must not write credentials")
    }

    @Test
    fun `recreated setup offers an interrupted move again`() {
        val controller = host()
        accept()
        controller.recreate()
        provider.release.countDown()
        awaitRead()

        assertTrue(
            ShadowDialog.getShownDialogs().any { it.isShowing },
            "The recreated setup must present the interrupted move without another recreation",
        )
    }

    @Test
    fun `an open setup imports and explains the next step`() {
        host()
        provider.release.countDown()
        accept()
        awaitRead()

        assertEquals("synthetic-moved-token", prefs.getString(Ankiquest.PREF_TOKEN, null))
        assertIs<State.Done>(state())
        assertTrue(ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    private fun host(): ActivityController<IntroductionActivity> =
        Robolectric.buildActivity(IntroductionActivity::class.java).also {
            saveControllerForCleanup(it)
            it.setup()
        }

    private fun accept() {
        assertIs<AlertDialog>(ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooperUntil { provider.entered.count == 0L }
    }

    private fun state(): State? = ReflectionHelpers.getStaticField(AnkiquestMoveImport::class.java, "state")

    private fun awaitRead() {
        advanceRobolectricLooperUntil { state() != State.Importing }
    }

    private class PendingProvider : ContentProvider() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var payload: String

        override fun onCreate() = true

        override fun call(
            method: String,
            arg: String?,
            extras: Bundle?,
        ): Bundle {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "The test must release the old app's reply" }
            return Bundle().apply { putString(AnkiquestMove.KEY_PAYLOAD, payload) }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
