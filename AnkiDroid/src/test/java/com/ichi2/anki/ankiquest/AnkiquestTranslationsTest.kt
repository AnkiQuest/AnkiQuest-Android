// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every AnkiQuest string has a German, Spanish, French and Portuguese translation. */
class AnkiquestTranslationsTest {
    @Test
    fun `AnkiQuest strings are translated into every AnkiQuest language`() {
        val sources = File(RES, "values").listFiles { file -> file.name.startsWith(PREFIX) && file.extension == "xml" }.orEmpty()
        assertTrue(sources.isNotEmpty(), "no AnkiQuest strings found in $RES/values")

        // Each language's names, from all its files, since a translation may sit in another file.
        val translated = LANGUAGES.associateWith { language -> names(File(RES, "values-$language")) }
        val missing =
            sources
                .filter { it.name !in KNOWN_GAPS }
                .flatMap { source ->
                    val wanted = translatable(source)
                    LANGUAGES.flatMap { language ->
                        (wanted - translated.getValue(language)).map { "values-$language: ${source.name} $it" }
                    }
                }
        if (missing.isNotEmpty()) {
            fail("Untranslated AnkiQuest strings:\n" + missing.sorted().joinToString("\n"))
        }
    }

    /** The names in [source] that need a translation. */
    private fun translatable(source: File): Set<String> =
        resources(source)
            .filter { it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .toSet()

    /** Every string and plural named in [folder]. */
    private fun names(folder: File): Set<String> =
        folder
            .listFiles { file -> file.extension == "xml" }
            .orEmpty()
            .flatMap { resources(it) }
            .map { it.getAttribute("name") }
            .toSet()

    private fun resources(file: File): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return TEXT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }

    private companion object {
        const val RES = "src/main/res"
        const val PREFIX = "ankiquest"
        val LANGUAGES = listOf("de", "es", "fr", "pt")
        val TEXT_TAGS = listOf("string", "plurals", "string-array")

        /** Files still waiting for translations: AnkiQuest/AnkiQuest-Android#386. */
        val KNOWN_GAPS =
            setOf(
                "ankiquest.xml",
                "ankiquest_community.xml",
                "ankiquest_companion.xml",
                "ankiquest_settings.xml",
            )
    }
}
