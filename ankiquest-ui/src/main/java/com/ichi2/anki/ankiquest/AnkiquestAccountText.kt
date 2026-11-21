// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.ankiquest.ui.R
import timber.log.Timber
import java.net.UnknownHostException

// How the app words AnkiquestAccount's failures from :ankiquest-core.

/** What the sign-in screen says when signing in to [url] failed with [error]. */
fun AnkiquestAccount.message(
    context: Context,
    error: Throwable,
    url: String,
): String =
    when (error) {
        is AnkiquestHttpException ->
            when (error.code) {
                400 -> context.getString(R.string.ankiquest_sign_in_invalid)
                401 -> context.getString(R.string.ankiquest_sign_in_wrong)
                403 -> context.getString(R.string.ankiquest_sign_in_closed)
                409 -> context.getString(R.string.ankiquest_sign_in_taken)
                429 -> context.getString(R.string.ankiquest_sign_in_limited)
                else -> context.getString(R.string.ankiquest_check_http, error.code)
            }
        is AnkiquestAccount.SecondFactorNeeded ->
            when (error.step) {
                AnkiquestAccount.SecondFactor.ASKED -> context.getString(R.string.ankiquest_two_factor_asked)
                AnkiquestAccount.SecondFactor.REFUSED -> context.getString(R.string.ankiquest_two_factor_refused)
                AnkiquestAccount.SecondFactor.LOCKED -> context.getString(R.string.ankiquest_two_factor_locked)
            }
        is AnkiquestAccount.InsecureServer -> context.getString(R.string.ankiquest_check_insecure, url)
        is UnknownHostException -> context.getString(R.string.ankiquest_check_unreachable, url)
        is IllegalArgumentException -> context.getString(R.string.ankiquest_check_bad_url, url)
        else -> {
            Timber.w(error, "ankiquest sign-in failed")
            context.getString(R.string.ankiquest_check_failed, error.message ?: error.javaClass.simpleName)
        }
    }
