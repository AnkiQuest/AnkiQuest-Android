// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Tests for [AnkiquestBranding] */
class AnkiquestBrandingTest {
    @TempDir
    lateinit var dir: File

    private val english =
        """
        <resources xmlns:tools="http://schemas.android.com/tools">
            <string name="info_rate" comment="AnkiDroid stays in comments">Rate AnkiDroid</string>
            <string name="empty_string"/>
            <string name="link" translatable="false">https://AnkiDroid.example</string>
            <!-- <string name="old">AnkiDroid</string> -->
            <string name="kept">AnkiDroid manual</string>
            <string name="anki_manual">Anki manual</string>
            <plurals name="due">
                <item quantity="one">%d AnkiDroid card due</item>
                <item quantity="other">%d AnkiDroid cards due</item>
            </plurals>
        </resources>
        """.trimIndent()

    private val german =
        """
        <resources>
            <string name="info_rate">AnkiDroid bewerten</string>
            <string name="kept">AnkiDroid-Handbuch</string>
            <string name="anki_manual">AnkiDroid-Handbuch</string>
        </resources>
        """.trimIndent()

    @Test
    fun `text that names AnkiDroid says AnkiQuest and attributes stay`() {
        val rebranded = AnkiquestBranding.rebrand(english).toMap()
        assertEquals(
            """<string name="info_rate" comment="AnkiDroid stays in comments">Rate AnkiQuest</string>""",
            rebranded["info_rate"],
        )
        assertTrue(rebranded.getValue("due").contains("%d AnkiQuest cards due"))
        assertFalse("link" in rebranded, "untranslatable")
        assertFalse("old" in rebranded, "commented out")
        assertFalse("anki_manual" in rebranded)
    }

    @Test
    fun `names come from the English text, minus the kept ones`() {
        assertEquals(setOf("info_rate", "due"), AnkiquestBranding.names(listOf(english), setOf("kept")))
    }

    @Test
    fun `the keep list ignores comments and blank lines`() {
        assertEquals(setOf("a", "b"), AnkiquestBranding.keepList("# heading\na   # why\n\n b\n"))
    }

    @Test
    fun `each locale gets the names English rebrands, not its own extra mentions`() {
        val upstream = File(dir, "res")
        File(upstream, "values").apply { mkdirs() }.resolve("02-strings.xml").writeText(english)
        File(upstream, "values-de").apply { mkdirs() }.resolve("02-strings.xml").writeText(german)
        File(upstream, "drawable").mkdirs()
        val output = File(dir, "out")
        File(output, "values-old").mkdirs()

        AnkiquestBranding.generate(upstream, setOf("kept"), output)

        assertEquals(listOf("values", "values-de"), output.list()!!.sorted(), "stale output is removed")
        val de = File(output, "values-de/${AnkiquestBranding.FILE}").readText()
        assertTrue(de.contains("""<string name="info_rate">AnkiQuest bewerten</string>"""))
        assertFalse(de.contains("anki_manual"), "a translation's own mention stays upstream's")
        assertFalse(de.contains("kept"))
    }

    @Test
    fun `the overrides for upstream's real strings are well-formed in every locale`() {
        val upstream = File("../common/android/src/main/res")
        val keep = AnkiquestBranding.keepList(File("../AnkiDroid/ankiquest-branding-keep.txt").readText())
        val output = File(dir, "real")
        AnkiquestBranding.generate(upstream, keep, output)

        val files = output.walk().filter { it.name == AnkiquestBranding.FILE }.toList()
        assertTrue(files.size > MIN_LOCALES, "${files.size} locales")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        files.forEach { parser.parse(it) }
        assertFalse(File(output, "values/${AnkiquestBranding.FILE}").readText().contains(">AnkiDroid"))
    }

    private companion object {
        const val MIN_LOCALES = 50
    }
}
