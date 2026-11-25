// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

/**
 * Every AnkiQuest preference key starts with `ankiquest`. They hold the device token, the account,
 * the server address and account-scoped caches, so none of them goes into crash reports or analytics.
 */
object AnkiquestPreferences {
    /** The server address. */
    const val PREF_URL = "ankiquestUrl"

    /** The player's name on the server. */
    const val PREF_USER = "ankiquestUser"

    /** This device's token. */
    const val PREF_TOKEN = "ankiquestToken"

    /** Whether the recap shows after studying. */
    const val PREF_SESSION_SUMMARY = "ankiquestSessionSummary"

    /** Whether finishing a deck asks to tell friends. */
    const val PREF_ASK_DECK_SHARE = "ankiquestAskToShareDecks"

    /** The namespace as a regular expression, the form ACRA's exclusion list takes. */
    const val NAMESPACE = "(?i)ankiquest.*"

    private val namespace = Regex(NAMESPACE)

    /** Whether [key] is an AnkiQuest preference. */
    fun owns(key: String): Boolean = namespace.matches(key)
}
