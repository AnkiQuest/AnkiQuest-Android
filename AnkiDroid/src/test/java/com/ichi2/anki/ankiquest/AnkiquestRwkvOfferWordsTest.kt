// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.Configuration
import android.text.format.Formatter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Download list's rows say the model, its download size, what it needs and why it is greyed out, in every AnkiQuest language. */
@RunWith(AndroidJUnit4::class)
class AnkiquestRwkvOfferWordsTest : RobolectricTest() {
    private val collection = RwkvCollection(cards = 22_000, notes = 22_000)
    private val big = RwkvLock.ENTRIES.single { it.size == RwkvSize.BIG && it.head == RwkvHead.TWO_HEAD }
    private val defaultLocale = Locale.getDefault()

    /** The rows format numbers in the default locale, which each test sets per language. */
    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun inLocale(tag: String): Context {
        val configuration = Configuration(targetContext.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return targetContext.createConfigurationContext(configuration)
    }

    @Test
    fun `a model that does not fit says what it needs and what the phone allows`() {
        val needed = 1_200_000_000L
        val allowed = 900_000_000L
        val offer = RwkvOffer(big, needed, RwkvOfferState.TooBig(needed, allowed), RwkvPick.OTHER)
        for (tag in LANGUAGES) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val context = inLocale(tag)
            val text = RwkvOfferWords(context).text(offer, collection)
            assertTrue("SM91S2" in text.title, "$tag: ${text.title}")
            assertTrue(Formatter.formatShortFileSize(context, big.zipBytes) in text.title, "$tag: ${text.title}")
            for (part in listOf(Formatter.formatShortFileSize(context, needed), Formatter.formatShortFileSize(context, allowed))) {
                assertTrue(part in text.detail, "$tag: ${text.detail}")
            }
            assertTrue(NumberFormat.getIntegerInstance().format(collection.cards) in text.detail, "$tag: ${text.detail}")
        }
    }

    @Test
    fun `the recommended model says why`() {
        val offer = RwkvOffer(big, 700_000_000L, RwkvOfferState.Fits, RwkvPick.RECOMMENDED)
        for (tag in LANGUAGES) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val context = inLocale(tag)
            val text = RwkvOfferWords(context).text(offer, collection)
            assertTrue(context.getString(com.ichi2.anki.R.string.ankiquest_rwkv_recommended) in text.title, "$tag: ${text.title}")
            assertTrue("%.4f".format(big.logLoss) in text.detail, "$tag: ${text.detail}")
            assertEquals(2, text.detail.lines().size, "$tag: ${text.detail}")
        }
    }

    private companion object {
        val LANGUAGES = listOf("en", "de", "es", "fr", "pt")
    }
}
