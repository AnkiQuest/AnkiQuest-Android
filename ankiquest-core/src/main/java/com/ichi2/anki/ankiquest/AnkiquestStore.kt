// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.common.android.ApplicationContextInitializer
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs

/**
 * Where AnkiQuest keeps the player's settings: the app's default preferences, the same ones
 * `AnkiDroidApp.sharedPrefs()` answers. The app sets [appContext] when it starts.
 */
object AnkiquestStore {
    /** The test override is read first, so tests without an application context still work. */
    fun preferences(): SharedPreferences = SharedPreferencesTestHook.override ?: appContext.sharedPrefs()

    /** [preferences], or null before the app has set [appContext], as under a test-only application. */
    fun preferencesOrNull(): SharedPreferences? =
        SharedPreferencesTestHook.override ?: ApplicationContextInitializer.instanceOrNull?.sharedPrefs()
}
