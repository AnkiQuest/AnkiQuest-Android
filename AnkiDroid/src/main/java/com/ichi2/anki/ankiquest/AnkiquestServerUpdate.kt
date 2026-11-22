// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.StringRes
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCapabilities.Support
import com.ichi2.anki.common.utils.android.showThemedToast
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * The one message for a server that lacks what this app needs. The app has no fallbacks for older
 * servers: a player whose server is outdated is told to update it instead.
 */
internal object AnkiquestServerUpdate {
    /** Tells the player to update [account]'s server and answers true when it last reported that it is outdated. */
    fun refuse(
        context: Context,
        account: HomeAccount,
    ): Boolean {
        if (AnkiquestCapabilities.support(account) != Support.OUTDATED) {
            return false
        }
        showThemedToast(context, AnkiquestLanguage.context(context).getString(MESSAGE), shortLength = false)
        return true
    }

    /** The message for [error] when it is a missing endpoint on [account]'s outdated server, or null. */
    @StringRes
    fun message(
        account: HomeAccount?,
        error: Exception,
    ): Int? {
        if (account == null || (error as? AnkiquestHttpException)?.code != HTTP_NOT_FOUND) {
            return null
        }
        return MESSAGE.takeIf { AnkiquestCapabilities.support(account) == Support.OUTDATED }
    }

    @StringRes
    private val MESSAGE = R.string.aq_server_outdated
}
