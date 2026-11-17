// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Replaces AnkiQuest server hosts with [SERVER] and credentials with [REMOVED] in crash report
 * text (#216). Hosts reach logs through OkHttp's messages ("Unable to resolve host ..."), and
 * tokens through `Authorization` headers and quoted answers.
 *
 * A credential is the stored device token, the word after "Bearer", or a run of at least
 * [MIN_TOKEN_LEN] token characters mixing letters and digits: the rule and the placeholder the
 * server applies to stored reports (AnkiQuest-Server#13, `crash_reports.rs`).
 *
 * Plain character scans, no regular expressions, so Android's ICU and the JVM agree.
 */
internal class AnkiquestRedaction(
    hosts: Collection<String>,
    secrets: Collection<String>,
) {
    private val hosts = hosts.filter { it.isNotBlank() }.map { it.lowercase() }.distinct()
    private val secrets = secrets.filter { it.length >= MIN_SECRET_LEN }.distinct()

    /** [value] without server hosts and credentials. */
    fun text(value: String): String {
        var clean = value
        for (secret in secrets) {
            clean = clean.replace(secret, REMOVED)
        }
        for (host in hosts) {
            clean = withoutHost(clean, host)
        }
        return withoutTokens(clean)
    }

    /**
     * A Java stack trace ([Throwable.printStackTrace]) with each line but the frames redacted. Frames
     * hold only class and method names, which the crash signature hashes, so they stay as they are.
     */
    fun trace(value: String): String = value.split('\n').joinToString("\n") { if (isFrame(it)) it else text(it) }

    /** [text] with each whole occurrence of [host], and the address after it, replaced by [SERVER]. */
    private fun withoutHost(
        text: String,
        host: String,
    ): String {
        val out = StringBuilder(text.length)
        var copied = 0
        var at = text.indexOf(host, ignoreCase = true)
        while (at >= 0) {
            val end = at + host.length

            // Only a whole name: not part of a longer host such as sub.example.net or example.network.
            val whole = (at == 0 || !isHostChar(text[at - 1])) && (end == text.length || !isLabelChar(text[end]))
            if (whole) {
                out.append(text, copied, at).append(SERVER)
                copied = addressEnd(text, end)
            }
            at = text.indexOf(host, end, ignoreCase = true)
        }
        if (copied == 0) {
            return text
        }
        return out.append(text, copied, text.length).toString()
    }

    companion object {
        /** What replaces a server host. */
        const val SERVER = "[server]"

        /** What replaces a credential; the server's placeholder. */
        const val REMOVED = "[removed]"

        /** The shortest run of token characters treated as a credential, as on the server. */
        private const val MIN_TOKEN_LEN = 32

        /** A stored token shorter than this is not replaced on its own: it would match ordinary words. */
        private const val MIN_SECRET_LEN = 8

        /** The word in front of a credential in an `Authorization` header. */
        private const val BEARER = "bearer"

        /** What starts a frame line of a stack trace, after its indentation. */
        private const val FRAME_PREFIX = "at "

        /** Dots in an IPv4 address. */
        private const val IPV4_DOTS = 3

        /** The fewest colons in an IPv6 address. */
        private const val IPV6_COLONS = 2

        /** The redaction for this device: the default server, and the server and token the player set. */
        fun of(prefs: SharedPreferences): AnkiquestRedaction {
            val custom =
                prefs
                    .getString(Ankiquest.PREF_URL, null)
                    ?.trim()
                    ?.toHttpUrlOrNull()
                    ?.host
            val defaultHost = AnkiquestAccount.DEFAULT_SERVER.toHttpUrlOrNull()?.host
            return AnkiquestRedaction(listOfNotNull(defaultHost, custom), listOfNotNull(prefs.getString(Ankiquest.PREF_TOKEN, null)))
        }

        /** Whether [line] is a frame, read the way the server reads it. */
        private fun isFrame(line: String): Boolean = line.trimStart().startsWith(FRAME_PREFIX)

        private fun isLabelChar(c: Char): Boolean = c.isAsciiLetterOrDigit() || c == '-'

        private fun isHostChar(c: Char): Boolean = isLabelChar(c) || c == '.'

        private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

        /**
         * Where the address after a host ends: Java prints a resolved host as `host/203.0.113.7`, so
         * an IP address after a slash goes with the host. [from] when there is none.
         */
        private fun addressEnd(
            text: String,
            from: Int,
        ): Int {
            if (from >= text.length || text[from] != '/') {
                return from
            }

            // IPv4: digits and three dots; a port after it stays.
            val v4 = run(text, from + 1) { it in '0'..'9' || it == '.' }
            if (text.substring(from + 1, v4).count { it == '.' } == IPV4_DOTS) {
                return v4
            }

            // IPv6: hex digits and colons.
            val v6 = run(text, from + 1) { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
            if (text.substring(from + 1, v6).count { it == ':' } >= IPV6_COLONS) {
                return v6
            }
            return from
        }

        /** The end of the run of characters from [from] that match [test]. */
        private fun run(
            text: String,
            from: Int,
            test: (Char) -> Boolean,
        ): Int {
            var end = from
            while (end < text.length && test(text[end])) {
                end++
            }
            return end
        }

        /** Characters of hex, base64url and similar credentials. */
        private fun isTokenChar(c: Char): Boolean = c.isAsciiLetterOrDigit() || c == '-' || c == '_'

        /** A run of token characters that is a credential: long and mixing letters and digits. */
        private fun looksLikeToken(word: String): Boolean =
            word.length >= MIN_TOKEN_LEN && word.any { it in '0'..'9' } && word.any { it in 'a'..'z' || it in 'A'..'Z' }

        /** [text] with long token-like words, and the word after "Bearer", replaced by [REMOVED]. */
        private fun withoutTokens(text: String): String {
            val out = StringBuilder(text.length)
            var afterBearer = false
            var at = 0
            while (at < text.length) {
                // Copy what separates words; a credential follows "Bearer" after spaces only.
                val start = run(text, at) { !isTokenChar(it) }
                val separator = text.substring(at, start)
                out.append(separator)
                afterBearer = afterBearer && separator.isNotEmpty() && separator.all { it == ' ' }

                // Replace the next word if it is a credential.
                val end = run(text, start, ::isTokenChar)
                if (end == start) {
                    break
                }
                val word = text.substring(start, end)
                out.append(if (afterBearer || looksLikeToken(word)) REMOVED else word)
                afterBearer = word.equals(BEARER, ignoreCase = true)
                at = end
            }
            return out.toString()
        }
    }
}
