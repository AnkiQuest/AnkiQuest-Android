// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.os.Bundle
import com.ichi2.anki.AnkiActivity

/**
 * Forwards old explicit links to the leaderboard within Friends, keeping the period and account.
 * Released apps (quest-22) posted notifications that target this class, so it stays.
 */
class AnkiquestLeaderboardActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val period = intent.getStringExtra(AnkiquestFriendsSections.EXTRA_BOARD_PERIOD) ?: AnkiquestBoards.Period.WEEK.query
        val account = intent.getStringExtra(AnkiquestFriendsSections.EXTRA_BOARD_ACCOUNT)
        startActivity(AnkiquestFriendsSections.boardIntent(this, period, account))
        finish()
    }
}
