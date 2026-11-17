// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.acraCoreConfigBuilder
import com.ichi2.anki.common.crashreporting.CrashReportService
import com.ichi2.anki.exception.UserSubmittedException
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import okhttp3.Response
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.collector.CustomDataCollector
import org.acra.collector.SharedPreferencesCollector
import org.acra.config.HttpSenderConfiguration
import org.acra.config.ReportingAdministrator
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.plugins.loadEnabled
import org.acra.sender.HttpSender
import org.json.JSONException
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestCrashReportTest : RobolectricTest() {
    /** AnkiQuest builds must never send reports to AnkiDroid's server (#329). */
    @Test
    fun `crash reports go to the AnkiQuest server`() {
        val config = CrashReportService.acraCoreConfigBuilder.build()
        val sender = config.pluginConfigurations.filterIsInstance<HttpSenderConfiguration>().single()

        // Not ankidroid.org or any of its subdomains.
        val host = URI(sender.uri).host
        assertFalse(host == ANKIDROID_HOST || host.endsWith(".$ANKIDROID_HOST"), sender.uri)

        // The server endpoint takes a JSON body by POST.
        assertEquals("${AnkiquestAccount.DEFAULT_SERVER}/api/crash-reports", sender.uri)
        assertEquals(HttpSender.Method.POST, sender.httpMethod)
        assertEquals(StringFormat.JSON, config.reportFormat)
    }

    /** The same crash reaches the server at most twice per device, labelled with its signature. */
    @Test
    fun `the same crash is reported at most twice`() {
        val crash = IllegalStateException("Card 12 missing")
        val builders = List(3) { ReportBuilder().exception(crash) }

        val verdicts = builders.map { administrator().shouldStartCollecting(targetContext, config(), it) }
        assertEquals(listOf(true, true, false), verdicts)

        // The signature goes into the report's custom data.
        val report = CrashReportData()
        CustomDataCollector().collect(ReportField.CUSTOM_DATA, targetContext, config(), builders[0], report)
        val custom = report[ReportField.CUSTOM_DATA.name] as JSONObject
        assertEquals(
            AnkiquestCrashSignature.of(crash, AnkiquestRedaction.of(AnkiDroidApp.sharedPrefs())),
            custom.getString(AnkiquestCrashAdministrator.SIGNATURE_FIELD),
        )

        // A different crash is still reported.
        val other = ReportBuilder().exception(IllegalArgumentException("Card 12 missing"))
        assertTrue(administrator().shouldStartCollecting(targetContext, config(), other))

        // Forgetting the record, as the developer test crash does, lets it through again.
        AnkiquestCrashAdministrator.forget(targetContext)
        assertTrue(administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(crash)))
    }

    /** Cancelling the "Ask me" dialog takes back that report's send, and no other crash's. */
    @Test
    fun `a cancelled report may be sent again`() {
        val cancelled = IllegalStateException("Card 12 missing")
        val other = IllegalArgumentException("Card 12 missing")
        val builders = List(2) { ReportBuilder().exception(cancelled) }
        builders.forEach { assertTrue(administrator().shouldStartCollecting(targetContext, config(), it)) }
        repeat(2) { assertTrue(administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(other))) }

        // The dialog reads the report ACRA saved, with the signature in its custom data.
        val report = CrashReportData()
        CustomDataCollector().collect(ReportField.CUSTOM_DATA, targetContext, config(), builders[1], report)
        AnkiquestCrashAdministrator.cancelled(targetContext, CrashReportData(report.toJSON()))

        assertTrue(administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(cancelled)))
        assertFalse(administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(other)))
    }

    /** ACRA's limiter is cleared on its own; AnkiQuest's record stays. */
    @Test
    fun `clearing ACRA's limiter keeps the record`() {
        val crash = IllegalStateException("Card 12 missing")
        repeat(2) { administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(crash)) }

        CrashReportService.deleteLimiterData(targetContext)

        assertFalse(administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(crash)))
    }

    @Test
    fun `reports the player submits are always sent`() {
        val submitted = UserSubmittedException("Sent by the player")

        val verdicts = List(3) { administrator().shouldStartCollecting(targetContext, config(), ReportBuilder().exception(submitted)) }

        assertEquals(listOf(true, true, true), verdicts)
    }

    @Test
    fun `crash reports omit AnkiQuest credentials and account caches`() {
        val keys =
            listOf(
                Ankiquest.PREF_URL,
                Ankiquest.PREF_USER,
                Ankiquest.PREF_TOKEN,
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

    /** Logs, traces and custom data in a report lose the server's address and any credentials (#216). */
    @Test
    fun `reports leave out server addresses and credentials`() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://$CUSTOM_HOST/")
            putString(Ankiquest.PREF_TOKEN, TOKEN)
        }
        val signature = AnkiquestCrashSignature.hash("synthetic crash")
        val report = CrashReportData()
        report.put(
            ReportField.LOGCAT,
            listOf(
                "W/AnkiDroid: java.net.UnknownHostException: Unable to resolve host \"$CUSTOM_HOST\": No address associated with hostname",
                "W/AnkiDroid: java.net.ConnectException: Failed to connect to $CUSTOM_HOST/$CUSTOM_ADDRESS:443",
                "D/AnkiDroid: --> POST https://$DEFAULT_HOST/api/reviews",
                "D/AnkiDroid: Authorization: Bearer $TOKEN",
                "D/AnkiDroid: Authorization: bearer $SHORT_SECRET",
            ).joinToString("\n"),
        )
        report.put(ReportField.STACK_TRACE, trace(IOException("Unable to resolve host \"$CUSTOM_HOST\"", IOException("token $TOKEN"))))
        report.put(
            ReportField.CUSTOM_DATA,
            JSONObject(
                mapOf(
                    "origin" to "AnkiquestApi",
                    "additionalInfo" to "https://$CUSTOM_HOST/api/profile?t=$TOKEN",
                    AnkiquestCrashAdministrator.SIGNATURE_FIELD to signature,
                ),
            ),
        )

        assertTrue(administrator().shouldSendReport(targetContext, config(), report))

        // No address or credential is left anywhere in the report.
        val sent = report.toJSON()
        for (secret in listOf(CUSTOM_HOST, DEFAULT_HOST, CUSTOM_ADDRESS, TOKEN, SHORT_SECRET)) {
            assertFalse(secret in sent, secret)
        }

        // What helps to diagnose the crash stays.
        val logcat = checkNotNull(report.getString(ReportField.LOGCAT))
        assertTrue("Unable to resolve host \"[server]\": No address associated with hostname" in logcat, logcat)
        assertTrue("Failed to connect to [server]:443" in logcat, logcat)
        assertTrue("Authorization: Bearer [removed]" in logcat, logcat)
        val custom = report[ReportField.CUSTOM_DATA.name] as JSONObject
        assertEquals("AnkiquestApi", custom.getString("origin"))
        assertEquals(signature, custom.getString(AnkiquestCrashAdministrator.SIGNATURE_FIELD))
    }

    /**
     * The server signs the trace it receives, which is redacted, so the device signs the redacted
     * message too; otherwise the same crash gets two signatures.
     */
    @Test
    fun `the signature matches the redacted trace`() {
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_URL, "https://$CUSTOM_HOST") }
        val crash = IOException("Unable to resolve host \"$CUSTOM_HOST\": Bearer $TOKEN")

        val (signature, trace) = signedTrace(crash)

        // The server reads the class and message from the first line, and the frames below it.
        val message = "Unable to resolve host \"[server]\": Bearer [removed]"
        assertEquals("java.io.IOException: $message", trace.lines().first())
        val canonical = AnkiquestCrashSignature.canonical("java.io.IOException", message, crash.stackTrace.toList())
        assertEquals(AnkiquestCrashSignature.hash(canonical), signature)
    }

    /**
     * The server's error text can quote what the player sent. Logged traces leave it out; the
     * screens that show it read [AnkiquestHttpException.explanation] or the message.
     */
    @Test
    fun `server error text stays out of logged exceptions`() {
        val error = AnkiquestHttpException(HTTP_FORBIDDEN, "No player named $SECRET")

        val (signature, trace) = signedTrace(error)

        assertFalse(SECRET in trace, trace)
        assertEquals("No player named $SECRET", error.explanation)
        assertEquals("No player named $SECRET", error.message)

        // The signature follows the trace the server receives.
        val canonical = AnkiquestCrashSignature.canonical(error.javaClass.name, "HTTP $HTTP_FORBIDDEN", error.stackTrace.toList())
        assertEquals(AnkiquestCrashSignature.hash(canonical), signature)
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
            return trace(assertFailsWith<JSONException> { AnkiquestApi.call(request) { read(it) } })
        } finally {
            server.stop(0)
        }
    }

    /** The text ACRA puts in `STACK_TRACE` for [error]. */
    private fun trace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    /** The signature the device sends for [crash], and the trace as it leaves the device. */
    private fun signedTrace(crash: Throwable): Pair<String, String> {
        val builder = ReportBuilder().exception(crash)
        assertTrue(administrator().shouldStartCollecting(targetContext, config(), builder))

        // ACRA collects the custom data and the trace, then asks whether to send them.
        val report = CrashReportData()
        CustomDataCollector().collect(ReportField.CUSTOM_DATA, targetContext, config(), builder, report)
        report.put(ReportField.STACK_TRACE, trace(crash))
        assertTrue(administrator().shouldSendReport(targetContext, config(), report))

        val custom = report[ReportField.CUSTOM_DATA.name] as JSONObject
        return custom.getString(AnkiquestCrashAdministrator.SIGNATURE_FIELD) to checkNotNull(report.getString(ReportField.STACK_TRACE))
    }

    private fun config() = CrashReportService.acraCoreConfigBuilder.build()

    /** The administrator ACRA loads with the production configuration. */
    private fun administrator(): AnkiquestCrashAdministrator {
        val config = config()
        return config.pluginLoader
            .loadEnabled<ReportingAdministrator>(config)
            .filterIsInstance<AnkiquestCrashAdministrator>()
            .single()
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
        private const val ANKIDROID_HOST = "ankidroid.org"

        /** A server the player set instead of the default one, and the address it resolved to. */
        private const val CUSTOM_HOST = "quest.example.net"
        private const val CUSTOM_ADDRESS = "203.0.113.7"

        /** The device token format: 32 random bytes in lowercase hex. */
        private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** A credential too short to look like a token, recognised by the word in front of it. */
        private const val SHORT_SECRET = "short-secret"
        private val DEFAULT_HOST = checkNotNull(URI(AnkiquestAccount.DEFAULT_SERVER).host)
    }
}
