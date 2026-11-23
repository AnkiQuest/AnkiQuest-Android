// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestCelebrationsTest : RobolectricTest() {
    private lateinit var server: AnkiquestTestServer

    /** The bodies of the settings saved. */
    private val posted get() = server.requests.filter { it.method == "POST" && it.path == "/api/decks/hill" }.map { it.body }

    @Volatile
    private var settings = """{"decks":[],"recipients":[],"nudges":false}"""

    @Before
    fun startServer() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        server =
            AnkiquestTestServer { request ->
                if (request.path != "/api/decks/hill") {
                    return@AnkiquestTestServer null
                }
                if (request.method == "POST") {
                    settings = JSONObject(settings).put("celebrations", JSONObject(request.body).getBoolean("celebrations")).toString()
                }
                TestResponse.json(settings)
            }
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, server.url)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) {
            server.close()
        }
        SharedPreferencesTestHook.override = null
    }

    @Test
    fun `celebrations default to on for servers without the setting`() =
        runBlocking {
            assertTrue(Ankiquest.celebrations(Ankiquest.notificationSwitches()))
        }

    @Test
    fun `turning celebrations off only sends that choice`() =
        runBlocking {
            assertFalse(Ankiquest.setNotificationSwitch(Ankiquest.CELEBRATIONS, false).getBoolean("celebrations"))
            assertFalse(Ankiquest.celebrations(Ankiquest.notificationSwitches()))
            val body = JSONObject(posted.single())
            assertEquals(0, body.getJSONArray("decks").length())
            assertFalse(body.has("nudges"), "leaves nudges as they were")
        }
}
