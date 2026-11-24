// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AnkiquestAccountTest : RobolectricTest() {
    private lateinit var server: AnkiquestTestServer
    private lateinit var url: String

    /** Each request's path and JSON body. */
    private val received get() = server.requests.map { it.path to JSONObject(it.body) }

    @Before
    fun startServer() {
        server =
            AnkiquestTestServer { request ->
                val body = JSONObject(request.body)
                val (status, answer) =
                    when {
                        request.path == "/api/accounts/tokens" && body.getString("user") == "two" -> secondFactor(body)
                        request.path == "/api/accounts/tokens" && body.getString("password") == "right password" ->
                            200 to JSONObject().put("user", body.getString("user")).put("token", "device-token")
                        request.path == "/api/accounts/tokens" -> 401 to JSONObject().put("error", "no")
                        body.getString("user") == "taken" -> 409 to JSONObject().put("error", "taken")
                        else -> 201 to JSONObject().put("user", body.getString("user")).put("token", "first-token")
                    }
                TestResponse(status, answer.toString().toByteArray())
            }
        url = server.url
    }

    /** An account with two-factor authentication on, answering as docs/mobile-api.md in AnkiQuest-Server describes. */
    private fun secondFactor(body: JSONObject): Pair<Int, JSONObject> {
        val asked = JSONObject().put("second_factor", true)
        return when {
            body.getString("password") != "right password" -> 401 to JSONObject().put("error", "Username or password not recognized.")
            !body.has("code") -> 401 to asked.put("error", "Enter the code from your authenticator app.")
            body.getString("code") == "123456" -> 200 to JSONObject().put("user", "two").put("token", "device-token")
            body.getString("code") == "locked" -> 429 to asked.put("error", "Too many wrong codes. Try again in 15 minutes.")
            else -> 401 to asked.put("error", "That code is not right.")
        }
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun credentials(
        user: String = "ana",
        password: String = "right password",
        display: String = "",
        create: Boolean = false,
        code: String = "",
    ) = AnkiquestAccount.Credentials("$url/", user, password, display, create, code)

    @Test
    fun `signing in trades the password for a device token`() =
        runBlocking {
            val signed = AnkiquestAccount.submit(credentials(), device = "AnkiDroid (Pixel)")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "device-token"), signed)
            val (path, body) = received.single()
            assertEquals("/api/accounts/tokens", path)
            assertEquals("AnkiDroid (Pixel)", body.getString("device"))
            assertFalse(body.has("display"))
        }

    @Test
    fun `creating an account sends the display name and returns the first token`() =
        runBlocking {
            val signed = AnkiquestAccount.submit(credentials(user = "bo", display = "Bo", create = true), device = "AnkiDroid")
            assertEquals("first-token", signed.token)
            val (path, body) = received.single()
            assertEquals("/api/accounts", path)
            assertEquals("Bo", body.getString("display"))
        }

    /** The refusal of a sign-in for the two-factor account that sends [code]. */
    private suspend fun held(code: String) =
        assertFailsWith<AnkiquestAccount.SecondFactorNeeded> {
            AnkiquestAccount.submit(credentials(user = "two", code = code))
        }

    @Test
    fun `a second factor is asked for, and the code is sent only then`() =
        runBlocking {
            val asked = held("")
            assertEquals(AnkiquestAccount.SecondFactor.ASKED, asked.step)
            assertFalse(received.last().second.has("code"), "older servers refuse unknown fields")
            val wrong = held("000000")
            assertEquals(AnkiquestAccount.SecondFactor.REFUSED, wrong.step)
            val locked = held("locked")
            assertEquals(AnkiquestAccount.SecondFactor.LOCKED, locked.step)
            val signed = AnkiquestAccount.submit(credentials(user = "two", code = " 123456 "))
            assertEquals("device-token", signed.token)
            assertEquals("123456", received.last().second.getString("code"))

            // The same words as the website and the add-on.
            assertEquals(
                listOf(
                    R.string.ankiquest_two_factor_asked,
                    R.string.ankiquest_two_factor_refused,
                    R.string.ankiquest_two_factor_locked,
                ).map(targetContext::getString),
                listOf(asked, wrong, locked).map { AnkiquestAccount.message(targetContext, it, url) },
            )
            assertEquals("Enter the code from your authenticator app.", targetContext.getString(R.string.ankiquest_two_factor_asked))
        }

    @Test
    fun `a wrong password says nothing about a second factor`() =
        runBlocking {
            val wrong = assertFailsWith<AnkiquestHttpException> { AnkiquestAccount.submit(credentials(user = "two", password = "wrong")) }
            assertFalse(wrong.secondFactor)
            assertEquals(targetContext.getString(R.string.ankiquest_sign_in_wrong), AnkiquestAccount.message(targetContext, wrong, url))
        }

    @Test
    fun `a new account never sends a code`() {
        val request = AnkiquestAccount.httpRequest(credentials(user = "bo", create = true, code = "123456"), "AnkiDroid")
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
        assertFalse(JSONObject(buffer.readUtf8()).has("code"))
    }

    @Test
    fun `refusals explain themselves`() =
        runBlocking {
            val wrong = assertFailsWith<AnkiquestHttpException> { AnkiquestAccount.submit(credentials(password = "wrong")) }
            assertEquals(targetContext.getString(R.string.ankiquest_sign_in_wrong), AnkiquestAccount.message(targetContext, wrong, url))
            val taken =
                assertFailsWith<AnkiquestHttpException> {
                    AnkiquestAccount.submit(credentials(user = "taken", create = true))
                }
            assertEquals(targetContext.getString(R.string.ankiquest_sign_in_taken), AnkiquestAccount.message(targetContext, taken, url))
        }
}
