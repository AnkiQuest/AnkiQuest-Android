// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import com.ichi2.anki.FlashCardsContract

/**
 * Apps with a known release key offering the [FlashCardsContract] provider. Each is a candidate
 * only if signed with its key, and no other package may hold its authority. Other apps are found
 * by [ProviderDiscovery] and trusted through the user's pinned choice.
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
    val ANKIQUEST = ankiQuest("org.ankiquest.ankidroid")

    /**
     * AnkiQuest up to quest-23, installed as com.ichi2.anki.quest. Its provider predates the
     * discovery intent filter, so it is found only by its authority. Listed until its users have
     * moved to [ANKIQUEST].
     */
    val ANKIQUEST_OLD_PACKAGE = ankiQuest("com.ichi2.anki.quest")

    /** AnkiDroid first, then AnkiQuest */
    val ALL = listOf(ANKIDROID, ANKIQUEST, ANKIQUEST_OLD_PACKAGE)

    /** AnkiQuest installed as [packageName]; every package is signed with the same keys */
    private fun ankiQuest(packageName: String) =
        FlashCardsProvider(
            packageName = packageName,
            authority = "$packageName.flashcards",
            permission = "$packageName.permission.READ_WRITE_DATABASE",
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
}
