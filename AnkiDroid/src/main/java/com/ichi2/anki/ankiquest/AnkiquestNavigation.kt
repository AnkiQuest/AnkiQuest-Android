// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.ui.R as AnkiquestR

/** Entry policy is independent of connectivity: the local study library always works. */
object AnkiquestNavigation {
    const val PREF_OPEN_TODAY = "ankiquestOpenToday"

    fun enabled(): Boolean = Ankiquest.dashboardUrl() != null

    fun opensToday(intent: Intent): Boolean =
        enabled() &&
            AnkiDroidApp.sharedPrefs().getBoolean(PREF_OPEN_TODAY, false) &&
            !intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false) &&
            !intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            intent.action == Intent.ACTION_MAIN &&
            intent.data == null

    /** Where a bottom navigation item leads; null for Decks, the deck list itself. */
    fun destination(
        context: Context,
        item: Int,
    ): Intent? =
        when (item) {
            R.id.ankiquest_nav_today ->
                AnkiquestHomeActivity
                    .intent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            R.id.ankiquest_nav_friends -> AnkiquestFriendsActivity.intent(context)
            AnkiquestR.id.ankiquest_nav_progress -> AnkiquestProfileActivity.intent(context)
            else -> null
        }

    /** The deck list, brought back over any AnkiQuest screen, which goes on to study [deck] when one is given. */
    fun decks(
        context: Context,
        deck: Long? = null,
    ): Intent =
        Intent(context, DeckPicker::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            deck?.let { putExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, it) }
        }
}
