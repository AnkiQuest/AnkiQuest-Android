// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Before the player's first upload the server leaves other players out of `/api/leaderboard`
 * (docs/mobile-api.md, "seeing other players: upload first"). The leaderboard widget then says
 * why, after the player's own row when the server lists it, as the in-app board does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestWidgetLockTest : RobolectricTest() {
    private lateinit var server: HttpServer

    /** The rows `/api/leaderboard` answers. */
    @Volatile
    private var board = JSONArray()

    /** Whether the server already holds one of the player's reviews. */
    private enum class Uploaded { YES, NOT_YET }

    @Volatile
    private var uploaded = Uploaded.NOT_YET

    @Before
    fun serveBoard() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        mockkObject(AnkiquestNotifier)
        coEvery { AnkiquestNotifier.onLeaderboard(any()) } just Runs
        every { AnkiquestNotifier.onProfile(any(), any()) } just Runs
        every { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) } just Runs
        AnkiquestWidget.forgetFresh()

        // Every locked answer carries the header; only the board's rows matter here.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/api/leaderboard") board.toString() else "{}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            if (uploaded == Uploaded.NOT_YET) {
                exchange.responseHeaders.set(AnkiquestLock.HEADER, "upload_first")
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    @After
    fun stopServer() {
        try {
            if (::server.isInitialized) server.stop(0)
        } finally {
            AnkiquestWidget.forgetFresh()
            unmockkObject(AnkiquestNotifier)
            SharedPreferencesTestHook.override = null
        }
    }

    @Test
    fun `a locked board shows the player's own row, then why the others are missing`() =
        runBlocking {
            board = JSONArray().put(row("hill", "Hill"))
            val id = placeWidget()

            AnkiquestPoll.run(targetContext)

            val lines = shown(id)
            assertEquals("Hill", lines.first(), "the player's own row comes first")
            assertEquals(targetContext.getString(R.string.aq_upload_first), lines.last())
        }

    @Test
    fun `a locked board without the player's row says why instead of calling it empty`() =
        runBlocking {
            val id = placeWidget()

            AnkiquestPoll.run(targetContext)

            val lines = shown(id)
            assertTrue(targetContext.getString(R.string.aq_upload_first) in lines, "$lines")
            assertFalse(targetContext.getString(R.string.ankiquest_widget_empty) in lines, "$lines")
        }

    @Test
    fun `a board fetched while offline keeps saying why the others are missing`() =
        runBlocking {
            board = JSONArray().put(row("hill", "Hill"))
            val id = placeWidget()
            AnkiquestPoll.run(targetContext)

            // The next poll cannot reach the server, so the widget draws the cached board.
            server.stop(0)
            AnkiquestPoll.run(targetContext)

            assertEquals(targetContext.getString(R.string.aq_upload_first), shown(id).last())
        }

    @Test
    fun `after the first upload the board shows everyone without the line`() =
        runBlocking {
            uploaded = Uploaded.YES
            board = JSONArray().put(row("hill", "Hill")).put(row("cerro", "Cerro"))
            val id = placeWidget()

            AnkiquestPoll.run(targetContext)

            assertEquals(listOf("Hill", "Cerro"), shown(id).filter { it == "Hill" || it == "Cerro" })
            assertFalse(targetContext.getString(R.string.aq_upload_first) in shown(id))
        }

    private fun placeWidget(): Int =
        shadowOf(AppWidgetManager.getInstance(targetContext)).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)

    /** The texts widget [id] shows: its status line when visible, then each list item's texts in order. */
    private fun shown(id: Int): List<String> {
        val root = shadowOf(AppWidgetManager.getInstance(targetContext)).getViewFor(id)
        val status = root.findViewById<TextView>(R.id.ankiquest_widget_status)
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        val lines = mutableListOf<String>()
        if (status.visibility == View.VISIBLE) {
            lines += status.text.toString()
        }
        if (list.visibility != View.VISIBLE) {
            return lines
        }
        val adapter = list.adapter ?: return lines
        for (position in 0 until adapter.count) {
            lines += texts(adapter.getView(position, null, list))
        }
        return lines
    }

    /** The visible, non-empty texts of [view] and its children, in layout order. */
    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) {
            return emptyList()
        }
        if (view is TextView) {
            return listOfNotNull(view.text.toString().takeIf { it.isNotBlank() })
        }
        if (view !is ViewGroup) {
            return emptyList()
        }
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }

    private fun row(
        user: String,
        display: String,
    ): JSONObject =
        JSONObject()
            .put("user", user)
            .put("display", display)
            .put("level", 1)
            .put("xp", 0)
            .put("week_xp", 0)
            .put("streak", 0)
}
