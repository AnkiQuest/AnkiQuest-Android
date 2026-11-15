// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.annotation.DrawableRes
import com.ichi2.anki.R

/** Where crash reports go and how their dialog looks: the AnkiQuest server, never ankidroid.org (#329). */
internal object AnkiquestCrashReports {
    /** The server endpoint takes ACRA's JSON report by POST and keeps it for 30 days. */
    const val URL = "${AnkiquestAccount.DEFAULT_SERVER}/api/crash-reports"

    /** The report dialog's icon. */
    @DrawableRes
    val ICON = R.drawable.aki_face
}
