// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

/**
 * The newest upstream AnkiDroid commit merged into an AnkiQuest build, from `-PupstreamCommit`.
 * ankiquest_apk.yml passes it; local and unit-test builds have none and show [UNKNOWN].
 */
object UpstreamCommit {
    const val UNKNOWN = "unknown"

    private val FULL_HASH = Regex("[0-9a-f]{40}")

    /** [property] when it is a full commit hash, [UNKNOWN] when absent; anything else fails the build. */
    fun of(property: String?): String {
        if (property == null) {
            return UNKNOWN
        }
        require(property.matches(FULL_HASH)) { "upstreamCommit must be a full commit hash" }
        return property
    }
}
