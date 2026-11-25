// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Visible strings say AnkiQuest where upstream's say AnkiDroid, in English and in every locale
 * upstream translates, except the names in `ankiquest-branding-keep.txt`.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestBrandingTest : RobolectricTest() {
    private val keep =
        File(KEEP_FILE)
            .readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Every string resource of the app and of upstream's shared strings, by name. */
    private val strings: Map<String, Int> =
        listOf(com.ichi2.anki.R.string::class.java, com.ichi2.anki.common.android.R.string::class.java)
            .flatMap { it.fields.toList() }
            .associate { it.name to it.getInt(null) }

    private fun Context.text(id: Int): String? = runCatching { getString(id) }.getOrNull()

    private fun inLocale(tag: String): Context {
        val configuration = Configuration(targetContext.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return targetContext.createConfigurationContext(configuration)
    }

    @Test
    fun `no visible English string says AnkiDroid unless it is kept`() {
        val english = inLocale("en")
        val offenders = strings.filter { (name, id) -> name !in keep && UPSTREAM in english.text(id).orEmpty() }
        assertEquals(emptyList(), offenders.keys.sorted())
    }

    @Test
    fun `the rebranded strings say AnkiQuest`() {
        val english = inLocale("en")
        for (name in listOf("info_rate", "feedback_title", "sync_conflict_keep_local_new", "permissions_screen_headline")) {
            assertTrue(OURS in english.text(strings.getValue(name)).orEmpty(), name)
        }
    }

    @Test
    fun `sign-ins are listed under AnkiQuest`() {
        assertTrue(AnkiquestAccount.device().startsWith("$OURS ("))
    }

    @Test
    fun `translations of a rebranded string say AnkiQuest too`() {
        val english = inLocale("en")
        val rebranded = strings.filter { (name, id) -> name !in keep && OURS in english.text(id).orEmpty() }
        assertTrue(rebranded.size > MIN_REBRANDED, "${rebranded.size} strings say AnkiQuest")

        val offenders =
            locales().flatMap { tag ->
                val translated = inLocale(tag)
                rebranded.filter { (_, id) -> UPSTREAM in translated.text(id).orEmpty() }.keys.map { "$tag: $it" }
            }
        assertEquals(emptyList(), offenders.sorted())
    }

    /**
     * The locales upstream translates into, from its resource directories. Three-letter codes
     * (`values-heb`, `values-ind`, ...) are left out: [Locale.forLanguageTag] does not map them to
     * those directories.
     */
    private fun locales(): List<String> =
        File(UPSTREAM_RES)
            .listFiles()
            .orEmpty()
            .mapNotNull { LOCALE_DIRECTORY.matchEntire(it.name) }
            .map { match -> listOf(match.groupValues[1], match.groupValues[2]).filter { it.isNotEmpty() }.joinToString("-") }
            .sorted()
            .also { assertTrue(it.size > MIN_LOCALES, "found ${it.size} locales in $UPSTREAM_RES") }

    companion object {
        private const val UPSTREAM = "AnkiDroid"
        private const val OURS = "AnkiQuest"

        /** Relative to the AnkiDroid module, where unit tests run. */
        private const val KEEP_FILE = "ankiquest-branding-keep.txt"
        private const val UPSTREAM_RES = "../common/android/src/main/res"

        private val LOCALE_DIRECTORY = Regex("""values-([a-z]{2})(?:-r([A-Z]{2}))?""")

        /** Fewer would mean the overrides or the locales were not found at all. */
        private const val MIN_REBRANDED = 30
        private const val MIN_LOCALES = 50
    }
}
