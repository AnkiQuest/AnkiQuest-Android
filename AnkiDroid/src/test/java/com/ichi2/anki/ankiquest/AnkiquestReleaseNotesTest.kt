// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals

class AnkiquestReleaseNotesTest {
    @Test
    fun `Blank lines separate paragraphs`() {
        assertEquals("<p>One.</p><p>Two\nlines.</p>", AnkiquestReleaseNotes.html("One.\n\n\nTwo\nlines.\n"))
    }

    @Test
    fun `Bold text and links are kept`() {
        assertEquals(
            """<p><b>Undo.</b> See <a href="https://example.com/a?b=1&amp;c=2">the notes</a>.</p>""",
            AnkiquestReleaseNotes.html("**Undo.** See [the notes](https://example.com/a?b=1&c=2)."),
        )
    }

    @Test
    fun `Markup in the text is shown as written`() {
        assertEquals("<p>a &lt;b&gt; &amp; &quot;c&quot;</p>", AnkiquestReleaseNotes.html("a <b> & \"c\""))
    }
}
