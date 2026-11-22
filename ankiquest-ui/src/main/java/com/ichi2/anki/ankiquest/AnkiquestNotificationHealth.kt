// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit

/** The inbox timestamp belongs to the authenticated server account that fetched it. */
object AnkiquestNotificationHealth {
    private const val SYNC_KEY = "ankiquestInboxSyncedAt:"

    fun recordSuccessfulSync(
        scope: String,
        at: Long,
        preferences: SharedPreferences = AnkiquestStore.preferences(),
    ) {
        if (HomeAccount.current(preferences)?.scope != scope) return
        preferences.edit { putLong("$SYNC_KEY$scope", at) }
    }

    fun lastSuccessfulSync(preferences: SharedPreferences = AnkiquestStore.preferences()): Long? {
        val account = HomeAccount.current(preferences)?.takeIf { it.token.isNotEmpty() } ?: return null
        return preferences.getLong("$SYNC_KEY${account.scope}", 0L).takeIf { it > 0L }
    }
}
