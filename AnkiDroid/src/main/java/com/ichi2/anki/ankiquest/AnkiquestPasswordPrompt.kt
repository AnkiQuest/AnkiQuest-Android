// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Methods

// Replaced by the implementation in the next commit; only here so the tests compile and fail.
@Suppress("UNUSED_PARAMETER")
internal object AnkiquestPasswordPrompt {
    const val SNOOZE_MS = 0L

    fun offered(
        methods: Methods,
        snoozedAt: Long?,
        now: Long,
    ): Boolean = false

    fun snoozedAt(
        prefs: SharedPreferences,
        account: HomeAccount,
    ): Long? = null

    fun snooze(
        prefs: SharedPreferences,
        account: HomeAccount,
        now: Long,
    ) {}
}
