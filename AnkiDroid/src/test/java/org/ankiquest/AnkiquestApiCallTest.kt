// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AnkiquestApi.call] with a method and a path: one request for the signed-in account. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestApiCallTest : RobolectricTest() {
    private lateinit var server: AnkiquestTestServer

    private val url get() = server.url

    /** Each request as "METHOD path authorization body". */
    private val requests get() = server.requests.map { "${it.method} ${it.uri.rawPath} ${it.authorization} ${it.body}" }

    @Before
    fun startServer() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        server = AnkiquestTestServer { TestResponse(bytes = """{"ok":true}""".toByteArray()) }
    }

    @After
    fun stopServer() {
        server.close()
        SharedPreferencesTestHook.override = null
    }

    private fun signIn(token: String) {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, url)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, token)
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
