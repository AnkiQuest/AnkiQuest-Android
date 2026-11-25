// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.view.View
import androidx.core.view.isVisible
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooper
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooperUntil
import com.ichi2.anki.ankiquest.ui.R
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import com.ichi2.anki.R as AnkiDroidR

/** One request an [AnkiquestTestServer] received, with its whole body. */
internal class TestRequest(
    val method: String,
    val uri: URI,
    val headers: Headers,
    val bytes: ByteArray,
) {
    val path: String get() = uri.path
    val body: String get() = bytes.decodeToString()
    val authorization: String? get() = headers.getFirst("Authorization")

    /** "METHOD uri", as tests list requests. */
    override fun toString() = "$method $uri"
}

/** What an [AnkiquestTestServer] answers; an empty or missing body is sent without one. */
internal class TestResponse(
    val status: Int = HTTP_OK,
    val bytes: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        private const val CONTENT_TYPE = "Content-Type"
        private const val JSON = "application/json"

        /** [body] as JSON text. */
        fun json(
            body: Any,
            status: Int = HTTP_OK,
            headers: Map<String, String> = emptyMap(),
        ) = TestResponse(status, body.toString().toByteArray(Charsets.UTF_8), mapOf(CONTENT_TYPE to JSON) + headers)
    }
}

/**
 * A local HTTP server for AnkiQuest tests. Every request is kept in [requests], then answered by
 * [answer]; null answers `404`. Close it after the test.
 */
internal class AnkiquestTestServer(
    private val answer: (TestRequest) -> TestResponse?,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<TestRequest>()

    private val server = HttpServer.create(InetSocketAddress(HOST, 0), 0)

    /** The server's address, without a trailing slash. */
    val url: String

    init {
        server.createContext("/") { exchange -> exchange.use(::reply) }
        server.start()
        url = "http://$HOST:${server.address.port}"
    }

    private fun reply(exchange: HttpExchange) {
        val request =
            TestRequest(
                exchange.requestMethod,
                exchange.requestURI,
                exchange.requestHeaders,
                exchange.requestBody.use { it.readBytes() },
            )
        requests += request
        val response = answer(request) ?: TestResponse(HTTP_NOT_FOUND)
        for ((name, value) in response.headers) {
            exchange.responseHeaders.set(name, value)
        }

        // A length of -1 tells the server there is no body; 0 would mean a chunked one.
        val bytes = response.bytes
        if (bytes == null || bytes.isEmpty()) {
            exchange.sendResponseHeaders(response.status, NO_BODY)
            return
        }
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    private companion object {
        const val HOST = "127.0.0.1"
        const val NO_BODY = -1L
    }
}

/** Waits until the Friends screen's board and feed have loaded. */
internal fun AnkiquestFriendsActivity.awaitBoard() {
    advanceRobolectricLooperUntil {
        !findViewById<View>(R.id.aq_friends_board_loading).isVisible &&
            !findViewById<View>(AnkiDroidR.id.aq_feed_loading).isVisible
    }
    advanceRobolectricLooper()
}
