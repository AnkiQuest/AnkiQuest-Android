// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/** Where crash reports go: the AnkiQuest server, never ankidroid.org (#329). */
internal object AnkiquestCrashReports {
    /** The server endpoint takes ACRA's JSON report by POST and keeps it for 30 days. */
    const val URL = "${AnkiquestAccount.DEFAULT_SERVER}/api/crash-reports"
}
