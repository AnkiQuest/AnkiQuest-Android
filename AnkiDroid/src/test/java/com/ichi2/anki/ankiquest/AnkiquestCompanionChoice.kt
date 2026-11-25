// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import kotlin.test.assertTrue

/** Signs in a test account, alice, and makes [choice] her companion. */
internal fun chooseCompanion(choice: Choice) {
    AnkiDroidApp.sharedPrefs().edit(commit = true) {
        putString(AnkiquestPreferences.PREF_URL, "https://example.com")
        putString(AnkiquestPreferences.PREF_USER, "alice")
        putString(AnkiquestPreferences.PREF_TOKEN, "token-alice")
    }
    assertTrue(AnkiquestCompanion.remember(requireNotNull(AnkiquestHomeData.account()), choice.id))
}

/** Ankilope's core pose for each streak mood; the cow draws hers. On fire she studies until her on-fire art exists. */
internal val ankilopeMoodPoses =
    mapOf(
        AnkiquestMood.Mood.UNKNOWN to R.drawable.ankilope_face,
        AnkiquestMood.Mood.ASLEEP to R.drawable.ankilope_sleeping,
        AnkiquestMood.Mood.SAD to R.drawable.ankilope_sad,
        AnkiquestMood.Mood.FROZEN_1 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.FROZEN_2 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.FROZEN_3 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.HOPEFUL to R.drawable.ankilope_hopeful,
        AnkiquestMood.Mood.FIRE to R.drawable.ankilope_study,
    )
