// SPDX-License-Identifier: AGPL-3.0-only

package org.ankiquest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.ichi2.anki.R
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.utils.TruncatedString
import com.ichi2.utils.copyToClipboard
import timber.log.Timber

/** AnkiQuest's own pages: the repositories, the bug form and the release notes. */
internal object AnkiquestLinks {
    const val REPOSITORY = "https://github.com/AnkiQuest/AnkiQuest-Android"
    const val SERVER_REPOSITORY = "https://github.com/AnkiQuest/AnkiQuest-Server"
    const val DESKTOP_REPOSITORY = "https://github.com/AnkiQuest/AnkiQuest-Desktop"
    const val BUG_FORM = "$REPOSITORY/issues/new?template=bug_report_form.yml"

    // ankiquest_apk.yml publishes each quest-<n> release here, with AnkiquestReleaseNotes as notes.
    const val RELEASES = "$REPOSITORY/releases"
    private const val PLAY_DETAILS = "https://play.google.com/store/apps/details?id="

    /** Whether AnkiQuest has a Google Play listing yet (#319). */
    enum class PlayListing { ABSENT, PUBLISHED }

    /** The listing this build knows about. Set to [PlayListing.PUBLISHED] once #319 ships. */
    private val PLAY_LISTING = PlayListing.ABSENT

    /** Where "Rate AnkiQuest" sends the player. */
    enum class RateTarget {
        /** Star the repository while there is no store listing. */
        GITHUB,

        /** The app's Google Play page. */
        PLAY_STORE,
        ;

        fun url(packageName: String): String =
            when (this) {
                GITHUB -> REPOSITORY
                PLAY_STORE -> PLAY_DETAILS + packageName
            }

        companion object {
            fun of(listing: PlayListing = PLAY_LISTING): RateTarget =
                when (listing) {
                    PlayListing.ABSENT -> GITHUB
                    PlayListing.PUBLISHED -> PLAY_STORE
                }
        }
    }

    /** The page "Rate AnkiQuest" opens in this build. */
    fun rateUrl(context: Context): String = RateTarget.of().url(context.packageName)
}

/**
 * Opens [url] in whichever app handles https links.
 *
 * Starts the intent without a resolveActivity() check: package visibility can hide the
 * browser from that check while it still handles the link. When no app takes it, the
 * link is copied so the player can paste it into a browser.
 */
internal fun Activity.openAnkiquestLink(url: String) {
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No app opens %s", url)
        val copied = copyToClipboard(TruncatedString.from(url), successMessageId = R.string.ankiquest_link_copied)

        // From S_V2 the system confirms the copy itself, without saying why the link was copied
        if (copied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            showSnackbar(R.string.ankiquest_link_copied)
        }
    }
}
