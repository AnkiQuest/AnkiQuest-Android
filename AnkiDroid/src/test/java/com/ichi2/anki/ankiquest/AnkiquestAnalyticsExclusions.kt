// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.preferences.PreferenceTestUtils

/**
 * AnkiQuest preference keys that analytics must not report, for upstream's `PreferencesAnalyticsTest`:
 * every settings key in [AnkiquestPreferences], the namespace crash reports exclude too.
 */
object AnkiquestAnalyticsExclusions {
    fun keys(context: Context): Set<String> =
        PreferenceTestUtils
            .getAllPreferenceKeys(context)
            .subtract(PreferenceTestUtils.getDeveloperOptionsKeys(context))
            .filterTo(mutableSetOf(), AnkiquestPreferences::owns)
}
