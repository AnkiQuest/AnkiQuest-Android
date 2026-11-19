// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.ui.R
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestDeckAlertsTest : RobolectricTest() {
    private val identity = HomeAccount("https://quest.example.test", "hill", "synthetic-token")
    private var account: HomeAccount? = identity
    private val repository = mockk<SubscriptionRepository>()
    private val enabled = IncomingSubscriptions(true, setOf("other"), listOf(IncomingSender("cerro", "Cerro")))

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestSubscriptions, HomeAccount.Companion)
        every { HomeAccount.current(any()) } answers { account }
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(identity) } returns enabled
        coEvery { repository.setSubscribed(identity, "cerro", false) } returns enabled.copy(unsubscribedSenders = setOf("other", "cerro"))
        coEvery { repository.setSubscribed(identity, "cerro", true) } returns enabled
    }

    @After
    fun cleanup() = unmockkObject(AnkiquestHomeData, AnkiquestSubscriptions, HomeAccount.Companion)

    @Test
    fun `stop changes just that sender and offers Undo after success`() {
        val screen = launch().get()
        val action = AnkiquestDeckAlerts(screen)
        val answer = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.setSubscribed(identity, "cerro", false) } coAnswers { answer.await() }
        action.stop(identity, "cerro")
        idle()
        assertTrue(action.busy())
        assertNull(undo(screen))
        answer.complete(enabled.copy(unsubscribedSenders = setOf("other", "cerro")))
        idle()
        assertFalse(action.busy())
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", false) }
        assertNotNull(undo(screen)).performClick()
        idle()
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", true) }
    }

    @Test
    fun `a failed stop stays retryable and has no Undo`() {
        val screen = launch().get()
        val action = AnkiquestDeckAlerts(screen)
        coEvery { repository.setSubscribed(identity, "cerro", false) } throws IOException("offline")
        action.stop(identity, "cerro")
        idle()
        assertFalse(action.busy())
        assertNull(undo(screen))
        assertEquals(screen.getString(R.string.aq_deck_alerts_failed), ShadowToast.getTextOfLatestToast())
        coEvery { repository.setSubscribed(identity, "cerro", false) } returns enabled.copy(unsubscribedSenders = setOf("other", "cerro"))
        action.stop(identity, "cerro")
        idle()
        assertNotNull(undo(screen))
    }

    @Test
    fun `an already stopped sender cannot gain a resubscribe Undo`() {
        val screen = launch().get()
        val action = AnkiquestDeckAlerts(screen)
        for (settings in listOf(enabled.copy(unsubscribedSenders = setOf("other", "cerro")), enabled.copy(senders = emptyList()))) {
            coEvery { repository.load(identity) } returns settings
            action.stop(identity, "cerro")
            idle()
            assertNull(undo(screen))
        }
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), any()) }
    }

    @Test
    fun `an account or credential change while loading cannot write or offer Undo`() {
        val screen = launch().get()
        val action = AnkiquestDeckAlerts(screen)
        for (changed in listOf(identity.copy(user = "other"), identity.copy(token = "new-token"))) {
            account = identity
            val result = CompletableDeferred<IncomingSubscriptions>()
            coEvery { repository.load(identity) } coAnswers { result.await() }
            action.stop(identity, "cerro")
            idle()
            account = changed
            result.complete(enabled)
            idle()
            assertNull(undo(screen))
        }
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), any()) }
    }

    @Test
    fun `pausing cancels a pending read and returning makes the action usable again`() {
        val controller = launch()
        val action = AnkiquestDeckAlerts(controller.get())
        val result = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.load(identity) } coAnswers { result.await() }
        var refreshed = 0
        action.stop(identity, "cerro") { refreshed++ }
        idle()
        controller.pause().stop()
        result.complete(enabled)
        idle()
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), any()) }
        assertEquals(0, refreshed)
        controller.start().resume()
        assertEquals(1, refreshed)
        coEvery { repository.load(identity) } returns enabled
        action.stop(identity, "cerro")
        idle()
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", false) }
        assertNotNull(undo(controller.get()))
    }

    @Test
    fun `Undo cannot act after the account credentials changed`() {
        val screen = launch().get()
        AnkiquestDeckAlerts(screen).stop(identity, "cerro")
        idle()
        val inverse = assertNotNull(undo(screen))
        account = identity.copy(token = "new-token")
        inverse.performClick()
        idle()
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), true) }
    }

    @Test
    fun `an applied stop survives pause`() {
        val controller = launch()
        val screen = controller.get()
        val action = AnkiquestDeckAlerts(screen)
        val answer = CompletableDeferred<IncomingSubscriptions>()
        var settled = false
        var refreshed = 0
        coEvery { repository.setSubscribed(identity, "cerro", false) } coAnswers {
            answer.await().also { settled = true }
        }
        action.stop(identity, "cerro") { refreshed++ }
        idle()
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", false) }
        controller.pause().stop()
        answer.complete(enabled.copy(unsubscribedSenders = setOf("other", "cerro")))
        idle()
        assertTrue(settled, "A stop already sent to the server must finish")
        assertEquals(0, refreshed)
        assertNull(undo(screen))

        controller.start().resume()
        idle()
        assertEquals(1, refreshed)
        assertNotNull(undo(screen)).performClick()
        idle()
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", true) }
    }

    @Test
    fun `missing sender is not stopped`() {
        val screen = launch().get()
        coEvery { repository.load(identity) } returns enabled.copy(senders = emptyList())
        AnkiquestDeckAlerts(screen).stop(identity, "cerro")
        idle()
        assertEquals("cerro no longer shares deck alerts.", ShadowToast.getTextOfLatestToast())
        assertNull(undo(screen))
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), any()) }
    }

    @Test
    fun `a paused write cannot follow a changed account`() {
        val controller = launch()
        val screen = controller.get()
        val action = AnkiquestDeckAlerts(screen)
        val answer = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.setSubscribed(identity, "cerro", false) } coAnswers { answer.await() }
        var refreshed = 0
        action.stop(identity, "cerro") { refreshed++ }
        idle()
        controller.pause().stop()
        account = identity.copy(token = "replacement-token")
        answer.complete(enabled.copy(unsubscribedSenders = setOf("other", "cerro")))
        idle()
        controller.start().resume()
        idle()
        assertFalse(action.busy())
        assertNull(undo(screen))
        assertEquals(0, refreshed)
        coVerify(exactly = 0) { repository.setSubscribed(any(), any(), true) }
    }

    @Test
    fun `a failed paused write reports on return`() {
        val controller = launch()
        val screen = controller.get()
        val action = AnkiquestDeckAlerts(screen)
        val answer = CompletableDeferred<IncomingSubscriptions>()
        coEvery { repository.setSubscribed(identity, "cerro", false) } coAnswers { answer.await() }
        var refreshed = 0
        action.stop(identity, "cerro") { refreshed++ }
        idle()
        controller.pause().stop()
        answer.completeExceptionally(IOException("offline"))
        idle()
        assertEquals(0, refreshed)
        assertNull(undo(screen))
        controller.start().resume()
        idle()
        assertEquals(1, refreshed)
        assertFalse(action.busy())
        assertNull(undo(screen))
        assertEquals(screen.getString(R.string.aq_deck_alerts_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(action.canStop(identity, "cerro"))
    }

    @Test
    fun `notification names the sender`() {
        val action =
            assertNotNull(
                AnkiquestDeckAlerts.action(targetContext, 31, 41, identity.notificationAccount, identity.scope, "cerro", "completion"),
            )
        assertEquals("Stop deck alerts from cerro", action.title.toString())
    }

    @Test
    fun `a notification request is bound to its account and consumed across recreation`() {
        val controller = launch()
        val screen = controller.get()
        screen.intent = AnkiquestDeckAlerts.intent(targetContext, identity, "cerro", 31)
        val action = AnkiquestDeckAlerts(screen)
        idle()
        controller.pause().resume()
        val saved = Bundle()
        action.save(saved)
        AnkiquestDeckAlerts(screen, saved)
        idle()
        coVerify(exactly = 1) { repository.setSubscribed(identity, "cerro", false) }

        account = identity.copy(user = "another")
        AnkiquestDeckAlerts(screen)
        idle()
        coVerify(exactly = 1) { repository.load(any()) }
        assertEquals(screen.getString(R.string.aq_home_account_route_changed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `only a known other sender completion gets an account-specific notification action`() {
        val first =
            assertNotNull(
                AnkiquestDeckAlerts.action(targetContext, 31, 41, identity.notificationAccount, identity.scope, "cerro", "completion"),
            )
        val original = shadowOf(first.actionIntent).savedIntent
        assertEquals(AnkiquestProfileActivity::class.java.name, original.component?.className)
        assertEquals("cerro", original.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
        for ((sender, kind) in listOf("" to "completion", "hill" to "completion", "cerro" to "reply", "cerro" to "share")) {
            assertNull(AnkiquestDeckAlerts.action(targetContext, 31, 41, identity.notificationAccount, identity.scope, sender, kind))
        }
        account = identity.copy(server = "https://elsewhere.example.test")
        assertNull(AnkiquestDeckAlerts.action(targetContext, 31, 41, identity.notificationAccount, identity.scope, "cerro", "completion"))
        val second =
            assertNotNull(
                AnkiquestDeckAlerts.action(targetContext, 31, 41, account!!.notificationAccount, account!!.scope, "cerro", "completion"),
            )
        assertFalse(
            first.actionIntent == second.actionIntent,
            "the same notification number on another account must not replace this action",
        )
        assertEquals(original.data, shadowOf(first.actionIntent).savedIntent.data)
    }

    private fun launch() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            EmptyAnkiActivity::class.java,
            Intent(targetContext, EmptyAnkiActivity::class.java),
        ).also { it.get().setContentView(FrameLayout(it.get())) }

    private fun undo(screen: EmptyAnkiActivity): View? = screen.findViewById(com.google.android.material.R.id.snackbar_action)

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
}
