// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AnkiquestApi.call] with a method and a path: one request for the signed-in account. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestApiCallTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun startServer() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            requests.add(
                "${exchange.requestMethod} ${exchange.requestURI.rawPath} ${exchange.requestHeaders.getFirst("Authorization")} $body",
            )
            val bytes = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        SharedPreferencesTestHook.override = null
    }

    private fun signIn(token: String) {
        AnkiDroidApp.sharedPrefs().edit {
            putString(AnkiquestPreferences.PREF_URL, url)
            putString(AnkiquestPreferences.PREF_USER, "hill")
            putString(AnkiquestPreferences.PREF_TOKEN, token)
        }
    }

    @Test
    fun `sends the method, path, token and body and answers the JSON`() =
        runBlocking {
            signIn("hill-token")
            val account = HomeAccount(url, "hill", "hill-token")

            val answer = AnkiquestApi.call(account, "PUT", "api/goal/hill", JSONObject().put("mode", "cards"))

            assertTrue(answer.getBoolean("ok"))
            assertEquals(listOf("PUT /api/goal/hill Bearer hill-token {\"mode\":\"cards\"}"), requests)
        }

    @Test
    fun `another signed-in account sends nothing`() =
        runBlocking<Unit> {
            signIn("cerro-token")

            assertFailsWith<HomeAccountChanged> { AnkiquestApi.call(HomeAccount(url, "hill", "hill-token"), "GET", "api/goal/hill") }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a required token that is missing sends nothing`() =
        runBlocking {
            signIn("")
            val account = HomeAccount(url, "hill", "")

            val error =
                assertFailsWith<AnkiquestHttpException> {
                    AnkiquestApi.call(account, "GET", "api/goal/hill", token = AnkiquestApi.Token.REQUIRED)
                }

            assertEquals(HTTP_UNAUTHORIZED, error.code)
            assertTrue(requests.isEmpty())
        }
}
