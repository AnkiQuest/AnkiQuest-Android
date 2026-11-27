// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import org.json.JSONObject
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

/** Ankilope's core pose for each streak mood. */
internal val ankilopeMoodPoses =
    mapOf(
        AnkiquestMood.Mood.UNKNOWN to R.drawable.ankilope_face,
        AnkiquestMood.Mood.ASLEEP to R.drawable.ankilope_asleep,
        AnkiquestMood.Mood.SAD to R.drawable.ankilope_sad,
        AnkiquestMood.Mood.FREEZE to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.FROZEN_1 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.FROZEN_2 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.FROZEN_3 to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.THAWING to R.drawable.ankilope_frozen,
        AnkiquestMood.Mood.HOPEFUL to R.drawable.ankilope_hopeful,
        AnkiquestMood.Mood.ALMOST to R.drawable.ankilope_hopeful,
        AnkiquestMood.Mood.FIRE to R.drawable.ankilope_streak,
        AnkiquestMood.Mood.NEGLECTED to R.drawable.ankilope_neglected,
        AnkiquestMood.Mood.ABANDONED to R.drawable.ankilope_neglected,
        AnkiquestMood.Mood.DEAD to R.drawable.ankilope_neglected,
    )

/** Bluma's core pose for each streak mood, from her own art. */
internal val blumaMoodPoses =
    mapOf(
        AnkiquestMood.Mood.UNKNOWN to R.drawable.bluma_face,
        AnkiquestMood.Mood.ASLEEP to R.drawable.bluma_asleep,
        AnkiquestMood.Mood.SAD to R.drawable.bluma_sad,
        AnkiquestMood.Mood.FREEZE to R.drawable.bluma_frozen,
        AnkiquestMood.Mood.FROZEN_1 to R.drawable.bluma_frozen,
        AnkiquestMood.Mood.FROZEN_2 to R.drawable.bluma_frozen,
        AnkiquestMood.Mood.FROZEN_3 to R.drawable.bluma_frozen,
        AnkiquestMood.Mood.THAWING to R.drawable.bluma_frozen,
        AnkiquestMood.Mood.HOPEFUL to R.drawable.bluma_hopeful,
        AnkiquestMood.Mood.ALMOST to R.drawable.bluma_hopeful,
        AnkiquestMood.Mood.FIRE to R.drawable.bluma_streak,
        AnkiquestMood.Mood.NEGLECTED to R.drawable.bluma_neglected,
        AnkiquestMood.Mood.ABANDONED to R.drawable.bluma_neglected,
        AnkiquestMood.Mood.DEAD to R.drawable.bluma_neglected,
    )

/**
 * The shared pose's Aki drawable; her missing frozen extensions and the freeze picture use the core
 * frozen pose, her missing away extensions the core neglected pose.
 */
internal fun akiMoodArt(expected: JSONObject): String {
    val pose = expected.getString("pose")
    val shown =
        when (pose) {
            "freeze", "frozen_2", "frozen_3" -> "frozen"
            "abandoned", "dead" -> "neglected"
            else -> pose
        }
    return "aki_$shown"
}

/**
 * Anki-tan's pose for each streak mood but fire, which burns by streak: her streak pose, and her
 * crown from 100 days. Frozen 2 and 3 have no art of their own yet (AnkiQuest-Server#57).
 */
internal val ankiTanMoodPoses =
    mapOf(
        AnkiquestMood.Mood.UNKNOWN to R.drawable.anki_tan_face,
        AnkiquestMood.Mood.ASLEEP to R.drawable.anki_tan_asleep,
        AnkiquestMood.Mood.SAD to R.drawable.anki_tan_sad,
        AnkiquestMood.Mood.FREEZE to R.drawable.anki_tan_frozen,
        AnkiquestMood.Mood.FROZEN_1 to R.drawable.anki_tan_frozen,
        AnkiquestMood.Mood.FROZEN_2 to R.drawable.anki_tan_frozen,
        AnkiquestMood.Mood.FROZEN_3 to R.drawable.anki_tan_frozen,
        AnkiquestMood.Mood.THAWING to R.drawable.anki_tan_frozen,
        AnkiquestMood.Mood.HOPEFUL to R.drawable.anki_tan_hopeful,
        AnkiquestMood.Mood.ALMOST to R.drawable.anki_tan_hopeful,
        AnkiquestMood.Mood.NEGLECTED to R.drawable.anki_tan_neglected,
        AnkiquestMood.Mood.ABANDONED to R.drawable.anki_tan_neglected,
        AnkiquestMood.Mood.DEAD to R.drawable.anki_tan_neglected,
    )
