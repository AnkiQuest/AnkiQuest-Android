// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection.HTTP_CONFLICT
import java.net.HttpURLConnection.HTTP_OK
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestGlobalHidesTest : RobolectricTest() {
    private data class Request(
        val method: String,
        val path: String,
        val token: String?,
        val body: String,
    )

    private enum class ResponseOwner { CURRENT, CHANGED }

    @Test
    fun `supported servers load only the captured viewer's hidden players`() {
        withServer { account, requests ->
            val people = runBlocking { AnkiquestGlobalHides.load(account) }
            assertEquals(listOf(AnkiquestGlobalHides.Player("ana", "Ana")), people)
            assertEquals(listOf(Request("GET", "/api/leaderboard/hidden/hill", "Bearer test-token", "")), requests.toList())
        }
    }

    @Test
    fun `hide and show again send the target and return the updated list`() {
        withServer { account, requests ->
            for (action in AnkiquestGlobalHides.Action.entries) {
                val people = runBlocking { AnkiquestGlobalHides.change(account, "ana", action) }
                assertEquals(listOf(AnkiquestGlobalHides.Player("ana", "Ana")), people)
            }
            assertEquals(listOf("hide", "unhide"), requests.map { JSONObject(it.body).getString("action") })
            assertTrue(
                requests.all {
                    it.method == "POST" && it.path == "/api/leaderboard/hidden/hill" && it.token == "Bearer test-token" &&
                        JSONObject(it.body).getString("user") == "ana"
                },
            )
        }
    }

    @Test
    fun `changed accounts and credentials never send stale hiding requests`() {
        withServer { account, requests ->
            for (changed in listOf(
                account.copy(user = "other"),
                account.copy(token = "new-token"),
                account.copy(server = "http://127.0.0.1:9"),
            )) {
                every { AnkiquestHomeData.account(any()) } returns changed
                assertFailsWith<HomeAccountChanged> { runBlocking { AnkiquestGlobalHides.load(account) } }
                assertFailsWith<HomeAccountChanged> {
                    runBlocking { AnkiquestGlobalHides.change(account, "ana", AnkiquestGlobalHides.Action.HIDE) }
                }
            }
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `an account change during a save rejects the response and retains the original request owner`() {
        withServer(responseOwner = ResponseOwner.CHANGED) { account, requests ->
            assertFailsWith<HomeAccountChanged> {
                runBlocking { AnkiquestGlobalHides.change(account, "ana", AnkiquestGlobalHides.Action.HIDE) }
            }
            assertEquals("/api/leaderboard/hidden/hill", requests.single().path)
            assertEquals("Bearer test-token", requests.single().token)
        }
    }

    @Test
    fun `a server refusal stays visible and never pretends the player was hidden`() {
        withServer(status = HTTP_CONFLICT) { account, _ ->
            val failure =
                assertFailsWith<AnkiquestHttpException> {
                    runBlocking { AnkiquestGlobalHides.change(account, "ana", AnkiquestGlobalHides.Action.HIDE) }
                }
            assertEquals(HTTP_CONFLICT, failure.code)
        }
    }

    @Test
    fun `cancelled capability discovery does not become unsupported`() {
        withServer { account, requests ->
            coEvery { AnkiquestCapabilities.current(account) } throws CancellationException("screen left")
            assertFailsWith<CancellationException> { runBlocking { AnkiquestGlobalHides.load(account) } }
            assertTrue(requests.isEmpty())
        }
    }

    private fun withServer(
        status: Int = HTTP_OK,
        responseOwner: ResponseOwner = ResponseOwner.CURRENT,
        block: (HomeAccount, List<Request>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<Request>()
        val current = AtomicReference<HomeAccount>()
        val server =
            AnkiquestTestServer { request ->
                requests.add(Request(request.method, request.path, request.authorization, request.body))
                if (responseOwner == ResponseOwner.CHANGED) {
                    current.set(current.get().copy(user = "other", token = "other-token"))
                }
                TestResponse.json("""[{"user":"ana","display":"Ana"}]""", status)
            }
        val account = HomeAccount(server.url, "hill", "test-token")
        current.set(account)
        mockkObject(AnkiquestHomeData, AnkiquestCapabilities)
        every { AnkiquestHomeData.account(any()) } answers { current.get() }
        coEvery { AnkiquestCapabilities.current(any()) } returns setOf("global_leaderboard_hide")
        try {
            block(account, requests)
        } finally {
            unmockkObject(AnkiquestHomeData, AnkiquestCapabilities)
            server.close()
        }
    }
}
