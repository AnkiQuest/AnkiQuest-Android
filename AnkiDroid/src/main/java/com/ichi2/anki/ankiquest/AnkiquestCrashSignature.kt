// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import java.security.MessageDigest
import java.util.Locale

/**
 * Identifies a crash independently of the player and the build: the exception class, its message
 * with every word containing a digit (numbers, ids, paths) replaced, and the class and method of
 * the top [FRAMES] stack frames without line numbers, hashed with SHA-256.
 */
internal object AnkiquestCrashSignature {
    private const val FRAMES = 8
    private const val ALGORITHM = "SHA-256"
    private const val PLACEHOLDER = "#"
    private val variable = Regex("[\\w-]*\\d[\\w-]*")

    /** The lowercase hex signature of [error]. */
    fun of(error: Throwable): String {
        val message = error.message.orEmpty().replace(variable, PLACEHOLDER)
        val frames = error.stackTrace.take(FRAMES).map { "${it.className}.${it.methodName}" }
        val text = (listOf(error.javaClass.name, message) + frames).joinToString("\n")

        val digest = MessageDigest.getInstance(ALGORITHM).digest(text.toByteArray(Charsets.UTF_8))
        // Locale.ROOT: some locales format digits in other scripts.
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
