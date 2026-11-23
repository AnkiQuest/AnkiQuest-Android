// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestCapabilitiesTest : RobolectricTest() {
    private lateinit var server: AnkiquestTestServer
    private lateinit var account: HomeAccount
    private val clock = MockTime(1_750_000_000_000)
    private val requests get() = server.requests.map { it.path to it.authorization }

    @Volatile private var status = 200

    @Volatile private var body = """{"version":1,"features":["activity","cookie_session","wins"]}"""

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        TimeManager.resetWith(clock)
        AnkiquestCapabilities.forgetMemory()
        server = AnkiquestTestServer { TestResponse(status, body.toByteArray()) }
        account = HomeAccount(server.url, "cerro", "secret")
    }

    @After
    fun stop() {
        runCatching { server.close() }
        AnkiquestCapabilities.forgetMemory()
        SharedPreferencesTestHook.override = null
    }

    @Test
    fun `features are read from the answer and anything unreadable counts as none`() {
        assertEquals(setOf("activity", "wins"), AnkiquestCapabilities.parse("""{"version":1,"features":["activity",7,"","wins"]}"""))
        assertEquals(emptySet(), AnkiquestCapabilities.parse("""{"version":1}"""))
        assertEquals(emptySet(), AnkiquestCapabilities.parse("""{"features":"wins"}"""))
        assertEquals(emptySet(), AnkiquestCapabilities.parse("<html>not found</html>"))
    }

    @Test
    fun `capabilities are asked for without credentials`() =
        runBlocking {
            assertEquals(setOf("activity", "cookie_session", "wins"), AnkiquestCapabilities.refresh(account))
            assertEquals(listOf("/api/capabilities" to null), requests.toList())
            assertTrue(AnkiquestCapabilities.has(account, "cookie_session"))
            assertFalse(AnkiquestCapabilities.has(account, "devices"))
        }

    @Test
    fun `a server without the endpoint has no features`() =
        runBlocking {
            AnkiquestCapabilities.refresh(account)
            status = 404
            body = """{"error":"not found"}"""
            assertEquals(emptySet(), AnkiquestCapabilities.refresh(account))
            assertFalse(AnkiquestCapabilities.has(account, "cookie_session"))
        }

    @Test
    fun `an unreadable answer replaces the known features with none`() =
        runBlocking {
            AnkiquestCapabilities.refresh(account)
            body = "not json"
            assertEquals(emptySet(), AnkiquestCapabilities.refresh(account))
        }

    @Test
    fun `an unreachable server keeps the features it last reported`() =
        runBlocking {
            AnkiquestCapabilities.refresh(account)
            status = 503
            assertEquals(setOf("activity", "cookie_session", "wins"), AnkiquestCapabilities.refresh(account))
            server.close()
            assertEquals(setOf("activity", "cookie_session", "wins"), AnkiquestCapabilities.refresh(account))
        }

    @Test
    fun `fresh features are reused and asked again once they are old`() =
        runBlocking {
            AnkiquestCapabilities.current(account)
            clock.addM(60)
            body = """{"version":1,"features":["wins"]}"""
            assertEquals(setOf("activity", "cookie_session", "wins"), AnkiquestCapabilities.current(account))
            assertEquals(1, requests.size)
            clock.addM(360)
            assertEquals(setOf("wins"), AnkiquestCapabilities.current(account))
            assertEquals(2, requests.size)
        }

    @Test
    fun `features survive a restart through the stored copy`() =
        runBlocking {
            AnkiquestCapabilities.current(account)
            AnkiquestCapabilities.forgetMemory()
            assertTrue(AnkiquestCapabilities.has(account, "cookie_session"))
            assertEquals(setOf("activity", "cookie_session", "wins"), AnkiquestCapabilities.current(account))
            assertEquals(1, requests.size)
        }

    @Test
    fun `another account or server asks again`() =
        runBlocking {
            AnkiquestCapabilities.current(account)
            body = """{"version":1,"features":["wins"]}"""
            assertEquals(setOf("wins"), AnkiquestCapabilities.current(account.copy(user = "hill", token = "other")))
            assertEquals(2, requests.size)
            val elsewhere = account.copy(server = "${server.url}/other")
            assertFalse(AnkiquestCapabilities.has(elsewhere, "wins"))
            AnkiquestCapabilities.current(elsewhere)
            assertEquals("/other/api/capabilities", requests.last().first)
            assertTrue(AnkiquestCapabilities.has(elsewhere, "wins"))
        }
}
