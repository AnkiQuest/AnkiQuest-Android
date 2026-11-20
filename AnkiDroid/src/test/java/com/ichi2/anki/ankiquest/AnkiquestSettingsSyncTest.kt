// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestSettingsSync.Entry
import com.ichi2.anki.ankiquest.AnkiquestSettingsSync.Local
import com.ichi2.anki.ankiquest.AnkiquestSettingsSync.Setting
import com.ichi2.anki.ankiquest.AnkiquestSettingsSync.Trigger
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
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
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings sync against `tests/fixtures/synced_settings.json` from the ankiquest server, and a server that merges like it. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestSettingsSyncTest : RobolectricTest() {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource(FIXTURE)) { "missing $FIXTURE" }.readText(),
        )
    private lateinit var server: HttpServer
    private val clock = MockTime(NOW)
    private val requests = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<JSONObject>()

    /** What the fake server keeps: key -> {value, updated_at}. */
    private val stored = JSONObject()

    @Volatile private var features = """["settings_sync"]"""

    /** Runs while the server answers a read, as a player changing a setting meanwhile. */
    @Volatile private var duringRead: () -> Unit = {}

    /** Runs while the server answers a capability request. */
    @Volatile private var duringCapabilities: () -> Unit = {}

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        TimeManager.resetWith(clock)
        AnkiquestCapabilities.forgetMemory()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += "${exchange.requestMethod} $path"
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val answer =
                when {
                    path == "/api/capabilities" -> {
                        duringCapabilities()
                        """{"version":1,"features":$features}"""
                    }
                    exchange.requestMethod == "GET" -> {
                        duringRead()
                        JSONObject().put("settings", stored).toString()
                    }
                    else -> {
                        val incoming = JSONObject(body).getJSONObject("settings")
                        sent += incoming
                        store(incoming)
                        JSONObject().put("settings", stored).toString()
                    }
                }.toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
            exchange.close()
        }
        server.start()
        targetContext.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "cerro")
            putString(Ankiquest.PREF_TOKEN, "secret")
        }
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        AnkiquestCapabilities.forgetMemory()
        SharedPreferencesTestHook.override = null
    }

    /** The server's rule: per key, the later time wins and a tie keeps the stored value. */
    private fun store(incoming: JSONObject) {
        for (key in incoming.keys()) {
            val entry = incoming.getJSONObject(key)
            val old = stored.optJSONObject(key)
            if (old == null || entry.getLong("updated_at") > old.getLong("updated_at")) {
                stored.put(key, entry)
            }
        }
    }

    private fun entry(
        value: Any,
        at: Long,
    ) = JSONObject().put("value", value).put("updated_at", at)

    private fun records(): JSONObject = JSONObject(assertNotNull(prefs().getString(AnkiquestSettingsSync.PREF_CHANGED, null)))

    private fun prefs() = targetContext.sharedPrefs()

    private fun switchOff() = prefs().edit(commit = true) { putBoolean(AnkiquestSettingsSync.PREF_SWITCH, false) }

    private fun account() = assertNotNull(AnkiquestHomeData.account())

    private fun cases(name: String): List<JSONObject> = fixture.getJSONArray(name).objects()

    @Test
    fun `the synced keys are the fixture's Android keys`() {
        val keys = cases("keys").filter { !it.isNull("android") }
        assertEquals(keys.map { it.getString("key") }.toSet(), Setting.entries.map { it.key }.toSet())
        for (row in keys) {
            val setting = Setting.entries.single { it.key == row.getString("key") }
            assertEquals(row.getString("android"), setting.pref, row.toString())
            assertEquals(row.get("default"), setting.default, row.toString())
            assertTrue(setting.usable(row.get("default")), row.toString())
            when (row.getString("type")) {
                "bool" -> assertFalse(setting.usable(1), row.toString())
                "int" -> {
                    assertTrue(setting.usable(row.getInt("min")) && setting.usable(row.getInt("max")), row.toString())
                    assertFalse(setting.usable(row.getInt("min") - 1) || setting.usable(row.getInt("max") + 1), row.toString())
                    assertFalse(setting.usable(true), row.toString())
                }
                else -> error("unknown type in $row")
            }
        }

        // The settings screen stores these under its own resource keys.
        assertEquals(Setting.MOOD_ICON.pref, targetContext.getString(R.string.ankiquest_mood_icon_key))
        assertEquals(Setting.OPEN_TODAY.pref, targetContext.getString(R.string.ankiquest_open_today_key))
        assertEquals(Setting.ASK_TO_SHARE_DECKS.pref, targetContext.getString(R.string.ankiquest_deck_share_ask_key))
        assertEquals(Setting.STREAK_CUE.pref, targetContext.getString(R.string.ankiquest_streak_cue_key))
    }

    @Test
    fun `the switch matches the fixture and the desktop wording`() {
        val switch = fixture.getJSONObject("switch")
        assertEquals(switch.getString("android"), AnkiquestSettingsSync.PREF_SWITCH)
        assertEquals(switch.getString("android"), targetContext.getString(R.string.ankiquest_sync_settings_key))
        assertEquals(switch.getString("title"), targetContext.getString(R.string.ankiquest_sync_settings_title))
        assertEquals(switch.getString("summary"), targetContext.getString(R.string.ankiquest_sync_settings_summary))
        assertEquals(switch.getBoolean("default"), AnkiquestSettingsSync.enabled(prefs()))
    }

    @Test
    fun `merging matches the fixture's client cases`() {
        val cases = cases("client_merge")
        check(cases.isNotEmpty())
        for (case in cases) {
            val setting = Setting.entries.single { it.key == case.getString("key") }
            val local = case.getJSONObject("local").let { Local(it.get("value"), it.getLong("changed_at")) }
            val remote = case.optJSONObject("remote")?.let { Entry(it.get("value"), it.getLong("updated_at")) }
            val result = AnkiquestSettingsSync.merge(setting, local, remote)
            val expected = case.getJSONObject("expected")
            assertEquals(expected.get("value"), result.value, case.getString("name"))
            assertEquals(expected.getLong("changed_at"), result.changedAt, case.getString("name"))
            val push = expected.optJSONObject("push")
            assertEquals(push?.let { Entry(it.get("value"), it.getLong("updated_at")) }, result.push, case.getString("name"))
        }
    }

    @Test
    fun `answers keep well-formed entries only`() {
        val answer =
            JSONObject(
                """{"settings":{"notify_rank":{"value":false,"updated_at":5},"streak_warning_hours":{"value":2.5,"updated_at":5},
                "session_summary":{"value":true,"updated_at":-1},"mood_icon":{"updated_at":5},"open_today":7,
                "future_setting":{"value":{"a":1},"updated_at":5}}}""",
            )
        val entries = AnkiquestSettingsSync.entries(answer)
        assertEquals(setOf("notify_rank", "streak_warning_hours", "future_setting"), entries.keys)
        assertEquals(Entry(false, 5), entries["notify_rank"])
        assertFalse(Setting.STREAK_WARNING_HOURS.usable(entries.getValue("streak_warning_hours").value), "a fraction is not usable")
        assertTrue(AnkiquestSettingsSync.entries(JSONObject()).isEmpty())
    }

    @Test
    fun `newer server values are applied and newer local ones sent, with the server's time kept`() =
        runBlocking {
            stored.put("notify_rank", entry(false, NOW - 5_000))
            stored.put("streak_warning_hours", entry(6, NOW - 5_000))
            stored.put("future_setting", entry("kept", NOW - 5_000))
            stored.put("open_today", entry(false, NOW - 9_000))
            prefs().edit(commit = true) { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true) }
            assertTrue(AnkiquestSettingsSync.note(Setting.OPEN_TODAY))
            // A choice made before sync existed and different from the default goes up as the oldest change.
            prefs().edit(commit = true) { putBoolean(AnkiquestDeckShare.PREF_ASK, false) }

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            assertFalse(prefs().getBoolean(AnkiquestNotifier.PREF_RANK, true))
            assertEquals("6", prefs().getString(AnkiquestNotifier.PREF_STREAK_HOURS, null), "kept as text")
            assertTrue(prefs().getBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, false))
            val push = sent.single()
            assertEquals(setOf("open_today", "ask_to_share_decks"), push.keys().asSequence().toSet())
            assertEquals(NOW, push.getJSONObject("open_today").getLong("updated_at"))
            assertEquals(0L, push.getJSONObject("ask_to_share_decks").getLong("updated_at"))
            assertEquals("kept", stored.getJSONObject("future_setting").getString("value"), "unknown keys stay on the server")

            val records = records()
            assertEquals(NOW - 5_000, records.getJSONObject("notify_rank").getLong("at"))
            assertEquals(NOW, records.getJSONObject("open_today").getLong("at"))
            assertEquals(0L, records.getJSONObject("session_summary").getLong("at"))
            assertNull(records.optJSONObject("future_setting"))
        }

    @Test
    fun `a change from a fast clock takes the server's time, so it is not sent again`() =
        runBlocking {
            prefs().edit(commit = true) { putBoolean(AnkiquestNotifier.PREF_RANK, false) }
            assertTrue(AnkiquestSettingsSync.note(Setting.NOTIFY_RANK))
            // The server stores a future time as its own now.
            server.removeContext("/")
            server.createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                val answer =
                    when {
                        exchange.requestURI.path == "/api/capabilities" -> """{"version":1,"features":["settings_sync"]}"""
                        exchange.requestMethod == "GET" -> """{"settings":{}}"""
                        else -> """{"settings":{"notify_rank":{"value":false,"updated_at":${NOW - 1_000}}}}"""
                    }.toByteArray()
                exchange.sendResponseHeaders(200, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
                exchange.close()
            }

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            assertEquals(NOW - 1_000, records().getJSONObject("notify_rank").getLong("at"))
        }

    @Test
    fun `a setting changed while the server answers keeps its value and is sent next time`() =
        runBlocking {
            stored.put("streak_warning_hours", entry(6, NOW - 5_000))
            duringRead = {
                prefs().edit(commit = true) { putString(AnkiquestNotifier.PREF_STREAK_HOURS, "3") }
                AnkiquestSettingsSync.note(Setting.STREAK_WARNING_HOURS)
            }

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            assertEquals("3", prefs().getString(AnkiquestNotifier.PREF_STREAK_HOURS, null))
            assertEquals(NOW, records().getJSONObject("streak_warning_hours").getLong("at"))

            duringRead = {}
            clock.addM(1)
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            assertEquals(3, stored.getJSONObject("streak_warning_hours").getInt("value"))
            assertEquals("3", prefs().getString(AnkiquestNotifier.PREF_STREAK_HOURS, null))
        }

    @Test
    fun `with the switch off nothing is sent or applied, and changes still get their time`() =
        runBlocking {
            stored.put("notify_rank", entry(false, NOW - 5_000))
            prefs().edit(commit = true) {
                putBoolean(AnkiquestSettingsSync.PREF_SWITCH, false)
                putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true)
            }
            assertTrue(AnkiquestSettingsSync.note(Setting.OPEN_TODAY))

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            assertTrue(requests.none { it.contains("/api/settings/") })
            assertTrue(prefs().getBoolean(AnkiquestNotifier.PREF_RANK, true))
            assertEquals(NOW, records().getJSONObject("open_today").getLong("at"))
        }

    @Test
    fun `switching sync off during a read prevents the following write`() =
        runBlocking {
            prefs().edit(commit = true) { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true) }
            assertTrue(AnkiquestSettingsSync.note(Setting.OPEN_TODAY))
            duringRead = { switchOff() }

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            assertEquals(listOf("GET /api/capabilities", "GET /api/settings/cerro"), requests.toList())
            assertTrue(sent.isEmpty(), "the switch stopped outgoing changes")
        }

    @Test
    fun `switching sync off while capabilities are fetched prevents the read`() =
        runBlocking {
            prefs().edit(commit = true) { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true) }
            assertTrue(AnkiquestSettingsSync.note(Setting.OPEN_TODAY))
            duringCapabilities = { switchOff() }

            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            assertEquals(listOf("GET /api/capabilities"), requests.toList())
            assertTrue(sent.isEmpty(), "the switch stopped outgoing changes")
        }

    @Test
    fun `changes held back by the switch are sent once it is on again`() =
        runBlocking {
            prefs().edit(commit = true) { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true) }
            assertTrue(AnkiquestSettingsSync.note(Setting.OPEN_TODAY))
            duringRead = { switchOff() }
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            assertTrue(sent.isEmpty())

            // The value and its change time wait here until the switch is on again.
            assertEquals(NOW, records().getJSONObject("open_today").getLong("at"))
            duringRead = {}
            clock.addM(1)
            prefs().edit(commit = true) { putBoolean(AnkiquestSettingsSync.PREF_SWITCH, true) }
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)

            val push = sent.single()
            assertEquals(entry(true, NOW).toString(), push.getJSONObject("open_today").toString())
            assertTrue(prefs().getBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, false))
        }

    @Test
    fun `a server without settings sync is not asked`() =
        runBlocking {
            features = """["wins"]"""
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            assertEquals(listOf("GET /api/capabilities"), requests.toList())
        }

    @Test
    fun `the regular poll syncs at most once a minute`() =
        runBlocking {
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            AnkiquestSettingsSync.sync(targetContext, Trigger.POLL)
            assertEquals(1, requests.count { it == "GET /api/settings/cerro" })
            clock.addM(1)
            AnkiquestSettingsSync.sync(targetContext, Trigger.POLL)
            assertEquals(2, requests.count { it == "GET /api/settings/cerro" })
        }

    @Test
    fun `a value applied by a sync is not taken for a change made here`() =
        runBlocking {
            stored.put("mood_icon", entry(false, NOW - 5_000))
            AnkiquestSettingsSync.sync(targetContext, Trigger.START)
            assertFalse(prefs().getBoolean(AnkiquestIcon.PREF_ENABLED, true))
            assertFalse(AnkiquestSettingsSync.note(Setting.MOOD_ICON))
            assertEquals(NOW - 5_000, records().getJSONObject("mood_icon").getLong("at"))
        }

    @Test
    fun `changing a synced setting sends it`() {
        runBlocking { AnkiquestCapabilities.refresh(account()) }
        AnkiquestSettingsSync.listen(ApplicationProvider.getApplicationContext<Application>())
        prefs().edit(commit = true) { putBoolean(AnkiquestStreakCue.PREF_ENABLED, false) }

        val deadline = System.currentTimeMillis() + WAIT_MS
        while (sent.none { it.has("streak_cue") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val push = assertNotNull(sent.firstOrNull { it.has("streak_cue") }, "sent after the change")
        assertEquals(entry(false, NOW).toString(), push.getJSONObject("streak_cue").toString())
    }

    private companion object {
        const val NOW = 1_793_183_400_000L
        const val WAIT_MS = 5_000L
        const val FIXTURE = "ankiquest/synced_settings.json"
    }
}
