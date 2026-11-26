// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context

/**
 * The release notes bundled with this build. ankiquest_apk.yml publishes the same file as the
 * notes of each quest-<n> release, so the app shows the notes of the release it was built from.
 */
internal object AnkiquestReleaseNotes {
    const val ASSET = "ankiquest/release-notes.md"

    /** The RWKV help page, in the same words as the release notes. */
    const val GUIDE = "ankiquest/rwkv-guide.md"
    private val PARAGRAPH = Regex("""\n\s*\n""")
    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")

    fun read(context: Context, asset: String = ASSET): String =
        context.assets
            .open(asset)
            .bufferedReader()
            .use { it.readText() }

    /** [markdown] as HTML a TextView shows: paragraphs, bold text and links. Other syntax stays as written. */
    fun html(markdown: String): String =
        markdown
            .split(PARAGRAPH)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("") { "<p>${inline(it)}</p>" }

    private fun inline(text: String): String =
        escape(text)
            .replace(LINK, """<a href="$2">$1</a>""")
            .replace(BOLD, "<b>$1</b>")

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
