// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import java.security.MessageDigest

/**
 * Identifies a crash independently of the player and the build, exactly as the server does
 * (`tests/fixtures/crash_signatures.json`): the lowercase hex SHA-256 of the exception class, its
 * message without ASCII digits or trailing [WHITE_SPACE], and `class.method` of the top [FRAMES]
 * stack frames, joined by newlines. Causes and line numbers are not part of it.
 */
internal object AnkiquestCrashSignature {
    private const val FRAMES = 8
    private const val ALGORITHM = "SHA-256"
    private const val HEX = "0123456789abcdef"
    private val digits = Regex("[0-9]")

    /**
     * Unicode `White_Space`, which the server's Rust `trim_end` removes. Kotlin's [String.trimEnd]
     * differs: it also removes U+001C to U+001F and keeps U+0085.
     */
    private const val WHITE_SPACE =
        "\u0009\u000A\u000B\u000C\u000D\u0020\u0085\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
            "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"

    /** The signature of [error]. */
    fun of(error: Throwable): String = hash(canonical(error.javaClass.name, error.localizedMessage, error.stackTrace.toList()))

    /** The text the signature hashes. */
    fun canonical(
        type: String,
        message: String?,
        frames: List<StackTraceElement>,
    ): String {
        val text = message.orEmpty().replace(digits, "").trimEnd { it in WHITE_SPACE }
        val calls = frames.take(FRAMES).map { "${it.className}.${it.methodName}" }
        return (listOf(type, text) + calls).joinToString("\n")
    }

    /** Lowercase hex SHA-256 of [canonical]. */
    fun hash(canonical: String): String {
        val digest = MessageDigest.getInstance(ALGORITHM).digest(canonical.toByteArray(Charsets.UTF_8))
        // Not String.format: some locales format digits in other scripts.
        return buildString {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(HEX[value shr 4])
                append(HEX[value and 0xf])
            }
        }
    }
}
