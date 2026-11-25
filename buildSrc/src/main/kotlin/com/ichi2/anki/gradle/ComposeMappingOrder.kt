// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

/** First line of the block the Compose Gradle plugin adds to the R8 mapping. */
internal const val COMPOSE_MAPPING_HEADER = "ComposeStackTrace -> \$\$compose:"

/** Entries of a mapping block are indented; the next class line or the end of the file is not. */
private const val ENTRY_INDENT = " "

/**
 * Returns [text] with the entries of its Compose stack trace block sorted by the whole line,
 * independent of locale. Every other line, including the block's header, stays as it is, and the
 * block keeps the same lines, so retrace reads it as before.
 */
fun sortComposeMapping(text: String): String {
    val lines = text.split('\n')
    val header = lines.indexOf(COMPOSE_MAPPING_HEADER)
    if (header < 0) {
        return text
    }

    // The block runs from the line after the header to the next unindented line.
    val start = header + 1
    var end = start
    while (end < lines.size && lines[end].startsWith(ENTRY_INDENT)) {
        end++
    }

    val entries = lines.subList(start, end).sorted()
    return (lines.subList(0, start) + entries + lines.subList(end, lines.size)).joinToString("\n")
}
