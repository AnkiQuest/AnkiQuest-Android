// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.State
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Unlock
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hidden companion: unlocking it, keeping it, and syncing it with old and new servers. */
@RunWith(AndroidJUnit4::class)
class AnkiquestUnlocksTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var account: HomeAccount
    private val requests = CopyOnWriteArrayList<String>()

    // What the fake server holds.
    @Volatile private var features = listOf<String>()

    @Volatile private var unlocks = setOf<String>()

    @Volatile private var companion = "aki"

    /** The unlocks cannot be reached, as when the phone went offline after the capabilities check. */
    @Volatile private var offline = false

    @Before
    fun serve() {
        AnkiquestCapabilities.forgetMemory()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val sent = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += "${exchange.requestMethod} $path $sent".trim()
            val answer =
                when {
                    path == "/api/capabilities" -> JSONObject().put("version", 1).put("features", JSONArray(features))
                    path.startsWith("/api/unlocks/") && AnkiquestUnlocks.FEATURE in features && !offline -> {
                        if (sent.isNotEmpty()) {
                            unlocks = unlocks + JSONObject(sent).getString("unlock")
                        }
                        JSONObject().put("unlocks", JSONArray(unlocks.sorted()))
                    }
                    path.startsWith("/api/companion/") -> {
                        if (sent.isNotEmpty()) {
                            companion = JSONObject(sent).getString("companion")
                        }
                        JSONObject().put("companion", companion)
                    }
                    else -> null
                }
            val bytes = answer?.toString()?.toByteArray()
            if (bytes == null) {
                exchange.sendResponseHeaders(NOT_FOUND, -1)
            } else {
                exchange.sendResponseHeaders(OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        account = signIn("cerro")
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        AnkiquestCapabilities.forgetMemory()
    }

    private fun signIn(user: String): HomeAccount {
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, user)
            putString(Ankiquest.PREF_TOKEN, "token-$user")
        }
        return requireNotNull(AnkiquestHomeData.account())
    }

    /** The server reports [feature] from now on, as the app learns at its next capabilities check. */
    private fun serverGains(feature: String) =
        runBlocking {
            features = features + feature
            AnkiquestCapabilities.refresh(account)
        }

    /** One case of the shared fixture: taps as (choice, milliseconds), and whether the last one unlocks. */
    private data class TapCase(
        val name: String,
        val sequence: List<Pair<String, Long>>,
        val unlocks: Boolean,
    )

    /**
     * The cases of `tests/fixtures/companion_unlock.json` in float3/ankiquest, copied until the pinned
     * server commit (`ankiquest-server.sha`) includes the fixture; then this reads it.
     */
    private val tapCases =
        listOf(
            TapCase("nine quick taps unlock", ankilope(0, 300, 600, 900, 1200, 1500, 1800, 2100, 2400), unlocks = true),
            TapCase("eight taps are not enough", ankilope(0, 300, 600, 900, 1200, 1500, 1800, 2100), unlocks = false),
            TapCase(
                "a gap of exactly max_gap_ms still counts",
                ankilope(0, 2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000),
                unlocks = true,
            ),
            TapCase("a longer gap starts again", ankilope(0, 300, 600, 900, 3000, 3300, 3600, 3900, 4200), unlocks = false),
            TapCase(
                "the late tap is the first of the new run",
                ankilope(0, 5000, 5100, 5200, 5300, 5400, 5500, 5600, 5700, 5800),
                unlocks = true,
            ),
            TapCase(
                "a tap on another choice starts again",
                ankilope(0, 100, 200, 300) + ("aki" to 400L) + ankilope(500, 600, 700, 800),
                unlocks = false,
            ),
            TapCase(
                "taps on other choices never unlock",
                listOf<Long>(0, 100, 200, 300, 400).map { "aki" to it } + listOf<Long>(500, 600, 700, 800).map { "none" to it },
                unlocks = false,
            ),
        )

    private fun ankilope(vararg times: Long) = times.map { "ankilope" to it }

    @Test
    fun `the tap rule matches the shared fixture`() {
        assertEquals(UnlockTapRule(taps = 9, maxGapMs = 2000), UnlockTapRule.COW)
        for (case in tapCases) {
            val taps = UnlockTaps(UnlockTapRule.COW)
            val last = case.sequence.map { (choice, at) -> taps.tap(Choice.of(choice), at) }.last()
            assertEquals(case.unlocks, last == UnlockTaps.Progress.UNLOCKED, case.name)
        }
    }

    @Test
    fun `the cow is hidden until unlocked and the unlock belongs to the account`() {
        assertEquals(listOf(Choice.AKI, Choice.ANKILOPE, Choice.NONE), AnkiquestCompanion.choices(account))
        assertEquals(listOf(Choice.AKI, Choice.ANKILOPE, Choice.NONE), AnkiquestCompanion.choices(null))

        AnkiquestUnlocks.unlock(account, Unlock.COW)
        assertEquals(State.LOCAL, AnkiquestUnlocks.state(account, Unlock.COW))
        assertEquals(listOf(Choice.AKI, Choice.ANKILOPE, Choice.COW, Choice.NONE), AnkiquestCompanion.choices(account))

        val other = signIn("other")
        assertFalse(AnkiquestUnlocks.has(other, Unlock.COW))

        // Signing in again with a new token keeps the unlock: it belongs to the player, not the token.
        AnkiDroidApp.sharedPrefs().edit(commit = true) { putString(Ankiquest.PREF_USER, "cerro") }
        AnkiDroidApp.sharedPrefs().edit(commit = true) { putString(Ankiquest.PREF_TOKEN, "renewed") }
        assertTrue(AnkiquestUnlocks.has(requireNotNull(AnkiquestHomeData.account()), Unlock.COW))
    }

    @Test
    fun `an old server offers no unlock`() =
        runBlocking {
            AnkiquestCapabilities.refresh(account)
            assertFalse(AnkiquestUnlocks.available(account))

            serverGains(AnkiquestUnlocks.FEATURE)
            assertTrue(AnkiquestUnlocks.available(account))
        }

    @Test
    fun `an unlock made offline stays on this phone and is sent with the choice later`() =
        runBlocking {
            serverGains(AnkiquestUnlocks.FEATURE)
            offline = true
            AnkiquestUnlocks.unlock(account, Unlock.COW)

            assertEquals(Choice.COW, AnkiquestCompanion.choose(account, Choice.COW))
            assertEquals(Choice.COW, AnkiquestCompanion.choice())
            assertEquals(State.LOCAL, AnkiquestUnlocks.state(account, Unlock.COW))
            assertEquals("aki", companion)

            // Back online: the unlock goes first, since the server refuses a cow it does not know.
            offline = false
            requests.clear()
            AnkiquestUnlocks.sync(account)

            assertEquals(State.SYNCED, AnkiquestUnlocks.state(account, Unlock.COW))
            assertEquals(setOf("cow"), unlocks)
            assertEquals("cow", companion)
            val unlock = requests.indexOfFirst { it.startsWith("POST /api/unlocks/cerro") }
            val choice = requests.indexOfFirst { it.startsWith("POST /api/companion/cerro") }
            assertTrue(unlock in 0 until choice, "$requests")
            assertEquals(Choice.COW, AnkiquestCompanion.choice())
        }

    @Test
    fun `the server's answer does not undo a cow it does not know yet`() =
        runBlocking {
            serverGains(AnkiquestUnlocks.FEATURE)
            offline = true
            AnkiquestUnlocks.unlock(account, Unlock.COW)
            AnkiquestCompanion.choose(account, Choice.COW)

            // Only the unlocks are out of reach; the server still answers its old choice.
            assertFalse(AnkiquestCompanion.refresh(account))
            assertEquals(Choice.COW, AnkiquestCompanion.choice())
        }

    @Test
    fun `unlocks from another device arrive without settings sync`() =
        runBlocking {
            serverGains(AnkiquestUnlocks.FEATURE)
            unlocks = setOf("cow")
            companion = "cow"

            assertTrue(AnkiquestCompanion.refresh(account))

            assertEquals(State.SYNCED, AnkiquestUnlocks.state(account, Unlock.COW))
            assertEquals(Choice.COW, AnkiquestCompanion.choice())
            assertTrue(requests.none { it.startsWith("POST") }, "$requests")
        }

    @Test
    fun `a synced cow is saved on the server`() =
        runBlocking {
            serverGains(AnkiquestUnlocks.FEATURE)
            AnkiquestUnlocks.unlock(account, Unlock.COW)

            assertEquals(Choice.COW, AnkiquestCompanion.choose(account, Choice.COW))

            assertEquals(setOf("cow"), unlocks)
            assertEquals("cow", companion)
        }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
