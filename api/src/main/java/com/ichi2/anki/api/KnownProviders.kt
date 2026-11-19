// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import com.ichi2.anki.FlashCardsContract

/**
 * Apps AnkiDroid's maintainers list as offering the [FlashCardsContract] provider.
 * The API only ever talks to these, and only after checking their signing certificate.
 *
 * To list an app, add it here and declare its permission and a `<queries>` entry in the
 * library's AndroidManifest.xml; KnownProvidersManifestTest checks both.
 */
internal object KnownProviders {
    /** AnkiDroid, the default */
    val ANKIDROID =
        FlashCardsProvider(
            packageName = BuildConfig.ANKI_PACKAGE_NAME,
            authority = FlashCardsContract.AUTHORITY,
            permission = FlashCardsContract.READ_WRITE_PERMISSION,
            certificates = AnkiDroidProvider.RELEASE_CERTIFICATES,
        )

    /** AnkiQuest, a fork of AnkiDroid: https://github.com/AnkiQuest/AnkiQuest-Android */
    val ANKIQUEST =
        FlashCardsProvider(
            packageName = "org.ankiquest.anki",
            authority = "org.ankiquest.anki.flashcards",
            permission = "org.ankiquest.anki.permission.READ_WRITE_DATABASE",
            // Read from the APK signing block of AnkiQuest.apk in the quest-23 release on
            // https://github.com/AnkiQuest/AnkiQuest-Android/releases. Its v3 signature rotates
            // the key: API 28 and up see both certificates in the signing history, older
            // versions only the first.
            certificates =
                setOf(
                    // The first key, in the v2 signature and at the start of the v3 lineage
                    "0A8EBEEA7A7D04F21A737DEBEDFB0B2EC439C84936E4BB69DC7F14894F0BE42A",
                    // The current key, in the v3 signature: CN=AnkiQuest, O=float3
                    "B397A61ECFAF0FE354DA611772515E683CCB6FD0E952D477B8B3BCA49E6C490D",
                ),
        )

    /** AnkiDroid first */
    val ALL = listOf(ANKIDROID, ANKIQUEST)
}
