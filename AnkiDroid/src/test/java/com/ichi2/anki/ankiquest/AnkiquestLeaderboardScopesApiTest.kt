// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestLeaderboardScopesApiTest : RobolectricTest() {
    @Test
    fun `fixture ignores startup sync`() {
        withServer { _, requests ->
            // Startup can overlap a board request; the fixture must isolate that unrelated sync.
            runBlocking { AnkiquestSettingsSync.sync(targetContext, AnkiquestSettingsSync.Trigger.START) }
            assertTrue(requests.isEmpty(), "Startup settings sync must not use the leaderboard fixture")
        }
    }

    @Test
    fun `Friends keeps its URL while explicit Group and Global include the requested period`() {
        withServer { account, requests ->
            runBlocking {
                AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.DAY)
                AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.MONTH, AnkiquestBoards.Source.Global)
                AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.YEAR, AnkiquestBoards.Source.Group(7))
            }
            assertEquals(
                listOf(
                    "/api/leaderboard?period=day",
                    "/api/leaderboard?period=month&scope=global",
                    "/api/leaderboard?period=year&scope=group&group=7",
                ),
                requests.map { it.first },
            )
            assertTrue(requests.all { it.second == "Bearer test-token" })
        }
    }

    @Test
    fun `a false visibility preference still means the server supports scopes`() {
        withServer(settings = """{"global_leaderboard":false}""") { account, requests ->
            assertTrue(runBlocking { AnkiquestBoards.hasScopes(account) })
            assertEquals(listOf("/api/decks/hill"), requests.map { it.first })
        }
        withServer(settings = "{}") { account, _ ->
            assertFalse(runBlocking { AnkiquestBoards.hasScopes(account) })
        }
    }

    @Test
    fun `an invalid group is rejected before any request`() {
        withServer { account, requests ->
            assertFailsWith<IllegalArgumentException> {
                runBlocking { AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Group(0)) }
            }
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `an account change cannot send the old account's scoped request`() {
        withServer { account, requests ->
            every { AnkiquestHomeData.account(any()) } returns account.copy(user = "other", token = "other-token")
            assertFailsWith<HomeAccountChanged> {
                runBlocking { AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Global) }
            }
            assertTrue(requests.isEmpty())
        }
    }

    private fun withServer(
        settings: String = "{}",
        block: (HomeAccount, List<Pair<String, String?>>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // The settings sync Ankiquest.init starts on Dispatchers.IO reads the mocked account too, and can
            // ask this server for its capabilities during the test on a slow runner (Windows run 44378382365).
            // That request is not the code under test, so it is answered without being recorded.
            if (exchange.requestURI.path != CAPABILITIES) {
                requests.add(exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization"))
            }
            val body =
                if (exchange.requestURI.path.startsWith("/api/decks/")) {
                    settings
                } else {
                    "[]"
                }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        // Startup sync must not borrow the fixture's account and add unrelated requests.
        val prefs = AnkiDroidApp.sharedPrefs()
        val previousSync = prefs.all[AnkiquestSettingsSync.PREF_SWITCH] as Boolean?
        prefs.edit(commit = true) { putBoolean(AnkiquestSettingsSync.PREF_SWITCH, false) }
        try {
            mockkObject(AnkiquestHomeData)
            val account = HomeAccount("http://127.0.0.1:${server.address.port}", "hill", "test-token")
            every { AnkiquestHomeData.account(any()) } returns account
            block(account, requests)
        } finally {
            unmockkObject(AnkiquestHomeData)
            server.stop(0)

            // Restore the preference only after the fake account is no longer visible.
            prefs.edit(commit = true) {
                if (previousSync == null) {
                    remove(AnkiquestSettingsSync.PREF_SWITCH)
                } else {
                    putBoolean(AnkiquestSettingsSync.PREF_SWITCH, previousSync)
                }
            }
        }
    }

    private companion object {
        /** `GET /api/capabilities`, which background work may send at any time. */
        const val CAPABILITIES = "/api/capabilities"
    }
}
