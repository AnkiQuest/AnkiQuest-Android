// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/** Android's decorative suffix on a conquest's first line; the shared fixtures leave it out. */
private const val TROPHY = " 🏆"

/** [text] without the first line's trophy, keeping all other text and line breaks. */
internal fun withoutTrophy(text: String): String {
    val lines = text.split("\n", limit = 2)
    val first = lines.first().removeSuffix(TROPHY)
    return (listOf(first) + lines.drop(1)).joinToString("\n")
}
