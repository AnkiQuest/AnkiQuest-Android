// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.Exit
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.State
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.Step
import com.ichi2.anki.ankiquest.AnkiquestSharing.Audience
import com.ichi2.anki.ankiquest.AnkiquestSharing.MomentRef
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyAnkiActivity
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The share sheet: picking friends and groups, the note, sending with Undo, and the ways it can fail. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnkiquestShareSheetTest : RobolectricTest() {
    private var account = HomeAccount("https://quest.example.test", "aldanita", "synthetic-token")
    private val moment = MomentRef.Win(17)
    private val card = AnkiquestShare.Card("Level 54", "I reached level 54.")
    private val members = listOf(AnkiquestSocial.Person("aldanita", "Aldana"), AnkiquestSocial.Person("ana", "Ana"))
    private var overview =
        AnkiquestSocial.Overview(
            friends = listOf(AnkiquestSocial.Person("ana", "Ana"), AnkiquestSocial.Person("cerro", "Cerro")),
            incoming = emptyList(),
            outgoing = emptyList(),
            muted = emptyList(),
            groups = listOf(AnkiquestSocial.Group(3, "Spanish club", owner = false, invite = null, members = members)),
            public = true,
        )
    private val exits = mutableListOf<Exit>()
    private var elsewhere = 0
    private var exported: AnkiquestShare.Card? = null

    @Before
    fun prepare() {
        TimeManager.resetWith(MockTime(NOW))
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestSharing, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { AnkiquestSocial.overview(account) } answers { overview }
        every { AnkiquestShare.render(any(), any()) } returns createBitmap(1, 1)
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestSharing, AnkiquestShare)
        TimeManager.reset()
    }

    @Test
    fun `All friends comes first, then friends and groups, and search narrows them`() {
        val (_, dialog) = open()
        assertEquals(listOf("All friends", "Ana", "Cerro", "Spanish club"), chips(dialog).map { it.text.toString() })
        assertEquals("Group: Spanish club", chips(dialog).last().contentDescription)

        dialog.findViewById<EditText>(R.id.aq_share_search)!!.setText("SPA")
        assertEquals(listOf("All friends", "Spanish club"), chips(dialog).map { it.text.toString() })

        dialog.findViewById<EditText>(R.id.aq_share_search)!!.setText("nobody")
        assertTrue(texts(dialog).contains(targetContext.getString(R.string.ankiquest_share_no_match)))
    }

    @Test
    fun `Send waits for a choice, and All friends and chosen people replace each other`() {
        val (sheet, dialog) = open()
        val send = dialog.findViewById<View>(R.id.aq_share_send)!!
        assertFalse(send.isEnabled)

        chip(dialog, "Ana").performClick()
        chip(dialog, "Spanish club").performClick()
        assertTrue(send.isEnabled)
        assertEquals(listOf("Ana", "Spanish club"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })

        chip(dialog, "All friends").performClick()
        assertEquals(listOf("All friends"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })
        assertEquals(AnkiquestMomentShare.Choice.Everyone, assertIs<State.Picking>(sheet.state).choice)

        chip(dialog, "All friends").performClick()
        assertFalse(send.isEnabled)
    }

    @Test
    fun `Send looks disabled until someone is chosen`() {
        val (_, dialog) = open()
        val send = dialog.findViewById<MaterialButton>(R.id.aq_share_send)!!
        val waiting = looks(send)
        assertFalse(send.isEnabled)

        chip(dialog, "Ana").performClick()
        assertTrue(send.isEnabled)
        val ready = looks(send)
        assertNotEquals(waiting.first, ready.first, "a disabled Send must not share the enabled background")
        assertNotEquals(waiting.second, ready.second, "a disabled Send must not share the enabled text colour")
    }

    @Test
    fun `the sheet speaks the player's language`() {
        AnkiDroidApp.sharedPrefs().edit { putString("language", "es-ES") }
        val (_, dialog) = open()
        val shown = texts(dialog)
        assertTrue("Enviar a" in shown, "section title in $shown")
        assertTrue("Más aplicaciones…" in shown, "other apps in $shown")
        assertEquals("Todos los amigos", chips(dialog).first().text.toString())
        assertEquals("Buscar amigos y grupos", dialog.findViewById<EditText>(R.id.aq_share_search)!!.hint.toString())
        assertEquals("Añadir una nota (opcional)", dialog.findViewById<EditText>(R.id.aq_share_note)!!.hint.toString())
    }

    @Test
    fun `the note is held to the server's length and sent as one line`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(1, 17, null)
        val (_, dialog) = open()
        val note = dialog.findViewById<EditText>(R.id.aq_share_note)!!
        note.setText("x".repeat(200))
        assertEquals(AnkiquestSharing.MAX_NOTE, note.text.length)

        note.setText("Took me\na month!")
        chip(dialog, "Cerro").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify { AnkiquestSharing.send(account, moment, Audience.Chosen(listOf("cerro"), emptyList()), "Took me a month!") }
    }

    @Test
    fun `the note accepts 140 Unicode characters including replacement and sends them intact`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(1, 17, null)
        val (_, dialog) = open()
        val note = dialog.findViewById<EditText>(R.id.aq_share_note)!!
        val symbol = "\uD83C\uDF31"
        val full = symbol.repeat(AnkiquestSharing.MAX_NOTE)
        note.setText(full + symbol)
        assertEquals(full, note.text.toString())

        // Replacing a selected character frees one character, not two UTF-16 units.
        note.text.replace(0, symbol.length, "z")
        val expected = "z" + symbol.repeat(AnkiquestSharing.MAX_NOTE - 1)
        assertEquals(expected, note.text.toString())
        note.text.append(symbol)
        assertEquals(expected, note.text.toString())
        chip(dialog, "Cerro").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify { AnkiquestSharing.send(account, moment, Audience.Chosen(listOf("cerro"), emptyList()), expected) }
    }

    @Test
    fun `sending posts in the app and offers Undo, which takes it back`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(2, 17, NOW + DAY)
        coEvery { AnkiquestSharing.retract(account, AnkiquestSharing.Sent(2, 17, NOW + DAY)) } returns 2
        var sent: AnkiquestSharing.Sent? = null
        val (_, dialog, screen) = open { sent = it }
        chip(dialog, "Ana").performClick()
        chip(dialog, "Spanish club").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()

        coVerify { AnkiquestSharing.send(account, moment, Audience.Chosen(listOf("ana"), listOf(3L)), null) }
        assertFalse(dialog.isShowing)
        assertEquals(17L, sent?.win)
        assertEquals(
            "Shared with 2 friends",
            screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text.toString(),
        )
        val undo = assertNotNull(screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_action))
        assertEquals(targetContext.getString(CommonString.undo), undo.text.toString())
        assertTrue(exits.isEmpty(), "the sheet is not done while Undo is offered")

        undo.performClick()
        advanceRobolectricLooperUntil { ShadowToast.getTextOfLatestToast() != null }
        coVerify(exactly = 1) { AnkiquestSharing.retract(account, AnkiquestSharing.Sent(2, 17, NOW + DAY)) }
        assertEquals(targetContext.getString(R.string.aq_conquest_untold), ShadowToast.getTextOfLatestToast())
        idle()
        assertEquals(listOf(Exit.SENT), exits)
    }

    @Test
    fun `Undo on a second send preserves the first recipient`() {
        // Model the server's contract: no selector retracts the whole win; until names one send.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val delivered = ConcurrentHashMap<String, Long>()
        val requests = CopyOnWriteArrayList<String>()
        server.createContext("/api/share/aldanita") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI}"
            val answer =
                if (exchange.requestMethod == "POST") {
                    val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    val who = body.getJSONObject("to").getJSONArray("friends").getString(0)
                    val until = NOW + DAY + delivered.size
                    delivered[who] = until
                    JSONObject().put("recipients", 1).put("win", 17).put("undo_until", until)
                } else {
                    val until =
                        exchange.requestURI.rawQuery
                            ?.removePrefix("until=")
                            ?.toLong()
                    val removed = delivered.filterValues { until == null || it == until }.keys
                    removed.forEach(delivered::remove)
                    JSONObject().put("retracted", removed.size)
                }
            val bytes = answer.toString().toByteArray()
            exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        unmockkObject(AnkiquestSharing)
        mockkObject(AnkiquestCapabilities)
        every { AnkiquestCapabilities.has(any(), any()) } returns true
        account = account.copy(server = "http://127.0.0.1:${server.address.port}")
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { AnkiquestSocial.overview(account) } answers { overview }
        try {
            val (_, first, screen) = open()
            chip(first, "Cerro").performClick()
            first.findViewById<View>(R.id.aq_share_send)!!.performClick()
            awaitSend(first)

            val (_, second) = show(screen)
            chip(second, "Ana").performClick()
            second.findViewById<View>(R.id.aq_share_send)!!.performClick()
            awaitSend(second)
            assertEquals(setOf("cerro", "ana"), delivered.keys.toSet())

            assertNotNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action)).performClick()
            advanceRobolectricLooperUntil { ShadowToast.getTextOfLatestToast() != null }

            assertEquals(setOf("cerro"), delivered.keys.toSet(), "the earlier send must survive")
            assertEquals("DELETE /api/share/aldanita/17?until=${NOW + DAY + 1}", requests.last())
        } finally {
            server.stop(0)
            unmockkObject(AnkiquestCapabilities)
        }
    }

    @Test
    fun `Undo too late says so`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(1, 17, NOW + DAY)
        coEvery { AnkiquestSharing.retract(account, AnkiquestSharing.Sent(1, 17, NOW + DAY)) } throws AnkiquestHttpException(409)
        val (_, dialog, screen) = open()
        chip(dialog, "All friends").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify { AnkiquestSharing.send(account, moment, Audience.Circle, null) }
        screen.findViewById<View>(com.google.android.material.R.id.snackbar_action)!!.performClick()
        advanceRobolectricLooperUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals(targetContext.getString(R.string.aq_conquest_untell_late), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `nobody new getting it has no Undo`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(0, 17, null)
        val (_, dialog, screen) = open()
        chip(dialog, "Ana").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        assertNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action))
        assertEquals(targetContext.getString(R.string.ankiquest_share_nobody), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(Exit.SENT), exits)
    }

    @Test
    fun `a failed send keeps the sheet and the choice, and can be tried again`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } throws IOException("offline")
        val (sheet, dialog) = open()
        chip(dialog, "Ana").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        assertTrue(dialog.isShowing)
        val failed = assertIs<State.Picking>(sheet.state)
        assertEquals(Step.Failed, failed.step)
        assertEquals(listOf("Ana"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })
        val status = dialog.findViewById<TextView>(R.id.aq_share_status)!!
        assertEquals(targetContext.getString(R.string.ankiquest_share_failed), status.text.toString())
        assertTrue(dialog.findViewById<View>(R.id.aq_share_send)!!.isEnabled)
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `a refused send shows the server's reason`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } throws AnkiquestHttpException(403, "Cerro is not your friend.")
        val (sheet, dialog) = open()
        chip(dialog, "Cerro").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        assertTrue(dialog.isShowing)
        val status = dialog.findViewById<TextView>(R.id.aq_share_status)!!
        assertTrue(status.isVisible)
        assertEquals("Couldn’t share it (Cerro is not your friend.).", status.text.toString())
        assertEquals(Step.Refused("Cerro is not your friend."), assertIs<State.Picking>(sheet.state).step)
        assertTrue(dialog.findViewById<View>(R.id.aq_share_send)!!.isEnabled)
    }

    @Test
    fun `More apps opens Android's share sheet, even when friends cannot be loaded`() {
        coEvery { AnkiquestSocial.overview(account) } throws IOException("offline")
        val (sheet, dialog) = open()
        assertEquals(State.Offline, sheet.state)
        assertFalse(dialog.findViewById<View>(R.id.aq_share_send)!!.isVisible)
        dialog.findViewById<View>(R.id.aq_share_more)!!.performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, elsewhere)
        assertEquals(listOf(Exit.ELSEWHERE), exits)
    }

    @Test
    fun `with no friends yet the sheet invites one`() {
        overview = overview.copy(friends = emptyList(), groups = emptyList())
        val (sheet, dialog, screen) = open()
        assertEquals(State.Lonely, sheet.state)
        assertFalse(dialog.findViewById<View>(R.id.aq_share_send)!!.isVisible)
        texts(dialog).first { it == targetContext.getString(R.string.ankiquest_share_invite) }
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text.toString() == targetContext.getString(R.string.ankiquest_share_invite) }
            .performClick()
        idle()
        assertEquals(AnkiquestFriendsActivity::class.java.name, shadowOf(screen).nextStartedActivity?.component?.className)
        assertEquals(listOf(Exit.CANCELLED), exits)
    }

    @Test
    fun `cancelling the sheet sends nothing`() {
        val (_, dialog) = open()
        chip(dialog, "Ana").performClick()
        dialog.cancel()
        idle()
        coVerify(exactly = 0) { AnkiquestSharing.send(any(), any(), any(), any()) }
        assertEquals(listOf(Exit.CANCELLED), exits)
    }

    @Test
    fun `a finished deck keeps its edited Unicode name in the picture and chosen send`() {
        val symbol = "\uD83C\uDF31"
        val name = symbol.repeat(AnkiquestShare.MAX_LABEL)
        val reference = MomentRef.Deck("7", "Japanese")
        coEvery { AnkiquestSharing.send(account, reference.copy(name = name), any(), any()) } returns AnkiquestSharing.Sent(1, 91, null)
        val (_, dialog) = open(reference = reference)
        val field = assertNotNull(dialog.findViewById<EditText>(R.id.aq_share_deck_name))
        field.setText(name + symbol)
        assertEquals(name, field.text.toString())
        assertTrue(texts(dialog).contains("I finished my $name studies for today."))
        chip(dialog, "Cerro").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify(
            exactly = 1,
        ) { AnkiquestSharing.send(account, reference.copy(name = name), Audience.Chosen(listOf("cerro"), emptyList()), null) }
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `an edited deck picture goes to other apps and blank names cannot be shared`() {
        val (_, dialog) = open(reference = MomentRef.Deck("7", "Japanese"))
        val field = assertNotNull(dialog.findViewById<EditText>(R.id.aq_share_deck_name))
        chip(dialog, "Ana").performClick()
        field.setText("   ")
        assertFalse(dialog.findViewById<View>(R.id.aq_share_send)!!.isEnabled)
        assertFalse(dialog.findViewById<View>(R.id.aq_share_more)!!.isEnabled)
        field.setText("Japanese vocabulary")
        dialog.findViewById<View>(R.id.aq_share_more)!!.performClick()
        idle()
        assertEquals("I finished my Japanese vocabulary studies for today.", exported?.body)
        coVerify(exactly = 0) { AnkiquestSharing.send(any(), any(), any(), any()) }
    }

    @Test
    fun `the finished deck sheet owns the post-study slot and honors Don't ask again`() {
        val (_, dialog, host) = open(reference = MomentRef.Deck("7", "Japanese"), slot = AnkiquestShareSheet.Slot.POST_STUDY)
        assertFalse(AnkiquestPostStudyPrompts.ready(host))
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text.toString() == targetContext.getString(R.string.ankiquest_deck_share_never) }
            .performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertTrue(AnkiquestPostStudyPrompts.ready(host))
        assertFalse(AnkiDroidApp.sharedPrefs().getBoolean(AnkiquestDeckShare.PREF_ASK, true))
        coVerify(exactly = 0) { AnkiquestSharing.send(any(), any(), any(), any()) }
    }

    private data class Opened(
        val sheet: AnkiquestShareSheet,
        val dialog: BottomSheetDialog,
        val screen: EmptyAnkiActivity,
    )

    private fun open(
        reference: MomentRef = moment,
        slot: AnkiquestShareSheet.Slot = AnkiquestShareSheet.Slot.INDEPENDENT,
        sent: (AnkiquestSharing.Sent) -> Unit = {},
    ): Opened {
        val screen =
            startActivityControllerNormallyOpenCollectionWithIntent(
                EmptyAnkiActivity::class.java,
                Intent(targetContext, EmptyAnkiActivity::class.java),
            ).get()
        screen.setContentView(FrameLayout(screen))
        return show(screen, sent, reference, slot)
    }

    private fun show(
        screen: EmptyAnkiActivity,
        sent: (AnkiquestSharing.Sent) -> Unit = {},
        reference: MomentRef = moment,
        slot: AnkiquestShareSheet.Slot = AnkiquestShareSheet.Slot.INDEPENDENT,
    ): Opened {
        val sheet =
            AnkiquestShareSheet(
                screen,
                account,
                AnkiquestShareSheet.Subject.Known(reference),
                card,
                elsewhere = {
                    elsewhere++
                    exported = it
                },
                sent = sent,
                closed = { exits += it },
                slot = slot,
            )
        sheet.show()
        idle()
        advanceRobolectricLooperUntil { sheet.state != State.Loading }
        return Opened(sheet, assertIs<BottomSheetDialog>(ShadowDialog.getLatestDialog()), screen)
    }

    private fun chips(dialog: BottomSheetDialog): List<Chip> =
        dialog
            .findViewById<ChipGroup>(R.id.aq_share_targets)!!
            .allViews
            .filterIsInstance<Chip>()
            .toList()

    private fun chip(
        dialog: BottomSheetDialog,
        label: String,
    ): Chip = chips(dialog).first { it.text.toString() == label }

    private fun texts(dialog: BottomSheetDialog): List<String> =
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .toList()

    /** The background and text colour [button] is drawn with in its current state. */
    private fun looks(button: MaterialButton): Pair<Int, Int> =
        button.backgroundTintList!!.getColorForState(button.drawableState, 0) to button.currentTextColor

    /** Finish the snackbar's animation without reaching its timeout. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    /** Wait for the HTTP answer without draining the snackbar's future timeout. */
    private fun awaitSend(dialog: BottomSheetDialog) {
        val deadline = System.nanoTime() + Duration.ofSeconds(SEND_TIMEOUT_SECONDS).toNanos()
        while (dialog.isShowing && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(dialog.isShowing, "the send should finish")
        idle()
    }

    private companion object {
        const val SEND_TIMEOUT_SECONDS = 10L
        const val POLL_MS = 10L
        const val HTTP_OK = 200
        const val NOW = 1_793_183_460_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
