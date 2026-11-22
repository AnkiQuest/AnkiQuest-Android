// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.os.Bundle
import com.ichi2.anki.AnkiActivity

/**
 * Forwards old explicit links to the feed within Friends, keeping the notification's account.
 * Released apps (quest-22) posted notifications that target this class, so it stays.
 */
class AnkiquestFeedActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(AnkiquestFriendsSections.feedIntent(this, intent.getStringExtra(AnkiquestFriendsSections.EXTRA_FEED_ACCOUNT)))
        finish()
    }
}
