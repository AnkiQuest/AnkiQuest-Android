// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.core.text.parseAsHtml
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R

/**
 * The AnkiQuest changelog: this build's release notes, with Rate AnkiQuest and a link to all
 * releases. AnkiDroid's own changelog (Info) has no Rate button.
 */
class AnkiquestChangelogActivity : AnkiActivity(R.layout.activity_ankiquest_changelog) {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)

        // Header, as on the other AnkiQuest pages
        findViewById<TextView>(R.id.aq_changelog_title).apply {
            setText(R.string.ankiquest_changelog)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_changelog_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val root = findViewById<View>(R.id.aq_changelog_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        // The release this build is, and its notes
        findViewById<TextView>(R.id.aq_changelog_release).text =
            AnkiquestUpdater.installed() ?: getString(R.string.ankiquest_local_build)
        findViewById<TextView>(R.id.aq_changelog_notes).apply {
            text = AnkiquestReleaseNotes.html(AnkiquestReleaseNotes.read(context)).parseAsHtml().trim()
            movementMethod = LinkMovementMethod.getInstance()
        }

        // Rate AnkiQuest is offered here and on About only
        findViewById<View>(R.id.aq_changelog_rate).setOnClickListener {
            openAnkiquestLink(AnkiquestLinks.rateUrl(this))
        }
        findViewById<View>(R.id.aq_changelog_releases).setOnClickListener {
            openAnkiquestLink(AnkiquestLinks.RELEASES)
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AnkiquestChangelogActivity::class.java)
    }
}
