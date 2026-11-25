// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import kotlin.test.assertTrue

/** Signs in a test account, alice, and makes [choice] her companion. */
internal fun chooseCompanion(choice: Choice) {
    AnkiDroidApp.sharedPrefs().edit(commit = true) {
        putString(Ankiquest.PREF_URL, "https://example.com")
        putString(Ankiquest.PREF_USER, "alice")
        putString(Ankiquest.PREF_TOKEN, "token-alice")
    }
    assertTrue(AnkiquestCompanion.remember(requireNotNull(AnkiquestHomeData.account()), choice.id))
}

/**
 * The Art name of a pose `streak_moods.json` names. The pinned fixture still names Aki's drawables
 * (`aki_sleeping`); newer ones use Art names, which pass unchanged.
 */
internal fun fixturePose(name: String): String = if (name == "aki_sleeping") "asleep" else name.removePrefix("aki_")

/** Ankilope, earned at a level; a pack of the pinned Art release. */
internal val Choice.Companion.ANKILOPE get() = Choice("ankilope")

/** Bluma, earned by hours of study; a pack of the pinned Art release. */
internal val Choice.Companion.COW get() = Choice("cow")
