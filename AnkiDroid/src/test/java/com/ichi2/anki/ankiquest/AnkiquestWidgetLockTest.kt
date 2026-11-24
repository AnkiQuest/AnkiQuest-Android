// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.RemoteViewsCompat
import androidx.core.widget.RemoteViewsCompat.RemoteCollectionItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
    private lateinit var server: AnkiquestTestServer

    /** The rows `/api/leaderboard` answers. */
    @Volatile
    private var board = JSONArray()

    /** Whether the server already holds one of the player's reviews. */
    private enum class Uploaded { YES, NOT_YET }

    @Volatile
    private var uploaded = Uploaded.NOT_YET

    /** The list items each widget was last given. Robolectric's widget host does not load them into its list. */
    private val items = mutableMapOf<Int, RemoteCollectionItems>()

    @Before
    fun serveBoard() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        mockkObject(AnkiquestNotifier)
        coEvery { AnkiquestNotifier.onLeaderboard(any()) } just Runs
        every { AnkiquestNotifier.onProfile(any(), any(), any()) } just Runs
        every { AnkiquestNotifier.onDeckCompletions(any(), any(), any(), any()) } just Runs
        AnkiquestWidget.forgetFresh()

        // Keep each widget's list items, then hand them on as usual.
        mockkStatic(RemoteViewsCompat::class)
        mockkObject(RemoteViewsCompat)
        every {
            RemoteViewsCompat.setRemoteAdapter(any(), any<RemoteViews>(), any(), any(), any<RemoteCollectionItems>())
        } answers {
            items[thirdArg()] = arg(4)
            callOriginal()
        }

        // Every locked answer carries the header; only the board's rows matter here.
        server =
            AnkiquestTestServer { request ->
                val body =
                    when {
                        request.path == "/api/leaderboard" -> board
                        // A server with scopes answers the Global setting with the player's decks.
                        request.path.startsWith("/api/decks/") -> JSONObject().put(AnkiquestBoards.GLOBAL_SETTING, true)
                        else -> "{}"
                    }
                val lock = if (uploaded == Uploaded.NOT_YET) mapOf(AnkiquestLock.HEADER to "upload_first") else emptyMap()
                TestResponse.json(body, headers = lock)
            }
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, server.url)
            putString(Ankiquest.PREF_USER, "aldanita")
            putString(Ankiquest.PREF_TOKEN, "aldanita-token")
        }
    }

    @After
    fun stopServer() {
        try {
            if (::server.isInitialized) {
                server.close()
            }
        } finally {
            AnkiquestWidget.forgetFresh()
            unmockkObject(RemoteViewsCompat)
            unmockkStatic(RemoteViewsCompat::class)
            unmockkObject(AnkiquestNotifier)
            SharedPreferencesTestHook.override = null
        }
    }

    @Test
    fun `a locked board shows the player's own row, then why the others are missing`() =
        runBlocking {
            board = JSONArray().put(row("aldanita", "Aldana"))
            val id = placeWidget()

            AnkiquestPoll.run(targetContext)

            val lines = shown(id)
            assertEquals("Aldana", lines.first(), "the player's own row comes first")
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
            board = JSONArray().put(row("aldanita", "Aldana"))
            val id = placeWidget()
            AnkiquestPoll.run(targetContext)

            // The next poll cannot reach the server, so the widget draws the cached board.
            server.close()
            AnkiquestPoll.run(targetContext)

            assertEquals(targetContext.getString(R.string.aq_upload_first), shown(id).last())
        }

    @Test
    fun `after the first upload the board shows everyone without the line`() =
        runBlocking {
            uploaded = Uploaded.YES
            board = JSONArray().put(row("aldanita", "Aldana")).put(row("cerro", "Cerro"))
            val id = placeWidget()

            AnkiquestPoll.run(targetContext)

            assertEquals(listOf("Aldana", "Cerro"), shown(id).filter { it == "Aldana" || it == "Cerro" })
            assertFalse(targetContext.getString(R.string.aq_upload_first) in shown(id))
        }

    @Test
    fun `a board unlocked by the first upload drops the line on the next poll`() =
        runBlocking {
            board = JSONArray().put(row("hill", "Hill"))
            val id = placeWidget()
            AnkiquestPoll.run(targetContext)
            assertEquals(targetContext.getString(R.string.aq_upload_first), shown(id).last())

            // The upload reached the server, which now lists everyone.
            uploaded = Uploaded.YES
            board = JSONArray().put(row("hill", "Hill")).put(row("cerro", "Cerro"))
            AnkiquestWidget.forgetFresh()
            AnkiquestPoll.run(targetContext)

            assertEquals(listOf("Hill", "Cerro"), shown(id).filter { it == "Hill" || it == "Cerro" })
            assertFalse(targetContext.getString(R.string.aq_upload_first) in shown(id))
        }

    @Test
    fun `the transparent widget says why the others are missing`() =
        runBlocking {
            board = JSONArray().put(row("hill", "Hill"))
            val id = placeWidget(Kind.TRANSPARENT)

            AnkiquestPoll.run(targetContext)

            val lines = shown(id)
            assertEquals("Hill", lines.first(), "the player's own row comes first")
            assertEquals(targetContext.getString(R.string.aq_upload_first), lines.last())
        }

    @Test
    fun `Global and group widgets say why the others are missing, until the first upload`() =
        runBlocking {
            board = JSONArray().put(row("hill", "Hill"))
            val global = placeWidget()
            val group = placeWidget(Kind.TRANSPARENT)
            AnkiquestWidgetSettings.save(global, AnkiquestWidgetSettings(AnkiquestWidgetSettings.Who.GLOBAL))
            AnkiquestWidgetSettings.save(group, AnkiquestWidgetSettings(AnkiquestWidgetSettings.Who.GROUP, 7, "Study group"))

            AnkiquestPoll.run(targetContext)
            for (id in listOf(global, group)) {
                val lines = shown(id)
                assertEquals("Hill", lines.first(), "widget $id: the player's own row comes first")
                assertEquals(targetContext.getString(R.string.aq_upload_first), lines.last(), "widget $id")
            }

            // After the upload the same widgets show everyone without the line.
            uploaded = Uploaded.YES
            board = JSONArray().put(row("hill", "Hill")).put(row("cerro", "Cerro"))
            AnkiquestWidget.forgetFresh()
            AnkiquestPoll.run(targetContext)
            for (id in listOf(global, group)) {
                val lines = shown(id)
                assertEquals(listOf("Hill", "Cerro"), lines.filter { it == "Hill" || it == "Cerro" }, "widget $id")
                assertFalse(targetContext.getString(R.string.aq_upload_first) in lines, "widget $id: $lines")
            }
        }

    /** Which of the two leaderboard widgets to place. */
    private enum class Kind { SOLID, TRANSPARENT }

    private fun placeWidget(kind: Kind = Kind.SOLID): Int {
        val manager = shadowOf(AppWidgetManager.getInstance(targetContext))
        return when (kind) {
            Kind.SOLID -> manager.createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
            Kind.TRANSPARENT -> manager.createWidget(AnkiquestTransparentWidget::class.java, R.layout.widget_ankiquest_transparent)
        }
    }

    /** What widget [id] shows: its status line when visible, then one line per list item (a player's name, or the item's text). */
    private fun shown(id: Int): List<String> {
        val root = shadowOf(AppWidgetManager.getInstance(targetContext)).getViewFor(id)
        val status = root.findViewById<TextView>(R.id.ankiquest_widget_status)
        val list = root.findViewById<ListView>(R.id.ankiquest_widget_list)
        val lines = mutableListOf<String>()
        if (status.isVisible) {
            lines += status.text.toString()
        }
        if (!list.isVisible) {
            return lines
        }

        // The list items as the launcher would inflate them.
        val given = items[id] ?: return lines
        for (position in 0 until given.itemCount) {
            val item = given.getItemView(position).apply(targetContext, FrameLayout(targetContext))
            val name = item.findViewById<TextView>(R.id.ankiquest_widget_name)
            lines += name?.text?.toString() ?: texts(item).joinToString(" ")
        }
        return lines
    }

    /** The visible, non-empty texts of [view] and its children, in layout order. */
    private fun texts(view: View): List<String> {
        if (!view.isVisible) {
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
