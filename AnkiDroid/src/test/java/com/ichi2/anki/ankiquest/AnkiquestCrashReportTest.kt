// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.acraCoreConfigBuilder
import com.ichi2.anki.common.crashreporting.CrashReportService
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import okhttp3.Response
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.collector.SharedPreferencesCollector
import org.acra.data.CrashReportData
import org.json.JSONException
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AnkiquestCrashReportTest : RobolectricTest() {
    @Test
    fun `crash reports omit AnkiQuest credentials and account caches`() {
        val keys =
            listOf(
                AnkiquestPreferences.PREF_URL,
                AnkiquestPreferences.PREF_USER,
                AnkiquestPreferences.PREF_TOKEN,
                "ankiquestMomentsPending:account-scope",
                "ankiquestWidgetLeaderboard:account-scope",
                "ankiquestMoodIconProfile:account-scope",
                "ankiquestLastUsername:server-scope",
                "AnkiQuestFuturePrivateCache",
            )
        AnkiDroidApp.sharedPrefs().edit {
            keys.forEach { putString(it, "synthetic-private-value") }
            putLong("ankiquestLastSuccessfulPoll:account-scope", 123L)
        }

        val collected = collect()
        val leaked = (keys + "ankiquestLastSuccessfulPoll:account-scope").filter { collected.has(it) }
        assertEquals(emptyList(), leaked, "private preferences must not enter a crash report")
    }

    @Test
    fun `safe diagnostics remain while existing upstream secrets stay excluded`() {
        AnkiDroidApp.sharedPrefs().edit {
            putString("theme", "light")
            putString("username", "synthetic-ankiweb-user")
            putString("hkey", "synthetic-ankiweb-secret")
            putString("currentSyncUri", "https://private.example")
            putString("browser_search_history", "private search")
        }

        val collected = collect()
        assertEquals("light", collected.getString("theme"))
        for (key in listOf("username", "hkey", "currentSyncUri", "browser_search_history")) {
            assertFalse(collected.has(key), key)
        }
    }

    /**
     * Failures are logged with their exception (`Timber.w(e, ...)`), and logcat goes into crash
     * reports. org.json puts the text it could not read into its message, so a server answer that
     * fails to parse must not carry its token or messages into that exception.
     */
    @Test
    fun `unreadable server answers stay out of logged exceptions`() {
        val answers =
            listOf(
                // Truncated: the syntax error quotes the whole input.
                "{\"user\": \"ana\", \"token\": \"$SECRET\"",
                // Wrong top-level type: the message quotes the value.
                "[\"$SECRET\"]",
            )
        for (answer in answers) {
            val trace = failure(answer) { AnkiquestApi.jsonObject(it) }
            assertFalse(SECRET in trace, trace)
        }

        // A field of the wrong type: the message quotes the field's value.
        val trace = failure("{\"user\": \"$SECRET\"}") { AnkiquestApi.jsonObject(it).getJSONObject("user") }
        assertFalse(SECRET in trace, trace)
    }

    /** The stack trace a log line would print for the exception [read] throws on [answer]. */
    private fun failure(
        answer: String,
        read: (Response) -> Any,
    ): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()
            val error = assertFailsWith<JSONException> { AnkiquestApi.call(request) { read(it) } }
            return StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        } finally {
            server.stop(0)
        }
    }

    private fun collect(): JSONObject {
        val report = CrashReportData()
        SharedPreferencesCollector().collect(
            ReportField.SHARED_PREFERENCES,
            targetContext,
            CrashReportService.acraCoreConfigBuilder.build(),
            ReportBuilder(),
            report,
        )
        return (report[ReportField.SHARED_PREFERENCES.name] as JSONObject).getJSONObject("default")
    }

    companion object {
        private const val SECRET = "synthetic-private-token"
    }
}
