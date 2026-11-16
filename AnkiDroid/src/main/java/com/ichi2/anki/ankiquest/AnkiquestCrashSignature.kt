// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import java.security.MessageDigest

/**
 * Identifies a crash independently of the player and the build, exactly as the server does
 * (`tests/fixtures/crash_signatures.json`): the lowercase hex SHA-256 of the exception class, its
 * message without ASCII digits or trailing whitespace, and `class.method` of the top [FRAMES]
 * stack frames, joined by newlines. Causes and line numbers are not part of it.
 */
internal object AnkiquestCrashSignature {
    private const val FRAMES = 8
    private const val ALGORITHM = "SHA-256"
    private const val HEX = "0123456789abcdef"
    private val digits = Regex("[0-9]")

    /** The signature of [error]. */
    fun of(error: Throwable): String = hash(canonical(error.javaClass.name, error.localizedMessage, error.stackTrace.toList()))

    /** The text the signature hashes. */
    fun canonical(
        type: String,
        message: String?,
        frames: List<StackTraceElement>,
    ): String {
        val text = message.orEmpty().replace(digits, "").trimEnd()
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
