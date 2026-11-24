// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A friend's win from the server: its card as the picture, a 🎉 cheer and a reply in your own words. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestFriendsWinTest : RobolectricTest() {
    private lateinit var server: AnkiquestTestServer
    private lateinit var base: String

    /** Whether the server refuses every request, as after a cheer it will not take. */
    @Volatile private var refuse = false
    private lateinit var account: HomeAccount
    private val requests get() = server.requests.map { it.path to it.body }
    private val manager get() = targetContext.getSystemService<NotificationManager>()!!

    @Before
    fun startServer() {
        val png =
            ByteArrayOutputStream()
                .also { createBitmap(8, 8).compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        server =
            AnkiquestTestServer { request ->
                when {
                    refuse -> TestResponse(HTTP_BAD_REQUEST)
                    request.path == "/w/token.png" -> TestResponse(bytes = png)
                    request.path == "/api/react/hill" -> TestResponse(bytes = "{}".toByteArray())
                    else -> null
                }
            }
        base = server.url
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, base)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
        account = AnkiquestHomeData.account()!!
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) {
            server.close()
        }
    }

    private fun share(
        id: Long,
        image: String? = "$base/w/token.png",
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("created_at", TimeManager.time.intTimeMS() / 1000 - 30)
            .put("kind", "share")
            .put("sender", "cerro")
            .put("title", "🎉 Cerro: Level 54")
            .put("body", "“You reached level 54.”")
            .put("win", 17)
            .apply { image?.let { put("image", it) } }

    private fun deliver(vararg entries: JSONObject) =
        AnkiquestNotifier.onDeckCompletions(targetContext, account.notificationAccount, JSONArray(entries.toList()), account.scope)

    private fun posted(id: Long): Notification = shadowOf(manager).getNotification(5_140_000 + id.toInt())

    private fun reply(notification: Notification): Notification.Action =
        notification.actions.single { it.remoteInputs?.isNotEmpty() == true }

    @Test
    fun `a friend's win shows its card and can be cheered or answered in your own words`() {
        deliver(share(1))
        val notification = posted(1)
        assertEquals(Notification.BigPictureStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(listOf("🎉 Cheer", "Reply"), notification.actions.map { it.title.toString() })
        val input = reply(notification).remoteInputs.single()
        assertTrue(input.choices.isNullOrEmpty(), "the cheer takes the place of the suggested words")
        assertTrue(input.allowFreeFormInput)
        assertTrue(requests.any { it.first == "/w/token.png" })
    }

    @Test
    fun `without its card a friend's win is still told in words`() {
        deliver(share(1, image = "$base/w/missing.png"), share(2, image = null))
        for (id in listOf(1L, 2L)) {
            assertEquals(Notification.BigTextStyle::class.java.name, posted(id).extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(listOf("🎉 Cheer", "Reply"), posted(id).actions.map { it.title.toString() })
        }
    }

    @Test
    fun `a win already cheered, and shares from older servers, keep the usual reply`() {
        deliver(share(1).put("reacted", true), share(2, image = null).apply { remove("win") })
        assertEquals(listOf("Reply"), posted(1).actions.map { it.title.toString() })
        assertTrue(
            reply(posted(1))
                .remoteInputs
                .single()
                .choices
                .isNullOrEmpty(),
        )
        assertEquals(listOf("Reply"), posted(2).actions.map { it.title.toString() })
        assertEquals(
            listOf("Good job!", "Nice one 🔥", "Keep it up!"),
            reply(posted(2))
                .remoteInputs
                .single()
                .choices
                .map { it.toString() },
        )
    }

    @Test
    fun `a cheer can be thanked`() {
        deliver(
            JSONObject()
                .put("id", 3)
                .put("created_at", TimeManager.time.intTimeMS() / 1000 - 30)
                .put("kind", "reaction")
                .put("sender", "cerro")
                .put("title", "🎉 Cerro cheered")
                .put("body", "“Level 54”"),
        )
        val notification = posted(3)
        assertEquals("🎉 Cerro cheered", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Reply"), notification.actions.map { it.title.toString() })
        assertEquals(
            "Thanks!",
            reply(notification)
                .remoteInputs
                .single()
                .choices
                .first()
                .toString(),
        )
    }

    @Test
    fun `cheering sends a reaction and leaves the reply`() =
        runBlocking {
            deliver(share(1))
            val cheer = posted(1).actions.first()
            val intent = shadowOf(cheer.actionIntent).savedIntent
            assertEquals(AnkiquestReply.ACTION_CHEER, intent.action)
            assertTrue(RemoteInput.getResultsFromIntent(intent) == null)
            val data = AnkiquestReply.data(intent, AnkiquestReply.message(intent))
            assertTrue(data.getBoolean(AnkiquestReply.EXTRA_REACT, false))
            assertEquals(AnkiquestReply.Outcome.SENT, AnkiquestReply.run(targetContext, data, 0))
            val react = requests.single { it.first == "/api/react/hill" }
            assertEquals(1L, JSONObject(react.second).getLong("notification"))
            val after = posted(1)
            assertTrue(
                after.extras
                    .getString(Notification.EXTRA_TEXT)
                    .orEmpty()
                    .startsWith("🎉 You cheered"),
            )
            assertEquals(listOf("Reply"), after.actions.map { it.title.toString() })
            assertFalse(requests.any { it.first.startsWith("/api/reply/") })
        }

    @Test
    fun `a refused cheer is not retried and keeps its button`() =
        runBlocking {
            deliver(share(1))
            val intent = shadowOf(posted(1).actions.first().actionIntent).savedIntent
            refuse = true
            val data = AnkiquestReply.data(intent, AnkiquestReply.message(intent))
            assertEquals(AnkiquestReply.Outcome.FAILED, AnkiquestReply.run(targetContext, data, 0))
            assertEquals(listOf("🎉 Cheer", "Reply"), posted(1).actions.map { it.title.toString() })
        }
}
