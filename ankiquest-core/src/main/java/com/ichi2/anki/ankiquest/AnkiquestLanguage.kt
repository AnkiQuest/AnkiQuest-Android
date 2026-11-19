// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Use AnkiDroid's selected language even in services and launcher widgets. */
object AnkiquestLanguage {
    /** AnkiDroid's `language` preference; empty follows the phone. */
    private const val PREF_LANGUAGE = "language"

    fun tag(): String = tag(AnkiquestStore.preferences().getString(PREF_LANGUAGE, ""))

    /** The tag for a [stored] `language` preference, which is empty or missing to follow the phone. */
    fun tag(stored: String?): String =
        stored?.takeIf { it.isNotEmpty() } ?: Resources
            .getSystem()
            .configuration.locales[0]
            .toLanguageTag()

    fun locale(): Locale = Locale.forLanguageTag(tag().replace('_', '-'))

    fun context(context: Context): Context {
        val locale = locale()
        if (context.resources.configuration.locales[0] == locale) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
}
