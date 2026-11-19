// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.os.Bundle
import com.ichi2.anki.AnkiActivity

/** Redirects old explicit links to their section within Friends, retaining notification context. */
class AnkiquestLeaderboardActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            AnkiquestFriendsSections.leaderboard(
                this,
                intent.getStringExtra(AnkiquestFriendsSections.EXTRA_BOARD_PERIOD) ?: AnkiquestBoards.Period.WEEK.query,
                intent.getStringExtra(AnkiquestFriendsSections.EXTRA_BOARD_ACCOUNT),
            ),
        )
        finish()
    }
}
