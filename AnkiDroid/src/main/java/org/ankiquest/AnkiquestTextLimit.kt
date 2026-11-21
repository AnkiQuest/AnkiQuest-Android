// SPDX-License-Identifier: AGPL-3.0-only

package org.ankiquest

import android.text.InputFilter

/** Match the server's Unicode character limits without splitting supplementary characters. */
internal object AnkiquestTextLimit {
    fun take(
        text: String,
        maximum: Int,
    ): String {
        val count = minOf(maximum, text.codePointCount(0, text.length))
        return text.take(text.offsetByCodePoints(0, count))
    }

    /** Replacing selected text frees its characters before applying the limit. */
    fun filter(maximum: Int) =
        InputFilter { source, start, end, destination, replacedStart, replacedEnd ->
            val remaining =
                maximum - Character.codePointCount(destination, 0, destination.length) +
                    Character.codePointCount(destination, replacedStart, replacedEnd)
            when {
                Character.codePointCount(source, start, end) <= remaining -> null
                remaining <= 0 -> ""
                else -> source.subSequence(start, Character.offsetByCodePoints(source, start, remaining))
            }
        }
}
